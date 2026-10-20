package mod.kelvinlby.link;

/**
 * A single downsampled RGBD frame ready for the wire. Produced on the vision worker thread from a raw
 * framebuffer readback (see {@link VisionCapture}) and conflated through the link bridge to the
 * vision sender.
 *
 * <p>Both planes are row-major with a <b>top-left origin</b> (the capture flips the bottom-left-origin
 * GL framebuffer) and are already packed to the precision every consumer uses — the {@code OCLV} wire
 * format, the dataset recorder and the virtual cameras alike: {@link #rgb24} is interleaved RGB, one
 * byte per channel; {@link #depth16} is eye-space distance normalized by the far plane and quantized
 * to unsigned 16 bits ({@code round(clamp(distance/far, 0, 1) * 65535)}, 65535 = at/beyond the far
 * plane, e.g. sky). Packing once on the worker instead of carrying normalized floats keeps a frame at
 * ~1.6 MB rather than ~5.3 MB at 768&times;432, which is what keeps the steady-state allocation rate
 * (and with it GC pauses on the game threads) low. The raw {@link #near}/{@link #far} planes (in
 * blocks) travel alongside so the controller can recover absolute distance.
 *
 * <p>The arrays are shared between consumers and must be treated as immutable once published.
 *
 * @param width   frame width in pixels
 * @param height  frame height in pixels
 * @param near    eye-space near plane in blocks (used for depth linearization)
 * @param far     eye-space far plane in blocks (used to normalize depth)
 * @param rgb24   flat row-major interleaved RGB, length {@code width*height*3}
 * @param depth16 flat row-major distance/far as unsigned 16-bit values (in Java shorts), length
 *                {@code width*height}
 */
public record VisionFrame(
		int width,
		int height,
		float near,
		float far,
		byte[] rgb24,
		short[] depth16) {

	/**
	 * Pack normalized 0..1 planes. Non-finite or negative values become 0 and values above 1 become 1,
	 * so the result matches what the wire format has always carried for such inputs.
	 */
	public static VisionFrame fromNormalized(int width, int height, float near, float far,
			float[] rgb, float[] depth) {
		byte[] rgb24 = new byte[rgb.length];
		for (int i = 0; i < rgb.length; i++) {
			rgb24[i] = (byte) Math.round(clamp01(rgb[i]) * 255.0f);
		}
		short[] depth16 = new short[depth.length];
		for (int i = 0; i < depth.length; i++) {
			depth16[i] = quantizeDepth(depth[i]);
		}
		return new VisionFrame(width, height, near, far, rgb24, depth16);
	}

	/** One normalized depth value to its unsigned 16-bit storage value. */
	static short quantizeDepth(float normalized) {
		return (short) Math.round(clamp01(normalized) * 65535.0f);
	}

	private static float clamp01(float value) {
		if (!Float.isFinite(value) || value <= 0.0f) return 0.0f;
		return Math.min(value, 1.0f);
	}
}
