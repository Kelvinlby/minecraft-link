package mod.kelvinlby.link;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import mod.kelvinlby.OpenCrafterLink;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.GlGpuBuffer;
import net.minecraft.client.texture.GlTexture;

import java.nio.ByteBuffer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Real RGBD vision: reads the main framebuffer's colour + depth attachments on the render thread and
 * hands off compact, already-downsampled RGBD frames to the bridge.
 *
 * <p><b>Two capture seams per frame.</b> Depth is read at {@code WorldRenderEvents.END_MAIN} (the
 * terminal world-render phase), where the main framebuffer's depth attachment is fully written.
 * Colour, however, cannot be read there in every rendering environment: under Iris (shader packs) the
 * world is rendered into Iris's own deferred G-buffers and only composited back onto MC's main
 * framebuffer colour attachment during Iris's post/composite passes, which run <em>after</em>
 * {@code END_MAIN}. At {@code END_MAIN} the colour texture would hold only the cleared sky colour. So
 * colour is instead read at the <b>first HUD element</b> seam ({@code HudElementRegistry.addFirst}),
 * which runs after the world + any shader composite but before any HUD overlay draws — valid in
 * vanilla and under Iris/Sodium alike, with no dependency on Iris classes. {@code END_MAIN} arms a
 * slot (issues the depth copy, records {@code far}/size); the HUD seam completes it (issues the colour
 * copy). See {@link #onWorldRenderEnd()} and {@link #onHudRenderFirst()}.
 *
 * <p>The GPU&rarr;CPU copy is asynchronous ({@link CommandEncoder#copyTextureToBuffer}); a small ring of
 * read-back buffers ({@link #RING} deep) lets the render thread issue a copy and pick up an
 * earlier-completed one without ever blocking. Once a slot's colour and depth copies have both
 * signalled, its buffers are mapped and <b>nearest-neighbour downsampled directly out of the mapped
 * memory</b> into compact per-texel byte arrays — so the render thread never touches the full-res
 * frame. The heavy float conversion + depth linearization runs on the bridge's vision worker
 * ({@link LinkBridge#enqueueVisionRaw}).
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

	/** Resolved live each frame so a bridge swap (settings save -&gt; reloadLink) doesn't orphan capture. */
	private final Supplier<LinkBridge> bridge;
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

	/** Monotonic capture counter; stamped onto a slot at {@code END_MAIN} for cross-seam matching/debugging. */
	private long frameCounter;
	/**
	 * The slot {@link #onWorldRenderEnd()} armed this frame (depth copy issued) that
	 * {@link #onHudRenderFirst()} must complete with the colour copy. {@code null} when no slot is armed.
	 * Render thread only (both seams run on the render thread), so no synchronization is needed.
	 */
	private Slot armedSlot;

	/**
	 * A lazily-created GL framebuffer object used solely to read the depth attachment. MC's
	 * {@link CommandEncoder#copyTextureToBuffer} always attaches the source texture as
	 * {@code GL_COLOR_ATTACHMENT0}, which is invalid for a depth-format texture (the resulting read
	 * framebuffer is incomplete and {@code glReadPixels} fails with {@code GL_INVALID_FRAMEBUFFER_OPERATION}),
	 * so we bind the depth texture to our own FBO's {@code GL_DEPTH_ATTACHMENT} and read it ourselves.
	 * {@code 0} means "not yet created". Render thread only.
	 */
	private int depthFbo;

	/** Slot lifecycle across the two capture seams. */
	private enum State {
		/** Reusable — no capture in progress. */
		FREE,
		/** {@code END_MAIN} issued the depth copy; the HUD seam must still issue the colour copy. */
		AWAIT_COLOR_ISSUE,
		/** Both copies issued; drain once {@code colorReady && depthReady}. */
		IN_FLIGHT
	}

	/** One ring entry: a colour + depth read-back buffer pair plus its cross-seam state. */
	private static final class Slot {
		GpuBuffer color;
		GpuBuffer depth;
		volatile boolean colorReady;
		volatile boolean depthReady;
		State state = State.FREE;
		long frameId;
		int srcW;
		int srcH;
		float far;
	}

	/**
	 * @param targetW supplies the downsample target width in pixels; read live each drain so changes to
	 *                the in-game camera setting take effect without restarting
	 * @param targetH supplies the downsample target height in pixels (read live, as above)
	 */
	public VisionCapture(Supplier<LinkBridge> bridge, IntSupplier targetW, IntSupplier targetH, int maxHz, boolean boxFilter) {
		this.bridge = bridge;
		this.targetWSupplier = targetW;
		this.targetHSupplier = targetH;
		this.minIntervalNs = (maxHz > 0) ? (1_000_000_000L / maxHz) : 0L;
		this.boxFilter = boxFilter;
	}

	/**
	 * Seam A. Invoked from the {@code WorldRenderEvents.END_MAIN} callback (the context is unused — the
	 * framebuffer and far plane come from {@link MinecraftClient}). Drains completed frames, makes the
	 * once-per-frame throttle decision, and — if capturing this frame — issues the depth read-back and
	 * <b>arms</b> a slot for {@link #onHudRenderFirst()} to complete with the colour read-back. The
	 * colour attachment is deliberately <em>not</em> copied here: under Iris it holds only the cleared
	 * sky colour until the post-composite passes that run after this seam. Runs on the render thread.
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
		GpuTexture colorTex = fb.getColorAttachment(); // read for size only; contents copied at the HUD seam
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

		// If we armed a slot last frame but the HUD seam never fired (e.g. a pause/menu screen opened
		// between END_MAIN and the HUD), reclaim it: skip that depth-only frame rather than emit it, and
		// don't leak a ring slot.
		if (armedSlot != null) {
			OpenCrafterLink.LOGGER.debug("[open-crafter-link] vision: HUD seam missed frame {}; reclaiming slot", armedSlot.frameId);
			armedSlot.state = State.FREE;
			armedSlot = null;
		}

		// Throttle the rate at which we issue new captures — decided once, here, for both seams.
		long now = System.nanoTime();
		if (now - lastCaptureNs < minIntervalNs) {
			return; // armedSlot already null: HUD seam will do nothing this frame
		}

		Slot slot = freeSlot();
		if (slot == null) {
			return; // all slots in flight — skip this frame rather than block the render thread
		}
		lastCaptureNs = now;
		slot.srcW = w;
		slot.srcH = h;
		slot.far = mc.gameRenderer.getFarPlaneDistance();
		slot.frameId = ++frameCounter;
		slot.colorReady = false;
		slot.depthReady = false;
		slot.state = State.AWAIT_COLOR_ISSUE;

		// Depth cannot go through MC's async copy (see copyDepthToBuffer): we issue our own asynchronous
		// glReadPixels into a read-back PBO and fence it, so the render thread never blocks — no frame-rate
		// cost. The colour copy is issued at the HUD seam, once the composited image is present.
		copyDepthToBuffer(depthTex, slot.depth, w, h, () -> slot.depthReady = true);
		armedSlot = slot;
	}

	/**
	 * Seam B. Invoked from the first HUD element ({@code HudElementRegistry.addFirst}), after the world
	 * and any shader-pack composite have been drawn to the main framebuffer's colour attachment but
	 * before any HUD overlay — so the captured colour is the pure 3D scene. Completes the slot armed by
	 * {@link #onWorldRenderEnd()} by issuing its asynchronous colour read-back. The {@code DrawContext}
	 * / tick counter are unused: the framebuffer comes from {@link MinecraftClient} directly. Runs on the
	 * render thread.
	 */
	public void onHudRenderFirst() {
		if (disposed) {
			return;
		}
		RenderSystem.assertOnRenderThread();

		Slot slot = armedSlot;
		armedSlot = null;
		if (slot == null) {
			return; // this frame was throttled, had no free slot, or capture is disabled — nothing to complete
		}

		MinecraftClient mc = MinecraftClient.getInstance();
		Framebuffer fb = mc.getFramebuffer();
		GpuTexture colorTex = (fb != null) ? fb.getColorAttachment() : null;
		if (colorTex == null) {
			slot.state = State.FREE; // abandon this frame; the depth copy already issued will simply be overwritten later
			return;
		}
		// The framebuffer changed size between the two seams (should not happen mid-frame): the depth PBO
		// was sized for the old dimensions, so abandon rather than pair mismatched planes.
		if (colorTex.getWidth(0) != slot.srcW || colorTex.getHeight(0) != slot.srcH) {
			slot.state = State.FREE;
			return;
		}

		CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
		enc.copyTextureToBuffer(colorTex, slot.color, 0L, () -> slot.colorReady = true, 0);
		slot.state = State.IN_FLIGHT;
	}

	/**
	 * Asynchronously read the depth attachment into {@code dst} (a {@code GL_PIXEL_PACK_BUFFER}-capable
	 * read-back PBO), mirroring what MC's {@link CommandEncoder#copyTextureToBuffer} does for colour but
	 * binding the source as {@code GL_DEPTH_ATTACHMENT} so the read framebuffer is complete. The pixels
	 * land as {@code w*h} little-endian floats in [0,1] (window-space depth), bottom-row first — the same
	 * layout {@link #downsampleDepth} consumes. Like the colour copy, the {@code glReadPixels} into a
	 * bound PBO returns immediately (the driver DMAs in the background); {@code onReady} fires from the
	 * render thread once the matching fence signals. Render thread only.
	 */
	private void copyDepthToBuffer(GpuTexture depthTex, GpuBuffer dst, int w, int h, Runnable onReady) {
		if (depthFbo == 0) {
			depthFbo = GlStateManager.glGenFramebuffers();
		}
		int depthGlId = ((GlTexture) depthTex).getGlId();
		int pboId = ((GlGpuBuffer) dst).id;

		GlStateManager.clearGlErrors();
		GlStateManager._glBindFramebuffer(GlConst.GL_READ_FRAMEBUFFER, depthFbo);
		GlStateManager._glFramebufferTexture2D(
				GlConst.GL_READ_FRAMEBUFFER, GlConst.GL_DEPTH_ATTACHMENT, GlConst.GL_TEXTURE_2D, depthGlId, 0);
		GlStateManager._glBindBuffer(GlConst.GL_PIXEL_PACK_BUFFER, pboId);
		GlStateManager._pixelStore(GlConst.GL_PACK_ROW_LENGTH, w);
		GlStateManager._readPixels(0, 0, w, h, GlConst.GL_DEPTH_COMPONENT, GlConst.GL_FLOAT, 0L);
		RenderSystem.queueFencedTask(onReady);
		// Detach + unbind so we never hold a reference to MC's depth texture across frames.
		GlStateManager._glFramebufferTexture2D(
				GlConst.GL_READ_FRAMEBUFFER, GlConst.GL_DEPTH_ATTACHMENT, GlConst.GL_TEXTURE_2D, 0, 0);
		GlStateManager._glBindFramebuffer(GlConst.GL_READ_FRAMEBUFFER, 0);
		GlStateManager._glBindBuffer(GlConst.GL_PIXEL_PACK_BUFFER, 0);
		int err = GlStateManager._getError();
		if (err != 0) {
			OpenCrafterLink.LOGGER.warn("[open-crafter-link] depth read-back glReadPixels failed: GL error {}", err);
		}
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
			if (slot.state != State.IN_FLIGHT || !slot.colorReady || !slot.depthReady) {
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
			slot.state = State.FREE; // free immediately for reuse
			bridge.get().enqueueVisionRaw(targetW, targetH, NEAR, slot.far, rgba, depth);
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
		armedSlot = null; // any slot armed before the resize belonged to the old ring
	}

	private Slot freeSlot() {
		if (ring == null) {
			return null;
		}
		for (Slot slot : ring) {
			if (slot.state == State.FREE) {
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
		armedSlot = null;
	}

	/** Free all GPU buffers. Must run on the render thread (called from {@code CLIENT_STOPPING}). */
	public void dispose() {
		disposed = true;
		if (!RenderSystem.isOnRenderThread()) {
			OpenCrafterLink.LOGGER.warn("[open-crafter-link] VisionCapture.dispose() off render thread; skipping GPU free");
			return;
		}
		closeRing();
		if (depthFbo != 0) {
			GlStateManager._glDeleteFramebuffers(depthFbo);
			depthFbo = 0;
		}
	}
}
