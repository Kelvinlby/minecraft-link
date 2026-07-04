package mod.kelvinlby.link;

import mod.kelvinlby.OpenCrafterLink;
import mod.kelvinlby.recorder.VisionTap;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * The {@link LinkBridge} implementation for the UDS transport: the same four-worker + single-slot
 * conflating model as {@link ZmqBridge}, but over plain {@code AF_UNIX} domain sockets (JEP 380,
 * built into Java 21 — no native library, no ZeroMQ) with a trivial {@code u32-LE length + payload}
 * framing around the existing {@link BinaryCodec} bytes.
 *
 * <p>Why not ZeroMQ over UDS: JeroMQ's {@code ipc://} is TCP-emulated (not real {@code AF_UNIX}) and
 * cannot interoperate with the controller's genuine {@code AF_UNIX}; a real ZMTP-over-UDS would need a
 * native libzmq JNI binding. The link only does <b>conflated, fire-and-forget PUB/SUB of
 * self-contained binary messages</b>, which needs none of ZMTP's routing/identity/REQ-REP — so the
 * length prefix alone reproduces everything the mod relies on.
 *
 * <p>Roles mirror the TCP bind/connect split: the mod is the <b>server</b> for telemetry and vision
 * (it binds a {@link ServerSocketChannel} and the controller connects), and the <b>client</b> for
 * instructions (it connects a {@link SocketChannel} to the controller's server). Each of the three
 * streams is its own socket path. Every socket is created and used entirely within its owning worker
 * thread; the game threads only touch the {@link AtomicReference} hand-off slots.
 *
 * <p>Robustness: a stream survives the controller not being up yet, going away, and reconnecting —
 * the accept/connect/read/write loops retry while {@code running}. {@link #stop()} flips the flag and
 * closes the sockets to unblock any in-flight blocking call, then joins the workers.
 */
public final class UdsBridge implements LinkBridge {

	/** 4-byte little-endian length prefix that precedes every framed payload. */
	private static final int LEN_PREFIX = 4;
	/** Sanity cap so a desynced/garbage prefix can't trigger a huge allocation. */
	private static final int MAX_FRAME = 64 * 1024 * 1024;
	/** Poll/retry backoff for accept/connect loops (ms) — keeps {@link #stop()} responsive. */
	private static final long RETRY_MS = 200L;

	/** Latest telemetry waiting to be published. Tick thread writes; sender thread drains. */
	private final AtomicReference<OutboundSnapshot> outbox = new AtomicReference<>();
	/** Latest instruction received. Receiver thread writes; tick thread drains-and-clears. */
	private final AtomicReference<InboundInstruction> inbox = new AtomicReference<>();

	/** Latest raw framebuffer readback. Render thread writes; vision worker drains. */
	private final AtomicReference<RawFrame> visionRaw = new AtomicReference<>();
	/** Latest converted vision frame. Vision worker writes; vision sender drains. */
	private final AtomicReference<VisionFrame> visionOutbox = new AtomicReference<>();

	private volatile boolean running;
	private volatile LinkConfig.UdsEndpoints endpoints;

	private Thread senderThread;
	private Thread receiverThread;
	private Thread visionWorkerThread;
	private Thread visionSenderThread;

	/**
	 * Currently-open sockets, tracked so {@link #stop()} can close them to interrupt a blocked
	 * accept/connect/read/write. Each is written only by its owning worker and read by {@code stop()}.
	 */
	private volatile Channel telemetryChannel;
	private volatile Channel instructionChannel;
	private volatile Channel visionChannel;

	/** Same as {@link ZmqBridge}'s: raw, already-downsampled framebuffer bytes handed render -&gt; worker. */
	private record RawFrame(int width, int height, float near, float far, byte[] rgba, byte[] depth) {}

	/** Start the worker threads on the given UDS endpoints. Sockets are created inside the threads that own them. */
	public synchronized void start(LinkConfig.UdsEndpoints endpoints) {
		if (running) {
			return;
		}
		this.endpoints = endpoints;
		running = true;

		senderThread = new Thread(this::senderLoop, "ocl-uds-sender");
		receiverThread = new Thread(this::receiverLoop, "ocl-uds-receiver");
		visionWorkerThread = new Thread(this::visionWorkerLoop, "ocl-vision-worker");
		visionSenderThread = new Thread(this::visionSenderLoop, "ocl-uds-vision-sender");
		senderThread.setDaemon(true);
		receiverThread.setDaemon(true);
		visionWorkerThread.setDaemon(true);
		visionSenderThread.setDaemon(true);
		senderThread.start();
		receiverThread.start();
		visionWorkerThread.start();
		visionSenderThread.start();

		OpenCrafterLink.LOGGER.info("[open-crafter-link] UDS bridge started: TEL {} | INSTR {} | VIS {}",
				endpoints.telemetry(), endpoints.instruction(), endpoints.vision());
	}

	/** Restart the worker threads on new endpoints. No-op'ing {@link #stop()} keeps this safe if not running. */
	public synchronized void restart(LinkConfig.UdsEndpoints endpoints) {
		stop();
		start(endpoints);
	}

	@Override
	public synchronized void stop() {
		if (!running) {
			return;
		}
		running = false;

		// Close any open sockets first so a blocked accept/connect/read/write throws and the loop exits.
		closeQuietly(telemetryChannel);
		closeQuietly(instructionChannel);
		closeQuietly(visionChannel);

		joinQuietly(senderThread);
		joinQuietly(receiverThread);
		joinQuietly(visionWorkerThread);
		joinQuietly(visionSenderThread);
		senderThread = null;
		receiverThread = null;
		visionWorkerThread = null;
		visionSenderThread = null;

		// Our two server sockets bound files; remove them so a later start() can bind cleanly.
		deleteQuietly(endpoints.telemetry());
		deleteQuietly(endpoints.vision());

		OpenCrafterLink.LOGGER.info("[open-crafter-link] UDS bridge stopped");
	}

	@Override
	public void publish(OutboundSnapshot snapshot) {
		outbox.set(snapshot);
	}

	@Override
	public InboundInstruction takeLatest() {
		return inbox.getAndSet(null);
	}

	@Override
	public void enqueueVisionRaw(int w, int h, float near, float far, byte[] rgba, byte[] depth) {
		visionRaw.set(new RawFrame(w, h, near, far, rgba, depth));
		LockSupport.unpark(visionWorkerThread);
	}

	// --------------------------------------------------------------------- //
	// Worker loops                                                          //
	// --------------------------------------------------------------------- //

	/**
	 * Telemetry sender (server): bind a UDS server socket, accept one controller connection, and stream
	 * conflated {@code OCLO} frames to it. On disconnect, loop back to accept the next connection.
	 */
	private void senderLoop() {
		serverSendLoop(endpoints.telemetry(), outbox, BinaryCodec::encodeOutbound,
				ch -> telemetryChannel = ch, 1_000_000L /* ~1ms */);
	}

	/**
	 * Vision sender (server): same server-send pattern as {@link #senderLoop()}, but for the larger
	 * {@code OCLV} frames drained from {@link #visionOutbox}.
	 */
	private void visionSenderLoop() {
		serverSendLoop(endpoints.vision(), visionOutbox, BinaryCodec::encodeVision,
				ch -> visionChannel = ch, 2_000_000L /* ~2ms */);
	}

	/**
	 * Instruction receiver (client): connect to the controller's UDS server, read length-prefixed
	 * {@code OCLI} frames, and conflate the newest into {@link #inbox}. Reconnects while running.
	 */
	private void receiverLoop() {
		ByteBuffer lenBuf = ByteBuffer.allocate(LEN_PREFIX).order(ByteOrder.LITTLE_ENDIAN);
		while (running) {
			try (SocketChannel ch = connect(endpoints.instruction())) {
				if (ch == null) {
					return; // stopped while waiting to connect
				}
				instructionChannel = ch;
				while (running) {
					byte[] payload = readFrame(ch, lenBuf);
					if (payload == null) {
						break; // peer closed — reconnect
					}
					inbox.set(BinaryCodec.decodeInbound(payload)); // conflating: newest wins
				}
			} catch (IOException e) {
				if (running) {
					OpenCrafterLink.LOGGER.debug("[open-crafter-link] instruction stream reconnecting", e);
				}
			} finally {
				instructionChannel = null;
			}
			parkBackoff();
		}
	}

	/**
	 * Vision worker: identical conversion to {@link ZmqBridge}'s — drain the latest raw readback, convert
	 * RGBA8 -&gt; normalized RGB and DEPTH32 -&gt; linearized far-normalized distance, then conflate the
	 * result for the vision sender. All heavy per-pixel math lives here, off the render thread.
	 */
	private void visionWorkerLoop() {
		try {
			while (running) {
				RawFrame raw = visionRaw.getAndSet(null);
				if (raw == null) {
					LockSupport.parkNanos(2_000_000L); // ~2ms; nothing fresh to convert
					continue;
				}
				VisionFrame frame = convert(raw);
				VisionTap.publish(frame); // no-op unless a recording session is active
				visionOutbox.set(frame);
				LockSupport.unpark(visionSenderThread);
			}
		} catch (Throwable t) {
			if (running) {
				OpenCrafterLink.LOGGER.error("[open-crafter-link] vision worker loop crashed", t);
			}
		}
	}

	// --------------------------------------------------------------------- //
	// Shared server-send / framing helpers                                  //
	// --------------------------------------------------------------------- //

	/**
	 * Generic "bind a UDS server, accept one client, stream conflated frames from {@code slot}" loop
	 * shared by telemetry and vision. Drops stale frames (conflation) and reconnects on client
	 * disconnect, all while {@code running}.
	 *
	 * @param path      socket path to bind
	 * @param slot      conflating source; {@code getAndSet(null)} yields the newest queued payload
	 * @param encoder   turns a payload into wire bytes ({@link BinaryCodec})
	 * @param track     records the live channel so {@link #stop()} can close it to unblock
	 * @param idleNanos park time when the slot is empty (matches the per-stream cadence)
	 */
	private <T> void serverSendLoop(Path path, AtomicReference<T> slot,
			java.util.function.Function<T, byte[]> encoder,
			java.util.function.Consumer<Channel> track, long idleNanos) {
		while (running) {
			ServerSocketChannel server = null;
			try {
				deleteQuietly(path);
				server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
				server.bind(UnixDomainSocketAddress.of(path));
				track.accept(server);

				while (running) {
					try (SocketChannel client = server.accept()) { // blocks until a controller connects
						if (client == null || !running) {
							break;
						}
						streamTo(client, slot, encoder, idleNanos);
					} catch (IOException e) {
						if (running) {
							OpenCrafterLink.LOGGER.debug("[open-crafter-link] client stream on {} ended", path, e);
						}
					}
				}
			} catch (IOException e) {
				if (running) {
					OpenCrafterLink.LOGGER.debug("[open-crafter-link] server on {} rebinding", path, e);
				}
			} catch (Throwable t) {
				if (running) {
					OpenCrafterLink.LOGGER.error("[open-crafter-link] server loop on {} crashed", path, t);
				}
			} finally {
				closeQuietly(server);
				track.accept(null);
			}
			parkBackoff();
		}
	}

	/** Drain-and-send the conflating {@code slot} to one connected client until it closes or we stop. */
	private <T> void streamTo(SocketChannel client, AtomicReference<T> slot,
			java.util.function.Function<T, byte[]> encoder, long idleNanos) throws IOException {
		while (running) {
			T payload = slot.getAndSet(null);
			if (payload == null) {
				LockSupport.parkNanos(idleNanos);
				continue;
			}
			writeFrame(client, encoder.apply(payload)); // throws on a broken pipe -> reconnect
		}
	}

	/** Connect a UDS client socket to {@code path}, retrying while running; {@code null} if we stopped. */
	private SocketChannel connect(Path path) throws IOException {
		while (running) {
			if (Files.exists(path)) {
				try {
					SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
					ch.connect(UnixDomainSocketAddress.of(path));
					return ch;
				} catch (IOException e) {
					// server not accepting yet — fall through and retry
				}
			}
			parkBackoff();
		}
		return null;
	}

	/** Write {@code u32-LE length + payload}. */
	private static void writeFrame(SocketChannel ch, byte[] payload) throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(LEN_PREFIX + payload.length).order(ByteOrder.LITTLE_ENDIAN);
		buf.putInt(payload.length);
		buf.put(payload);
		buf.flip();
		while (buf.hasRemaining()) {
			ch.write(buf);
		}
	}

	/**
	 * Read one {@code u32-LE length + payload} frame fully. Returns {@code null} on a clean EOF (peer
	 * closed). {@code lenBuf} is a reused 4-byte scratch buffer.
	 */
	private static byte[] readFrame(SocketChannel ch, ByteBuffer lenBuf) throws IOException {
		lenBuf.clear();
		if (!readFully(ch, lenBuf)) {
			return null;
		}
		lenBuf.flip();
		int len = lenBuf.getInt();
		if (len < 0 || len > MAX_FRAME) {
			throw new IOException("framing desync: implausible length " + len);
		}
		ByteBuffer body = ByteBuffer.allocate(len);
		if (!readFully(ch, body)) {
			return null;
		}
		return body.array();
	}

	/** Fill {@code buf} to its limit; {@code false} on EOF before it's full. */
	private static boolean readFully(SocketChannel ch, ByteBuffer buf) throws IOException {
		while (buf.hasRemaining()) {
			if (ch.read(buf) < 0) {
				return false;
			}
		}
		return true;
	}

	/** Convert a raw readback into a wire-ready {@link VisionFrame}. Identical to {@link ZmqBridge}'s. */
	private static VisionFrame convert(RawFrame raw) {
		int w = raw.width();
		int h = raw.height();
		int pixels = w * h;

		float[] rgb = new float[pixels * 3];
		byte[] rgba = raw.rgba();
		for (int p = 0, src = 0, dst = 0; p < pixels; p++, src += 4, dst += 3) {
			rgb[dst]     = (rgba[src]     & 0xFF) / 255.0f;
			rgb[dst + 1] = (rgba[src + 1] & 0xFF) / 255.0f;
			rgb[dst + 2] = (rgba[src + 2] & 0xFF) / 255.0f;
			// alpha (src + 3) dropped
		}

		float near = raw.near();
		float far = raw.far();
		float[] depth = new float[pixels];
		ByteBuffer db = ByteBuffer.wrap(raw.depth()).order(ByteOrder.LITTLE_ENDIAN);
		float invFar = (far > 0.0f) ? 1.0f / far : 0.0f;
		for (int p = 0; p < pixels; p++) {
			float rawD = db.getFloat(p * 4);                 // perspective depth in [0,1]
			float zNdc = rawD * 2.0f - 1.0f;                 // -> NDC [-1,1]
			float denom = far + near - zNdc * (far - near);
			float zLinear = (denom != 0.0f) ? (2.0f * near * far) / denom : far; // eye-space blocks
			float norm = zLinear * invFar;                   // normalize 0..1 by far
			depth[p] = (norm < 0.0f) ? 0.0f : (Math.min(norm, 1.0f));
		}

		return new VisionFrame(w, h, near, far, rgb, depth);
	}

	// --------------------------------------------------------------------- //
	// Small utilities                                                       //
	// --------------------------------------------------------------------- //

	private void parkBackoff() {
		LockSupport.parkNanos(RETRY_MS * 1_000_000L);
	}

	private static void closeQuietly(Channel ch) {
		if (ch != null) {
			try {
				ch.close();
			} catch (IOException ignored) {
				// best-effort
			}
		}
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// best-effort; bind will surface a real problem
		}
	}

	private static void joinQuietly(Thread t) {
		if (t == null) {
			return;
		}
		try {
			t.join(1000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
