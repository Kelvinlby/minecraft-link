package mod.kelvinlby.recorder.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mod.kelvinlby.OpenCrafterLink;
import mod.kelvinlby.media.FfmpegEncoder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Recorder-only configuration, migrated once from the former combined OCL config. */
public final class RecorderConfig {
	public enum EffectOverride {
		AS_RECORDED("As recorded"),
		FORCE_ENABLED("Force enabled"),
		FORCE_DISABLED("Force disabled");

		private final String label;

		EffectOverride(String label) { this.label = label; }
		public String label() { return label; }
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("open-crafter-recorder.json");
	private static final Path LEGACY_PATH = FabricLoader.getInstance().getConfigDir().resolve("open-crafter-link.json");
	private static RecorderConfig instance;

	public boolean recordDataset;
	public boolean autoReplay;
	public boolean eagerPacketEncoding;
	public boolean quitWhenReplayFinished;
	public int recordSampleHz = 20;
	public boolean disableRecipeBookWhileRecording = true;
	public FfmpegEncoder.Backend recordBackend = FfmpegEncoder.Backend.AUTO;
	public FfmpegEncoder.Codec recordCodec = FfmpegEncoder.Codec.H264;
	public int recordQuality = 18;
	public int recordKeyframeSec = 2;
	public String ffmpegPath = "";
	public boolean overrideGamma;
	public double recordingGamma = 0.5;
	public boolean resizeWindowAtLaunch = true;
	public int launchWindowWidth = 768;
	public int launchWindowHeight = 432;
	public EffectOverride nightVisionOverride = EffectOverride.AS_RECORDED;
	public EffectOverride darknessOverride = EffectOverride.AS_RECORDED;

	public static RecorderConfig get() {
		if (instance == null) instance = load();
		return instance;
	}

	public FfmpegEncoder.Settings toVideoSettings() {
		return new FfmpegEncoder.Settings(ffmpegPath, recordBackend, recordCodec, recordQuality, recordKeyframeSec);
	}

	/** Auto replay must reproduce the recorded recipe-book interactions instead of blocking them. */
	public boolean shouldDisableRecipeBookWhileRecording() {
		return !autoReplay && disableRecipeBookWhileRecording;
	}

	public RecorderConfig normalize() {
		recordSampleHz = clamp(recordSampleHz, 1, 60);
		recordQuality = clamp(recordQuality, 0, 51);
		recordKeyframeSec = clamp(recordKeyframeSec, 1, 30);
		recordingGamma = Math.max(0.0, Math.min(10.0, recordingGamma));
		launchWindowWidth = clamp(launchWindowWidth, 16, 1920);
		launchWindowHeight = clamp(launchWindowHeight, 16, 1080);
		if (recordBackend == null) recordBackend = FfmpegEncoder.Backend.AUTO;
		if (recordCodec == null) recordCodec = FfmpegEncoder.Codec.H264;
		if (nightVisionOverride == null) nightVisionOverride = EffectOverride.AS_RECORDED;
		if (darknessOverride == null) darknessOverride = EffectOverride.AS_RECORDED;
		if (ffmpegPath == null) ffmpegPath = "";
		return this;
	}

	private static RecorderConfig load() {
		Path source = Files.exists(PATH) ? PATH : LEGACY_PATH;
		if (Files.exists(source)) {
			try (Reader reader = Files.newBufferedReader(source)) {
				JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
				RecorderConfig loaded = GSON.fromJson(json, RecorderConfig.class);
				if (loaded != null) {
					if (!json.has("autoReplay") && "PACKET_RECORDING".equals(json.has("recordingMode")
							? json.get("recordingMode").getAsString() : null)) loaded.autoReplay = true;
					loaded.normalize();
					if (source.equals(LEGACY_PATH)) loaded.save();
					return loaded;
				}
			} catch (IOException | RuntimeException e) {
				OpenCrafterLink.LOGGER.warn("[open-crafter-recorder] failed to read config, using defaults", e);
			}
		}
		return new RecorderConfig().normalize();
	}

	public void save() {
		normalize();
		try (Writer writer = Files.newBufferedWriter(PATH)) {
			GSON.toJson(this, writer);
		} catch (IOException e) {
			OpenCrafterLink.LOGGER.error("[open-crafter-recorder] failed to write config", e);
		}
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
