package mod.kelvinlby.link;

import mod.kelvinlby.OpenCrafterLink;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Owns the ZMQ context, the PUB (outbound) and SUB (inbound) sockets, and the two worker threads
 * that drive them. ZMQ sockets are not thread-safe, so each socket is created and used entirely
 * within its own thread; the tick thread only ever touches the two {@link AtomicReference} handoffs.
 *
 * <p>Those references act as JVM-side single-slot conflating queues that mirror {@code ZMQ_CONFLATE}:
 * the newest payload always wins and stale ones are dropped, in both directions, without the tick
 * thread ever blocking.
 */
public final class ZmqBridge {
	private final ZContext ctx = new ZContext();

	/** Latest telemetry waiting to be published. Tick thread writes; sender thread drains. */
	private final AtomicReference<OutboundSnapshot> outbox = new AtomicReference<>();
	/** Latest instruction received. Receiver thread writes; tick thread drains-and-clears. */
	private final AtomicReference<InboundInstruction> inbox = new AtomicReference<>();

	private volatile boolean running;
	private Thread senderThread;
	private Thread receiverThread;

	/** Start the worker threads. Sockets are created inside the threads that own them. */
	public synchronized void start() {
		if (running) {
			return;
		}
		running = true;

		senderThread = new Thread(this::senderLoop, "ocl-zmq-sender");
		receiverThread = new Thread(this::receiverLoop, "ocl-zmq-receiver");
		senderThread.setDaemon(true);
		receiverThread.setDaemon(true);
		senderThread.start();
		receiverThread.start();

		OpenCrafterLink.LOGGER.info("[open-crafter-link] bridge started: PUB {} | SUB {}",
				LinkConfig.PUB_ENDPOINT, LinkConfig.SUB_ENDPOINT);
	}

	/** Stop the worker threads and tear down sockets + context. Idempotent. */
	public synchronized void stop() {
		if (!running) {
			return;
		}
		running = false;

		joinQuietly(senderThread);
		joinQuietly(receiverThread);
		senderThread = null;
		receiverThread = null;

		// Threads have exited and released their sockets; closing the context is now safe.
		ctx.close();
		OpenCrafterLink.LOGGER.info("[open-crafter-link] bridge stopped");
	}

	/** Tick thread: hand off the latest telemetry. O(1), non-blocking, conflating (newest wins). */
	public void publish(OutboundSnapshot snapshot) {
		outbox.set(snapshot);
	}

	/** Tick thread: consume-and-clear the latest instruction, or {@code null} if none arrived. */
	public InboundInstruction takeLatest() {
		return inbox.getAndSet(null);
	}

	private void senderLoop() {
		ZMQ.Socket pub = ctx.createSocket(SocketType.PUB);
		try {
			pub.setConflate(true); // must precede bind
			pub.bind(LinkConfig.PUB_ENDPOINT);

			while (running) {
				OutboundSnapshot snapshot = outbox.getAndSet(null);
				if (snapshot == null) {
					LockSupport.parkNanos(1_000_000L); // ~1ms; nothing fresh to send
					continue;
				}
				// Heavy work (encode, and eventually vision) is fine here — off the tick thread.
				byte[] payload = BinaryCodec.encodeOutbound(snapshot);
				pub.send(payload, 0);
			}
		} catch (Throwable t) {
			if (running) {
				OpenCrafterLink.LOGGER.error("[open-crafter-link] sender loop crashed", t);
			}
		} finally {
			pub.close();
		}
	}

	private void receiverLoop() {
		ZMQ.Socket sub = ctx.createSocket(SocketType.SUB);
		try {
			sub.setConflate(true);                  // must precede connect; requires subscribe-all
			sub.subscribe(ZMQ.SUBSCRIPTION_ALL);
			sub.setReceiveTimeOut(LinkConfig.RECV_POLL_MS);
			sub.connect(LinkConfig.SUB_ENDPOINT);

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
		} finally {
			sub.close();
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
