package mod.kelvinlby.recorder;

import mod.kelvinlby.link.VisionFrame;

/**
 * A {@link VisionFrame} in its final storage precision: interleaved RGB as one byte per channel
 * (exactly the {@code rgb24} raster the video encoder consumes) and depth quantized to uint16
 * ({@code round(clamp(d,0,1) * 65535)}, the value written verbatim into the 16-bit depth PNGs). The
 * link's vision worker already produces frames in this precision, so wrapping one shares its arrays
 * instead of copying them; a queued sample retains ~1.7 MB, and repeated ticks (no fresh frame) share
 * the previous instance.
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

	/** Wrap a converted vision frame. Its planes are immutable once published, so they are shared. */
	public static PackedFrame of(VisionFrame v) {
		return new PackedFrame(v.width(), v.height(), v.near(), v.far(), v.rgb24(), v.depth16());
	}
}
