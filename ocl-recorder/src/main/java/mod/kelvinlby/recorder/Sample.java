package mod.kelvinlby.recorder;

import mod.kelvinlby.link.InventoryState;

/**
 * One aligned dataset sample: a packed RGBD frame, the action set, and the inventory snapshot observed
 * at the same clock tick, stamped with a monotonic sequence number and a wall-clock timestamp. Produced
 * by {@link Sampler} on its fixed clock and drained by {@link DatasetWriter}.
 *
 * <p>{@code frameRepeated} is true when no fresh RGBD frame was available this tick (e.g. the game
 * was paused or in a menu) and the previous frame was reused, so the stream stays 1:1 with the
 * clock; the loader can use it to mask duplicated frames if desired. Repeated samples share the
 * same {@link PackedFrame} instance — no per-tick re-pack.
 *
 * <p>{@code inventory} is the current screen's contents (every slot's item + count, plus the cursor)
 * latched on the client tick thread — an observation like {@code vision}, distinct from the discrete
 * actions carried on {@code action}. It lets each sample be inventory-self-describing without replaying
 * the action stream.
 *
 * @param seqno         monotonic sample index, starting at 0
 * @param timestampNs   {@code System.nanoTime()} at the moment the sample was latched
 * @param vision        the packed RGBD frame (byte RGB + uint16 depth, plus near/far)
 * @param action        the action set observed at this tick
 * @param inventory     the inventory contents observed at this tick (never null; may be empty)
 * @param frameRepeated whether {@code vision} is a reused previous frame (no fresh capture this tick)
 */
public record Sample(
		long seqno,
		long timestampNs,
		PackedFrame vision,
		ActionSet action,
		InventoryState inventory,
		boolean frameRepeated) {
}
