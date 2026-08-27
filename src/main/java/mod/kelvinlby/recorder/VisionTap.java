package mod.kelvinlby.recorder;

import mod.kelvinlby.OpenCrafterLink;
import mod.kelvinlby.link.VisionFrame;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A transport-agnostic seam that lets local consumers observe the RGBD frames the link already
 * produces, without coupling the link bridge to any of them. The shared vision worker, right after it
 * converts a raw readback into a wire-ready {@link VisionFrame} (see {@code AbstractLinkBridge.convert}),
 * also calls {@link #publish(VisionFrame)}. Each consumer reads the latest frame via {@link #latest()}
 * on its own fixed clock.
 *
 * <p>This reuses all the existing render-thread readback + float conversion + depth linearization
 * work; consumers never touch the GPU or re-do any per-pixel math. The single-slot
 * {@link AtomicReference} mirrors the link's own conflating handoffs: a consumer always latches the
 * newest converted frame, and if none is fresh (menu/paused) it repeats the last one.
 *
 * <h2>Why activation is refcounted</h2>
 * There are now two independent consumers — the dataset {@link Recorder} and the virtual cameras —
 * with unrelated lifecycles. A single {@code active} boolean made whichever one stopped last win: a
 * recording session ending called {@code setActive(false)} and silently starved a running virtual
 * camera (and cleared {@link #LATEST} out from under it). So interest is tracked as a
 * {@link Consumer} set, and publishing stops only once <em>every</em> consumer has gone away.
 *
 * <h2>Why eager replay gets its own lane</h2>
 * Conflation is right for live consumers — a virtual camera wants the newest frame, never a backlog.
 * It is wrong for eager replay, which pipelines several sample windows through the GPU read-back ring
 * at once and must receive <em>every</em> one of them, in order: a conflating slot would silently drop
 * whichever frames the sampler did not latch in time, punching holes in the dataset. So a frame
 * published with a sample sequence number goes onto a bounded FIFO ({@link #EAGER}) as well, drained by
 * {@link #takeEager}. Replay caps its in-flight windows well below that queue's capacity, so the offer
 * cannot fail in practice; if it ever did, the frame is dropped loudly rather than silently.
 *
 * <p>All state is static because there is exactly one link per client; the bridge threads publish and
 * the consumer threads read, both non-blocking. {@link #any} is a plain volatile mirror of "the set is
 * non-empty" so the publish hot path never touches the lock.
 */
public final class VisionTap {
	private VisionTap() {}

	/** An independent downstream reader of the tap, each with its own lifecycle. */
	public enum Consumer {
		/** The dataset recorder, armed per world-scoped session. */
		RECORDER,
		/** The v4l2 virtual cameras, toggled from the settings screen. */
		VIRTUAL_CAMERA
	}

	/** Newest converted frame. Bridge vision-worker threads write; consumer threads read. */
	private static final AtomicReference<VisionFrame> LATEST = new AtomicReference<>();

	/** Consumers currently wanting frames. Guarded by the class monitor; read only via {@link #any}. */
	private static final Set<Consumer> ACTIVE = EnumSet.noneOf(Consumer.class);

	/** Lock-free mirror of {@code !ACTIVE.isEmpty()} for the publish hot path. */
	private static volatile boolean any;

	/**
	 * Depth of the eager FIFO. Comfortably above the number of sample windows replay keeps in flight
	 * (which is itself bounded by the read-back ring), so publishing never has to drop or block.
	 */
	private static final int EAGER_CAPACITY = 16;

	/** Ordered eager-replay lane. Vision worker offers; the recorder's sampler thread takes. */
	private static final BlockingQueue<SeqFrame> EAGER = new ArrayBlockingQueue<>(EAGER_CAPACITY);

	/** One converted frame together with the sample window it was rendered for. */
	public record SeqFrame(long seq, VisionFrame frame) {}

	/**
	 * Register or clear one consumer's interest. The tap keeps publishing while any other consumer is
	 * still active; the retained frame is dropped only when the last one leaves.
	 */
	public static synchronized void setActive(Consumer consumer, boolean on) {
		if (on) {
			ACTIVE.add(consumer);
		} else {
			ACTIVE.remove(consumer);
		}
		any = !ACTIVE.isEmpty();
		if (!any) {
			LATEST.set(null);
		}
		if (consumer == Consumer.RECORDER) {
			EAGER.clear(); // never let frames from a finished session leak into the next one
		}
	}

	/** True while at least one consumer wants frames — bridges check this before publishing. */
	public static boolean isActive() {
		return any;
	}

	/** Bridge vision worker: hand off the newest converted RGBD frame. O(1), non-blocking. */
	public static void publish(VisionFrame frame) {
		publish(frame, -1L);
	}

	/**
	 * Bridge vision worker: hand off a converted RGBD frame. O(1), non-blocking. A non-negative
	 * {@code seq} additionally queues it on the ordered eager lane for {@link #takeEager}.
	 */
	public static void publish(VisionFrame frame, long seq) {
		if (seq >= 0 && !EAGER.offer(new SeqFrame(seq, frame))) {
			// Cannot happen while replay caps its in-flight windows below EAGER_CAPACITY; if the cap is
			// ever raised past it, this is the sample that goes missing — so say so.
			OpenCrafterLink.LOGGER.error("[open-crafter-link] eager vision queue overflow; dropping sample {}", seq);
		}
		if (any) {
			LATEST.set(frame);
		}
	}

	/**
	 * Eager replay's sampler: take the next converted frame in sample order, or {@code null} if none
	 * arrived within {@code timeoutMillis} (so the caller can re-check its stop flag).
	 */
	public static SeqFrame takeEager(long timeoutMillis) throws InterruptedException {
		return EAGER.poll(timeoutMillis, TimeUnit.MILLISECONDS);
	}

	/** Consumers: the most recent frame published, or {@code null} if none since the last reset. */
	public static VisionFrame latest() {
		return LATEST.get();
	}
}
