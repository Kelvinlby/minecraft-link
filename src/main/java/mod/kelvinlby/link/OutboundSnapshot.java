package mod.kelvinlby.link;

/**
 * Immutable snapshot of player state published to the controller each tick.
 *
 * <p>Vision travels on its own frame-rate {@code OCLV} stream (see {@link VisionCapture} /
 * {@link BinaryCodec#encodeVision}), never with this per-tick telemetry.
 *
 * @param yaw          player yaw in degrees
 * @param pitch        player pitch in degrees ([-90, 90])
 * @param selectedSlot main-hand hotbar slot (0..8)
 */
public record OutboundSnapshot(
		float yaw,
		float pitch,
		int selectedSlot) {
}
