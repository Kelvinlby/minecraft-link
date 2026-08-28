package mod.kelvinlby.recorder.config;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.LabelOption;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.DoubleSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import mod.kelvinlby.media.FfmpegEncoder;
import mod.kelvinlby.recorder.OpenCrafterRecorderClient;
import mod.kelvinlby.recorder.config.RecorderConfig.EffectOverride;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** YACL screen owned by the optional recorder mod. */
public final class RecorderConfigScreen {
	private RecorderConfigScreen() {}

	public static Screen create(Screen parent) {
		RecorderConfig cfg = RecorderConfig.get();
		RecorderConfig defaults = new RecorderConfig().normalize();

		Option<Boolean> autoReplay = boolOption("Auto replay",
				"Consume packet recordings from <gameDir>/open-crafter-link/replay in a client-only world.",
				defaults.autoReplay, () -> cfg.autoReplay, v -> cfg.autoReplay = v, cfg.recordDataset);
		Option<Boolean> eager = boolOption("Eager encoding",
				"Auto replay only. Advance on a virtual sample clock as quickly as rendering and encoding allow.",
				defaults.eagerPacketEncoding, () -> cfg.eagerPacketEncoding, v -> cfg.eagerPacketEncoding = v,
				cfg.recordDataset && cfg.autoReplay);
		Option<Boolean> quit = boolOption("Quit game when finished",
				"Auto replay only. Exit after the batch completes or halts on a failed input.",
				defaults.quitWhenReplayFinished, () -> cfg.quitWhenReplayFinished,
				v -> cfg.quitWhenReplayFinished = v, cfg.recordDataset && cfg.autoReplay);
		Option<Boolean> disableRecipeBook = boolOption("Disable recipe book while recording",
				"Force manual crafting during live demonstrations so recipe placement remains observable."
						+ " Auto replay always leaves the recipe book enabled.",
				defaults.disableRecipeBookWhileRecording, () -> cfg.disableRecipeBookWhileRecording,
				v -> cfg.disableRecipeBookWhileRecording = v, !cfg.autoReplay);
		autoReplay.addListener((option, value) -> {
			eager.setAvailable(option.available() && value);
			quit.setAvailable(option.available() && value);
			disableRecipeBook.setAvailable(!value);
		});

		Option<Boolean> enabled = boolOption("Record dataset",
				"Record each joined world, or run pending packet replays when Auto replay is enabled.",
				defaults.recordDataset, () -> cfg.recordDataset, v -> cfg.recordDataset = v, true);
		enabled.addListener((option, value) -> {
			autoReplay.setAvailable(value);
			eager.setAvailable(value && autoReplay.pendingValue());
			quit.setAvailable(value && autoReplay.pendingValue());
		});

		Option<Integer> sampleRate = Option.<Integer>createBuilder()
				.name(Text.literal("Sample rate"))
				.description(OptionDescription.of(Text.literal("Aligned samples per second; 20 Hz matches Minecraft ticks.")))
				.binding(defaults.recordSampleHz, () -> cfg.recordSampleHz, v -> cfg.recordSampleHz = v)
				.controller(o -> IntegerSliderControllerBuilder.create(o).range(1, 60).step(1)
						.formatValue(v -> Text.literal(v + " Hz"))).build();

		Option<Double> gamma = Option.<Double>createBuilder().name(Text.literal("Gamma"))
				.description(OptionDescription.of(Text.literal(
						"Gamma used by the lightmap during gameplay and in captured frames.")))
				.binding(defaults.recordingGamma, () -> cfg.recordingGamma, v -> cfg.recordingGamma = v)
				.controller(o -> DoubleSliderControllerBuilder.create(o).range(0.0, 10.0).step(0.01)
						.formatValue(v -> Text.literal(String.format("%.2f", v))))
				.available(cfg.overrideGamma).build();
		Option<Boolean> overrideGamma = boolOption("Override gamma",
				"Use the configured gamma during ordinary gameplay and recording.", defaults.overrideGamma,
				() -> cfg.overrideGamma, v -> cfg.overrideGamma = v, true);
		overrideGamma.addListener((option, value) -> gamma.setAvailable(value));

		ConfigCategory.Builder recording = ConfigCategory.createBuilder().name(Text.literal("Recording"))
				.option(enabled).option(sampleRate).option(disableRecipeBook);
		ConfigCategory.Builder replay = ConfigCategory.createBuilder().name(Text.literal("Auto replay"))
				.option(autoReplay).option(eager).option(quit);
		ConfigCategory.Builder rendering = ConfigCategory.createBuilder().name(Text.literal("Rendering"));
		if (!FfmpegEncoder.available(cfg.ffmpegPath)) {
			rendering.option(LabelOption.create(Text.literal(
					"⚠ FFmpeg not found — RGB video will be omitted; actions and depth still record.")
					.formatted(Formatting.RED)));
		}
		rendering.group(videoGroup(cfg, defaults))
				.group(OptionGroup.createBuilder().name(Text.literal("Window"))
						.option(boolOption("Resize window at launch",
								"Start in windowed mode at the recorder-owned window resolution below.",
								defaults.resizeWindowAtLaunch, () -> cfg.resizeWindowAtLaunch,
								v -> cfg.resizeWindowAtLaunch = v, true))
						.option(Option.<Integer>createBuilder().name(Text.literal("Height"))
								.binding(defaults.launchWindowHeight, () -> cfg.launchWindowHeight,
										v -> cfg.launchWindowHeight = v)
								.controller(o -> IntegerSliderControllerBuilder.create(o).range(16, 1080).step(1)).build())
						.option(Option.<Integer>createBuilder().name(Text.literal("Width"))
								.binding(defaults.launchWindowWidth, () -> cfg.launchWindowWidth,
										v -> cfg.launchWindowWidth = v)
								.controller(o -> IntegerSliderControllerBuilder.create(o).range(16, 1920).step(1)).build()).build())
				.group(OptionGroup.createBuilder().name(Text.literal("Lightmap"))
						.option(overrideGamma).option(gamma).build())
				.group(OptionGroup.createBuilder().name(Text.literal("Status effects"))
						.option(effectOption("Night vision",
								"Override night-vision rendering during gameplay and recording without changing the player's real effect state.",
								defaults.nightVisionOverride, () -> cfg.nightVisionOverride,
								v -> cfg.nightVisionOverride = v))
						.option(effectOption("Darkness",
								"Override darkness rendering during gameplay and recording without changing the player's real effect state.",
								defaults.darknessOverride, () -> cfg.darknessOverride,
								v -> cfg.darknessOverride = v)).build());

		return YetAnotherConfigLib.createBuilder()
				.title(Text.literal("Open Crafter Dataset Recorder"))
				.category(recording.build()).category(replay.build())
				.category(rendering.build())
				.save(() -> { cfg.save(); OpenCrafterRecorderClient.syncConfig(); })
				.build().generateScreen(parent);
	}

	private static Option<EffectOverride> effectOption(String name, String description, EffectOverride fallback,
			java.util.function.Supplier<EffectOverride> getter,
			java.util.function.Consumer<EffectOverride> setter) {
		return Option.<EffectOverride>createBuilder().name(Text.literal(name))
				.description(OptionDescription.of(Text.literal(description)))
				.binding(fallback, getter, setter)
				.controller(o -> EnumControllerBuilder.create(o).enumClass(EffectOverride.class)
						.formatValue(v -> Text.literal(v.label()))).build();
	}

	private static Option<Boolean> boolOption(String name, String description, boolean fallback,
			java.util.function.Supplier<Boolean> getter, java.util.function.Consumer<Boolean> setter,
			boolean available) {
		return Option.<Boolean>createBuilder().name(Text.literal(name))
				.description(OptionDescription.of(Text.literal(description)))
				.binding(fallback, getter, setter).controller(TickBoxControllerBuilder::create)
				.available(available).build();
	}

	private static OptionGroup videoGroup(RecorderConfig cfg, RecorderConfig defaults) {
		return OptionGroup.createBuilder().name(Text.literal("Video encoding"))
				.option(Option.<FfmpegEncoder.Backend>createBuilder().name(Text.literal("Encoder backend"))
						.binding(defaults.recordBackend, () -> cfg.recordBackend, v -> cfg.recordBackend = v)
						.controller(o -> EnumControllerBuilder.create(o).enumClass(FfmpegEncoder.Backend.class)).build())
				.option(Option.<FfmpegEncoder.Codec>createBuilder().name(Text.literal("Codec"))
						.binding(defaults.recordCodec, () -> cfg.recordCodec, v -> cfg.recordCodec = v)
						.controller(o -> EnumControllerBuilder.create(o).enumClass(FfmpegEncoder.Codec.class)).build())
				.option(Option.<Integer>createBuilder().name(Text.literal("Quality"))
						.binding(defaults.recordQuality, () -> cfg.recordQuality, v -> cfg.recordQuality = v)
						.controller(o -> IntegerSliderControllerBuilder.create(o).range(0, 51).step(1)).build())
				.option(Option.<Integer>createBuilder().name(Text.literal("Keyframe interval"))
						.binding(defaults.recordKeyframeSec, () -> cfg.recordKeyframeSec, v -> cfg.recordKeyframeSec = v)
						.controller(o -> IntegerSliderControllerBuilder.create(o).range(1, 30).step(1)
								.formatValue(v -> Text.literal(v + " s"))).build())
				.option(Option.<String>createBuilder().name(Text.literal("FFmpeg path"))
						.binding(defaults.ffmpegPath, () -> cfg.ffmpegPath, v -> cfg.ffmpegPath = v)
						.controller(StringControllerBuilder::create).build())
				.build();
	}
}
