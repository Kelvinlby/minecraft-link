package mod.kelvinlby.link;

import mod.kelvinlby.OpenCrafterLink;
import mod.kelvinlby.recorder.VisionTap;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Owns the ZMQ context, the three sockets — PUB (outbound telemetry), SUB (inbound instructions),
 * and a second PUB (outbound RGBD vision) — and the four worker threads that drive them (sender,
 * receiver, vision-worker, vision-sender). ZMQ sockets are not thread-safe, so each socket is created
 * and used entirely within its own thread; the game threads never touch a socket, only the
 * {@link AtomicReference} handoffs (the tick thread touches {@code outbox}/{@code inbox}; the render
 * thread touches {@code visionRaw}).
 *
 * <p>Those references act as JVM-side single-slot conflating queues that mirror {@code ZMQ_CONFLATE}:
 * the newest payload always wins and stale ones are dropped, in every direction, without the game
 * threads ever blocking.
 */
public final class ZmqBridge implements LinkBridge {
	/** Recreated on each {@link #start}: a {@link ZContext} cannot be reused after {@code close()}. */
	private ZContext ctx;

	/** Latest telemetry waiting to be published. Tick thread writes; sender thread drains. */
	private final AtomicReference<OutboundSnapshot> outbox = new AtomicReference<>();
	/** Latest instruction received. Receiver thread writes; tick thread drains-and-clears. */
	private final AtomicReference<InboundInstruction> inbox = new AtomicReference<>();

	/** Latest raw framebuffer readback. Render thread writes; vision worker drains. */
	private final AtomicReference<RawFrame> visionRaw = new AtomicReference<>();
	/** Latest converted vision frame. Vision worker writes; vision sender drains. */
	private final AtomicReference<VisionFrame> visionOutbox = new AtomicReference<>();

	private volatile boolean running;
	/** Endpoints the worker threads bind/connect; set by {@link #start(LinkConfig.Endpoints)}. */
	private volatile LinkConfig.Endpoints endpoints;
	private Thread senderThread;
	private Thread receiverThread;
	private Thread visionWorkerThread;
	private Thread visionSenderThread;

	/**
	 * Raw, already-downsampled framebuffer bytes handed from the render thread to the vision worker.
	 * Color is interleaved RGBA8 ({@code w*h*4} bytes), depth is DEPTH32 ({@code w*h*4} bytes, native
	 * float). The heavy per-pixel float conversion + depth linearization happens on the worker.
	 */
	private record RawFrame(int width, int height, float near, float far, byte[] rgba, byte[] depth) {}

	/** Start the worker threads on the given endpoints. Sockets are created inside the threads that own them. */
	public synchronized void start(LinkConfig.Endpoints endpoints) {
		if (running) {
			return;
		}
		this.endpoints = endpoints;
		ctx = new ZContext(); // fresh context; the previous one (if any) was closed in stop()
		running = true;

		senderThread = new Thread(this::senderLoop, "ocl-zmq-sender");
		receiverThread = new Thread(this::receiverLoop, "ocl-zmq-receiver");
		visionWorkerThread = new Thread(this::visionWorkerLoop, "ocl-vision-worker");
		visionSenderThread = new Thread(this::visionSenderLoop, "ocl-vision-sender");
		senderThread.setDaemon(true);
		receiverThread.setDaemon(true);
		visionWorkerThread.setDaemon(true);
		visionSenderThread.setDaemon(true);
		senderThread.start();
		receiverThread.start();
		visionWorkerThread.start();
		visionSenderThread.start();

		OpenCrafterLink.LOGGER.info("[open-crafter-link] bridge started: PUB {} | SUB {} | VIS-PUB {}",
				endpoints.pub(), endpoints.sub(), endpoints.visPub());
	}

	/** Restart the worker threads on new endpoints. No-op'ing {@link #stop()} keeps this safe if not running. */
	public synchronized void restart(LinkConfig.Endpoints endpoints) {
		stop();
		start(endpoints);
	}

	/** Stop the worker threads and tear down sockets + context. Idempotent. */
	@Override
	public synchronized void stop() {
		if (!running) {
			return;
		}
		running = false;

		joinQuietly(senderThread);
		joinQuietly(receiverThread);
		joinQuietly(visionWorkerThread);
		joinQuietly(visionSenderThread);
		senderThread = null;
		receiverThread = null;
		visionWorkerThread = null;
		visionSenderThread = null;

		// Threads have exited and released their sockets; closing the context is now safe.
		ctx.close();
		ctx = null; // a closed ZContext cannot be reused; start() will create a fresh one
		OpenCrafterLink.LOGGER.info("[open-crafter-link] bridge stopped");
	}

	/** Tick thread: hand off the latest telemetry. O(1), non-blocking, conflating (newest wins). */
	@Override
	public void publish(OutboundSnapshot snapshot) {
		outbox.set(snapshot);
	}

	/** Tick thread: consume-and-clear the latest instruction, or {@code null} if none arrived. */
	@Override
	public InboundInstruction takeLatest() {
		return inbox.getAndSet(null);
	}

	/**
	 * Render thread: hand off raw, already-downsampled framebuffer bytes. O(1), non-blocking,
	 * conflating (newest wins — a frame the worker hasn't picked up yet is simply dropped). The
	 * worker does the float conversion + depth linearization off the render thread.
	 *
	 * @param rgba interleaved RGBA8, {@code w*h*4} bytes
	 * @param depth DEPTH32 native-float bytes, {@code w*h*4} bytes
	 */
	@Override
	public void enqueueVisionRaw(int w, int h, float near, float far, byte[] rgba, byte[] depth) {
		visionRaw.set(new RawFrame(w, h, near, far, rgba, depth));
		LockSupport.unpark(visionWorkerThread);
	}

	private void senderLoop() {
        try (ZMQ.Socket pub = ctx.createSocket(SocketType.PUB)) {
            pub.setConflate(true); // must precede bind
            pub.bind(endpoints.pub());

            while (running) {
                OutboundSnapshot snapshot = outbox.getAndSet(null);
                if (snapshot == null) {
                    LockSupport.parkNanos(1_000_000L); // ~1ms; nothing fresh to send
                    continue;
                }
                // Heavy work (encoding) is fine here — off the tick thread.
                byte[] payload = BinaryCodec.encodeOutbound(snapshot);
                pub.send(payload, 0);
            }
        } catch (Throwable t) {
            if (running) {
                OpenCrafterLink.LOGGER.error("[open-crafter-link] sender loop crashed", t);
            }
        }
	}

	private void receiverLoop() {
        try (ZMQ.Socket sub = ctx.createSocket(SocketType.SUB)) {
            sub.setConflate(true);                  // must precede connect; requires subscribe-all
            sub.subscribe(ZMQ.SUBSCRIPTION_ALL);
            sub.setReceiveTimeOut(LinkConfig.RECV_POLL_MS);
            sub.connect(endpoints.sub());

            while (running) {
                byte[] msg = sub.recv(0); // returns null on timeout, keeping stop() responsive
                if (msg == null) {
                    continue;
                }
                inbox.set(BinaryCodec.decodeInbound(msg)); // conflating: newest instruction wins
            }
        } catch (Throwable t) {
            if (running) {
                OpenCrafterLink.LOGGER.error("[open-crafter-link] receiver loop crashed", t);
            }
        }
	}

	/**
	 * Vision worker: drain the latest raw readback, convert RGBA8 -&gt; normalized RGB and DEPTH32 -&gt;
	 * linearized, far-normalized distance, then conflate the result for the vision sender. All the heavy
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

	/** Convert a raw readback into a wire-ready {@link VisionFrame}. */
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

	private void visionSenderLoop() {
        try (ZMQ.Socket pub = ctx.createSocket(SocketType.PUB)) {
            pub.setConflate(true); // must precede bind
            pub.bind(endpoints.visPub());

            while (running) {
                VisionFrame frame = visionOutbox.getAndSet(null);
                if (frame == null) {
                    LockSupport.parkNanos(2_000_000L); // ~2ms; nothing fresh to send
                    continue;
                }
                byte[] payload = BinaryCodec.encodeVision(frame);
                pub.send(payload, 0);
            }
        } catch (Throwable t) {
            if (running) {
                OpenCrafterLink.LOGGER.error("[open-crafter-link] vision sender loop crashed", t);
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
