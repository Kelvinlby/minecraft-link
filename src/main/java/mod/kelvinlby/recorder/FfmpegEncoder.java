package mod.kelvinlby.recorder;

import mod.kelvinlby.OpenCrafterLink;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Streams raw RGB24 frames into a system-installed {@code ffmpeg} process that encodes them to
 * {@code rgb.mp4}. Piping to the external binary is the one cross-platform road to the GPU encoders
 * (NVENC, VAAPI, QSV, AMF, VideoToolbox) with {@code libx264}/{@code libx265} as the universal CPU
 * fallback — all vastly faster than the pure-Java encoder this replaced, whose per-frame cost made
 * the writer fall behind the sample clock permanently.
 *
 * <p><b>Binary discovery</b> ({@link #locate}): the {@code ocl.ffmpegPath} launch property, then the
 * configured path from the settings screen, then plain {@code ffmpeg} on the PATH. Working binaries
 * are cached; misses are re-probed so installing ffmpeg (or fixing the path) takes effect without a
 * restart. The settings screen calls {@link #available} to surface a missing binary to the user.
 *
 * <p><b>Encoder selection</b> ({@link #pick}): candidates for the configured backend/codec are tried
 * in preference order, each verified with a tiny throwaway test encode (a listed encoder is not
 * necessarily usable — e.g. {@code h264_nvenc} on a machine without an NVIDIA card). The first
 * success is cached per (binary, backend, codec, quality). A {@code GPU} backend that finds no
 * working hardware encoder falls back to CPU with a warning rather than failing the session.
 *
 * <p><b>Session</b>: the process consumes rawvideo RGB24 on stdin and writes a <em>fragmented</em>
 * MP4 ({@code -movflags +frag_keyframe+empty_moov}), so the file stays playable even if the game
 * crashes without {@link #close()}. ffmpeg's stderr goes to {@code ffmpeg.log} in the session
 * directory. If the process dies mid-session, {@link #writeFrame} reports it once and becomes a
 * no-op — the recorder keeps writing depth + actions.
 */
public final class FfmpegEncoder {

	/** Which encoder family to use. {@code AUTO} tries GPU first, then CPU. */
	public enum Backend { AUTO, GPU, CPU }

	/** Output codec. H264 is the compatibility default; H265 is smaller at the same quality. */
	public enum Codec { H264, H265 }

	/**
	 * Video-encoding settings snapshot, taken from the config when a session starts.
	 *
	 * @param ffmpegPath  explicit ffmpeg binary path; blank = search the PATH
	 * @param backend     encoder family preference
	 * @param codec       output codec
	 * @param quality     CRF/CQ-style quality, 0–51, lower = better/larger
	 * @param keyframeSec keyframe ("reset") interval in seconds — also the fragmentation granularity,
	 *                    i.e. the upper bound on video lost to a hard crash
	 */
	public record Settings(String ffmpegPath, Backend backend, Codec codec, int quality, int keyframeSec) {}

	// --------------------------------------------------------------------- //
	// Binary discovery                                                       //
	// --------------------------------------------------------------------- //

	/** Binaries verified to run ({@code -version}); only successes are cached so installs are picked up. */
	private static final Map<String, Boolean> BINARY_OK = new ConcurrentHashMap<>();

	/** Resolve a working ffmpeg binary (launch property &rarr; configured path &rarr; PATH), or null. */
	public static String locate(String configuredPath) {
		String prop = System.getProperty("ocl.ffmpegPath");
		List<String> candidates = new ArrayList<>(3);
		if (prop != null && !prop.isBlank()) {
			candidates.add(prop.trim());
		}
		if (configuredPath != null && !configuredPath.isBlank()) {
			candidates.add(configuredPath.trim());
		}
		candidates.add("ffmpeg"); // PATH lookup; resolves ffmpeg.exe on Windows
		for (String candidate : candidates) {
			if (BINARY_OK.containsKey(candidate) || run(candidate, "-version")) {
				BINARY_OK.put(candidate, true);
				return candidate;
			}
		}
		return null;
	}

	/** Whether a usable ffmpeg exists — the settings screen shows a warning in the Recording tab when not. */
	public static boolean available(String configuredPath) {
		return locate(configuredPath) != null;
	}

	// --------------------------------------------------------------------- //
	// Encoder selection                                                      //
	// --------------------------------------------------------------------- //

	/**
	 * One concrete ffmpeg video encoder plus everything it needs around it.
	 *
	 * @param name        the {@code -c:v} value
	 * @param globalArgs  global/pre-input arguments (hardware device initialization)
	 * @param filterSuffix appended to the even-dimension pad filter (pixel format / hw upload)
	 * @param qualityArgs the encoder-specific quality flags (each family names its knob differently)
	 */
	private record Encoder(String name, List<String> globalArgs, String filterSuffix, List<String> qualityArgs) {}

	/** Probe results, keyed by binary + backend + codec + quality. */
	private static final Map<String, Encoder> PICKED = new ConcurrentHashMap<>();

	/**
	 * Candidate encoders in preference order for this OS. GPU candidates precede the CPU one under
	 * {@code AUTO}; a forced {@code GPU} backend still ends with the CPU encoder as a guarded fallback
	 * (see {@link #pick}).
	 */
	private static List<Encoder> candidates(Backend backend, Codec codec, int quality) {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		boolean windows = os.contains("win");
		boolean mac = os.contains("mac");
		String family = (codec == Codec.H265) ? "hevc" : "h264";
		String q = Integer.toString(quality);

		Encoder cpu = new Encoder(codec == Codec.H265 ? "libx265" : "libx264",
				List.of(), ",format=yuv420p", List.of("-preset", "veryfast", "-crf", q));
		if (backend == Backend.CPU) {
			return List.of(cpu);
		}

		List<Encoder> list = new ArrayList<>();
		if (mac) {
			// VideoToolbox's -q:v is 1..100 with higher = better; map the 0..51 CRF-style scale onto it.
			String vtq = Integer.toString(Math.max(1, Math.round((51 - quality) * 100.0f / 51.0f)));
			list.add(new Encoder(family + "_videotoolbox", List.of(), ",format=yuv420p", List.of("-q:v", vtq)));
		} else {
			list.add(new Encoder(family + "_nvenc", List.of(), ",format=yuv420p",
					List.of("-rc", "vbr", "-cq", q, "-b:v", "0")));
			if (windows) {
				list.add(new Encoder(family + "_amf", List.of(), ",format=yuv420p",
						List.of("-rc", "cqp", "-qp_i", q, "-qp_p", q)));
			} else {
				// VAAPI: the default device, then each render node explicitly — on multi-GPU boxes the
				// default can fail while a specific node (e.g. the AMD iGPU next to an NVIDIA card) works.
				List<String> devices = new ArrayList<>();
				devices.add(""); // libva default
				devices.addAll(renderNodes());
				for (String dev : devices) {
					String init = dev.isEmpty() ? "vaapi=va" : "vaapi=va:" + dev;
					list.add(new Encoder(family + "_vaapi",
							List.of("-init_hw_device", init, "-filter_hw_device", "va"),
							",format=nv12,hwupload", List.of("-qp", q)));
				}
			}
			list.add(new Encoder(family + "_qsv", List.of(), ",format=nv12", List.of("-global_quality", q)));
		}
		list.add(cpu);
		return list;
	}

	/** The DRM render nodes ({@code /dev/dri/renderD*}) present on this machine, sorted. Linux only. */
	private static List<String> renderNodes() {
		java.io.File[] nodes = new java.io.File("/dev/dri")
				.listFiles((d, name) -> name.startsWith("renderD"));
		if (nodes == null) {
			return List.of();
		}
		return java.util.Arrays.stream(nodes).map(java.io.File::getPath).sorted().toList();
	}

	/** First candidate that survives a test encode, cached. Null only for a broken ffmpeg build. */
	private static Encoder pick(String bin, Settings s) {
		String key = bin + "|" + s.backend() + "|" + s.codec() + "|" + s.quality();
		Encoder cached = PICKED.get(key);
		if (cached != null) {
			return cached;
		}
		List<Encoder> tries = candidates(s.backend(), s.codec(), s.quality());
		for (int i = 0; i < tries.size(); i++) {
			Encoder e = tries.get(i);
			if (!probe(bin, e)) {
				continue;
			}
			if (s.backend() == Backend.GPU && i == tries.size() - 1) {
				OpenCrafterLink.LOGGER.warn(
						"[open-crafter-link] no working GPU encoder for {}; falling back to {}", s.codec(), e.name());
			}
			PICKED.put(key, e);
			return e;
		}
		return null;
	}

	/**
	 * Verify an encoder end-to-end with a tiny synthetic encode — listed &ne; usable (missing
	 * GPU/driver). 256×256, not smaller: hardware encoders enforce minimum dimensions (VAAPI
	 * commonly 128×128) and a sub-minimum probe would falsely reject a working GPU.
	 */
	private static boolean probe(String bin, Encoder e) {
		List<String> cmd = new ArrayList<>();
		cmd.add(bin);
		cmd.addAll(List.of("-hide_banner", "-v", "error"));
		cmd.addAll(e.globalArgs());
		cmd.addAll(List.of("-f", "lavfi", "-i", "color=black:s=256x256:r=20", "-frames:v", "8",
				"-vf", PAD_EVEN + e.filterSuffix(), "-c:v", e.name()));
		cmd.addAll(e.qualityArgs());
		cmd.addAll(List.of("-f", "null", "-"));
		boolean ok = run(cmd.toArray(new String[0]));
		OpenCrafterLink.LOGGER.debug("[open-crafter-link] ffmpeg encoder probe {}: {}", e.name(), ok ? "ok" : "unusable");
		return ok;
	}

	/** Run a command silently; true iff it exits 0 within 15 s. */
	private static boolean run(String... cmd) {
		try {
			Process p = new ProcessBuilder(cmd)
					.redirectOutput(ProcessBuilder.Redirect.DISCARD)
					.redirectError(ProcessBuilder.Redirect.DISCARD)
					.start();
			if (!p.waitFor(15, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return false;
			}
			return p.exitValue() == 0;
		} catch (IOException e) {
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	// --------------------------------------------------------------------- //
	// Session                                                                //
	// --------------------------------------------------------------------- //

	/** H.264/5 need even dimensions; pad rather than fail on an odd configured resolution. */
	private static final String PAD_EVEN = "pad=ceil(iw/2)*2:ceil(ih/2)*2";

	private final Process process;
	private final OutputStream stdin;
	private final String encoderName;
	private boolean dead;

	/**
	 * Spawn an encoding session writing {@code outFile}, or return null (already logged) when no
	 * ffmpeg/encoder is usable. Called lazily on the writer thread once the first frame fixes the
	 * dimensions, so binary/encoder probing never runs on the game threads.
	 */
	public static FfmpegEncoder open(Path outFile, Path logFile, int width, int height, int fps, Settings s) {
		String bin = locate(s.ffmpegPath());
		if (bin == null) {
			OpenCrafterLink.LOGGER.error(
					"[open-crafter-link] ffmpeg not found — rgb.mp4 will NOT be recorded (actions + depth still are). "
							+ "Install ffmpeg or set its path in the settings screen's Recording tab.");
			return null;
		}
		Encoder enc = pick(bin, s);
		if (enc == null) {
			OpenCrafterLink.LOGGER.error(
					"[open-crafter-link] this ffmpeg build has no usable {} encoder — rgb.mp4 will NOT be recorded",
					s.codec());
			return null;
		}

		List<String> cmd = new ArrayList<>();
		cmd.add(bin);
		cmd.addAll(List.of("-hide_banner", "-loglevel", "warning", "-y"));
		cmd.addAll(enc.globalArgs());
		cmd.addAll(List.of("-f", "rawvideo", "-pix_fmt", "rgb24",
				"-s", width + "x" + height, "-r", Integer.toString(fps), "-i", "pipe:0"));
		cmd.addAll(List.of("-vf", PAD_EVEN + enc.filterSuffix(), "-c:v", enc.name()));
		cmd.addAll(enc.qualityArgs());
		cmd.addAll(List.of("-g", Integer.toString(fps * Math.max(1, s.keyframeSec()))));
		if (s.codec() == Codec.H265) {
			cmd.addAll(List.of("-tag:v", "hvc1")); // HEVC-in-MP4 tag most players expect
		}
		// Fragmented MP4 + per-packet flush: the file on disk stays playable up to the last completed
		// fragment (≈ one keyframe interval) even if the game dies without close().
		cmd.addAll(List.of("-movflags", "+frag_keyframe+empty_moov", "-flush_packets", "1"));
		cmd.add(outFile.toString());

		try {
			Process p = new ProcessBuilder(cmd)
					.redirectOutput(ProcessBuilder.Redirect.DISCARD)
					.redirectError(logFile.toFile())
					.start();
			OpenCrafterLink.LOGGER.info("[open-crafter-link] encoding rgb.mp4 with {} ({}x{} @ {} Hz)",
					enc.name(), width, height, fps);
			return new FfmpegEncoder(p, enc.name());
		} catch (IOException e) {
			OpenCrafterLink.LOGGER.error("[open-crafter-link] failed to start ffmpeg — rgb.mp4 will NOT be recorded", e);
			return null;
		}
	}

	private FfmpegEncoder(Process process, String encoderName) {
		this.process = process;
		this.encoderName = encoderName;
		this.stdin = new BufferedOutputStream(process.getOutputStream(), 1 << 16);
	}

	/** The ffmpeg encoder in use (e.g. {@code h264_nvenc}), recorded in the manifest. */
	public String encoderName() {
		return encoderName;
	}

	/**
	 * Feed one raw RGB24 frame (writer thread). Returns false — permanently, after logging once — if
	 * the ffmpeg process has died; the session then continues without video.
	 */
	public boolean writeFrame(byte[] rgb24) {
		if (dead) {
			return false;
		}
		try {
			stdin.write(rgb24);
			return true;
		} catch (IOException e) {
			dead = true;
			OpenCrafterLink.LOGGER.error(
					"[open-crafter-link] ffmpeg died mid-session — rgb.mp4 is truncated (see ffmpeg.log); "
							+ "continuing with depth + actions only", e);
			return false;
		}
	}

	/**
	 * Close stdin (ffmpeg finalizes the MP4 on EOF) and wait for the process to exit.
	 *
	 * @return null on a clean exit, else a short description of what went wrong (also logged)
	 */
	public String close() {
		try {
			stdin.close();
		} catch (IOException ignored) {
			// dying pipe — the exit-code path below reports the failure
		}
		try {
			if (!process.waitFor(10, TimeUnit.SECONDS)) {
				OpenCrafterLink.LOGGER.warn("[open-crafter-link] ffmpeg did not exit within 10s; killing it");
				process.destroyForcibly();
				return "ffmpeg hung and was killed — rgb.mp4 may be truncated";
			}
			if (dead) {
				return "ffmpeg died mid-session — rgb.mp4 is truncated (see ffmpeg.log)";
			}
			if (process.exitValue() != 0) {
				OpenCrafterLink.LOGGER.warn("[open-crafter-link] ffmpeg exited with code {} (see ffmpeg.log)",
						process.exitValue());
				return "ffmpeg exited with code " + process.exitValue() + " (see ffmpeg.log)";
			}
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			return "interrupted while waiting for ffmpeg";
		}
	}
}
