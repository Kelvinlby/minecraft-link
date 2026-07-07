package mod.kelvinlby.recorder;

import mod.kelvinlby.link.VisionFrame;

/**
 * A {@link VisionFrame} packed into its final storage precision: interleaved RGB as one byte per
 * channel (exactly the {@code rgb24} raster the video encoder consumes) and depth quantized to
 * uint16 ({@code round(clamp(d,0,1) * 65535)}, the value written verbatim into the 16-bit depth
 * PNGs). Packing happens once, on the sampler's clock thread, so a queued sample retains ~1.7 MB
 * instead of the ~5.3 MB of raw floats — the writer queue can no longer balloon the heap when the
 * encoder falls behind. Repeated ticks (no fresh frame) share the previous instance.
 *
 * @param width   frame width in pixels
 * @param height  frame height in pixels
 * @param near    eye-space near plane in blocks (carried through to actions.jsonl/manifest)
 * @param far     eye-space far plane in blocks (needed to recover absolute depth)
 * @param rgb24   row-major interleaved RGB, one byte per channel, length {@code width*height*3}
 * @param depth16 row-major depth quantized to uint16 (stored in Java shorts), length {@code width*height}
 */
public record PackedFrame(
		int width,
		int height,
		float near,
		float far,
		byte[] rgb24,
		short[] depth16) {

	/** Pack a converted vision frame. ~1–2 ms at 768×432 — cheap enough for the clock thread. */
	public static PackedFrame of(VisionFrame v) {
		float[] rgb = v.rgb();
		byte[] rgb24 = new byte[rgb.length];
		for (int i = 0; i < rgb.length; i++) {
			int c = Math.round(rgb[i] * 255.0f);
			rgb24[i] = (byte) (c < 0 ? 0 : Math.min(c, 255));
		}
		float[] depth = v.depth();
		short[] depth16 = new short[depth.length];
		for (int i = 0; i < depth.length; i++) {
			float d = depth[i];
			d = d < 0.0f ? 0.0f : Math.min(d, 1.0f);
			depth16[i] = (short) Math.round(d * 65535.0f); // unsigned 16-bit gray
		}
		return new PackedFrame(v.width(), v.height(), v.near(), v.far(), rgb24, depth16);
	}
}
