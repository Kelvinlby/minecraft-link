package mod.kelvinlby.recorder;

import mod.kelvinlby.link.VisionFrame;

/**
 * One aligned dataset sample: an RGBD frame and the action set observed at the same clock tick,
 * stamped with a monotonic sequence number and a wall-clock timestamp. Produced by {@link Sampler} on
 * its fixed clock and drained by {@link DatasetWriter}.
 *
 * <p>{@code frameRepeated} is true when no fresh RGBD frame was available this tick (e.g. the game was
 * paused or in a menu) and the previous frame was reused, so the stream stays 1:1 with the clock; the
 * loader can use it to mask duplicated frames if desired.
 *
 * @param seqno        monotonic sample index, starting at 0
 * @param timestampNs  {@code System.nanoTime()} at the moment the sample was latched
 * @param vision       the RGBD frame (RGB + linearized/normalized depth, plus near/far)
 * @param action       the action set observed at this tick
 * @param frameRepeated whether {@code vision} is a reused previous frame (no fresh capture this tick)
 */
public record Sample(
		long seqno,
		long timestampNs,
		VisionFrame vision,
		ActionSet action,
		boolean frameRepeated) {
}
