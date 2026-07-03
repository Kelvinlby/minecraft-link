package mod.kelvinlby.recorder;

import mod.kelvinlby.link.VisionFrame;

import java.util.concurrent.atomic.AtomicReference;

/**
 * A transport-agnostic seam that lets the recorder observe the RGBD frames the link already produces,
 * without coupling either ZMQ/UDS bridge to the recorder. Each bridge's vision worker, right after it
 * converts a raw readback into a wire-ready {@link VisionFrame} (see {@code ZmqBridge.convert} /
 * {@code UdsBridge.convert}), also calls {@link #publish(VisionFrame)}. The recorder's {@code Sampler}
 * reads the latest frame via {@link #latest()} on its own fixed clock.
 *
 * <p>This reuses all the existing render-thread readback + float conversion + depth linearization
 * work; the recorder never touches the GPU or re-does any per-pixel math. The single-slot
 * {@link AtomicReference} mirrors the link's own conflating handoffs: the recorder always latches the
 * newest converted frame, and if none is fresh (menu/paused) the sampler repeats the last one.
 *
 * <p>All fields are static because there is exactly one link and one recorder per client; the bridge
 * threads publish and the sampler thread reads, both non-blocking.
 */
public final class VisionTap {
	private VisionTap() {}

	/** Newest converted frame. Bridge vision-worker threads write; the sampler thread reads. */
	private static final AtomicReference<VisionFrame> LATEST = new AtomicReference<>();

	/** Whether any recorder is currently interested, so the bridges can cheaply skip publishing. */
	private static volatile boolean active;

	/** Enable/disable the tap. Called by {@link Recorder} on start/stop. */
	public static void setActive(boolean on) {
		active = on;
		if (!on) {
			LATEST.set(null);
		}
	}

	/** True while a recording session wants frames — bridges check this before publishing. */
	public static boolean isActive() {
		return active;
	}

	/** Bridge vision worker: hand off the newest converted RGBD frame. O(1), non-blocking. */
	public static void publish(VisionFrame frame) {
		if (active) {
			LATEST.set(frame);
		}
	}

	/** Sampler: the most recent frame published, or {@code null} if none since the last reset. */
	public static VisionFrame latest() {
		return LATEST.get();
	}
}
