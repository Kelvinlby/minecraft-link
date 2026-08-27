package mod.kelvinlby.recorder.config;

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
import mod.kelvinlby.media.FfmpegEncoder;
import mod.kelvinlby.recorder.OpenCrafterRecorderClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** YACL screen owned by the optional recorder mod. */
public final class RecorderConfigScreen {
	private RecorderConfigScreen() {}

	public static Screen create(Screen parent) {
		RecorderConfig cfg = RecorderConfig.get();
		RecorderConfig defaults = new RecorderConfig();
		ConfigCategory.Builder cat = ConfigCategory.createBuilder().name(Text.literal("Recording"));
		if (!FfmpegEncoder.available(cfg.ffmpegPath)) {
			cat.option(LabelOption.create(Text.literal(
					"⚠ FFmpeg not found — RGB video will be omitted; actions and depth still record.")
					.formatted(Formatting.RED)));
		}

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

		cat.option(enabled).option(autoReplay).option(eager).option(quit).option(disableRecipeBook)
				.option(Option.<Integer>createBuilder()
						.name(Text.literal("Sample rate"))
						.description(OptionDescription.of(Text.literal("Aligned samples per second; 20 Hz matches Minecraft ticks.")))
						.binding(defaults.recordSampleHz, () -> cfg.recordSampleHz, v -> cfg.recordSampleHz = v)
						.controller(o -> IntegerSliderControllerBuilder.create(o).range(1, 60).step(1)
								.formatValue(v -> Text.literal(v + " Hz"))).build())
				.group(videoGroup(cfg, defaults));

		return YetAnotherConfigLib.createBuilder()
				.title(Text.literal("Open Crafter Dataset Recorder"))
				.category(cat.build())
				.save(() -> { cfg.save(); OpenCrafterRecorderClient.syncConfig(); })
				.build().generateScreen(parent);
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
