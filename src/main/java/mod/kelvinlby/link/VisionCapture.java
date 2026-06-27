package mod.kelvinlby.link;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import mod.kelvinlby.OpenCrafterLink;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;

import java.nio.ByteBuffer;
import java.util.function.IntSupplier;

/**
 * Real RGBD vision: reads the main framebuffer's colour + depth attachments on the render thread, at
 * the {@code WorldRenderEvents.END_MAIN} seam — after the full 3D world (including the first-person
 * hand) but before the HUD — so the captured frame is the pure 3D scene with no overlay.
 *
 * <p>The GPU&rarr;CPU copy is asynchronous ({@link CommandEncoder#copyTextureToBuffer}); a small ring of
 * read-back buffers ({@link #RING} deep) lets the render thread issue a copy and pick up an
 * earlier-completed one without ever blocking. Once a slot's colour and depth copies have both
 * signalled, its buffers are mapped and <b>nearest-neighbour downsampled directly out of the mapped
 * memory</b> into compact per-texel byte arrays — so the render thread never touches the full-res
 * frame. The heavy float conversion + depth linearization runs on the bridge's vision worker
 * ({@link ZmqBridge#enqueueVisionRaw}).
 *
 * <p>All GPU access is confined to the render thread and guarded with
 * {@link RenderSystem#assertOnRenderThread()}. {@link #dispose()} (also render-thread) frees the GPU
 * buffers; late completion callbacks no-op once disposed.
 */
public final class VisionCapture {
	/** Depth of the read-back ring (triple-buffered to absorb async-copy latency). */
	private static final int RING = 3;
	/** Eye-space near plane (blocks); GameRenderer's near plane is fixed at 0.05. */
	private static final float NEAR = 0.05f;

	private final ZmqBridge bridge;
	private final IntSupplier targetWSupplier;
	private final IntSupplier targetHSupplier;
	private final long minIntervalNs;
	private final boolean boxFilter;

	/** Target dimensions resolved once per drain so in-game settings changes take effect live. */
	private int targetW;
	private int targetH;

	private long lastCaptureNs;
	private int fbW = -1;
	private int fbH = -1;
	private Slot[] ring;
	private volatile boolean disposed;

	/** One ring entry: a colour + depth read-back buffer pair plus its in-flight state. */
	private static final class Slot {
		GpuBuffer color;
		GpuBuffer depth;
		volatile boolean colorReady;
		volatile boolean depthReady;
		boolean pending;
		int srcW;
		int srcH;
		float far;
	}

	/**
	 * @param targetW supplies the downsample target width in pixels; read live each drain so changes to
	 *                the in-game camera setting take effect without restarting
	 * @param targetH supplies the downsample target height in pixels (read live, as above)
	 */
	public VisionCapture(ZmqBridge bridge, IntSupplier targetW, IntSupplier targetH, int maxHz, boolean boxFilter) {
		this.bridge = bridge;
		this.targetWSupplier = targetW;
		this.targetHSupplier = targetH;
		this.minIntervalNs = (maxHz > 0) ? (1_000_000_000L / maxHz) : 0L;
		this.boxFilter = boxFilter;
	}

	/**
	 * Invoked from the {@code WorldRenderEvents.END_MAIN} callback (the context is unused — the
	 * framebuffer and far plane come from {@link MinecraftClient}). Runs on the render thread.
	 */
	public void onWorldRenderEnd() {
		if (disposed) {
			return;
		}
		RenderSystem.assertOnRenderThread();

		MinecraftClient mc = MinecraftClient.getInstance();
		Framebuffer fb = mc.getFramebuffer();
		if (fb == null) {
			return;
		}
		GpuTexture colorTex = fb.getColorAttachment();
		GpuTexture depthTex = fb.getDepthAttachment();
		if (colorTex == null || depthTex == null) {
			return; // depth not always present (e.g. some custom framebuffers); bail safely
		}

		int w = colorTex.getWidth(0);
		int h = colorTex.getHeight(0);
		if (w <= 0 || h <= 0) {
			return;
		}
		ensureRing(w, h);

		// First drain any completed copies (this is what actually produces frames).
		drainReadySlots();

		// Throttle the rate at which we issue new captures.
		long now = System.nanoTime();
		if (now - lastCaptureNs < minIntervalNs) {
			return;
		}

		Slot slot = freeSlot();
		if (slot == null) {
			return; // all slots in flight — skip this frame rather than block the render thread
		}
		lastCaptureNs = now;
		slot.srcW = w;
		slot.srcH = h;
		slot.far = mc.gameRenderer.getFarPlaneDistance();
		slot.colorReady = false;
		slot.depthReady = false;
		slot.pending = true;

		CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
		enc.copyTextureToBuffer(colorTex, slot.color, 0L, () -> slot.colorReady = true, 0);
		enc.copyTextureToBuffer(depthTex, slot.depth, 0L, () -> slot.depthReady = true, 0);
	}

	/** Map + downsample every slot whose colour and depth copies have both completed. Render thread. */
	private void drainReadySlots() {
		if (ring == null) {
			return;
		}
		// Resolve the target resolution live (e.g. from the in-game settings screen) for this batch.
		targetW = Math.max(1, targetWSupplier.getAsInt());
		targetH = Math.max(1, targetHSupplier.getAsInt());
		CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
		for (Slot slot : ring) {
			if (!slot.pending || !slot.colorReady || !slot.depthReady) {
				continue;
			}
			byte[] rgba;
			byte[] depth;
			try (GpuBuffer.MappedView cv = enc.mapBuffer(slot.color, true, false)) {
				rgba = downsampleRgba(cv.data(), slot.srcW, slot.srcH);
			}
			try (GpuBuffer.MappedView dv = enc.mapBuffer(slot.depth, true, false)) {
				depth = downsampleDepth(dv.data(), slot.srcW, slot.srcH);
			}
			slot.pending = false; // free immediately for reuse
			bridge.enqueueVisionRaw(targetW, targetH, NEAR, slot.far, rgba, depth);
		}
	}

	/**
	 * Nearest-neighbour (or box-averaged) downsample of an RGBA8 source into a compact RGBA8 target,
	 * flipping vertically so the output is top-left origin. Returns {@code targetW*targetH*4} bytes.
	 */
	private byte[] downsampleRgba(ByteBuffer src, int srcW, int srcH) {
		byte[] out = new byte[targetW * targetH * 4];
		for (int ty = 0; ty < targetH; ty++) {
			// Output row ty (top-down) maps to GL source row from the bottom: flip vertically.
			int syTop = ty * srcH / targetH;
			int sy = srcH - 1 - syTop;
			for (int tx = 0; tx < targetW; tx++) {
				int sx = tx * srcW / targetW;
				int dst = (ty * targetW + tx) * 4;
				if (boxFilter) {
					boxAverageRgba(src, srcW, srcH, tx, ty, out, dst);
				} else {
					int s = (sy * srcW + sx) * 4;
					out[dst]     = src.get(s);
					out[dst + 1] = src.get(s + 1);
					out[dst + 2] = src.get(s + 2);
					out[dst + 3] = src.get(s + 3);
				}
			}
		}
		return out;
	}

	/** Box-average the source block covering target texel (tx,ty), writing RGBA8 to {@code out[dst..]}. */
	private void boxAverageRgba(ByteBuffer src, int srcW, int srcH, int tx, int ty, byte[] out, int dst) {
		int x0 = tx * srcW / targetW;
		int x1 = Math.max(x0 + 1, (tx + 1) * srcW / targetW);
		int y0Top = ty * srcH / targetH;
		int y1Top = Math.max(y0Top + 1, (ty + 1) * srcH / targetH);
		long r = 0, g = 0, b = 0, a = 0;
		int n = 0;
		for (int yTop = y0Top; yTop < y1Top; yTop++) {
			int sy = srcH - 1 - yTop;
			for (int x = x0; x < x1; x++) {
				int s = (sy * srcW + x) * 4;
				r += src.get(s) & 0xFF;
				g += src.get(s + 1) & 0xFF;
				b += src.get(s + 2) & 0xFF;
				a += src.get(s + 3) & 0xFF;
				n++;
			}
		}
		if (n == 0) {
			n = 1;
		}
		out[dst]     = (byte) (r / n);
		out[dst + 1] = (byte) (g / n);
		out[dst + 2] = (byte) (b / n);
		out[dst + 3] = (byte) (a / n);
	}

	/**
	 * Nearest-neighbour downsample of a DEPTH32 source into a compact DEPTH32 target (depth is never
	 * averaged — averaging non-linear depth across edges is meaningless). Vertically flipped to top-left
	 * origin. Native float bytes are copied verbatim; the worker reads them as little-endian floats.
	 * Returns {@code targetW*targetH*4} bytes.
	 */
	private byte[] downsampleDepth(ByteBuffer src, int srcW, int srcH) {
		byte[] out = new byte[targetW * targetH * 4];
		for (int ty = 0; ty < targetH; ty++) {
			int sy = srcH - 1 - (ty * srcH / targetH);
			for (int tx = 0; tx < targetW; tx++) {
				int sx = tx * srcW / targetW;
				int s = (sy * srcW + sx) * 4;
				int dst = (ty * targetW + tx) * 4;
				out[dst]     = src.get(s);
				out[dst + 1] = src.get(s + 1);
				out[dst + 2] = src.get(s + 2);
				out[dst + 3] = src.get(s + 3);
			}
		}
		return out;
	}

	/** (Re)allocate the read-back ring when the framebuffer size changes. Render thread. */
	private void ensureRing(int w, int h) {
		if (ring != null && w == fbW && h == fbH) {
			return;
		}
		RenderSystem.assertOnRenderThread();
		closeRing();

		long colorBytes = (long) w * h * 4; // RGBA8
		long depthBytes = (long) w * h * 4; // DEPTH32
		int usage = GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ;
		GpuDevice device = RenderSystem.getDevice();

		Slot[] fresh = new Slot[RING];
		for (int i = 0; i < RING; i++) {
			Slot slot = new Slot();
			slot.color = device.createBuffer(() -> "ocl-vision-color", usage, colorBytes);
			slot.depth = device.createBuffer(() -> "ocl-vision-depth", usage, depthBytes);
			fresh[i] = slot;
		}
		ring = fresh;
		fbW = w;
		fbH = h;
		lastCaptureNs = 0L;
	}

	private Slot freeSlot() {
		if (ring == null) {
			return null;
		}
		for (Slot slot : ring) {
			if (!slot.pending) {
				return slot;
			}
		}
		return null;
	}

	private void closeRing() {
		if (ring == null) {
			return;
		}
		for (Slot slot : ring) {
			if (slot.color != null) {
				slot.color.close();
			}
			if (slot.depth != null) {
				slot.depth.close();
			}
		}
		ring = null;
		fbW = -1;
		fbH = -1;
	}

	/** Free all GPU buffers. Must run on the render thread (called from {@code CLIENT_STOPPING}). */
	public void dispose() {
		disposed = true;
		if (!RenderSystem.isOnRenderThread()) {
			OpenCrafterLink.LOGGER.warn("[open-crafter-link] VisionCapture.dispose() off render thread; skipping GPU free");
			return;
		}
		closeRing();
	}
}
