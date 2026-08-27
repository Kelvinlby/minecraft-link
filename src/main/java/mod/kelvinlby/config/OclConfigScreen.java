package mod.kelvinlby.config;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.LabelOption;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import mod.kelvinlby.OpenCrafterLinkClient;
import mod.kelvinlby.link.LinkConfig;
import mod.kelvinlby.recorder.FfmpegEncoder;
import mod.kelvinlby.vcam.V4l2Device;
import mod.kelvinlby.vcam.VirtualCameraService;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Builds the YACL settings screen bound to the shared {@link OclConfig}. */
public final class OclConfigScreen {
	private OclConfigScreen() {}

	public static Screen create(Screen parent) {
		OclConfig cfg = OclConfig.get();
		OclConfig defaults = new OclConfig();

		return YetAnotherConfigLib.createBuilder()
				.title(Text.literal("Open Crafter Link"))
				// ---- Tab: Link ---- (options sit directly on the category; no section needed)
				.category(ConfigCategory.createBuilder()
						.name(Text.literal("Link"))
						.option(Option.<OclConfig.Transport>createBuilder()
								.name(Text.literal("Transport"))
								.description(OptionDescription.of(Text.literal(
										"How the mod talks to the Open Crafter controller. UDS (default) uses Unix "
												+ "domain sockets — faster and lower-latency, but same-machine only. TCP uses "
												+ "plain sockets over the network and works with a remote controller (no extra "
												+ "dependencies on the controller side).")))
								.binding(defaults.transport, () -> cfg.transport, v -> cfg.transport = v)
								.controller(opt -> EnumControllerBuilder.create(opt)
										.enumClass(OclConfig.Transport.class))
								.build())
						.option(Option.<String>createBuilder()
								.name(Text.literal("TCP URL"))
								.description(OptionDescription.of(Text.literal(
										"TCP mode only. Host of the Open Crafter controller (e.g. tcp://127.0.0.1 or "
												+ "127.0.0.1). Its host is used for the inbound instruction stream; the telemetry "
												+ "and vision streams bind locally on the canonical ports (5557 and 5559).")))
								.binding(defaults.tcpUrl, () -> cfg.tcpUrl, v -> cfg.tcpUrl = v)
								.controller(StringControllerBuilder::create)
								.build())
						.option(Option.<String>createBuilder()
								.name(Text.literal("UDS directory"))
								.description(OptionDescription.of(Text.literal(
										"UDS mode only. Directory holding the three .sock files. Leave blank to auto-resolve "
												+ "(uses $XDG_RUNTIME_DIR, or the Flatpak app runtime dir inside a sandbox). Set it "
												+ "only to pin a specific directory both the mod and controller can see.")))
								.binding(defaults.udsDir, () -> cfg.udsDir, v -> cfg.udsDir = v)
								.controller(StringControllerBuilder::create)
								.build())
						.option(Option.<Integer>createBuilder()
								.name(Text.literal("Input staleness"))
								.description(OptionDescription.of(Text.literal(
										"How many consecutive ticks to keep holding the last movement command "
												+ "after the controller stops sending fresh ones, before releasing all "
												+ "keys. The controller's send loop and the game's tick loop run on "
												+ "independent clocks, so a small grace window here prevents a held key "
												+ "(e.g. walking forward) from stuttering on/off. Too high delays release "
												+ "when the controller actually disconnects.")))
								.binding(defaults.inputStalenessTicks, () -> cfg.inputStalenessTicks, v -> cfg.inputStalenessTicks = v)
								.controller(opt -> IntegerSliderControllerBuilder.create(opt)
										.range(1, 20)
										.step(1)
										.formatValue(v -> Text.literal(v + " ticks")))
								.build())
						.build())
				// ---- Tab: Sensors ----
				.category(ConfigCategory.createBuilder()
						.name(Text.literal("Sensors"))
						.group(OptionGroup.createBuilder()
								.name(Text.literal("Camera"))
								.option(Option.<Integer>createBuilder()
										.name(Text.literal("Height"))
										.description(OptionDescription.of(Text.literal(
												"Height, in pixels, of the camera frames sent to the controller. "
														+ "Larger frames give the controller more detail but cost more "
														+ "bandwidth and processing time per tick.")))
										.binding(defaults.cameraHeight, () -> cfg.cameraHeight, v -> cfg.cameraHeight = v)
										.controller(opt -> IntegerSliderControllerBuilder.create(opt)
												.range(16, 1080)
												.step(1))
										.build())
								.option(Option.<Integer>createBuilder()
										.name(Text.literal("Width"))
										.description(OptionDescription.of(Text.literal(
												"Width, in pixels, of the camera frames sent to the controller. "
														+ "Larger frames give the controller more detail but cost more "
														+ "bandwidth and processing time per tick.")))
										.binding(defaults.cameraWidth, () -> cfg.cameraWidth, v -> cfg.cameraWidth = v)
										.controller(opt -> IntegerSliderControllerBuilder.create(opt)
												.range(16, 1920)
												.step(1))
										.build())
								.build())
						.group(virtualCameraGroup(cfg, defaults))
						.build())
				// ---- Tab: Recording ----
				.category(recordingCategory(cfg, defaults))
				// Persist, rebind the bridge (so a changed TCP URL takes effect live), then reconcile the
				// recorder and the virtual cameras to their toggles so enabling/disabling any of them
				// starts/stops immediately.
				.save(() -> {
					cfg.save();
					OpenCrafterLinkClient.reloadLink();
					OpenCrafterLinkClient.recorder().syncTo(cfg.recordDataset, cfg.autoReplay,
							cfg.eagerPacketEncoding, cfg.quitWhenReplayFinished,
							cfg.recordSampleHz, cfg.toVideoSettings());
					OpenCrafterLinkClient.virtualCamera().syncTo(cfg.virtualCameraRgb, cfg.virtualCameraDepth,
							cfg.cameraWidth, cfg.cameraHeight, LinkConfig.VISION_MAX_HZ, cfg.ffmpegPath);
				})
				.build()
				.generateScreen(parent);
	}

	/**
	 * The Sensors tab's "Virtual camera" group: two independent toggles that publish the colour and
	 * depth feeds as OS-level webcams.
	 *
	 * <p>Built in a helper because availability is conditional and worth explaining in place. Virtual
	 * cameras need a writable {@code v4l2loopback} node, which exists only on Linux and only once the
	 * user has loaded the kernel module — a mod cannot install it. So when the feature cannot work the
	 * checkboxes are <b>disabled</b> ({@code .available(false)}) rather than silently doing nothing, and
	 * a red label carries the exact fix from {@link V4l2Device#setupHint()} (a {@code modprobe} command,
	 * or a {@code flatpak override} when running sandboxed). This mirrors the Recording tab's
	 * missing-ffmpeg warning; the probe is plain filesystem reads, so reopening the screen after fixing
	 * the setup clears it without a restart.
	 */
	private static OptionGroup virtualCameraGroup(OclConfig cfg, OclConfig defaults) {
		OptionGroup.Builder group = OptionGroup.createBuilder()
				.name(Text.literal("Virtual camera"));

		String hint = V4l2Device.setupHint();
		boolean usable = V4l2Device.supported() && hint == null;
		if (hint != null) {
			group.option(LabelOption.create(Text.literal("⚠ " + hint).formatted(Formatting.RED)));
		} else {
			group.option(LabelOption.create(Text.literal(
					"Streams uncompressed frames at the camera resolution above to a v4l2loopback device, "
							+ "so any capture software (OBS, Discord, Zoom, ffplay) can read it as a webcam. "
							+ "Each camera needs its own device — load the module with devices=2.")));
		}
		if (!FfmpegEncoder.available(cfg.ffmpegPath)) {
			group.option(LabelOption.create(Text.literal(
							"⚠ FFmpeg not found — virtual cameras also need it. Set its path in the Recording tab.")
					.formatted(Formatting.RED)));
		}

		// Report what actually happened on the last save. Without this a camera that could not claim a
		// device left its checkbox ticked and looked enabled, while the consumer app got an unstartable
		// device (Discord "error 2011") — the failure had no presence in the UI at all.
		VirtualCameraService svc = OpenCrafterLinkClient.virtualCamera();
		String error = svc.lastError();
		if (error != null) {
			group.option(LabelOption.create(Text.literal("⚠ " + error).formatted(Formatting.RED)));
		}
		String rgbDev = svc.rgbDevice();
		String depthDev = svc.depthDevice();
		if (rgbDev != null || depthDev != null) {
			StringBuilder live = new StringBuilder("Streaming now:");
			if (rgbDev != null) {
				live.append("  RGB → ").append(rgbDev);
			}
			if (depthDev != null) {
				live.append("  Depth → ").append(depthDev);
			}
			group.option(LabelOption.create(Text.literal(live.toString()).formatted(Formatting.GREEN)));
		}

		return group
				.option(Option.<Boolean>createBuilder()
						.name(Text.literal("RGB virtual camera"))
						.description(OptionDescription.of(Text.literal(
								"Publish the colour feed as a virtual webcam — the same image the controller "
										+ "receives (the 3D world with the first-person hand, no HUD). Use it to preview "
										+ "what the agent sees, or to record with your own software instead of the "
										+ "built-in dataset recorder. Claims one loopback device.")))
						.binding(defaults.virtualCameraRgb, () -> cfg.virtualCameraRgb, v -> cfg.virtualCameraRgb = v)
						.controller(TickBoxControllerBuilder::create)
						.available(usable)
						.build())
				.option(Option.<Boolean>createBuilder()
						.name(Text.literal("Depth virtual camera"))
						.description(OptionDescription.of(Text.literal(
								"Publish the depth feed as a second, grayscale virtual webcam. Nearby blocks are "
										+ "black and the sky is white (distance normalized by the far plane). Needs its "
										+ "OWN loopback device, separate from the RGB camera — with only one device "
										+ "loaded, enabling both leaves this one off (load the module with devices=2). "
										+ "If it appears in Discord but fails to start, reload the module with "
										+ "exclusive_caps=1,1.")))
						.binding(defaults.virtualCameraDepth, () -> cfg.virtualCameraDepth, v -> cfg.virtualCameraDepth = v)
						.controller(TickBoxControllerBuilder::create)
						.available(usable)
						.build())
				.build();
	}

	/**
	 * The Recording tab: session toggle + rate, then the ffmpeg video-encoding options. Built in a
	 * helper because it starts with a conditional warning label when no usable ffmpeg binary exists
	 * (the availability probe is cheap and cached; a miss is re-probed each time the screen opens, so
	 * installing ffmpeg clears the warning without a restart).
	 */
	private static ConfigCategory recordingCategory(OclConfig cfg, OclConfig defaults) {
		ConfigCategory.Builder cat = ConfigCategory.createBuilder()
				.name(Text.literal("Recording"));
		if (!FfmpegEncoder.available(cfg.ffmpegPath)) {
			cat.option(LabelOption.create(Text.literal(
							"⚠ FFmpeg not found — rgb.mp4 will NOT be recorded (actions + depth still are). "
									+ "Install ffmpeg or set its path under Video encoding below.")
					.formatted(Formatting.RED)));
		}
		Option<Boolean> autoReplay = Option.<Boolean>createBuilder()
				.name(Text.literal("Auto replay"))
				.description(OptionDescription.of(Text.literal(
						"Automatically opens a client-only replay world, consumes every pending session from "
								+ "<gameDir>/open-crafter-link/replay, writes datasets under recording, then returns "
								+ "to the title screen. When off, entering a world records the player's live controls.")))
				.binding(defaults.autoReplay, () -> cfg.autoReplay, v -> cfg.autoReplay = v)
				.controller(TickBoxControllerBuilder::create)
				.available(cfg.recordDataset)
				.build();
		Option<Boolean> eager = Option.<Boolean>createBuilder()
				.name(Text.literal("Eager encoding"))
				.description(OptionDescription.of(Text.literal(
						"Auto replay only. Advances one configured output-sample interval per completed render "
								+ "and temporarily removes VSync/FPS caps. This changes throughput, not the dataset "
								+ "timeline or configured sample FPS.")))
				.binding(defaults.eagerPacketEncoding,
						() -> cfg.eagerPacketEncoding, v -> cfg.eagerPacketEncoding = v)
				.controller(TickBoxControllerBuilder::create)
				.available(cfg.recordDataset && cfg.autoReplay)
				.build();
		Option<Boolean> quitWhenFinished = Option.<Boolean>createBuilder()
				.name(Text.literal("Quit game when finished"))
				.description(OptionDescription.of(Text.literal(
						"Auto replay only. Close the game once the replays it started have all been processed, so "
								+ "a batch runner can treat the process exit as \"this instance is done\" and start the "
								+ "next one. Nothing to replay means nothing to finish: with an empty inbox the game "
								+ "just keeps running. It does exit if a failed input halts the batch — an inbox that "
								+ "is not empty afterwards is what tells the two apart.")))
				.binding(defaults.quitWhenReplayFinished,
						() -> cfg.quitWhenReplayFinished, v -> cfg.quitWhenReplayFinished = v)
				.controller(TickBoxControllerBuilder::create)
				.available(cfg.recordDataset && cfg.autoReplay)
				.build();
		autoReplay.addListener((option, value) -> {
			eager.setAvailable(option.available() && value);
			quitWhenFinished.setAvailable(option.available() && value);
		});

		Option<Boolean> enabled = Option.<Boolean>createBuilder()
						.name(Text.literal("Record dataset"))
						.description(OptionDescription.of(Text.literal(
								"Capture aligned RGBD frames + player actions to a dataset under "
										+ "<gameDir>/open-crafter-link/recording/<timestamp>/. With Auto replay off, every world you "
										+ "enter (single-player or multiplayer) is recorded to its own session, "
										+ "finalized when you leave the world — menus and the title screen are never "
										+ "recorded. With Auto replay on, pending replays run automatically. A toast "
										+ "shows save progress. Toggling this "
										+ "while in a world starts/stops a session when you save. Frames are recorded "
										+ "at the camera resolution set on the Sensors tab.")))
						.binding(defaults.recordDataset, () -> cfg.recordDataset, v -> cfg.recordDataset = v)
						.controller(TickBoxControllerBuilder::create)
						.build();
		enabled.addListener((option, value) -> {
			autoReplay.setAvailable(value);
			eager.setAvailable(value && autoReplay.pendingValue());
			quitWhenFinished.setAvailable(value && autoReplay.pendingValue());
		});

		return cat
				.option(enabled)
				.option(autoReplay)
				.option(eager)
				.option(quitWhenFinished)
				.option(Option.<Boolean>createBuilder()
						.name(Text.literal("Disable recipe book while recording"))
						.description(OptionDescription.of(Text.literal(
								"While recording, grey out the recipe-book button on the inventory (2×2) and "
										+ "crafting-table screens and close the book if open, so crafting is done by "
										+ "manually placing item stacks. A recipe-book click auto-fills the grid as a "
										+ "single action, which pollutes the dataset. On by default.")))
						.binding(defaults.disableRecipeBookWhileRecording,
								() -> cfg.disableRecipeBookWhileRecording,
								v -> cfg.disableRecipeBookWhileRecording = v)
						.controller(TickBoxControllerBuilder::create)
						.build())
				.option(Option.<Integer>createBuilder()
						.name(Text.literal("Sample rate"))
						.description(OptionDescription.of(Text.literal(
								"How many aligned samples to record per second. 20 Hz matches Minecraft's "
										+ "tick rate (one sample per tick). A rate change takes effect on the next "
										+ "session (stop and restart recording).")))
						.binding(defaults.recordSampleHz, () -> cfg.recordSampleHz, v -> cfg.recordSampleHz = v)
						.controller(opt -> IntegerSliderControllerBuilder.create(opt)
								.range(1, 60)
								.step(1)
								.formatValue(v -> Text.literal(v + " Hz")))
						.build())
				.group(OptionGroup.createBuilder()
						.name(Text.literal("Video encoding"))
						.description(OptionDescription.of(Text.literal(
								"How rgb.mp4 is encoded, via a system-installed FFmpeg. Changes apply to the "
										+ "next session (stop and restart recording).")))
						.option(Option.<FfmpegEncoder.Backend>createBuilder()
								.name(Text.literal("Encoder backend"))
								.description(OptionDescription.of(Text.literal(
										"AUTO tries the GPU encoders first (NVENC, VAAPI/AMF, QSV, VideoToolbox) and "
												+ "falls back to CPU x264/x265 if none works. GPU forces hardware encoding "
												+ "(still falls back to CPU, with a warning in the log, if no hardware "
												+ "encoder is usable). CPU never touches the GPU — use it if hardware "
												+ "encoding causes glitches.")))
								.binding(defaults.recordBackend, () -> cfg.recordBackend, v -> cfg.recordBackend = v)
								.controller(opt -> EnumControllerBuilder.create(opt)
										.enumClass(FfmpegEncoder.Backend.class))
								.build())
						.option(Option.<FfmpegEncoder.Codec>createBuilder()
								.name(Text.literal("Codec"))
								.description(OptionDescription.of(Text.literal(
										"H264 plays everywhere and is the safe default. H265 (HEVC) is ~30-50% smaller "
												+ "at the same quality but encodes slower on CPU and needs newer decoders.")))
								.binding(defaults.recordCodec, () -> cfg.recordCodec, v -> cfg.recordCodec = v)
								.controller(opt -> EnumControllerBuilder.create(opt)
										.enumClass(FfmpegEncoder.Codec.class))
								.build())
						.option(Option.<Integer>createBuilder()
								.name(Text.literal("Quality"))
								.description(OptionDescription.of(Text.literal(
										"CRF/CQ-style quality: lower = better quality and larger files. 18 is visually "
												+ "near-lossless; 23 is a good size/quality balance; above ~30 shows "
												+ "artifacts that may hurt training.")))
								.binding(defaults.recordQuality, () -> cfg.recordQuality, v -> cfg.recordQuality = v)
								.controller(opt -> IntegerSliderControllerBuilder.create(opt)
										.range(0, 51)
										.step(1))
								.build())
						.option(Option.<Integer>createBuilder()
								.name(Text.literal("Keyframe interval"))
								.description(OptionDescription.of(Text.literal(
										"Seconds between keyframes (full-image resets). Shorter = better seeking and "
												+ "less video lost if the game crashes mid-session, but larger files. "
												+ "2 s is a good default.")))
								.binding(defaults.recordKeyframeSec, () -> cfg.recordKeyframeSec, v -> cfg.recordKeyframeSec = v)
								.controller(opt -> IntegerSliderControllerBuilder.create(opt)
										.range(1, 30)
										.step(1)
										.formatValue(v -> Text.literal(v + " s")))
								.build())
						.option(Option.<String>createBuilder()
								.name(Text.literal("FFmpeg path"))
								.description(OptionDescription.of(Text.literal(
										"Explicit path to the ffmpeg binary. Leave blank to use the system PATH. "
												+ "The ocl.ffmpegPath launch property overrides this when set.")))
								.binding(defaults.ffmpegPath, () -> cfg.ffmpegPath, v -> cfg.ffmpegPath = v)
								.controller(StringControllerBuilder::create)
								.build())
						.build())
				.build();
	}
}
