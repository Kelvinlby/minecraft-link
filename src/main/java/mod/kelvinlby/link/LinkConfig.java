package mod.kelvinlby.link;

import mod.kelvinlby.OpenCrafterLink;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Tunable endpoints and constants for the link. Vision tunables and (optionally) the TCP host are
 * overridable at launch via system properties (e.g. {@code -Docl.tcpHost=192.168.1.5}) so they can be
 * changed without recompiling.
 *
 * <p>The TCP endpoints are resolved into an immutable {@link TcpEndpoints} at bridge-start time (see
 * {@link mod.kelvinlby.config.OclConfig#toEndpoints()}); the UDS transport ({@link UdsEndpoints},
 * {@link #resolveUdsDir()}) mirrors the same bind/connect asymmetry with {@code AF_UNIX} paths.
 *
 * <p>For both transports the mod <b>binds</b> the telemetry and vision servers (stable, long-lived
 * endpoints the controller connects to) and <b>connects</b> to the controller's instruction server.
 */
public final class LinkConfig {
	private LinkConfig() {}

	/** Default canonical TCP ports, mirrored in {@code pylib/README.md}'s wire table. */
	public static final int TELEMETRY_PORT = 5557;
	public static final int INSTRUCTION_PORT = 5558;
	public static final int VISION_PORT = 5559;

	/**
	 * Canonical UDS socket filenames, mirrored in {@code pylib/README.md}'s wire table. The mod BINDs a
	 * server socket at {@link #UDS_TELEMETRY} and {@link #UDS_VISION} (the controller connects); it
	 * CONNECTs to {@link #UDS_INSTRUCTION} (the controller binds a server there). The three files live in
	 * the directory chosen by {@link #resolveUdsDir()}.
	 */
	public static final String UDS_TELEMETRY = "open-crafter-link-telemetry.sock";
	public static final String UDS_INSTRUCTION = "open-crafter-link-instruction.sock";
	public static final String UDS_VISION = "open-crafter-link-vision.sock";

	/** Launch override of the UDS directory (e.g. {@code -Docl.udsDir=/run/user/1000}); wins over config. */
	public static final String UDS_DIR_OVERRIDE = System.getProperty("ocl.udsDir");

	/**
	 * The TCP host + ports the bridge binds/connects. Resolved per bridge start, so a settings change can
	 * rebuild it and restart the bridge on new endpoints.
	 *
	 * @param host            controller host the mod connects to for instructions (telemetry/vision bind
	 *                        on all interfaces, so no host is needed for them)
	 * @param telemetryPort   outbound telemetry — the mod BINDs {@code *:telemetryPort}; controller connects
	 * @param instructionPort inbound instructions — the mod CONNECTs to {@code host:instructionPort}
	 * @param visionPort      outbound RGBD vision — the mod BINDs {@code *:visionPort}; controller connects
	 */
	public record TcpEndpoints(String host, int telemetryPort, int instructionPort, int visionPort) {}

	/**
	 * The three UDS socket paths the {@link UdsBridge} binds/connects. Mirrors {@link Endpoints}, but
	 * carries resolved filesystem {@link Path}s (plain {@code AF_UNIX}, no ZMQ) instead of URL strings.
	 *
	 * @param telemetry   outbound telemetry — the mod BINDs a server socket here; the controller connects
	 * @param instruction inbound instructions — the mod CONNECTs here; the controller binds a server
	 * @param vision      outbound RGBD vision — the mod BINDs a second server socket here; controller connects
	 */
	public record UdsEndpoints(Path telemetry, Path instruction, Path vision) {}

	/**
	 * Resolve the directory that holds the three UDS {@code .sock} files, Flatpak-sandbox aware. Order:
	 * <ol>
	 *   <li>{@code -Docl.udsDir} launch override / the explicit {@code udsDir} arg from config, if set;</li>
	 *   <li>a Flatpak sandbox ({@code $FLATPAK_ID} set or {@code /.flatpak-info} present):
	 *       {@code $XDG_RUNTIME_DIR/app/$FLATPAK_ID/} — the one host-shared, writable path a bubblewrap
	 *       sandbox reliably sees, so the host controller and sandboxed mod meet on the same file;</li>
	 *   <li>{@code $XDG_RUNTIME_DIR} on Linux;</li>
	 *   <li>{@code java.io.tmpdir} (covers Windows/macOS and the last-resort Linux fallback).</li>
	 * </ol>
	 * The chosen directory is created if missing. Callers place {@link #UDS_TELEMETRY} etc. inside it.
	 *
	 * @param configuredDir the {@code OclConfig.udsDir} value ({@code ""}/blank = auto-resolve)
	 */
	public static Path resolveUdsDir(String configuredDir) {
		String explicit = (UDS_DIR_OVERRIDE != null && !UDS_DIR_OVERRIDE.isBlank())
				? UDS_DIR_OVERRIDE
				: (configuredDir != null && !configuredDir.isBlank() ? configuredDir : null);
		if (explicit != null) {
			return ensureDir(Path.of(explicit.trim()));
		}

		String xdg = System.getenv("XDG_RUNTIME_DIR");
		String flatpakId = System.getenv("FLATPAK_ID");
		boolean flatpak = (flatpakId != null && !flatpakId.isBlank()) || Files.exists(Path.of("/.flatpak-info"));
		if (flatpak && xdg != null && !xdg.isBlank() && flatpakId != null && !flatpakId.isBlank()) {
			return ensureDir(Path.of(xdg, "app", flatpakId));
		}
		if (xdg != null && !xdg.isBlank()) {
			return ensureDir(Path.of(xdg));
		}
		return ensureDir(Path.of(System.getProperty("java.io.tmpdir")));
	}

	/**
	 * The {@code sun_path} limit for {@code AF_UNIX} addresses is ~108 bytes on Linux (104 on macOS),
	 * counted for the <i>full</i> socket path (dir + filename). Warn if the chosen directory leaves too
	 * little room for the longest socket filename, since the bind would otherwise fail with an opaque
	 * "path too long". {@code $XDG_RUNTIME_DIR} ({@code /run/user/<uid>}) is comfortably short.
	 */
	private static final int SUN_PATH_MAX = 104;

	/** Longest of the three canonical socket filenames, used for the path-length check. */
	private static int longestSockName() {
		return Math.max(UDS_TELEMETRY.length(), Math.max(UDS_INSTRUCTION.length(), UDS_VISION.length()));
	}

	/** Create {@code dir} (and parents) if missing, restricting it to the owner where POSIX perms exist. */
	private static Path ensureDir(Path dir) {
		int worstPath = dir.toAbsolutePath().toString().length() + 1 /* '/' */ + longestSockName();
		if (worstPath > SUN_PATH_MAX) {
			OpenCrafterLink.LOGGER.warn(
					"[open-crafter-link] UDS dir {} makes a socket path ~{} chars, over the ~{} AF_UNIX limit — "
							+ "binding may fail. Set a shorter 'UDS directory' (e.g. /run/user/<uid>) or the ocl.udsDir property.",
					dir, worstPath, SUN_PATH_MAX);
		}
		try {
			if (!Files.isDirectory(dir)) {
				try {
					Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(
							PosixFilePermissions.fromString("rwx------")));
				} catch (UnsupportedOperationException noPosix) {
					Files.createDirectories(dir); // Windows: no POSIX perms
				}
			}
		} catch (IOException e) {
			OpenCrafterLink.LOGGER.warn("[open-crafter-link] could not create UDS dir {}, using it anyway", dir, e);
		}
		return dir;
	}

	/**
	 * Optional launch-time override of the controller host the mod connects to for instructions
	 * (e.g. {@code -Docl.tcpHost=192.168.1.5}). When set it wins over the in-game settings screen's
	 * {@code tcpUrl}; when unset ({@code null}) the runtime uses the configured host. Telemetry and
	 * vision always bind on all interfaces, so only the instruction host is overridable.
	 */
	public static final String TCP_HOST_OVERRIDE = System.getProperty("ocl.tcpHost");

	/**
	 * Optional launch-time override of the published RGBD frame's target (downsampled) resolution. When
	 * either property is set it wins over the in-game settings screen ({@code OclConfig.cameraWidth/Height});
	 * when unset ({@code null}) the runtime falls back to the configured camera resolution. The full
	 * framebuffer is captured on the render thread and nearest-neighbour downsampled to this size before
	 * going on the wire — keeping the payload small enough to sustain &gt;20&nbsp;Hz.
	 */
	public static final Integer VISION_TARGET_W = Integer.getInteger("ocl.visionWidth");
	public static final Integer VISION_TARGET_H = Integer.getInteger("ocl.visionHeight");

	/** Cap on capture rate (Hz). The render thread typically runs faster; captures above this are skipped. */
	public static final int VISION_MAX_HZ = Integer.getInteger("ocl.visionMaxHz", 40);

	/**
	 * When true, RGB is box-averaged over each downsample block instead of nearest-neighbour sampled.
	 * Depth is always nearest (averaging non-linear depth across edges is meaningless).
	 */
	public static final boolean VISION_BOX_FILTER = Boolean.getBoolean("ocl.visionBoxFilter");
}
