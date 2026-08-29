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
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Real RGBD vision: reads the main framebuffer's colour + depth attachments on the render thread and
 * hands off compact, already-downsampled RGBD frames to the bridge.
 *
 * <p><b>Three capture seams per frame.</b> {@code WorldRenderEvents.END_MAIN} claims and arms a capture,
 * and reads world depth there. A render-tail hook then reads a second depth plane after first-person
 * hands and held items have rendered but before Minecraft clears depth for the GUI. Finally, the
 * <b>first HUD element</b> seam ({@code HudElementRegistry.addFirst}) reads colour. Minecraft clears
 * depth between the world and hand passes, so the two depth planes are merged on read-back. Colour
 * cannot be read at {@code END_MAIN} in every
 * environment: under Iris (shader packs) the
 * world is rendered into Iris's own deferred G-buffers and only composited back onto MC's main
 * framebuffer colour attachment during Iris's post/composite passes, which run <em>after</em>
 * {@code END_MAIN}. At {@code END_MAIN} the colour texture would hold only the cleared sky colour. So
 * the first HUD seam runs after the world, hands, and any shader composite but before any HUD overlay
 * draws — valid in vanilla and under Iris/Sodium alike, with no dependency on Iris classes.
 * {@code END_MAIN} records {@code far}/size and issues world depth; the render-tail hook issues hand
 * depth; and the HUD seam issues matching colour. See {@link #onWorldRenderEnd()},
 * {@link #onFirstPersonRenderEnd()}, and {@link #onHudRenderFirst()}.
 *
 * <p><b>The downsample happens on the GPU, before the read-back.</b> Each attachment plane is first blitted
 * into a small offscreen framebuffer at exactly the dataset resolution ({@link #ensureShrinkTargets}),
 * and only that is read back. At a 2560&times;1440 window feeding 768&times;432 frames this is the
 * difference between moving ~44 MB and ~4 MB across PCIe every single frame, and between the render
 * thread reading a full-res mapped buffer with a stride and memcpy-ing a handful of small rows. Reading
 * the full-res attachments instead used to be affordable only because eager replay wasted several
 * renders per sample, which gave the transfers somewhere to hide; once each render produces a sample,
 * the read-back is on the critical path and its size is what sets the frame rate. Box filtering needs
 * every source texel, so that one option keeps the old full-res path ({@link #boxFilter}).
 *
 * <p>The GPU&rarr;CPU copy is asynchronous; a small ring of read-back buffers ({@link #RING} deep) lets
 * the render thread issue a copy and pick up an earlier-completed one without ever blocking. Once a
 * slot's colour and depth reads have both signalled, its buffers are mapped and copied into compact
 * per-texel byte arrays. The heavy float conversion + depth linearization runs on the bridge's vision
 * worker ({@link LinkBridge#enqueueVisionRaw}).
 *
 * <p><b>Eager replay pipelines through this ring.</b> Ordinary capture is rate-throttled; eager packet
 * replay instead captures exactly one frame per virtual sample window, arbitrated by
 * {@link EagerCaptureGate}. The window's sequence number is stamped onto the slot at claim time and
 * travels with the pixels all the way to the recorder, so several windows may be in flight at once
 * without the recorder having to guess which frame belongs to which sample. The gate is told the
 * capture is committed as soon as all three copies are <em>issued</em> — GL executes them in command order,
 * so replay can advance the world for the next window immediately instead of stalling a whole render
 * frame (or more) waiting for the CPU to consume this one.
 *
 * <p>All GPU access is confined to the render thread and guarded with
 * {@link RenderSystem#assertOnRenderThread()}. {@link #dispose()} (also render-thread) frees the GPU
 * buffers; late completion callbacks no-op once disposed.
 */
public final class VisionCapture {
	/**
	 * Depth of the read-back ring. Deeper is better — it is how many read-backs can be in flight, and so
	 * how much transfer latency the render thread can hide — but each slot costs three buffers the size of
	 * one read-back. Downsampling on the GPU makes a slot ~4 MB instead of ~44 MB at a 1440p window, so
	 * that path can afford a much deeper ring; the full-resolution fallback stays triple-buffered.
	 */
	private static final int RING_SHRUNK = 6;
	private static final int RING_FULL = 3;
	/** Eye-space near plane (blocks); GameRenderer's near plane is fixed at 0.05. */
	private static final float NEAR = 0.05f;
	/** Far plane used by GameRenderer's first-person projection in Minecraft 1.21.11. */
	private static final float HAND_FAR = 100.0f;

	/** Resolved live each frame so a bridge swap (settings save -&gt; reloadLink) doesn't orphan capture. */
	private final Supplier<LinkBridge> bridge;
	private final IntSupplier targetWSupplier;
	private final IntSupplier targetHSupplier;
	private final long minIntervalNs;
	private final boolean boxFilter;
	private final EagerCaptureGate eager;

	/** Target dimensions resolved once per frame so in-game settings changes take effect live. */
	private int targetW;
	private int targetH;

	private long lastCaptureNs;
	/** Dimensions the read-back buffers are sized for: the shrink target, or the framebuffer if not shrinking. */
	private int pboW = -1;
	private int pboH = -1;
	private Slot[] ring;
	/**
	 * Slots with all copies issued, in issue order — which is the order the recorder must receive them
	 * in. Iterating {@link #ring} by array index instead would publish a newer capture ahead of an older
	 * one whenever both complete in the same frame, scrambling a dataset's video timeline. GL signals
	 * fences in command order, so draining only from the head never leaves a ready slot waiting.
	 */
	private final ArrayDeque<Slot> inFlightOrder = new ArrayDeque<>();
	private volatile boolean disposed;

	/** Monotonic capture counter; stamped onto a slot at {@code END_MAIN} for cross-seam matching/debugging. */
	private long frameCounter;
	/**
	 * The slot {@link #onWorldRenderEnd()} armed this frame (world-depth copy issued) that the
	 * first-person and HUD seams must complete with the hand-depth and colour copies.
	 * {@code null} when no slot is armed.
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

	/**
	 * The offscreen framebuffer the attachments are blitted into at dataset resolution, and its two
	 * textures. Created lazily and rebuilt when the dataset resolution changes; {@code 0} means "not yet
	 * created". Render thread only.
	 */
	private int shrinkFbo;
	private int shrinkColorTex;
	private int shrinkDepthTex;
	private int shrinkW = -1;
	private int shrinkH = -1;
	/** Set if the shrink framebuffer cannot be built on this driver; capture falls back to full-res reads. */
	private boolean shrinkUnavailable;

	/** Render-thread cost of the capture path, reported periodically while eager replay is running. */
	private long issueNs;
	private long drainNs;
	private long timedCaptures;
	private boolean wasEager;

	/** Slot lifecycle across the two capture seams. */
	private enum State {
		/** Reusable — no capture in progress. */
		FREE,
		/** {@code END_MAIN} issued world depth; the render-tail hook must issue hand depth. */
		AWAIT_HAND_DEPTH_ISSUE,
		/** The render-tail hook issued hand depth; the HUD seam must issue colour. */
		AWAIT_COLOR_ISSUE,
		/** All three copies issued; drain once their ready flags are set. */
		IN_FLIGHT
	}

	/** One ring entry: colour, world-depth, and hand-depth read-back buffers plus cross-seam state. */
	private static final class Slot {
		GpuBuffer color;
		/** World depth captured before Minecraft clears depth for the first-person pass. */
		GpuBuffer depth;
		/** Hand/item depth captured after that clear; clear-value pixels reveal the world depth below. */
		GpuBuffer handDepth;
		volatile boolean colorReady;
		volatile boolean depthReady;
		volatile boolean handDepthReady;
		State state = State.FREE;
		long frameId;
		/** Eager sample window this capture belongs to, or {@link EagerCaptureGate#NO_CAPTURE}. */
		long sampleSeq = EagerCaptureGate.NO_CAPTURE;
		/** Framebuffer size this capture was taken from (checked across the two seams). */
		int srcW;
		int srcH;
		/** Dataset size this capture is destined for, latched so a live settings change can't skew a drain. */
		int dstW;
		int dstH;
		/** Whether the read-back is already at {@code dstW x dstH} because the GPU did the downsample. */
		boolean shrunk;
		float far;
	}

	/**
	 * @param targetW supplies the downsample target width in pixels; read live each drain so changes to
	 *                the in-game camera setting take effect without restarting
	 * @param targetH supplies the downsample target height in pixels (read live, as above)
	 */
	public VisionCapture(Supplier<LinkBridge> bridge, IntSupplier targetW, IntSupplier targetH, int maxHz,
			boolean boxFilter, EagerCaptureGate eager) {
		this.bridge = bridge;
		this.targetWSupplier = targetW;
		this.targetHSupplier = targetH;
		this.minIntervalNs = (maxHz > 0) ? (1_000_000_000L / maxHz) : 0L;
		this.boxFilter = boxFilter;
		this.eager = eager;
	}

	/**
	 * Seam A. Invoked from the {@code WorldRenderEvents.END_MAIN} callback (the context is unused — the
	 * framebuffer and far plane come from {@link MinecraftClient}). Drains completed frames, makes the
	 * once-per-frame throttle decision, and — if capturing this frame — issues the world-depth read-back
	 * and <b>arms</b> a slot for {@link #onHudRenderFirst()} to read hand depth and colour. Keeping world
	 * depth here is necessary because Minecraft clears it before drawing the first-person pass. Colour
	 * is deferred because it may not yet be composited under Iris. Runs on the render thread.
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
		// Resolved here rather than at drain time so the read-back buffers, the blit target and the slot
		// all agree on one size for this frame even if the settings screen changes it mid-flight.
		targetW = Math.max(1, targetWSupplier.getAsInt());
		targetH = Math.max(1, targetHSupplier.getAsInt());
		boolean shrink = !boxFilter && !shrinkUnavailable && ensureShrinkTargets();
		ensureRing(shrink ? targetW : w, shrink ? targetH : h, shrink);

		// First drain any completed copies (this is what actually produces frames).
		drainReadySlots();

		// If we armed a slot last frame but the HUD seam never fired (e.g. a pause/menu screen opened
		// between END_MAIN and the HUD), reclaim it: skip that frame rather than emit it, and
		// don't leak a ring slot.
		if (armedSlot != null) {
			OpenCrafterLink.LOGGER.debug("[open-crafter-link] vision: HUD seam missed frame {}; reclaiming slot", armedSlot.frameId);
			abandonArmed();
		}

		// Throttle ordinary capture by wall clock. Eager replay ignores that and instead grants exactly
		// one capture per virtual sample window, so no window can be captured twice or skipped — several
		// windows may be travelling through the ring at once, each tagged with the sample it belongs to.
		long now = System.nanoTime();
		boolean eagerActive = eager.active();
		if (eagerActive != wasEager) {
			wasEager = eagerActive; // don't average throttled frames into an eager run's cost report
			issueNs = 0L;
			drainNs = 0L;
			timedCaptures = 0L;
		}
		if (!eagerActive && now - lastCaptureNs < minIntervalNs) {
			return; // armedSlot already null: HUD seam will do nothing this frame
		}

		Slot slot = freeSlot();
		if (slot == null) {
			return; // all slots in flight — skip this frame rather than block the render thread
		}
		long seq = EagerCaptureGate.NO_CAPTURE;
		if (eagerActive) {
			seq = eager.claim();
			if (seq < 0) {
				return; // no window open, or this window's one capture is already claimed
			}
		}
		lastCaptureNs = now;
		slot.sampleSeq = seq;
		slot.srcW = w;
		slot.srcH = h;
		slot.dstW = targetW;
		slot.dstH = targetH;
		slot.shrunk = shrink;
		slot.far = mc.gameRenderer.getFarPlaneDistance();
		slot.frameId = ++frameCounter;
		slot.colorReady = false;
		slot.depthReady = false;
		slot.handDepthReady = false;
		slot.state = State.AWAIT_HAND_DEPTH_ISSUE;

		// Preserve world depth before GameRenderer clears the attachment for first-person rendering.
		// Hand depth is captured separately at the HUD seam and overlaid during drain.
		long start = System.nanoTime();
		if (shrink) {
			blitToShrink(depthTex, GlConst.GL_DEPTH_ATTACHMENT, GlConst.GL_DEPTH_BUFFER_BIT, w, h);
			readAttachment(shrinkFbo, GlConst.GL_DEPTH_COMPONENT, GlConst.GL_FLOAT,
					slot.depth, targetW, targetH, () -> slot.depthReady = true);
		} else {
			attachToDepthFbo(depthTex);
			readAttachment(depthFbo, GlConst.GL_DEPTH_COMPONENT, GlConst.GL_FLOAT,
					slot.depth, w, h, () -> slot.depthReady = true);
			detachDepthFbo();
		}
		issueNs += System.nanoTime() - start;
		armedSlot = slot;
	}

	/**
	 * Seam B. Invoked at the tail of {@code GameRenderer.renderWorld}, after both main-hand and off-hand
	 * first-person rendering (including bare arms), and immediately before {@code GameRenderer.render}
	 * clears depth for GUI rendering. Issues the hand/item-only depth read-back. Runs on the render thread.
	 */
	public void onFirstPersonRenderEnd() {
		if (disposed) {
			return;
		}
		RenderSystem.assertOnRenderThread();

		Slot slot = armedSlot;
		if (slot == null || slot.state != State.AWAIT_HAND_DEPTH_ISSUE) {
			return;
		}

		MinecraftClient mc = MinecraftClient.getInstance();
		Framebuffer fb = mc.getFramebuffer();
		GpuTexture depthTex = (fb != null) ? fb.getDepthAttachment() : null;
		if (depthTex == null) {
			armedSlot = null;
			abandon(slot);
			return;
		}
		if (depthTex.getWidth(0) != slot.srcW || depthTex.getHeight(0) != slot.srcH) {
			armedSlot = null;
			abandon(slot);
			return;
		}

		long start = System.nanoTime();
		if (slot.shrunk) {
			blitToShrink(depthTex, GlConst.GL_DEPTH_ATTACHMENT, GlConst.GL_DEPTH_BUFFER_BIT,
					slot.srcW, slot.srcH);
			readAttachment(shrinkFbo, GlConst.GL_DEPTH_COMPONENT, GlConst.GL_FLOAT,
					slot.handDepth, slot.dstW, slot.dstH, () -> slot.handDepthReady = true);
		} else {
			attachToDepthFbo(depthTex);
			readAttachment(depthFbo, GlConst.GL_DEPTH_COMPONENT, GlConst.GL_FLOAT,
					slot.handDepth, slot.srcW, slot.srcH, () -> slot.handDepthReady = true);
			detachDepthFbo();
		}
		issueNs += System.nanoTime() - start;
		slot.state = State.AWAIT_COLOR_ISSUE;
	}

	/**
	 * Seam C. Invoked from the first HUD element ({@code HudElementRegistry.addFirst}), after the world,
	 * first-person rendering, and any shader-pack composite, but before any HUD element is drawn. Issues
	 * the matching asynchronous colour read-back. Runs on the render thread.
	 */
	public void onHudRenderFirst() {
		if (disposed) {
			return;
		}
		RenderSystem.assertOnRenderThread();

		Slot slot = armedSlot;
		armedSlot = null;
		if (slot == null) {
			return; // this frame was throttled, had no free slot, or capture is disabled
		}
		if (slot.state != State.AWAIT_COLOR_ISSUE) {
			abandon(slot); // first-person seam did not run; never publish a mismatched frame
			return;
		}

		MinecraftClient mc = MinecraftClient.getInstance();
		Framebuffer fb = mc.getFramebuffer();
		GpuTexture colorTex = (fb != null) ? fb.getColorAttachment() : null;
		if (colorTex == null || colorTex.getWidth(0) != slot.srcW || colorTex.getHeight(0) != slot.srcH) {
			abandon(slot);
			return;
		}

		long start = System.nanoTime();
		if (slot.shrunk) {
			blitToShrink(colorTex, GlConst.GL_COLOR_ATTACHMENT0, GlConst.GL_COLOR_BUFFER_BIT, slot.srcW, slot.srcH);
			readAttachment(shrinkFbo, GlConst.GL_RGBA, GlConst.GL_UNSIGNED_BYTE,
					slot.color, slot.dstW, slot.dstH, () -> slot.colorReady = true);
		} else {
			CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
			enc.copyTextureToBuffer(colorTex, slot.color, 0L, () -> slot.colorReady = true, 0);
		}
		issueNs += System.nanoTime() - start;
		slot.state = State.IN_FLIGHT;
		inFlightOrder.add(slot);
		// All three copies are now in the command stream and can only observe this window's contents, so
		// replay may advance to the next sample immediately — the pixels catch up asynchronously.
		if (slot.sampleSeq >= 0) {
			eager.commit(slot.sampleSeq);
		}
	}

	/** Give up a claimed-but-unissued capture: free the slot and hand the window back to replay. */
	private void abandon(Slot slot) {
		slot.state = State.FREE;
		if (slot.sampleSeq >= 0) {
			slot.sampleSeq = EagerCaptureGate.NO_CAPTURE;
			eager.release();
		}
	}

	/** {@link #abandon} the slot armed at {@code END_MAIN} that the HUD seam never completed. */
	private void abandonArmed() {
		Slot slot = armedSlot;
		armedSlot = null;
		if (slot != null) {
			abandon(slot);
		}
	}

	/**
	 * Build (or rebuild) the offscreen framebuffer the attachments are downsampled into, sized to the
	 * current dataset resolution. Returns false — permanently, after logging — if this driver will not
	 * give us a complete framebuffer, in which case capture falls back to reading the full-res
	 * attachments. Render thread only.
	 */
	private boolean ensureShrinkTargets() {
		if (shrinkFbo != 0 && shrinkW == targetW && shrinkH == targetH) {
			return true;
		}
		closeShrinkTargets();
		GlStateManager.clearGlErrors();
		shrinkColorTex = GlStateManager._genTexture();
		GlStateManager._bindTexture(shrinkColorTex);
		GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GlConst.GL_RGBA8, targetW, targetH, 0,
				GlConst.GL_RGBA, GlConst.GL_UNSIGNED_BYTE, null);
		// A blit destination is never sampled, but an incomplete mip chain still makes some drivers
		// treat the texture as unusable, so pin it to a single level with nearest filtering.
		GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
		GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
		GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, 0);

		// Must match MC's depth attachment format exactly: glBlitFramebuffer refuses a depth blit
		// between differing formats (MC's TextureFormat.DEPTH32 is GL_DEPTH_COMPONENT32).
		shrinkDepthTex = GlStateManager._genTexture();
		GlStateManager._bindTexture(shrinkDepthTex);
		GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GlConst.GL_DEPTH_COMPONENT32, targetW, targetH, 0,
				GlConst.GL_DEPTH_COMPONENT, GlConst.GL_FLOAT, null);
		GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
		GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
		GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, 0);
		GlStateManager._bindTexture(0);

		// Bind as DRAW only, and put the previous binding back: at END_MAIN the caller's draw target is
		// MC's main framebuffer, and leaving it unbound would send the rest of the frame to the screen.
		int prevDraw = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
		shrinkFbo = GlStateManager.glGenFramebuffers();
		GlStateManager._glBindFramebuffer(GlConst.GL_DRAW_FRAMEBUFFER, shrinkFbo);
		GlStateManager._glFramebufferTexture2D(GlConst.GL_DRAW_FRAMEBUFFER, GlConst.GL_COLOR_ATTACHMENT0,
				GlConst.GL_TEXTURE_2D, shrinkColorTex, 0);
		GlStateManager._glFramebufferTexture2D(GlConst.GL_DRAW_FRAMEBUFFER, GlConst.GL_DEPTH_ATTACHMENT,
				GlConst.GL_TEXTURE_2D, shrinkDepthTex, 0);
		int status = GL30.glCheckFramebufferStatus(GlConst.GL_DRAW_FRAMEBUFFER);
		GlStateManager._glBindFramebuffer(GlConst.GL_DRAW_FRAMEBUFFER, prevDraw);
		int err = GlStateManager._getError();
		if (status != GL30.GL_FRAMEBUFFER_COMPLETE || err != 0) {
			OpenCrafterLink.LOGGER.warn("[open-crafter-link] GPU downsample unavailable (fb status {}, GL error {});"
					+ " falling back to full-resolution read-back", status, err);
			closeShrinkTargets();
			shrinkUnavailable = true;
			return false;
		}
		shrinkW = targetW;
		shrinkH = targetH;
		OpenCrafterLink.LOGGER.info("[open-crafter-link] vision: downsampling on the GPU into {}x{}", targetW, targetH);
		return true;
	}

	/**
	 * Blit one of MC's full-resolution attachments into the matching attachment of the shrink
	 * framebuffer, scaling it down to the dataset resolution. {@code GL_NEAREST} is required for depth
	 * and keeps colour point-sampled, which is the same thing the CPU downsample did. Render thread only.
	 */
	private void blitToShrink(GpuTexture source, int attachment, int mask, int srcW, int srcH) {
		if (depthFbo == 0) {
			depthFbo = GlStateManager.glGenFramebuffers();
		}
		int prevDraw = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
		int prevRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		GlStateManager._glBindFramebuffer(GlConst.GL_READ_FRAMEBUFFER, depthFbo);
		GlStateManager._glFramebufferTexture2D(GlConst.GL_READ_FRAMEBUFFER, attachment,
				GlConst.GL_TEXTURE_2D, ((GlTexture) source).getGlId(), 0);
		GlStateManager._glBindFramebuffer(GlConst.GL_DRAW_FRAMEBUFFER, shrinkFbo);
		GlStateManager._glBlitFrameBuffer(0, 0, srcW, srcH, 0, 0, shrinkW, shrinkH, mask, GlConst.GL_NEAREST);
		// Never hold a reference to MC's attachment across frames, and hand the caller's targets back
		// exactly as they were — the world is still being drawn into them.
		GlStateManager._glFramebufferTexture2D(GlConst.GL_READ_FRAMEBUFFER, attachment,
				GlConst.GL_TEXTURE_2D, 0, 0);
		GlStateManager._glBindFramebuffer(GlConst.GL_DRAW_FRAMEBUFFER, prevDraw);
		GlStateManager._glBindFramebuffer(GlConst.GL_READ_FRAMEBUFFER, prevRead);
	}

	/** Attach MC's depth texture to our own read framebuffer (full-resolution fallback path). */
	private void attachToDepthFbo(GpuTexture depthTex) {
		if (depthFbo == 0) {
			depthFbo = GlStateManager.glGenFramebuffers();
		}
		int prevRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		GlStateManager._glBindFramebuffer(GlConst.GL_READ_FRAMEBUFFER, depthFbo);
		GlStateManager._glFramebufferTexture2D(GlConst.GL_READ_FRAMEBUFFER, GlConst.GL_DEPTH_ATTACHMENT,
				GlConst.GL_TEXTURE_2D, ((GlTexture) depthTex).getGlId(), 0);
		GlStateManager._glBindFramebuffer(GlConst.GL_READ_FRAMEBUFFER, prevRead);
	}

	private void detachDepthFbo() {
		int prevRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		GlStateManager._glBindFramebuffer(GlConst.GL_READ_FRAMEBUFFER, depthFbo);
		GlStateManager._glFramebufferTexture2D(GlConst.GL_READ_FRAMEBUFFER, GlConst.GL_DEPTH_ATTACHMENT,
				GlConst.GL_TEXTURE_2D, 0, 0);
		GlStateManager._glBindFramebuffer(GlConst.GL_READ_FRAMEBUFFER, prevRead);
	}

	/**
	 * Asynchronously read one attachment of {@code fbo} into {@code dst} (a {@code GL_PIXEL_PACK_BUFFER}
	 * read-back PBO). MC's {@link CommandEncoder#copyTextureToBuffer} always attaches its source as
	 * {@code GL_COLOR_ATTACHMENT0}, which is invalid for a depth-format texture and cannot read our own
	 * framebuffer at all, so we issue the {@code glReadPixels} ourselves. It returns immediately (the
	 * driver DMAs into the PBO in the background) and {@code onReady} fires from the render thread once
	 * the matching fence signals. Depth lands as {@code w*h} little-endian floats in [0,1] (window-space
	 * depth) and colour as RGBA8, both bottom-row first. Render thread only.
	 */
	private void readAttachment(int fbo, int format, int type, GpuBuffer dst, int w, int h, Runnable onReady) {
		GlStateManager.clearGlErrors();
		int prevRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		GlStateManager._glBindFramebuffer(GlConst.GL_READ_FRAMEBUFFER, fbo);
		GlStateManager._glBindBuffer(GlConst.GL_PIXEL_PACK_BUFFER, ((GlGpuBuffer) dst).id);
		GlStateManager._pixelStore(GlConst.GL_PACK_ROW_LENGTH, w);
		GlStateManager._readPixels(0, 0, w, h, format, type, 0L);
		RenderSystem.queueFencedTask(onReady);
		GlStateManager._pixelStore(GlConst.GL_PACK_ROW_LENGTH, 0); // back to "tightly packed" for everyone else
		GlStateManager._glBindFramebuffer(GlConst.GL_READ_FRAMEBUFFER, prevRead);
		GlStateManager._glBindBuffer(GlConst.GL_PIXEL_PACK_BUFFER, 0);
		int err = GlStateManager._getError();
		if (err != 0) {
			OpenCrafterLink.LOGGER.warn("[open-crafter-link] read-back glReadPixels failed: GL error {}", err);
		}
	}

	private void closeShrinkTargets() {
		if (shrinkFbo != 0) {
			GlStateManager._glDeleteFramebuffers(shrinkFbo);
			shrinkFbo = 0;
		}
		if (shrinkColorTex != 0) {
			GlStateManager._deleteTexture(shrinkColorTex);
			shrinkColorTex = 0;
		}
		if (shrinkDepthTex != 0) {
			GlStateManager._deleteTexture(shrinkDepthTex);
			shrinkDepthTex = 0;
		}
		shrinkW = -1;
		shrinkH = -1;
	}

	/** Map + downsample every slot whose colour and two depth copies have completed. Render thread. */
	private void drainReadySlots() {
		if (ring == null) {
			return;
		}
		CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
		for (Slot slot = inFlightOrder.peek(); slot != null; slot = inFlightOrder.peek()) {
			if (!slot.colorReady || !slot.depthReady || !slot.handDepthReady) {
				break; // the oldest capture is still in flight; later ones must wait behind it
			}
			inFlightOrder.poll();
			long start = System.nanoTime();
			byte[] rgba;
			byte[] depth;
			try (GpuBuffer.MappedView cv = enc.mapBuffer(slot.color, true, false)) {
				rgba = slot.shrunk ? flipRows(cv.data(), slot.dstW, slot.dstH)
						: downsampleRgba(cv.data(), slot.srcW, slot.srcH, slot.dstW, slot.dstH);
			}
			try (GpuBuffer.MappedView dv = enc.mapBuffer(slot.depth, true, false)) {
				depth = slot.shrunk ? flipRows(dv.data(), slot.dstW, slot.dstH)
						: downsampleDepth(dv.data(), slot.srcW, slot.srcH, slot.dstW, slot.dstH);
			}
			byte[] handDepth;
			try (GpuBuffer.MappedView hdv = enc.mapBuffer(slot.handDepth, true, false)) {
				handDepth = slot.shrunk ? flipRows(hdv.data(), slot.dstW, slot.dstH)
						: downsampleDepth(hdv.data(), slot.srcW, slot.srcH, slot.dstW, slot.dstH);
			}
			overlayHandDepth(depth, handDepth, slot.far);
			drainNs += System.nanoTime() - start;
			long seq = slot.sampleSeq;
			slot.sampleSeq = EagerCaptureGate.NO_CAPTURE;
			slot.state = State.FREE; // free immediately for reuse
			bridge.get().enqueueVisionRaw(slot.dstW, slot.dstH, NEAR, slot.far, rgba, depth, seq);
			reportCaptureCost();
		}
	}

	/**
	 * The GPU already produced a {@code dstW x dstH} image, so all that is left is GL's bottom-left
	 * origin: copy whole rows in reverse into a top-left-origin array. One sequential {@code memcpy} per
	 * row, which is what makes reading from mapped GPU memory cheap — the strided per-texel reads the
	 * full-resolution path has to do are the expensive part of a read-back, not the transfer itself.
	 */
	private byte[] flipRows(ByteBuffer src, int dstW, int dstH) {
		int rowBytes = dstW * 4;
		byte[] out = new byte[rowBytes * dstH];
		for (int ty = 0; ty < dstH; ty++) {
			src.get((dstH - 1 - ty) * rowBytes, out, ty * rowBytes, rowBytes);
		}
		return out;
	}

	/**
	 * Nearest-neighbour (or box-averaged) downsample of an RGBA8 source into a compact RGBA8 target,
	 * flipping vertically so the output is top-left origin. Returns {@code targetW*targetH*4} bytes.
	 */
	private byte[] downsampleRgba(ByteBuffer src, int srcW, int srcH, int dstW, int dstH) {
		byte[] out = new byte[dstW * dstH * 4];
		if (boxFilter) {
			for (int ty = 0; ty < dstH; ty++) {
				for (int tx = 0; tx < dstW; tx++) {
					boxAverageRgba(src, srcW, srcH, tx, ty, dstW, dstH, out, (ty * dstW + tx) * 4);
				}
			}
			return out;
		}
		// One whole RGBA8 texel per access instead of four single-byte ones. Source and destination share
		// a byte order, so the four bytes round-trip verbatim — this is a copy, not an int conversion.
		ByteBuffer dst = ByteBuffer.wrap(out).order(src.order());
		int[] columns = columnMap(srcW, dstW);
		for (int ty = 0; ty < dstH; ty++) {
			// Output row ty (top-down) maps to GL source row from the bottom: flip vertically.
			int row = (srcH - 1 - (ty * srcH / dstH)) * srcW;
			int outRow = ty * dstW;
			for (int tx = 0; tx < dstW; tx++) {
				dst.putInt((outRow + tx) * 4, src.getInt((row + columns[tx]) * 4));
			}
		}
		return out;
	}

	/**
	 * Source column for each target column, computed once per plane rather than re-dividing inside the
	 * inner loop (which cost one integer division per output texel).
	 */
	private int[] columnMap(int srcW, int dstW) {
		int[] columns = new int[dstW];
		for (int tx = 0; tx < dstW; tx++) {
			columns[tx] = tx * srcW / dstW;
		}
		return columns;
	}

	/** Box-average the source block covering target texel (tx,ty), writing RGBA8 to {@code out[dst..]}. */
	private void boxAverageRgba(ByteBuffer src, int srcW, int srcH, int tx, int ty, int dstW, int dstH,
			byte[] out, int dst) {
		int x0 = tx * srcW / dstW;
		int x1 = Math.max(x0 + 1, (tx + 1) * srcW / dstW);
		int y0Top = ty * srcH / dstH;
		int y1Top = Math.max(y0Top + 1, (ty + 1) * srcH / dstH);
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
	private byte[] downsampleDepth(ByteBuffer src, int srcW, int srcH, int dstW, int dstH) {
		byte[] out = new byte[dstW * dstH * 4];
		ByteBuffer dst = ByteBuffer.wrap(out).order(src.order()); // verbatim 4-byte copy, as above
		int[] columns = columnMap(srcW, dstW);
		for (int ty = 0; ty < dstH; ty++) {
			int row = (srcH - 1 - (ty * srcH / dstH)) * srcW;
			int outRow = ty * dstW;
			for (int tx = 0; tx < dstW; tx++) {
				dst.putInt((outRow + tx) * 4, src.getInt((row + columns[tx]) * 4));
			}
		}
		return out;
	}

	/**
	 * Overlay the post-clear first-person depth plane on the preserved world plane. The hand pass starts
	 * from an exact GL clear value of {@code 1.0}; every smaller value was written by a hand or held-item
	 * fragment and must replace the otherwise corresponding RGB pixel's world depth. The hand pass uses
	 * a 100-block far plane, so its values are reprojected into the world projection before the shared
	 * bridge linearizes the merged plane with the world's far distance.
	 */
	static void overlayHandDepth(byte[] world, byte[] hand, float worldFar) {
		ByteBuffer worldFloats = ByteBuffer.wrap(world).order(ByteOrder.LITTLE_ENDIAN);
		ByteBuffer handFloats = ByteBuffer.wrap(hand).order(ByteOrder.LITTLE_ENDIAN);
		for (int offset = 0; offset < world.length; offset += Float.BYTES) {
			float handValue = handFloats.getFloat(offset);
			if (handValue < 1.0f) {
				float handNdc = handValue * 2.0f - 1.0f;
				float handDenom = HAND_FAR + NEAR - handNdc * (HAND_FAR - NEAR);
				float distance = 2.0f * NEAR * HAND_FAR / handDenom;
				float worldNdc = (worldFar + NEAR - 2.0f * NEAR * worldFar / distance)
						/ (worldFar - NEAR);
				worldFloats.putFloat(offset, Math.max(0.0f, Math.min((worldNdc + 1.0f) * 0.5f, 1.0f)));
			}
		}
	}

	/**
	 * (Re)allocate the read-back ring when the size being read back changes — the dataset resolution
	 * when the GPU is doing the downsample, the framebuffer size when it is not. Render thread.
	 */
	private void ensureRing(int w, int h, boolean shrunk) {
		if (ring != null && w == pboW && h == pboH) {
			return;
		}
		RenderSystem.assertOnRenderThread();
		abandonArmed();
		closeRing();

		long colorBytes = (long) w * h * 4; // RGBA8
		long depthBytes = (long) w * h * 4; // DEPTH32
		int usage = GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ;
		GpuDevice device = RenderSystem.getDevice();

		int depth = shrunk ? RING_SHRUNK : RING_FULL;
		Slot[] fresh = new Slot[depth];
		for (int i = 0; i < depth; i++) {
			Slot slot = new Slot();
			slot.color = device.createBuffer(() -> "ocl-vision-color", usage, colorBytes);
			slot.depth = device.createBuffer(() -> "ocl-vision-depth", usage, depthBytes);
			slot.handDepth = device.createBuffer(() -> "ocl-vision-hand-depth", usage, depthBytes);
			fresh[i] = slot;
		}
		ring = fresh;
		pboW = w;
		pboH = h;
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
		// Pixels already committed to an eager sample window will never arrive now; tell replay so it
		// drops those samples instead of waiting on them forever.
		for (Slot pending : inFlightOrder) {
			if (pending.sampleSeq >= 0) {
				eager.cancel(pending.sampleSeq);
				pending.sampleSeq = EagerCaptureGate.NO_CAPTURE;
			}
		}
		inFlightOrder.clear();
		for (Slot slot : ring) {
			if (slot.color != null) {
				slot.color.close();
			}
			if (slot.depth != null) {
				slot.depth.close();
			}
			if (slot.handDepth != null) {
				slot.handDepth.close();
			}
		}
		ring = null;
		pboW = -1;
		pboH = -1;
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
		closeShrinkTargets();
		if (depthFbo != 0) {
			GlStateManager._glDeleteFramebuffers(depthFbo);
			depthFbo = 0;
		}
	}

	/**
	 * Periodically report what the capture path costs the render thread, so a slow run can be attributed
	 * to the read-back rather than guessed at. Only meaningful while eager replay is driving the clock —
	 * ordinary capture is rate-throttled and its cost per frame says nothing about throughput.
	 */
	private void reportCaptureCost() {
		if (!eager.active() || ++timedCaptures < 2000L) {
			return;
		}
		OpenCrafterLink.LOGGER.info("[open-crafter-link] vision capture cost per frame: {} ms issue + {} ms drain",
				String.format("%.2f", issueNs / 1e6 / timedCaptures),
				String.format("%.2f", drainNs / 1e6 / timedCaptures));
		issueNs = 0L;
		drainNs = 0L;
		timedCaptures = 0L;
	}
}
