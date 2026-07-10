package mod.kelvinlby.recorder;

import mod.kelvinlby.OpenCrafterLink;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Lifecycle facade for the dataset recorder. Owns a {@link Sampler} (which owns the fixed clock, the
 * writer thread, and a {@link DatasetWriter}) and the {@link ActionReader} that observes the human's
 * live inputs.
 *
 * <p><b>Sessions are world-scoped.</b> The "Record dataset" option <em>arms</em> the recorder
 * ({@link #syncTo}); a session then starts each time the player joins a world — single-player or
 * multiplayer ({@link #onWorldJoin}, wired to {@code ClientPlayConnectionEvents.JOIN}) — and is
 * finalized when they leave it ({@link #onWorldLeave}, {@code DISCONNECT}). Title/menu screens
 * between worlds are never recorded. Toggling the option while already in a world starts/stops a
 * session immediately on save.
 *
 * <p><b>Finalize is asynchronous with a progress toast.</b> Leaving a world triggers
 * {@link #stopAsync()}: a daemon thread drains every queued sample and closes the files while a
 * {@link SaveToast} shows the progress and the final saved/failed state — so the player knows when
 * the session is 100% on disk without the game thread ever blocking on it. Game shutdown instead
 * uses the synchronous, toast-less {@link #shutdown()}, which also joins any finalize still in
 * flight so quitting right after leaving a world cannot truncate the save.
 *
 * <p>Each session writes to {@code <gameDir>/open-crafter-link/<timestamp>/}. {@code getGameDir()} is
 * the Minecraft profile/instance root (note {@code OclConfig} uses {@code getConfigDir()} =
 * {@code <gameDir>/config}). The RGBD frames come from the link's existing vision pipeline via
 * {@link VisionTap}, which a session enables on start and disables on stop so the bridges skip the
 * tap when nobody is recording.
 */
public final class Recorder {

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	private final ActionReader actionReader = new ActionReader();
	private Sampler sampler;
	private volatile boolean running;

	// Armed config, applied to the next session. Guarded by this.
	private boolean armed;
	private int sampleHz = 20;
	private FfmpegEncoder.Settings video;

	/** The in-flight async finalize, if any; {@link #shutdown()} joins it. Guarded by this. */
	private Thread finalizeThread;

	/** The action reader to register on a client-tick event; it observes the human's live inputs. */
	public ActionReader actionReader() {
		return actionReader;
	}

	/** Whether a session is currently recording. */
	public boolean isRunning() {
		return running;
	}

	/**
	 * Reconcile to the config: arm/disarm world-scoped recording, and — when the player is already in
	 * a world — start or finalize a session right away so the settings toggle acts immediately.
	 * Called at init (where no world exists yet, so it only arms) and after each settings save.
	 */
	public synchronized void syncTo(boolean enabled, int hz, FfmpegEncoder.Settings videoSettings) {
		this.armed = enabled;
		this.sampleHz = hz;
		this.video = videoSettings;
		MinecraftClient mc = MinecraftClient.getInstance(); // null during client construction (mod init)
		boolean inWorld = mc != null && mc.world != null;
		if (enabled && inWorld && !running) {
			start();
		} else if (!enabled && running) {
			stopAsync();
		}
	}

	/** World joined (SP or MP) — begin a session if the recorder is armed. */
	public synchronized void onWorldJoin() {
		if (armed && !running) {
			start();
		}
	}

	/** World left — finalize the session in the background, with a save-progress toast. */
	public synchronized void onWorldLeave() {
		if (running) {
			stopAsync();
		}
	}

	/** Begin a new recording session at the armed settings. Caller holds the lock. */
	private void start() {
		Path dir = sessionDir();
		DatasetWriter writer = new DatasetWriter(dir, sampleHz, video);
		Sampler s = new Sampler(sampleHz, actionReader, writer);
		VisionTap.setActive(true); // bridges start publishing converted frames
		InventoryActionTap.resetDropped();
		InventoryActionTap.setActive(true); // the clickSlot mixin starts buffering slot clicks
		try {
			s.start();
		} catch (IOException e) {
			VisionTap.setActive(false);
			InventoryActionTap.setActive(false);
			OpenCrafterLink.LOGGER.error("[open-crafter-link] failed to start recording in {}", dir, e);
			return;
		}
		sampler = s;
		running = true;
	}

	/**
	 * Finalize the current session on a background thread: drain the writer queue, close the files,
	 * and keep a {@link SaveToast} updated with the progress and outcome. Caller holds the lock.
	 */
	private void stopAsync() {
		running = false;
		VisionTap.setActive(false);
		InventoryActionTap.setActive(false);
		Sampler s = sampler;
		sampler = null;
		if (s == null) {
			return;
		}
		SaveToast toast = new SaveToast(s.sessionName());
		Thread t = new Thread(() -> toast.done(s.stop(toast::progress)), "ocl-recorder-finalize");
		t.setDaemon(true);
		finalizeThread = t;
		t.start();
	}

	/**
	 * Synchronous, toast-less stop for game shutdown ({@code CLIENT_STOPPING} / the JVM shutdown
	 * hook): finalizes any running session on the calling thread and joins an async finalize still in
	 * flight, so quitting right after leaving a world cannot truncate the save. Idempotent.
	 */
	public void shutdown() {
		Sampler s;
		Thread inFlight;
		synchronized (this) {
			running = false;
			VisionTap.setActive(false);
			InventoryActionTap.setActive(false);
			s = sampler;
			sampler = null;
			inFlight = finalizeThread;
			finalizeThread = null;
		}
		if (s != null) {
			s.stop(null);
		}
		if (inFlight != null && inFlight.isAlive()) {
			try {
				inFlight.join(30_000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private static Path sessionDir() {
		Path root = FabricLoader.getInstance().getGameDir().resolve("open-crafter-link");
		return root.resolve(LocalDateTime.now().format(STAMP));
	}
}
