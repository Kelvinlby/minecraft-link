package mod.kelvinlby.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mod.kelvinlby.OpenCrafterLink;
import mod.kelvinlby.link.LinkConfig;
import mod.kelvinlby.recorder.FfmpegEncoder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * User-editable settings, persisted as JSON in the Fabric config directory. This is the data the YACL
 * screen ({@link OclConfigScreen}) binds to. It is intentionally a plain mutable holder — the fields
 * are public so the screen's getter/setter lambdas stay trivial.
 *
 * <p>The link transport is {@link #transport}: {@link Transport#UDS} (plain {@code AF_UNIX} domain sockets,
 * faster local-only — the default) or {@link Transport#TCP} (plain TCP, for a networked controller).
 * {@link #toEndpoints()} converts these settings into the TCP host + ports; {@link #toUdsEndpoints()} into
 * the three UDS socket paths. The screen rebuilds whichever the chosen transport needs and restarts the
 * bridge on save.
 *
 * <p>The camera resolution ({@link #cameraWidth}/{@link #cameraHeight}) drives the vision pipeline's
 * downsample target (see {@code OpenCrafterLinkClient} and {@code VisionCapture}); the
 * {@code ocl.visionWidth}/{@code ocl.visionHeight} launch properties override it when set.
 *
 * <p>The recorder toggle ({@link #recordDataset}) and rate ({@link #recordSampleHz}) drive the dataset
 * {@code Recorder}: the screen calls {@code Recorder#syncTo} on save so flipping the toggle starts or
 * finalizes a session live. The recorder taps the link's existing vision frames, so it records at the
 * camera resolution above — there is no separate recording resolution.
 */
public class OclConfig {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH =
			FabricLoader.getInstance().getConfigDir().resolve(OpenCrafterLink.MOD_ID + ".json");

	private static OclConfig instance;

	/** Link transport. Gson serializes enums by name, so no extra persistence wiring is needed. */
	public enum Transport {
		/** Plain TCP (length-prefixed framing) — works across a network; no third-party deps either side. */
		TCP,
		/** Plain {@code AF_UNIX} domain sockets (length-prefixed framing) — the default; faster, same-machine only. */
		UDS
	}

	// --- Link ---
	/** Which transport the link uses. Defaults to {@link Transport#UDS} (faster, local-only); switch to
	 * {@link Transport#TCP} for a networked controller. */
	public Transport transport = Transport.UDS;

	/** Controller host for TCP mode, host only (e.g. {@code tcp://127.0.0.1} or {@code 127.0.0.1}); ports are canonical. Used only in TCP mode. */
	public String tcpUrl = "tcp://127.0.0.1";

	/**
	 * Directory holding the three UDS {@code .sock} files (UDS mode only). Blank = auto-resolve
	 * (Flatpak-sandbox aware; see {@link LinkConfig#resolveUdsDir(String)}). Set it only to pin a
	 * specific directory both the mod and the controller can see.
	 */
	public String udsDir = "";

	/**
	 * How many consecutive ticks {@link mod.kelvinlby.link.InputDriver} keeps holding the last received
	 * movement instruction after the controller stops sending fresh ones, before treating it as genuine
	 * silence and releasing all driven keys. The controller's send loop and the client tick loop are
	 * independent clocks, so a small grace window absorbs normal jitter without producing a stutter on
	 * an otherwise-continuous key hold.
	 */
	public int inputStalenessTicks = 5;

	// --- Sensors / Camera ---
	/** Height, in pixels, of the camera frames sent to the controller. */
	public int cameraHeight = 432;
	/** Width, in pixels, of the camera frames sent to the controller. */
	public int cameraWidth = 768;

	// --- Sensors / Virtual camera (v4l2loopback; Linux only — see V4l2Device) ---
	/**
	 * Expose the colour feed as an OS-level virtual camera, so it can be previewed or recorded with any
	 * software that reads a webcam (OBS, Discord, Zoom, browsers). Streams the same frames the link
	 * sends, at the {@link #cameraWidth}/{@link #cameraHeight} camera resolution, uncompressed. Off by
	 * default: a camera that claimed a device the moment the mod loaded would be a surprise.
	 */
	public boolean virtualCameraRgb = false;
	/**
	 * Expose the depth feed as a second, grayscale virtual camera. Near is black and the sky (at or
	 * beyond the far plane) is white. Independent of {@link #virtualCameraRgb}; each claims its own
	 * loopback device.
	 */
	public boolean virtualCameraDepth = false;

	// --- Recording ---
	/**
	 * Whether a dataset-recording session is active. Toggling this in the settings screen starts (on
	 * enable) or finalizes (on disable) a session on save; see {@code Recorder#syncTo}. Frames are
	 * recorded at the link camera resolution ({@link #cameraWidth}/{@link #cameraHeight}) — the recorder
	 * taps the link's existing vision pipeline rather than rendering its own.
	 */
	public boolean recordDataset = false;
	/** Automatically encode pending packet replays in a client-only in-memory world. */
	public boolean autoReplay = false;
	/** Run packet replay on a virtual sample clock, rendering/encoding as quickly as hardware allows. */
	public boolean eagerPacketEncoding = false;
	/** Recorder sample rate in Hz (aligned RGBD-frame + action-set samples per second). Default = one per vanilla tick. */
	public int recordSampleHz = 20;
	/**
	 * While a recording session is active, disable the recipe-book button on the 2×2 inventory and
	 * crafting-table screens and force the book closed, so crafting must be done by manually placing
	 * item stacks (a recipe-book click auto-fills the grid as one opaque action, polluting the dataset).
	 * On by default. Read live each frame by {@code RecipeBookScreenMixin} — no restart needed to change.
	 */
	public boolean disableRecipeBookWhileRecording = true;

	// --- Recording / video encoding (rgb.mp4 via a system-installed ffmpeg; see FfmpegEncoder) ---
	/** Encoder family: AUTO tries the GPU encoders first then falls back to CPU x264/x265. */
	public FfmpegEncoder.Backend recordBackend = FfmpegEncoder.Backend.AUTO;
	/** Output codec. H264 is the compatibility default; H265 is smaller at the same quality. */
	public FfmpegEncoder.Codec recordCodec = FfmpegEncoder.Codec.H264;
	/** CRF/CQ-style quality, 0–51, lower = better/larger. 18 is visually near-lossless. */
	public int recordQuality = 18;
	/** Keyframe ("reset") interval in seconds; also bounds how much video a hard crash can lose. */
	public int recordKeyframeSec = 2;
	/** Explicit path to the ffmpeg binary. Blank = search the system PATH. */
	public String ffmpegPath = "";

	/** Snapshot the video-encoding settings for a recording session. */
	public FfmpegEncoder.Settings toVideoSettings() {
		return new FfmpegEncoder.Settings(ffmpegPath, recordBackend, recordCodec, recordQuality, recordKeyframeSec);
	}

	/** Shared singleton so the Mod Menu factory and any future runtime reader see the same state. */
	public static OclConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	/** Reads the config file if present; on any error (missing/corrupt) returns defaults. */
	private static OclConfig load() {
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH)) {
				JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
				OclConfig loaded = GSON.fromJson(json, OclConfig.class);
				if (loaded != null) {
					// One-time migration from the former two-value recording-source selector.
					if (!json.has("autoReplay") && "PACKET_RECORDING".equals(json.has("recordingMode")
							? json.get("recordingMode").getAsString() : null)) loaded.autoReplay = true;
					return loaded;
				}
			} catch (IOException | RuntimeException e) {
				OpenCrafterLink.LOGGER.warn("[open-crafter-link] failed to read config, using defaults", e);
			}
		}
		return new OclConfig();
	}

	/** Writes the current values as pretty JSON. */
	public void save() {
		try (Writer writer = Files.newBufferedWriter(PATH)) {
			GSON.toJson(this, writer);
		} catch (IOException e) {
			OpenCrafterLink.LOGGER.error("[open-crafter-link] failed to write config", e);
		}
	}

	/**
	 * Resolve the TCP endpoints from this config. The mod BINDs telemetry and vision on all interfaces
	 * (canonical ports) and CONNECTs to the controller's host on the instruction port. The {@code
	 * ocl.tcpHost} launch property overrides the configured host.
	 */
	public LinkConfig.TcpEndpoints toEndpoints() {
		String host = firstNonNull(LinkConfig.TCP_HOST_OVERRIDE, hostOf(tcpUrl));
		return new LinkConfig.TcpEndpoints(
				host, LinkConfig.TELEMETRY_PORT, LinkConfig.INSTRUCTION_PORT, LinkConfig.VISION_PORT);
	}

	/**
	 * Resolve the three UDS socket paths from this config (UDS mode). The directory is chosen by
	 * {@link LinkConfig#resolveUdsDir(String)} (honouring {@link #udsDir} / the {@code ocl.udsDir}
	 * launch override, else auto — Flatpak aware); the three canonical filenames sit inside it. The
	 * mod BINDs a server socket for telemetry and vision and CONNECTs for instructions, mirroring the
	 * TCP bind/connect split.
	 */
	public LinkConfig.UdsEndpoints toUdsEndpoints() {
		Path dir = LinkConfig.resolveUdsDir(udsDir);
		return new LinkConfig.UdsEndpoints(
				dir.resolve(LinkConfig.UDS_TELEMETRY),
				dir.resolve(LinkConfig.UDS_INSTRUCTION),
				dir.resolve(LinkConfig.UDS_VISION));
	}

	/** Extract the host from a {@code tcp://host[:port]} URL, falling back to localhost if unparseable. */
	private static String hostOf(String tcpUrl) {
		String s = tcpUrl == null ? "" : tcpUrl.trim();
		int scheme = s.indexOf("://");
		if (scheme >= 0) {
			s = s.substring(scheme + 3);
		}
		int colon = s.indexOf(':'); // strip any user-supplied port; we use the canonical instruction port
		if (colon >= 0) {
			s = s.substring(0, colon);
		}
		int slash = s.indexOf('/');
		if (slash >= 0) {
			s = s.substring(0, slash);
		}
		return s.isEmpty() ? "127.0.0.1" : s;
	}

	private static String firstNonNull(String override, String derived) {
		return override != null ? override : derived;
	}
}
