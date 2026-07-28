package mod.kelvinlby.recorder;

import mod.kelvinlby.link.VisionFrame;

import java.util.EnumSet;
import java.util.Set;
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
	}

	/** True while at least one consumer wants frames — bridges check this before publishing. */
	public static boolean isActive() {
		return any;
	}

	/** Bridge vision worker: hand off the newest converted RGBD frame. O(1), non-blocking. */
	public static void publish(VisionFrame frame) {
		if (any) {
			LATEST.set(frame);
		}
	}

	/** Consumers: the most recent frame published, or {@code null} if none since the last reset. */
	public static VisionFrame latest() {
		return LATEST.get();
	}
}
