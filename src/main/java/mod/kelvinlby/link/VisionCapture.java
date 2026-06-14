package mod.kelvinlby.link;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Window;

/**
 * Vision data source. Currently a documented dummy: it reports the real window framebuffer
 * dimensions but does not yet read back the actual pixels.
 */
public final class VisionCapture {
	private VisionCapture() {}

	/** @return {@code {framebufferWidth, framebufferHeight}} of the current window. */
	public static int[] dimensions(MinecraftClient mc) {
		Window w = mc.getWindow();
		return new int[]{w.getFramebufferWidth(), w.getFramebufferHeight()};
	}

	/**
	 * Dummy vision frame: a flat {@code w*h*3} float array (all zeros) shaped exactly like the real
	 * frame would be — row-major, interleaved RGB, normalized 0..1.
	 *
	 * <p>TODO(real vision): read {@code mc.getFramebuffer().getColorAttachment()} (a Blaze3D
	 * {@code GpuTexture}) instead of returning zeros:
	 * <ol>
	 *   <li>On the render thread, schedule a {@code GpuTexture} -&gt; readback buffer copy via the
	 *       {@code CommandEncoder} (GPU readback is async; do not fence on the tick thread).</li>
	 *   <li>Map the readback buffer on a worker thread and convert to {@code float[w*h*3]}
	 *       normalized 0..1 in row-major interleaved-RGB order.</li>
	 *   <li>Double-buffer the latest frame and hand it to the sender thread; the wire format's
	 *       {@code vision} field is already shaped for this, so only this method changes.</li>
	 * </ol>
	 */
	public static float[] dummyRgb(int w, int h) {
		return new float[Math.max(0, w * h * 3)];
	}
}
