package mod.kelvinlby.link;

import mod.kelvinlby.OpenCrafterLink;
import mod.kelvinlby.recorder.VisionTap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * The transport-agnostic core shared by every {@link LinkBridge} that speaks the length-prefixed
 * {@link BinaryCodec} framing over a {@link java.nio.channels}-family socket — {@link UdsBridge} over
 * {@code AF_UNIX}, {@link TcpBridge} over {@code AF_INET}. It owns the JVM-side single-slot conflating
 * queues, the non-conflating action FIFO, the four worker threads, the vision conversion, and all the
 * frame read/write/reconnect plumbing. Subclasses supply only the three per-transport pieces: how to
 * open the two outbound server channels, how to open the inbound instruction client channel, and any
 * teardown ({@link #onStopped()}).
 *
 * <p>The conflating slots (newest payload wins, stale dropped) mirror what {@code ZMQ_CONFLATE} did,
 * so the game threads only ever call the O(1), non-blocking hand-off methods and never touch a socket.
 * Every socket is created and used entirely within its owning worker thread.
 *
 * <p>Robustness: a stream survives the controller not being up yet, going away, and reconnecting — the
 * accept/connect/read/write loops retry while {@link #running}. {@link #stop()} flips the flag and
 * closes the live channels to unblock any in-flight blocking call, then joins the workers.
 */
public abstract class AbstractLinkBridge implements LinkBridge {

	/** 4-byte little-endian length prefix that precedes every framed payload. */
	protected static final int LEN_PREFIX = 4;
	/** Sanity cap so a desynced/garbage prefix can't trigger a huge allocation. */
	protected static final int MAX_FRAME = 64 * 1024 * 1024;
	/** Poll/retry backoff for accept/connect loops (ms) — keeps {@link #stop()} responsive. */
	protected static final long RETRY_MS = 200L;
	/** Cap on the pending-action FIFO; a runaway sender drops oldest rather than growing unbounded. */
	private static final int MAX_ACTIONS = 64;

	/** Latest telemetry waiting to be published. Tick thread writes; sender thread drains. */
	private final AtomicReference<OutboundSnapshot> outbox = new AtomicReference<>();
	/** Latest movement instruction received. Receiver thread writes; tick thread reads. */
	private final AtomicReference<InboundInstruction> inbox = new AtomicReference<>();
	/**
	 * Pending inventory actions. Receiver thread offers; tick thread polls. Non-conflating (FIFO) so no
	 * action is lost when movement floods the inbox; bounded so a runaway sender can't grow it unbounded.
	 */
	private final ConcurrentLinkedQueue<InventoryAction> actionQueue = new ConcurrentLinkedQueue<>();

	/** Latest raw framebuffer readback. Render thread writes; vision worker drains. */
	private final AtomicReference<RawFrame> visionRaw = new AtomicReference<>();
	/** Latest converted vision frame. Vision worker writes; vision sender drains. */
	private final AtomicReference<VisionFrame> visionOutbox = new AtomicReference<>();

	protected volatile boolean running;

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

	/** Raw, already-downsampled framebuffer bytes handed render -&gt; vision worker (RGBA8 + DEPTH32). */
	private record RawFrame(int width, int height, float near, float far, byte[] rgba, byte[] depth) {}

	// --------------------------------------------------------------------- //
	// Lifecycle                                                             //
	// --------------------------------------------------------------------- //

	/**
	 * Spawn the four daemon workers. Called by a subclass's typed {@code start(...)} once it has stored
	 * its endpoints. Idempotent while already running.
	 */
	protected final synchronized void startWorkers() {
		if (running) {
			return;
		}
		running = true;
		String p = threadPrefix();
		senderThread = new Thread(this::senderLoop, "ocl-" + p + "-sender");
		receiverThread = new Thread(this::receiverLoop, "ocl-" + p + "-receiver");
		visionWorkerThread = new Thread(this::visionWorkerLoop, "ocl-vision-worker");
		visionSenderThread = new Thread(this::visionSenderLoop, "ocl-" + p + "-vision-sender");
		for (Thread t : new Thread[]{senderThread, receiverThread, visionWorkerThread, visionSenderThread}) {
			t.setDaemon(true);
			t.start();
		}
	}

	@Override
	public final synchronized void stop() {
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
		senderThread = receiverThread = visionWorkerThread = visionSenderThread = null;

		onStopped(); // transport-specific teardown (UDS deletes its bound .sock files; TCP: nothing)
	}

	// --------------------------------------------------------------------- //
	// Transport-agnostic hand-offs (called by the game threads)             //
	// --------------------------------------------------------------------- //

	@Override
	public final void publish(OutboundSnapshot snapshot) {
		outbox.set(snapshot);
	}

	@Override
	public final InboundInstruction takeLatest() {
		return inbox.get();
	}

	@Override
	public final InventoryAction takeAction() {
		return actionQueue.poll();
	}

	/** Receiver thread: enqueue a real action, dropping the oldest if the bounded queue is full. */
	private void enqueueAction(InventoryAction action) {
		if (action == null || action.op() == InventoryAction.Op.NONE) {
			return;
		}
		while (actionQueue.size() >= MAX_ACTIONS) {
			actionQueue.poll(); // drop-oldest safety valve; actions are rare so this shouldn't trigger
		}
		actionQueue.offer(action);
	}

	@Override
	public final void enqueueVisionRaw(int w, int h, float near, float far, byte[] rgba, byte[] depth) {
		visionRaw.set(new RawFrame(w, h, near, far, rgba, depth));
		LockSupport.unpark(visionWorkerThread);
	}

	// --------------------------------------------------------------------- //
	// Per-transport hooks                                                   //
	// --------------------------------------------------------------------- //

	/** Short prefix for worker thread names (e.g. {@code "uds"} / {@code "tcp"}). */
	protected abstract String threadPrefix();

	/** Open (bind) the outbound telemetry server channel. Called inside the sender worker. */
	protected abstract ServerSocketChannel openTelemetryServer() throws IOException;

	/** Open (bind) the outbound vision server channel. Called inside the vision-sender worker. */
	protected abstract ServerSocketChannel openVisionServer() throws IOException;

	/**
	 * Open (connect) one inbound instruction client channel, retrying while {@link #running}; return
	 * {@code null} if we stopped before connecting. Called inside the receiver worker.
	 */
	protected abstract SocketChannel openInstructionClient() throws IOException;

	/** Transport-specific teardown after the workers have joined (e.g. delete bound socket files). */
	protected abstract void onStopped();

	// --------------------------------------------------------------------- //
	// Worker loops                                                          //
	// --------------------------------------------------------------------- //

	private void senderLoop() {
		serverSendLoop(this::openTelemetryServer, outbox, BinaryCodec::encodeOutbound,
				ch -> telemetryChannel = ch, 1_000_000L /* ~1ms */);
	}

	private void visionSenderLoop() {
		serverSendLoop(this::openVisionServer, visionOutbox, BinaryCodec::encodeVision,
				ch -> visionChannel = ch, 2_000_000L /* ~2ms */);
	}

	/**
	 * Instruction receiver (client): connect to the controller's server, read length-prefixed
	 * {@code OCLI} frames, conflate the newest movement into {@link #inbox} and route actions to the
	 * FIFO. Reconnects while running.
	 */
	private void receiverLoop() {
		ByteBuffer lenBuf = ByteBuffer.allocate(LEN_PREFIX).order(ByteOrder.LITTLE_ENDIAN);
		while (running) {
			try (SocketChannel ch = openInstructionClient()) {
				if (ch == null) {
					return; // stopped while waiting to connect
				}
				instructionChannel = ch;
				while (running) {
					byte[] payload = readFrame(ch, lenBuf);
					if (payload == null) {
						break; // peer closed — reconnect
					}
					BinaryCodec.InboundMessage msg = BinaryCodec.decodeInbound(payload);
					inbox.set(msg.movement());   // movement conflates: newest wins
					enqueueAction(msg.action()); // actions are FIFO, never conflated
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
	 * Vision worker: drain the latest raw readback, convert RGBA8 -&gt; normalized RGB and DEPTH32 -&gt;
	 * linearized far-normalized distance, then conflate the result for the vision sender. All heavy
	 * per-pixel math lives here, off the render thread.
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
	 * Generic "bind a server, accept one client, stream conflated frames from {@code slot}" loop shared
	 * by telemetry and vision. Drops stale frames (conflation) and reconnects on client disconnect, all
	 * while {@code running}.
	 *
	 * @param server    opens (binds) a fresh server channel each rebind
	 * @param slot      conflating source; {@code getAndSet(null)} yields the newest queued payload
	 * @param encoder   turns a payload into wire bytes ({@link BinaryCodec})
	 * @param track     records the live channel so {@link #stop()} can close it to unblock
	 * @param idleNanos park time when the slot is empty (matches the per-stream cadence)
	 */
	private <T> void serverSendLoop(ServerChannelFactory server, AtomicReference<T> slot,
			java.util.function.Function<T, byte[]> encoder,
			java.util.function.Consumer<Channel> track, long idleNanos) {
		while (running) {
			ServerSocketChannel srv = null;
			try {
				srv = server.open();
				track.accept(srv);

				while (running) {
					try (SocketChannel client = srv.accept()) { // blocks until a controller connects
						if (client == null || !running) {
							break;
						}
						streamTo(client, slot, encoder, idleNanos);
					} catch (IOException e) {
						if (running) {
							OpenCrafterLink.LOGGER.debug("[open-crafter-link] client stream ended", e);
						}
					}
				}
			} catch (IOException e) {
				if (running) {
					OpenCrafterLink.LOGGER.debug("[open-crafter-link] send server rebinding", e);
				}
			} catch (Throwable t) {
				if (running) {
					OpenCrafterLink.LOGGER.error("[open-crafter-link] send server loop crashed", t);
				}
			} finally {
				closeQuietly(srv);
				track.accept(null);
			}
			parkBackoff();
		}
	}

	/** Opens (binds) a fresh {@link ServerSocketChannel}; supplied per stream by the subclass. */
	@FunctionalInterface
	protected interface ServerChannelFactory {
		ServerSocketChannel open() throws IOException;
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

	/** Write {@code u32-LE length + payload}. */
	protected static void writeFrame(SocketChannel ch, byte[] payload) throws IOException {
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
	protected static byte[] readFrame(SocketChannel ch, ByteBuffer lenBuf) throws IOException {
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

	/** Convert a raw readback into a wire-ready {@link VisionFrame} (off the render thread). */
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

	protected void parkBackoff() {
		LockSupport.parkNanos(RETRY_MS * 1_000_000L);
	}

	protected static void closeQuietly(Channel ch) {
		if (ch != null) {
			try {
				ch.close();
			} catch (IOException ignored) {
				// best-effort
			}
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
