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
import mod.kelvinlby.media.FfmpegEncoder;
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
								.name(Text.literal("TCP bind address"))
								.description(OptionDescription.of(Text.literal(
										"Local address for telemetry and vision listeners. 127.0.0.1 is private to this "
												+ "machine. Set 0.0.0.0 only on a trusted network when a remote controller must connect.")))
								.binding(defaults.tcpBind, () -> cfg.tcpBind, v -> cfg.tcpBind = v)
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
				// Persist, rebind the bridge, then reconcile the virtual cameras live.
				.save(() -> {
					cfg.save();
					OpenCrafterLinkClient.reloadLink();
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
	 * or a {@code flatpak override} when running sandboxed). The FFmpeg probe is a plain process check,
	 * so reopening the screen after fixing the setup clears its warning without a restart.
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
							"⚠ FFmpeg not found — virtual cameras need it. Set its path below.")
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
				.option(Option.<String>createBuilder()
						.name(Text.literal("FFmpeg path"))
						.description(OptionDescription.of(Text.literal(
								"Explicit path to the FFmpeg binary used by virtual cameras. Leave blank to search PATH.")))
						.binding(defaults.ffmpegPath, () -> cfg.ffmpegPath, v -> cfg.ffmpegPath = v)
						.controller(StringControllerBuilder::create)
						.build())
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

}
