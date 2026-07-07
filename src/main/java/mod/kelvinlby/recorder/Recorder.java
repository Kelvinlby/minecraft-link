package mod.kelvinlby.recorder;

import mod.kelvinlby.OpenCrafterLink;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Lifecycle facade for the dataset recorder. Owns a {@link Sampler} (which owns the fixed clock, the
 * writer thread, and a {@link DatasetWriter}) and the {@link ActionReader} that observes the human's
 * live inputs. One session per {@link #start(int)} / {@link #stop()} pair.
 *
 * <p>Each session writes to {@code <gameDir>/open-crafter-link/<timestamp>/}. {@code getGameDir()} is
 * the Minecraft profile/instance root (note {@code OclConfig} uses {@code getConfigDir()} =
 * {@code <gameDir>/config}). The RGBD frames come from the link's existing vision pipeline via
 * {@link VisionTap}, which this enables on start and disables on stop so the bridges skip the tap when
 * nobody is recording.
 *
 * <p>Wiring: the client registers {@link #actionReader()} on a client-tick event and calls
 * {@link #syncTo} at init and after each settings save so toggling the "Record dataset" option
 * starts/stops a session live.
 */
public final class Recorder {

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	private final ActionReader actionReader = new ActionReader();
	private Sampler sampler;
	private volatile boolean running;

	/** The action reader to register on a client-tick event; it observes the human's live inputs. */
	public ActionReader actionReader() {
		return actionReader;
	}

	/** Whether a session is currently recording. */
	public boolean isRunning() {
		return running;
	}

	/**
	 * Reconcile the running state to the config: start a fresh session if recording is enabled and not
	 * already running, stop the current one if disabled. Called at init and after a settings save.
	 * {@code video} is the config's ffmpeg encoding settings, snapshotted for the session.
	 */
	public synchronized void syncTo(boolean enabled, int sampleHz, FfmpegEncoder.Settings video) {
		if (enabled && !running) {
			start(sampleHz, video);
		} else if (!enabled && running) {
			stop();
		}
	}

	/** Begin a new recording session at {@code sampleHz}. No-op if already running. */
	public synchronized void start(int sampleHz, FfmpegEncoder.Settings video) {
		if (running) {
			return;
		}
		Path dir = sessionDir();
		DatasetWriter writer = new DatasetWriter(dir, sampleHz, video);
		Sampler s = new Sampler(sampleHz, actionReader, writer);
		VisionTap.setActive(true); // bridges start publishing converted frames
		try {
			s.start();
		} catch (IOException e) {
			VisionTap.setActive(false);
			OpenCrafterLink.LOGGER.error("[open-crafter-link] failed to start recording in {}", dir, e);
			return;
		}
		sampler = s;
		running = true;
	}

	/** Finalize and close the current session. Idempotent. */
	public synchronized void stop() {
		if (!running) {
			return;
		}
		running = false;
		VisionTap.setActive(false);
		if (sampler != null) {
			sampler.stop();
			sampler = null;
		}
	}

	private static Path sessionDir() {
		Path root = FabricLoader.getInstance().getGameDir().resolve("open-crafter-link");
		return root.resolve(LocalDateTime.now().format(STAMP));
	}
}
