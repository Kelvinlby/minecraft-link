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
 * @param health       current health in half-heart points (0..20)
 * @param food         current hunger/food level (0..20)
 * @param xpLevel      current experience level
 */
public record OutboundSnapshot(
		float yaw,
		float pitch,
		int selectedSlot,
		float health,
		int food,
		int xpLevel) {
}
