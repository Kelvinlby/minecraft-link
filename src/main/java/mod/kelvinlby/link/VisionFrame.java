package mod.kelvinlby.link;

/**
 * A single downsampled RGBD frame ready for the wire. Produced on the vision worker thread from a raw
 * framebuffer readback (see {@link VisionCapture}) and conflated through the link bridge to the
 * vision sender.
 *
 * <p>Both planes are row-major with a <b>top-left origin</b> (the capture flips the bottom-left-origin
 * GL framebuffer). {@link #rgb} is interleaved RGB normalized 0..1; {@link #depth} is the per-pixel
 * eye-space distance normalized 0..1 by the far plane (1.0 = at/beyond the far plane, e.g. sky). The
 * raw {@link #near}/{@link #far} planes (in blocks) travel alongside so the controller can recover
 * absolute distance.
 *
 * @param width  frame width in pixels
 * @param height frame height in pixels
 * @param near   eye-space near plane in blocks (used for depth linearization)
 * @param far    eye-space far plane in blocks (used to normalize depth)
 * @param rgb    flat row-major interleaved RGB, length {@code width*height*3}, normalized 0..1
 * @param depth  flat row-major distance, length {@code width*height}, normalized 0..1 (distance/far)
 */
public record VisionFrame(
		int width,
		int height,
		float near,
		float far,
		float[] rgb,
		float[] depth) {
}
