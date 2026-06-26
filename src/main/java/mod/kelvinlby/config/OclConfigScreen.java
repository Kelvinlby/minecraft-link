package mod.kelvinlby.config;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import mod.kelvinlby.config.OclConfig.Transport;
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
						.option(Option.<Transport>createBuilder()
								.name(Text.literal("Type"))
								.description(OptionDescription.of(Text.literal(
										"Transport used to talk to the Open Crafter controller: a local Unix domain "
												+ "socket (faster, same-machine only) or TCP.\n\n"
												+ "Unix domain socket support depends on your OS: Linux and macOS support it; "
												+ "on Windows only newer builds do (Windows 10 build 17063 / 2018 and later). "
												+ "If your OS does not support Unix domain sockets, choose TCP.")))
								.binding(defaults.transport, () -> cfg.transport, v -> cfg.transport = v)
								.controller(opt -> EnumControllerBuilder.create(opt)
										.enumClass(Transport.class)
										.formatValue(t -> Text.literal(
												t == Transport.UNIX_DOMAIN_SOCKET ? "Unix Domain Socket" : "TCP")))
								.build())
						.option(Option.<String>createBuilder()
								.name(Text.literal("Socket Path"))
								.description(OptionDescription.of(Text.literal(
										"Filesystem path of the Unix domain socket. Used only when Type is Unix Domain Socket.\n\n"
												+ "Pick a path accessible to BOTH the Minecraft instance and Open Crafter. "
												+ "If you use a sandboxed launcher (e.g. a Flatpak Minecraft), its filesystem is "
												+ "isolated, so a path the launcher can write may not be visible to Open Crafter "
												+ "(and vice versa). Choose a location both are allowed to reach, or switch to TCP.")))
								.binding(defaults.socketPath, () -> cfg.socketPath, v -> cfg.socketPath = v)
								.controller(StringControllerBuilder::create)
								.build())
						.option(Option.<String>createBuilder()
								.name(Text.literal("TCP URL"))
								.description(OptionDescription.of(Text.literal(
										"ZMQ endpoint URL (e.g. tcp://127.0.0.1). Used only when Type is TCP.")))
								.binding(defaults.tcpUrl, () -> cfg.tcpUrl, v -> cfg.tcpUrl = v)
								.controller(StringControllerBuilder::create)
								.build())
						.build())
				.save(cfg::save)
				.build()
				.generateScreen(parent);
	}
}
