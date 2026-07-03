package mod.kelvinlby.config;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import mod.kelvinlby.OpenCrafterLinkClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

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
				.category(ConfigCategory.createBuilder()
						.name(Text.literal("Recording"))
						.group(OptionGroup.createBuilder()
								.name(Text.literal("Resolution"))
								.option(Option.<Integer>createBuilder()
										.name(Text.literal("Width"))
										.description(OptionDescription.of(Text.literal(
												"Width, in pixels, of recorded frames.")))
										.binding(defaults.recordingWidth, () -> cfg.recordingWidth, v -> cfg.recordingWidth = v)
										.controller(opt -> IntegerSliderControllerBuilder.create(opt)
												.range(16, 1920)
												.step(1))
										.build())
								.option(Option.<Integer>createBuilder()
										.name(Text.literal("Height"))
										.description(OptionDescription.of(Text.literal(
												"Height, in pixels, of recorded frames.")))
										.binding(defaults.recordingHeight, () -> cfg.recordingHeight, v -> cfg.recordingHeight = v)
										.controller(opt -> IntegerSliderControllerBuilder.create(opt)
												.range(16, 1080)
												.step(1))
										.build())
								.build())
						.build())
				// Persist, then rebind the bridge so a changed TCP URL takes effect without a client restart.
				.save(() -> {
					cfg.save();
					OpenCrafterLinkClient.reloadLink();
				})
				.build()
				.generateScreen(parent);
	}
}
