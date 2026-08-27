package mod.kelvinlby.vcam;

import mod.kelvinlby.OpenCrafterLink;
import mod.kelvinlby.media.FfmpegEncoder;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One {@code ffmpeg} process pumping raw frames into a {@code v4l2loopback} node, so the feed appears
 * to the rest of the OS as an ordinary webcam (selectable in OBS, Discord, Zoom, browsers, {@code
 * ffplay}). Deliberately built the same way as {@link FfmpegEncoder}: piping to the system binary is
 * what keeps this dependency-free, and {@link FfmpegEncoder#locate} is reused verbatim so a binary
 * installed after launch is still picked up and the settings screen's existing "ffmpeg not found"
 * warning covers this feature too.
 *
 * <h2>Why no encoder probing</h2>
 * Unlike the recorder, nothing here is <em>encoded</em>: frames are handed to the v4l2 device as
 * uncompressed {@code yuv420p}, which is what capture applications expect to read. So the whole
 * GPU/VAAPI candidate-and-probe machinery in {@link FfmpegEncoder} is irrelevant — there is no codec
 * to choose and no hardware path to validate.
 *
 * <h2>Failure is expected and must stay quiet</h2>
 * The consumer application closing, or the user unloading the kernel module, breaks the pipe
 * mid-stream. {@link #writeFrame} therefore latches {@code dead} and logs <b>once</b>, then no-ops
 * forever — the same discipline as {@code FfmpegEncoder.writeFrame}, so a vanished device can neither
 * spam the log every frame nor take the game down with it.
 */
public final class VirtualCameraSink {

	/** Which plane a sink carries; selects the input pixel format and names the thread/logs. */
	public enum Kind {
		/** Interleaved 8-bit RGB, three bytes per pixel. */
		RGB("rgb24", 3),
		/** Single-channel 8-bit grayscale, one byte per pixel. */
		DEPTH("gray", 1);

		/** The ffmpeg {@code -pix_fmt} for the raw input. */
		final String pixFmt;
		/** Bytes per pixel the caller must supply per frame. */
		final int bytesPerPixel;

		Kind(String pixFmt, int bytesPerPixel) {
			this.pixFmt = pixFmt;
			this.bytesPerPixel = bytesPerPixel;
		}
	}

	private final Process process;
	private final Kind kind;
	private final V4l2Device device;
	private final int width;
	private final int height;
	private final OutputStream stdin;
	private boolean dead;

	/**
	 * Start feeding {@code device}, or return null (already logged) if ffmpeg is missing or the process
	 * cannot be spawned. Dimensions are the caller's responsibility to make even — see
	 * {@link #evenDown(int)}, which {@code VirtualCameraService} applies — because {@code yuv420p}
	 * cannot represent odd dimensions and ffmpeg would refuse the stream outright.
	 *
	 * @param ffmpegPath the configured ffmpeg path (blank = search PATH), as held by {@code OclConfig}
	 * @param fps        the nominal frame rate declared to the device
	 */
	public static VirtualCameraSink open(Kind kind, V4l2Device device, int width, int height, int fps,
			String ffmpegPath) {
		String bin = FfmpegEncoder.locate(ffmpegPath);
		if (bin == null) {
			OpenCrafterLink.LOGGER.error(
					"[open-crafter-link] ffmpeg not found — the {} virtual camera cannot start. "
							+ "Install ffmpeg or set its path in Open Crafter Link's Sensors settings.", kind);
			return null;
		}

		List<String> cmd = new ArrayList<>();
		cmd.add(bin);
		cmd.addAll(List.of("-hide_banner", "-loglevel", "warning"));
		cmd.addAll(List.of("-f", "rawvideo", "-pix_fmt", kind.pixFmt,
				"-s", width + "x" + height, "-r", Integer.toString(fps), "-i", "pipe:0"));
		// v4l2 consumers expect planar 8-bit YUV; ffmpeg converts both rgb24 and gray into it.
		cmd.addAll(List.of("-pix_fmt", "yuv420p", "-f", "v4l2", device.path().toString()));

		try {
			Process p = new ProcessBuilder(cmd)
					.redirectOutput(ProcessBuilder.Redirect.DISCARD)
					.redirectError(ProcessBuilder.Redirect.DISCARD)
					.start();
			OpenCrafterLink.LOGGER.info("[open-crafter-link] {} virtual camera streaming to {} (\"{}\") at {}x{} @ {} Hz",
					kind, device.path(), device.label(), width, height, fps);
			return new VirtualCameraSink(p, kind, device, width, height);
		} catch (IOException e) {
			OpenCrafterLink.LOGGER.error("[open-crafter-link] failed to start the {} virtual camera on {}",
					kind, device.path(), e);
			return null;
		}
	}

	private VirtualCameraSink(Process process, Kind kind, V4l2Device device, int width, int height) {
		this.process = process;
		this.kind = kind;
		this.device = device;
		this.width = width;
		this.height = height;
		this.stdin = new BufferedOutputStream(process.getOutputStream(), 1 << 16);
	}

	/** Largest even value not exceeding {@code n} (minimum 2) — {@code yuv420p} needs even dimensions. */
	public static int evenDown(int n) {
		return Math.max(2, n & ~1);
	}

	/** The device this sink writes to. */
	public V4l2Device device() {
		return device;
	}

	/** Frame width in pixels, after the even-dimension adjustment. */
	public int width() {
		return width;
	}

	/** Frame height in pixels, after the even-dimension adjustment. */
	public int height() {
		return height;
	}

	/** Exact byte length {@link #writeFrame} expects, for the caller to size its scratch buffer. */
	public int frameBytes() {
		return width * height * kind.bytesPerPixel;
	}

	/** Whether the pipe has broken; a dead sink is dropped by the service on its next reconcile. */
	public boolean isDead() {
		return dead;
	}

	/**
	 * Feed one raw frame. Returns false — permanently, after a single log line — once the pipe has
	 * broken (consumer closed, module unloaded, ffmpeg killed).
	 *
	 * @param frame exactly {@link #frameBytes()} bytes in this sink's pixel format
	 */
	public boolean writeFrame(byte[] frame) {
		if (dead) {
			return false;
		}
		try {
			stdin.write(frame, 0, Math.min(frame.length, frameBytes()));
			stdin.flush(); // a camera is live: never hold a frame back waiting for the buffer to fill
			return true;
		} catch (IOException e) {
			dead = true;
			OpenCrafterLink.LOGGER.warn(
					"[open-crafter-link] {} virtual camera stopped: the pipe to {} closed "
							+ "(consumer app exited, or v4l2loopback was unloaded)", kind, device.path());
			return false;
		}
	}

	/** Close stdin and reap the process, freeing the device for the next session. */
	public void close() {
		try {
			stdin.close();
		} catch (IOException ignored) {
			// broken pipe on the way out — nothing left to salvage
		}
		try {
			if (!process.waitFor(5, TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
		}
		OpenCrafterLink.LOGGER.info("[open-crafter-link] {} virtual camera released {}", kind, device.path());
	}
}
