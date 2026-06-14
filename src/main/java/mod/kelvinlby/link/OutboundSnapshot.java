package mod.kelvinlby.link;

/**
 * Immutable snapshot of player state published to the controller each tick.
 *
 * @param yaw          player yaw in degrees
 * @param pitch        player pitch in degrees ([-90, 90])
 * @param selectedSlot main-hand hotbar slot (0..8)
 * @param visionWidth  vision frame width in pixels (0 when vision disabled)
 * @param visionHeight vision frame height in pixels (0 when vision disabled)
 * @param visionRgb    flat row-major interleaved RGB, length {@code visionWidth*visionHeight*3},
 *                     normalized 0..1; may be {@code null} when vision is disabled or when the dummy
 *                     pixels are synthesized lazily during encoding (see {@link BinaryCodec})
 */
public record OutboundSnapshot(
		float yaw,
		float pitch,
		int selectedSlot,
		int visionWidth,
		int visionHeight,
		float[] visionRgb) {
}
