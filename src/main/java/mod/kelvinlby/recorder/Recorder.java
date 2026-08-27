package mod.kelvinlby.recorder;

import mod.kelvinlby.OpenCrafterLink;
import mod.kelvinlby.link.EagerCaptureGate;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;

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
 * <p>Each session writes to
 * {@code <gameDir>/open-crafter-link/recording/<timestamp>/}. {@code getGameDir()} is the
 * Minecraft profile/instance root (the config instead lives under {@code <gameDir>/config}).
 * The RGBD frames come from the link's existing vision pipeline via {@link VisionTap}, which a
 * session enables on start and disables on stop so the bridges skip the tap when nobody is recording.
 */
public final class Recorder {

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	private static final Path REPLAY_INBOX = OpenCrafterLink.PROFILE_DIR.resolve("replay");

	private final ActionReader actionReader = new ActionReader();
	private Sampler sampler;
	private PacketReplaySession replay;
	private ReplayWorldHost replayWorldHost;
	private volatile boolean running;

	// Armed config, applied to the next session. Guarded by this.
	private boolean armed;
	private boolean autoReplay;
	private boolean eagerPacketEncoding;
	private boolean quitWhenFinished;
	private int sampleHz = 20;
	private FfmpegEncoder.Settings video;

	/** The in-flight async finalize, if any; {@link #shutdown()} joins it. Guarded by this. */
	private Thread finalizeThread;
	private boolean autoWorldActive;
	private boolean autoBatchHalted;
	private boolean disconnectQueued;
	/** Set once the quit-when-finished shutdown has been scheduled, so it is requested exactly once. */
	private boolean quitScheduled;
	/** Whether this game session has taken an input out of the replay inbox; gates the quit on finish. */
	private boolean autoBatchRan;

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
	 *
	 * <p>{@code quitWhenFinished} makes an auto-replay instance close itself once it has drained the
	 * inbox, so an external batch runner can use the process exit as the completion signal for that
	 * instance. It only ever fires for a batch this game session actually started: an inbox that was
	 * empty the whole time leaves the game running as usual.
	 */
	public synchronized void syncTo(boolean enabled, boolean autoReplay, boolean eagerEncoding,
			boolean quitWhenFinished, int hz, FfmpegEncoder.Settings videoSettings) {
		this.armed = enabled;
		this.autoReplay = autoReplay;
		this.autoBatchHalted = false; // an explicit settings save/reload permits retrying a failed input
		this.eagerPacketEncoding = eagerEncoding;
		this.quitWhenFinished = quitWhenFinished;
		this.sampleHz = hz;
		this.video = videoSettings;
		MinecraftClient mc = MinecraftClient.getInstance(); // null during client construction (mod init)
		boolean inWorld = mc != null && mc.world != null;
		if (!enabled) {
			if (running) stopAsync();
			if (autoWorldActive) queueDisconnectToTitle(mc);
		} else if (autoReplay) {
			// Auto replay owns a client-only packet world. Never replay into the user's current save.
			if (inWorld && !autoWorldActive) {
				if (running) stopAsync();
				if (hasPendingReplay()) queueDisconnectToTitle(mc);
			}
		} else if (inWorld && !running) {
			start();
		}
		if (!autoReplay && autoWorldActive) {
			autoWorldActive = false;
			queueDisconnectToTitle(mc);
		}
	}

	/** World joined (SP or MP) — begin a session if the recorder is armed. */
	public synchronized void onWorldJoin() {
		if (!armed) return;
		if (autoReplay) {
			// The fake connection may emit Fabric's JOIN event while start() is already bootstrapping it.
			if (autoWorldActive) return;
			// A manually entered world is left alone while the replay inbox is empty.
			if (hasPendingReplay()) queueDisconnectToTitle(MinecraftClient.getInstance());
			return;
		}
		if (!running) {
			start();
		}
	}

	/** World left — finalize the session in the background, with a save-progress toast. */
	public synchronized void onWorldLeave() {
		if (running) {
			stopAsync();
		}
		autoWorldActive = false;
	}

	/** Begin a new recording session at the armed settings. Caller holds the lock. */
	private void start() {
		MinecraftClient mc = MinecraftClient.getInstance();
		PacketReplaySession replaySession = null;
		SampleSource source = actionReader;
		if (autoReplay) {
			autoWorldActive = true;
			autoBatchRan = true; // there was queued work, so this instance now has a batch to finish
			try {
				replaySession = PacketReplaySession.open(REPLAY_INBOX,
						sampleHz, eagerPacketEncoding, mc, this::onReplayFinished);
			} catch (IOException e) {
				OpenCrafterLink.LOGGER.error("[open-crafter-link] cannot start packet replay", e);
				autoBatchHalted = true;
				exitAutoReplayWorld();
				return;
			}
			if (replaySession == null) {
				// The queue probe that launched this world found a candidate. If none of them opens, the
				// probe would keep re-launching forever, so treat an unreadable inbox as a halt instead.
				if (hasPendingReplay()) {
					OpenCrafterLink.LOGGER.error("[open-crafter-link] auto replay halted: no readable session in {}", REPLAY_INBOX);
					autoBatchHalted = true;
				} else {
					OpenCrafterLink.LOGGER.info("[open-crafter-link] auto replay complete; {} is empty", REPLAY_INBOX);
				}
				exitAutoReplayWorld();
				return;
			}
			replayWorldHost = replaySession.worldHost();
			source = replaySession.actions();
		}
		Path dir = sessionDir();
		DatasetWriter writer = new DatasetWriter(dir, sampleHz, video);
		Sampler s = new Sampler(sampleHz, source, writer, replaySession);
		VisionTap.setActive(VisionTap.Consumer.RECORDER, true); // bridges start publishing converted frames
		InventoryActionTap.resetDropped();
		InventoryActionTap.setActive(replaySession == null); // packet mode supplies decoded click edges itself
		try {
			s.start();
		} catch (IOException e) {
			VisionTap.setActive(VisionTap.Consumer.RECORDER, false);
			InventoryActionTap.setActive(false);
			if (replaySession != null) replaySession.close();
			OpenCrafterLink.LOGGER.error("[open-crafter-link] failed to start recording in {}", dir, e);
			if (replaySession != null) {
				autoBatchHalted = true;
				exitAutoReplayWorld();
			}
			return;
		}
		sampler = s;
		replay = replaySession;
		running = true;
	}

	/** Called at the beginning of every rendered world frame to advance an active packet timeline. */
	public void onWorldRenderStart(MinecraftClient mc) {
		PacketReplaySession session;
		synchronized (this) { session = replay; }
		if (session != null) session.onRenderStart(mc);
	}

	/** Substitute replay time at the start of Minecraft's render loop while eager capture is active. */
	public long prepareRenderTime(long wallTimeMillis, MinecraftClient mc) {
		PacketReplaySession session;
		synchronized (this) { session = replay; }
		return session == null ? wallTimeMillis : session.prepareRenderTime(wallTimeMillis, mc);
	}

	/** Advance packet-world bootstrap even while the vanilla loading screen prevents world rendering. */
	public void onClientTick(MinecraftClient mc) {
		PacketReplaySession session;
		boolean launch = false;
		String quitReason = null;
		synchronized (this) {
			session = replay;
			// Between inputs the batch rests on the title screen with nothing in flight; that is the only
			// point at which the queue may be re-probed, and the only safe point at which to quit.
			if (armed && autoReplay && !running && !autoWorldActive && !quitScheduled
					&& !disconnectQueued && mc.world == null && mc.currentScreen instanceof TitleScreen
					&& (finalizeThread == null || !finalizeThread.isAlive())) {
				boolean pending = false;
				boolean probed = true;
				try {
					pending = PacketRecordingReader.hasPending(REPLAY_INBOX);
				} catch (IOException e) {
					probed = false; // an unreadable inbox is an unknown queue state, not an empty one
					OpenCrafterLink.LOGGER.error("[open-crafter-link] cannot inspect replay inbox {}", REPLAY_INBOX, e);
				}
				launch = pending && !autoBatchHalted;
				// Only a batch this instance actually started can finish. An inbox that was empty all
				// along is not a completed run, so the game is left alone to be played normally.
				if (probed && !launch && quitWhenFinished && autoBatchRan) {
					// A halted batch is finished too: it cannot make further progress on its own, and a
					// runner waiting on the process would otherwise wait forever. The inbox tells the two
					// outcomes apart — an empty one means every input was encoded and archived.
					quitReason = autoBatchHalted ? "halted on a failed input" : "inbox empty";
					quitScheduled = true;
				}
			}
		}
		if (session != null) session.onClientTick(mc);
		if (quitReason != null) {
			OpenCrafterLink.LOGGER.info("[open-crafter-link] auto replay finished ({}); quitting the game", quitReason);
			mc.scheduleStop();
		} else if (launch) {
			synchronized (this) {
				if (armed && autoReplay && !running && !autoWorldActive) start();
			}
		}
	}

	/** Vision capture bypasses its wall-clock throttle only while eager packet replay owns the timeline. */
	public synchronized boolean eagerCaptureActive() {
		return running && replay != null && replay.eager();
	}

	/**
	 * The eager-capture handshake handed to {@code VisionCapture}. Every call is forwarded to the live
	 * replay session, or ignored once there isn't one — capture seams can still fire for a frame or two
	 * after a session ends.
	 */
	public EagerCaptureGate captureGate() {
		return captureGate;
	}

	private final EagerCaptureGate captureGate = new EagerCaptureGate() {
		@Override public boolean active() {
			return eagerCaptureActive();
		}

		/** Render capture calls this once it has a free GPU slot; one claim per virtual sample window. */
		@Override public long claim() {
			synchronized (Recorder.this) {
				return replay == null ? NO_CAPTURE : replay.claimCapture();
			}
		}

		@Override public void commit(long seq) {
			synchronized (Recorder.this) {
				if (replay != null) replay.captureIssued(seq);
			}
		}

		@Override public void release() {
			synchronized (Recorder.this) {
				if (replay != null) replay.releaseCapture();
			}
		}

		@Override public void cancel(long seq) {
			synchronized (Recorder.this) {
				if (replay != null) replay.captureCancelled(seq);
			}
		}
	};

	private synchronized void onReplayFinished() {
		if (running && replay != null) stopAsync();
	}

	/**
	 * Finalize the current session on a background thread: drain the writer queue, close the files,
	 * and keep a {@link SaveToast} updated with the progress and outcome. Caller holds the lock.
	 */
	private void stopAsync() {
		running = false;
		VisionTap.setActive(VisionTap.Consumer.RECORDER, false);
		InventoryActionTap.setActive(false);
		Sampler s = sampler;
		sampler = null;
		PacketReplaySession replaySession = replay;
		replay = null;
		boolean archive = replaySession != null && replaySession.completedNaturally();
		if (replaySession != null) replaySession.close(); // wakes an eager sampler waiting for a render
		if (s == null) {
			return;
		}
		SaveToast toast = new SaveToast(s.sessionName());
		Thread t = new Thread(() -> {
			SaveResult result = s.stop(toast::progress);
			boolean archived = false;
			if (archive && result != null && result.ok()) {
				try { replaySession.archive(); archived = true; }
				catch (IOException e) {
					OpenCrafterLink.LOGGER.error("[open-crafter-link] dataset saved but packet input could not be archived", e);
				}
			}
			toast.done(result);
			boolean archivedFinal = archived;
			if (replaySession != null) MinecraftClient.getInstance().execute(() -> afterReplayFinalized(archivedFinal));
		}, "ocl-recorder-finalize");
		t.setDaemon(true);
		finalizeThread = t;
		t.start();
	}

	/** Batch mode: after one input is durably encoded and archived, consume the next pending session. */
	private synchronized void afterReplayFinalized(boolean archived) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (!archived || !armed || !autoReplay || !autoWorldActive || mc == null) {
			if (!archived) autoBatchHalted = true;
			exitAutoReplayWorld();
			return;
		}
		// Each recording carries its own configuration/registry stream, so give the next input a fresh
		// in-memory connection instead of trying to transition an existing PLAY connection backwards.
		exitAutoReplayWorld();
	}

	/** Unload the client-only replay world. With no integrated server, this never displays “Saving world”. */
	private synchronized void exitAutoReplayWorld() {
		autoWorldActive = false;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null) return;
		if (mc.world != null) queueDisconnectToTitle(mc);
		else {
			closeReplayWorldHost();
			if (!(mc.currentScreen instanceof TitleScreen)) mc.setScreen(new TitleScreen());
		}
	}

	private synchronized void queueDisconnectToTitle(MinecraftClient mc) {
		if (mc == null || disconnectQueued) return;
		disconnectQueued = true;
		mc.execute(() -> {
			synchronized (Recorder.this) { disconnectQueued = false; }
			if (mc.world != null) mc.disconnect(new TitleScreen(), false);
			else mc.setScreen(new TitleScreen());
			synchronized (Recorder.this) { closeReplayWorldHost(); }
		});
	}

	private void closeReplayWorldHost() {
		ReplayWorldHost host = replayWorldHost;
		replayWorldHost = null;
		if (host != null) host.close();
	}

	private boolean hasPendingReplay() {
		try {
			return PacketRecordingReader.hasPending(REPLAY_INBOX);
		} catch (IOException e) {
			OpenCrafterLink.LOGGER.error("[open-crafter-link] cannot inspect replay inbox {}", REPLAY_INBOX, e);
			return false;
		}
	}

	/**
	 * Synchronous, toast-less stop for game shutdown ({@code CLIENT_STOPPING} / the JVM shutdown
	 * hook): finalizes any running session on the calling thread and joins an async finalize still in
	 * flight, so quitting right after leaving a world cannot truncate the save. Idempotent.
	 */
	public void shutdown() {
		Sampler s;
		PacketReplaySession replaySession;
		boolean archive;
		Thread inFlight;
		synchronized (this) {
			running = false;
			VisionTap.setActive(VisionTap.Consumer.RECORDER, false);
			InventoryActionTap.setActive(false);
			s = sampler;
			sampler = null;
			replaySession = replay;
			replay = null;
			archive = replaySession != null && replaySession.completedNaturally();
			if (replaySession != null) replaySession.close();
			inFlight = finalizeThread;
			finalizeThread = null;
		}
		if (s != null) {
			SaveResult result = s.stop(null);
			if (archive && result != null && result.ok()) {
				try { replaySession.archive(); }
				catch (IOException e) { OpenCrafterLink.LOGGER.error("[open-crafter-link] failed to archive packet input", e); }
			}
		}
		if (inFlight != null && inFlight.isAlive()) {
			try {
				inFlight.join(30_000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		synchronized (this) { closeReplayWorldHost(); }
	}

	private static Path sessionDir() {
		Path root = OpenCrafterLink.PROFILE_DIR.resolve("recording");
		String stamp = LocalDateTime.now().format(STAMP);
		Path candidate = root.resolve(stamp);
		for (int i = 1; java.nio.file.Files.exists(candidate); i++) {
			candidate = root.resolve(stamp + "-" + i);
		}
		return candidate;
	}
}
