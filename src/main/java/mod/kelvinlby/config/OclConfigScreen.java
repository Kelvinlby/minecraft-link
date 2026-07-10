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
import mod.kelvinlby.recorder.FfmpegEncoder;
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
												+ "ZeroMQ over the network and works with a remote controller (needs pyzmq on "
												+ "the controller side).")))
								.binding(defaults.transport, () -> cfg.transport, v -> cfg.transport = v)
								.controller(opt -> EnumControllerBuilder.create(opt)
										.enumClass(OclConfig.Transport.class))
								.build())
						.option(Option.<String>createBuilder()
								.name(Text.literal("TCP URL"))
								.description(OptionDescription.of(Text.literal(
										"TCP mode only. ZMQ endpoint URL of the Open Crafter controller (e.g. tcp://127.0.0.1). "
												+ "Its host is used for the inbound instruction stream; the telemetry and "
												+ "vision streams bind locally on the canonical ports (5557 and 5559).")))
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
						.build())
				// ---- Tab: Recording ----
				.category(recordingCategory(cfg, defaults))
				// Persist, rebind the bridge (so a changed TCP URL takes effect live), then reconcile the
				// recorder to the toggle so enabling/disabling "Record dataset" starts/stops a session now.
				.save(() -> {
					cfg.save();
					OpenCrafterLinkClient.reloadLink();
					OpenCrafterLinkClient.recorder().syncTo(cfg.recordDataset, cfg.recordSampleHz, cfg.toVideoSettings());
				})
				.build()
				.generateScreen(parent);
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
		return cat
				.option(Option.<Boolean>createBuilder()
						.name(Text.literal("Record dataset"))
						.description(OptionDescription.of(Text.literal(
								"Capture aligned RGBD frames + player actions to a dataset under "
										+ "<gameDir>/open-crafter-link/<timestamp>/. While enabled, every world you "
										+ "enter (single-player or multiplayer) is recorded to its own session, "
										+ "finalized when you leave the world — menus and the title screen are never "
										+ "recorded. A toast shows the save progress on world exit. Toggling this "
										+ "while in a world starts/stops a session when you save. Frames are recorded "
										+ "at the camera resolution set on the Sensors tab.")))
						.binding(defaults.recordDataset, () -> cfg.recordDataset, v -> cfg.recordDataset = v)
						.controller(TickBoxControllerBuilder::create)
						.build())
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
