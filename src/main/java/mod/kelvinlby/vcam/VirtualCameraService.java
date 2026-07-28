package mod.kelvinlby.vcam;

import mod.kelvinlby.OpenCrafterLink;
import mod.kelvinlby.link.VisionFrame;
import mod.kelvinlby.recorder.VisionTap;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * Publishes the link's RGBD frames as up to two OS-level virtual cameras — one colour, one grayscale
 * depth — each toggled independently from the settings screen. This is what lets a user simply
 * <em>watch</em> what the agent sees, and record it with whatever tooling they already use (OBS,
 * Discord, Zoom) instead of the built-in dataset writer's fixed layout.
 *
 * <h2>Frame source</h2>
 * Frames come from {@link VisionTap} — the same converted {@link VisionFrame}s the recorder consumes,
 * at the configured camera resolution. Nothing new is read back from the GPU: {@code VisionCapture}
 * deliberately downsamples <em>inside</em> the mapped GPU buffer so the render thread never touches a
 * full-resolution frame, and tapping the existing stream keeps that property intact. The frames stay
 * uncompressed all the way to the device.
 *
 * <h2>Clock</h2>
 * A single daemon thread drives both sinks on the {@code next += periodNs} absolute-deadline park loop
 * copied from the recorder's {@code Sampler}, so scheduling jitter does not accumulate into rate
 * drift. Freshness is detected by object <b>identity</b> against the previous frame, because the tap
 * conflates but never clears on read. Unlike the recorder — which must emit exactly one sample per
 * tick — a camera must never stall: when no new frame has arrived (menu, paused, low fps) the previous
 * one is re-sent so the device keeps producing a continuous stream and consumers do not time out.
 *
 * <p>Conversion (float planes to {@code rgb24} / 8-bit gray) happens on this thread, off the render
 * and tick threads, into reused scratch buffers. Writes go straight into the ffmpeg pipe, which is
 * itself the buffer — no bounded queue is needed here, since for a live camera dropping a late frame
 * is the correct behaviour rather than a loss to be accounted for.
 *
 * <h2>Not world-scoped</h2>
 * Unlike the recorder, the cameras run whenever enabled, including on menu screens — a preview feed
 * that vanishes when you open the pause menu would be useless to a streamer. Sinks are held until the
 * user disables them or the client shuts down.
 */
public final class VirtualCameraService {

	/** Desired state, from the config. Guarded by {@code this}. */
	private boolean wantRgb;
	private boolean wantDepth;
	private int width = 768;
	private int height = 432;
	/** Volatile so the clock loop picks up a rate change without being restarted. */
	private volatile int fps = 20;
	private String ffmpegPath = "";

	/**
	 * Live sinks, null when the corresponding camera is off. Written only under the lock, but declared
	 * {@code volatile} so the clock thread can read them without ever acquiring it: {@code stopClock}
	 * joins the clock thread while holding the lock, so a clock thread that blocked on the same monitor
	 * would deadlock the settings-save path for the full join timeout.
	 */
	private volatile VirtualCameraSink rgbSink;
	private volatile VirtualCameraSink depthSink;

	private volatile boolean running;
	private Thread clockThread;

	/** Reused conversion scratch, sized on first use. Clock thread only. */
	private byte[] rgbScratch;
	private byte[] grayScratch;

	/**
	 * Why an enabled camera is not currently streaming, or null when everything wanted is live. Held so
	 * the settings screen can show the reason instead of leaving a ticked checkbox that silently does
	 * nothing — the failure mode that made a second camera look "enabled" while the consumer app got an
	 * unstartable device. Volatile: written under the lock, read by the screen on the render thread.
	 */
	private volatile String lastError;

	/**
	 * Reconcile to the settings: start or stop each camera independently. Idempotent — called at client
	 * init (so a toggle left enabled across restarts re-arms) and after every settings save.
	 *
	 * @param rgbOn      whether the colour camera should stream
	 * @param depthOn    whether the grayscale depth camera should stream
	 * @param w          frame width from the camera settings (rounded down to even)
	 * @param h          frame height from the camera settings (rounded down to even)
	 * @param maxHz      frame rate to declare to the devices
	 * @param ffmpegPath configured ffmpeg binary path; blank searches PATH
	 */
	public synchronized void syncTo(boolean rgbOn, boolean depthOn, int w, int h, int maxHz, String ffmpegPath) {
		int evenW = VirtualCameraSink.evenDown(w);
		int evenH = VirtualCameraSink.evenDown(h);
		if ((rgbOn || depthOn) && (evenW != w || evenH != h)) {
			OpenCrafterLink.LOGGER.info(
					"[open-crafter-link] virtual camera resolution adjusted to {}x{} (yuv420p needs even dimensions)",
					evenW, evenH);
		}

		// A resolution or binary change cannot be applied to a running ffmpeg — its input geometry is
		// fixed at spawn — so restart any live sink whose parameters moved. Compare the clamped rate,
		// not the raw argument, or every save would look like a change when maxHz <= 0.
		int newFps = Math.max(1, maxHz);
		boolean geometryChanged = evenW != width || evenH != height || newFps != fps
				|| !ffmpegPath.equals(this.ffmpegPath);
		this.width = evenW;
		this.height = evenH;
		this.fps = newFps;
		this.ffmpegPath = ffmpegPath;
		this.wantRgb = rgbOn;
		this.wantDepth = depthOn;

		if (geometryChanged) {
			closeSinks();
			rgbScratch = null;
			grayScratch = null;
		}
		if (!V4l2Device.supported()) {
			if (rgbOn || depthOn) {
				OpenCrafterLink.LOGGER.warn("[open-crafter-link] {}", V4l2Device.setupHint());
			}
			stopClock();
			return;
		}
		applyDesiredState();
	}

	/** Open/close sinks so the live set matches {@code wantRgb}/{@code wantDepth}. Caller holds the lock. */
	private void applyDesiredState() {
		lastError = null; // recomputed below; a fixed setup must clear the old complaint

		// Drop sinks that died (consumer exited, module unloaded) so they can be retried.
		if (rgbSink != null && rgbSink.isDead()) {
			rgbSink.close();
			rgbSink = null;
		}
		if (depthSink != null && depthSink.isDead()) {
			depthSink.close();
			depthSink = null;
		}

		if (!wantRgb && rgbSink != null) {
			rgbSink.close();
			rgbSink = null;
		}
		if (!wantDepth && depthSink != null) {
			depthSink.close();
			depthSink = null;
		}

		if ((wantRgb && rgbSink == null) || (wantDepth && depthSink == null)) {
			openMissingSinks();
		}

		if (rgbSink != null || depthSink != null) {
			VisionTap.setActive(VisionTap.Consumer.VIRTUAL_CAMERA, true);
			startClock();
		} else {
			stopClock();
			VisionTap.setActive(VisionTap.Consumer.VIRTUAL_CAMERA, false);
		}
	}

	/**
	 * Claim a distinct writable loopback node for each camera that still needs one. Caller holds the
	 * lock.
	 *
	 * <h2>Why the device count is checked before opening anything</h2>
	 * Enabling the cameras one at a time used to differ from enabling both at once. Each
	 * {@code syncTo} only opens the cameras that are missing, so turning on RGB and <em>then</em> depth
	 * reached this method with one device already claimed. With a single loopback node
	 * ({@code devices=1}) there was then nothing left for depth: it silently stayed off while its
	 * checkbox remained ticked and the clock kept running for RGB, so a consumer application pointed at
	 * the non-existent second camera failed to start capture (Discord reports error 2011). The shortfall
	 * was only a log line, so nothing surfaced in the UI.
	 *
	 * <p>Now the free devices are counted first and a shortfall is recorded in {@link #lastError} for
	 * the settings screen, so the cause is visible where the toggle is. Cameras that <em>can</em> be
	 * started still are — a missing second device must not take down a working first one.
	 */
	private void openMissingSinks() {
		boolean needRgb = wantRgb && rgbSink == null;
		boolean needDepth = wantDepth && depthSink == null;
		int needed = (needRgb ? 1 : 0) + (needDepth ? 1 : 0);
		if (needed == 0) {
			return;
		}

		// Devices not already fed by one of our own sinks, in stable index order.
		List<V4l2Device> free = V4l2Device.writable().stream().filter(d -> !isClaimed(d)).toList();
		if (free.isEmpty()) {
			String hint = V4l2Device.setupHint();
			lastError = (hint != null) ? hint
					: "All v4l2 loopback devices are already in use. Load another with: "
							+ "sudo modprobe v4l2loopback devices=2";
			OpenCrafterLink.LOGGER.warn("[open-crafter-link] {}", lastError);
			return;
		}
		if (free.size() < needed) {
			// Partial start: bring up what fits and say plainly what did not, naming the fix.
			lastError = "Only " + free.size() + " free v4l2 loopback device(s) but " + needed
					+ " needed — the RGB and depth cameras each need their own. Reload the module with: "
					+ "sudo modprobe -r v4l2loopback && sudo modprobe v4l2loopback devices=2 "
					+ "exclusive_caps=1,1 card_label=\"Minecraft RGB\",\"Minecraft Depth\"";
			OpenCrafterLink.LOGGER.warn("[open-crafter-link] {}", lastError);
		}

		// One device per camera, in order. A failed open is not retried on the next device: the causes
		// are global (no ffmpeg binary, bad configured path), so retrying would just spawn a doomed
		// process per loopback node and log the same error repeatedly.
		int next = 0;
		if (needRgb && next < free.size()) {
			rgbSink = VirtualCameraSink.open(
					VirtualCameraSink.Kind.RGB, free.get(next), width, height, fps, ffmpegPath);
			if (rgbSink == null) {
				lastError = "FFmpeg could not start the RGB virtual camera — see the log and the "
						+ "FFmpeg path in the Recording tab.";
				return; // ffmpeg unusable; depth would fail identically
			}
			next++;
		}
		if (needDepth && next < free.size()) {
			depthSink = VirtualCameraSink.open(
					VirtualCameraSink.Kind.DEPTH, free.get(next), width, height, fps, ffmpegPath);
			if (depthSink == null) {
				lastError = "FFmpeg could not start the depth virtual camera — see the log and the "
						+ "FFmpeg path in the Recording tab.";
			}
		}

		warnAboutSharedCaps();
	}

	/**
	 * Report any camera we just bound to a node loaded without {@code exclusive_caps}. Such a node
	 * streams perfectly from this side while consumers refuse it (Discord "error 2011"), so without
	 * this check the only symptom appears in another application with nothing in our log to match it
	 * against — see {@link V4l2Device#withoutExclusiveCaps()}. Caller holds the lock.
	 */
	private void warnAboutSharedCaps() {
		List<V4l2Device> bad = V4l2Device.withoutExclusiveCaps();
		if (bad.isEmpty()) {
			return;
		}
		List<String> affected = new ArrayList<>();
		for (V4l2Device d : bad) {
			if (rgbSink != null && rgbSink.device().path().equals(d.path())) {
				affected.add("RGB (" + d.path() + ")");
			}
			if (depthSink != null && depthSink.device().path().equals(d.path())) {
				affected.add("depth (" + d.path() + ")");
			}
		}
		if (affected.isEmpty()) {
			return; // the mis-loaded nodes are ones we are not using
		}
		lastError = "The " + String.join(" and ", affected) + " camera is on a loopback device loaded "
				+ "without exclusive_caps, so it advertises capture and output at once — Discord and "
				+ "Chromium will list it but fail to start it (Discord error 2011). Reload with: "
				+ V4l2Device.recommendedModprobe();
		OpenCrafterLink.LOGGER.warn("[open-crafter-link] {}", lastError);
	}

	/** Whether one of this service's sinks already feeds {@code dev}. Caller holds the lock. */
	private boolean isClaimed(V4l2Device dev) {
		return (rgbSink != null && rgbSink.device().path().equals(dev.path()))
				|| (depthSink != null && depthSink.device().path().equals(dev.path()));
	}

	private void startClock() {
		if (running) {
			return;
		}
		running = true;
		Thread t = new Thread(this::clockLoop, "ocl-vcam-clock");
		t.setDaemon(true);
		clockThread = t;
		t.start();
	}

	/**
	 * Stop the clock and wait for it to exit. Called with the lock held, which is safe only because
	 * {@link #clockLoop} never acquires it — see the {@code rgbSink}/{@code depthSink} field javadoc. If
	 * the loop is ever changed to synchronize, this join must move outside the lock.
	 */
	private void stopClock() {
		if (!running) {
			return;
		}
		running = false;
		Thread t = clockThread;
		clockThread = null;
		if (t != null) {
			LockSupport.unpark(t); // cut the current park short instead of waiting out a period
			try {
				t.join(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * Fixed-deadline clock: latch the newest frame, convert it, and push to whichever sinks are live.
	 * Re-sends the previous frame when nothing fresh has arrived, keeping the device stream continuous.
	 */
	private void clockLoop() {
		long next = System.nanoTime();
		VisionFrame lastFrame = null;
		boolean scratchValid = false;

		while (running) {
			next += 1_000_000_000L / Math.max(1, fps); // read live so a rate change applies in place
			parkUntil(next);
			if (!running) {
				break;
			}

			VisionFrame fresh = VisionTap.latest();
			if (fresh != null && fresh != lastFrame) {
				// The tap never clears on read, so identity is what distinguishes a new frame.
				lastFrame = fresh;
				scratchValid = convert(fresh);
			}
			if (!scratchValid) {
				continue; // nothing captured yet (title screen) or a size mismatch this tick
			}

			// Read without the lock — see the rgbSink/depthSink field javadoc (join-under-lock deadlock).
			VirtualCameraSink rgb = rgbSink;
			VirtualCameraSink depth = depthSink;
			if (rgb != null) {
				rgb.writeFrame(rgbScratch);
			}
			if (depth != null) {
				depth.writeFrame(grayScratch);
			}
		}
	}

	/**
	 * Fill the scratch buffers from a frame. Returns false when the frame's geometry does not match
	 * what the sinks were spawned with — ffmpeg's input size is fixed at spawn, so a mismatched frame
	 * must be skipped rather than written as a torn image. The next {@code syncTo} restarts the sinks
	 * at the new size.
	 *
	 * <p>Depth uses the logarithmic mapping described on {@link #depthToGray}, not the raw normalized
	 * value, which is what makes the picture readable at any render distance.
	 */
	private boolean convert(VisionFrame v) {
		if (v.width() != width || v.height() != height) {
			return false;
		}
		int pixels = width * height;
		if (rgbScratch == null || rgbScratch.length != pixels * 3) {
			rgbScratch = new byte[pixels * 3];
		}
		if (grayScratch == null || grayScratch.length != pixels) {
			grayScratch = new byte[pixels];
		}

		float[] rgb = v.rgb();
		int n = Math.min(rgb.length, rgbScratch.length);
		for (int i = 0; i < n; i++) {
			int c = Math.round(rgb[i] * 255.0f);
			rgbScratch[i] = (byte) (c < 0 ? 0 : Math.min(c, 255));
		}

		float[] depth = v.depth();
		int m = Math.min(depth.length, grayScratch.length);
		float far = v.far();
		for (int i = 0; i < m; i++) {
			grayScratch[i] = depthToGray(depth[i], far);
		}
		return true;
	}

	/** Distance mapped to the brightest grey; nothing closer is distinguished. */
	private static final float DEPTH_NEAR_M = 0.1f;
	/** Distance mapped to the darkest non-sky grey; everything beyond clips to it. */
	private static final float DEPTH_FAR_M = 128.0f;
	/** Normalized depth at/above which a pixel counts as sky rather than geometry. */
	private static final float SKY_CUTOFF = 65530.0f / 65535.0f;
	/** Flat grey for sky, dark enough to read as background without being crushed to black. */
	private static final byte SKY_GRAY = (byte) 30;

	private static final float LOG_LO = (float) Math.log(DEPTH_NEAR_M);
	private static final float LOG_HI = (float) Math.log(DEPTH_FAR_M);

	/**
	 * One normalized depth sample to an 8-bit grey, using the same curve as the dataset visualizer's
	 * {@code colorize_depth} (log distance between fixed clips, flat sky) but staying grayscale.
	 *
	 * <h2>Why the raw normalized value is unusable here</h2>
	 * {@link VisionFrame#depth} is distance divided by the <em>far plane</em>, and that plane is
	 * {@code gameRenderer.getFarPlaneDistance()} — roughly 192–512 blocks at normal render distances.
	 * Scaling it straight to 0..255 therefore spends the whole range on distances the player never
	 * looks at: a wall five blocks away lands at {@code 5/512 ≈ 0.01}, i.e. grey <b>2</b>, while the
	 * sky sits at 1.0, i.e. <b>255</b>. The result is the black-or-white image this replaced, and it
	 * degrades further as render distance grows, because the divisor grows with it.
	 *
	 * <p>Converting back to metres and taking a logarithm fixes both halves of that: the mapping no
	 * longer depends on the far plane (so the picture looks the same at 8 or 32 chunks), and the
	 * near-skewed distribution of real scenes — most pixels within a few blocks — is spread across the
	 * range instead of compressed into its bottom percent.
	 *
	 * <h2>Polarity</h2>
	 * Near is <b>bright</b> and far is dark, matching the visualizer. Sky is pinned to a flat dark grey
	 * rather than allowed to fall out of the curve, so the horizon reads as one region instead of
	 * banding into the most distant geometry.
	 */
	private static byte depthToGray(float normalized, float far) {
		if (normalized >= SKY_CUTOFF) {
			return SKY_GRAY;
		}
		float metres = Math.max(normalized * far, DEPTH_NEAR_M);
		float t = ((float) Math.log(metres) - LOG_LO) / (LOG_HI - LOG_LO);
		t = t < 0.0f ? 0.0f : Math.min(t, 1.0f);
		int g = Math.round((1.0f - t) * 255.0f); // near = bright
		return (byte) (g < 0 ? 0 : Math.min(g, 255));
	}

	private void parkUntil(long deadlineNs) {
		long wait;
		while (running && (wait = deadlineNs - System.nanoTime()) > 0) {
			LockSupport.parkNanos(wait);
		}
	}

	/** Whether either camera is currently streaming (for the settings screen and logs). */
	public synchronized boolean isStreaming() {
		return rgbSink != null || depthSink != null;
	}

	/**
	 * Why an enabled camera is not streaming, or null when everything wanted is live. The settings
	 * screen shows this so a toggle that could not take effect says so, instead of appearing on.
	 */
	public String lastError() {
		return lastError;
	}

	/** The device a running camera feeds, e.g. {@code /dev/video10}, or null when it is not streaming. */
	public synchronized String rgbDevice() {
		return rgbSink != null ? rgbSink.device().path().toString() : null;
	}

	/** The device the depth camera feeds, or null when it is not streaming. */
	public synchronized String depthDevice() {
		return depthSink != null ? depthSink.device().path().toString() : null;
	}

	/**
	 * Stop both cameras and release their devices. Safe to call from {@code CLIENT_STOPPING} and the
	 * JVM shutdown hook alike — it touches no GPU state, and leaking an ffmpeg process holding
	 * {@code /dev/videoN} would block the next launch. Idempotent.
	 */
	public void shutdown() {
		stopClock();
		synchronized (this) {
			wantRgb = false;
			wantDepth = false;
			lastError = null;
			closeSinks();
		}
		VisionTap.setActive(VisionTap.Consumer.VIRTUAL_CAMERA, false);
	}

	/** Close and clear both sinks. Caller holds the lock. */
	private void closeSinks() {
		if (rgbSink != null) {
			rgbSink.close();
			rgbSink = null;
		}
		if (depthSink != null) {
			depthSink.close();
			depthSink = null;
		}
	}
}
