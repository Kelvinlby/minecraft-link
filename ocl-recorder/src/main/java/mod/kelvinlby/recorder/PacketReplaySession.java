package mod.kelvinlby.recorder;

import mod.kelvinlby.OpenCrafterLink;
import mod.kelvinlby.link.EagerCaptureGate;
import mod.kelvinlby.link.InventoryState;
import mod.kelvinlby.mixin.RenderTickCounterDynamicAccessor;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.LevelLoadingScreen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.listener.ServerPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.state.NetworkState;
import net.minecraft.network.state.PlayStateFactories;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * One packet input being replayed into an already-live client world.
 *
 * <h2>Eager pipelining</h2>
 * Eager mode drives the render loop from a virtual clock: each <em>sample window</em> fixes a dataset
 * timestamp, applies every packet up to it, and is rendered exactly once. A window used to stay open
 * until the sampler thread had fully consumed its frame — through the GPU fence, the read-back drain,
 * the float conversion and the writer queue — which meant the render thread re-rendered the identical
 * scene for two or more frames per emitted sample, burning GPU time that produced nothing.
 *
 * <p>Now a window is retired as soon as its capture has been <em>issued</em> ({@link #captureIssued}):
 * the GPU copies are already in the command stream and can only observe that window's contents, so the
 * next window may open on the very next frame while the pixels are still travelling. Up to
 * {@link #MAX_IN_FLIGHT} windows are outstanding at once, matched to their frames by sequence number
 * rather than by "there is only ever one". Backpressure is unchanged in spirit — a slow writer simply
 * stops windows from opening — it just now regulates a pipeline instead of a lockstep.
 *
 * <p>Because the world keeps advancing while older frames are still in flight, each window's action set
 * and inventory observation are latched <b>on the render thread at open time</b>, when they belong to
 * the world state that frame will show, rather than being read later from the sampler thread.
 */
final class PacketReplaySession implements AutoCloseable {
	/**
	 * One virtual sample: its dataset timestamp and the observation latched with it. {@code action} and
	 * {@code inventory} are captured on the render thread when the window opens (see the class notes).
	 */
	public record SampleWindow(long seq, long timeMicros, long replayMicros,
			ActionSet action, InventoryState inventory) {}

	/**
	 * How many issued-but-unconsumed sample windows replay allows. The read-back ring is three deep, so
	 * one more than that keeps a window ready to claim the moment a slot frees without ever outrunning
	 * the bounded hand-off queues behind it ({@code VisionFrameBus.EAGER_CAPACITY}).
	 */
	private static final int MAX_IN_FLIGHT = 8;

	/**
	 * Longest the render thread waits for the pipeline to drain before giving up and drawing a frame
	 * that produces no sample. It is woken the instant a sample is retired, so this is only a ceiling.
	 */
	private static final long BACKPRESSURE_WAIT_MS = 2L;

	private final PacketRecordingReader reader;
	private final PacketActionInterpreter actions = new PacketActionInterpreter();
	private final PacketPlayerProjector playerProjector = new PacketPlayerProjector();
	private final boolean eager;
	private final long periodMicros;
	private final Runnable completionCallback;
	private final ReplayWorldHost worldHost;
	private NetworkState<ServerPlayPacketListener> c2sState;
	/** Internal replay time zero is the first PLAY packet; dataset time zero is reset after world bootstrap. */
	private final long playOriginMicros;
	private final long estimatedDurationMicros;
	private final ReplayProgressToast progressToast;

	private PacketRecordingReader.Rec next;
	private long lastRecordMicros;
	private boolean eof;
	private boolean failed;
	private boolean closed;
	/** The window awaiting its capture, or null when none is open (nothing to render for). */
	private SampleWindow openWindow;
	private boolean captureClaimed;
	/** Windows whose capture is issued but whose frame has not reached the dataset yet, by seq. */
	private final Map<Long, SampleWindow> issued = new HashMap<>();
	private int inFlight;
	/** Seq of the last window of the recording once it has been opened; -1 until then. */
	private long finalSeq = -1L;
	private boolean completionPending;
	private boolean naturalComplete;
	private boolean sawGameJoin;
	private volatile boolean captureReady;
	private long bootstrapMicros;
	private long captureOriginMicros;
	private volatile long captureWallStartNs;
	private long nextSeq;
	/** Throughput counters, logged on close: render passes vs. samples they actually produced. */
	private long renderCalls;
	private long windowsOpened;
	/** Wall-clock anchor plus replay elapsed time supplied to vanilla's render tick counter. */
	private boolean virtualClockStarted;
	private long virtualClockBaseMillis;
	private long virtualClockElapsedMicros;

	private Integer oldMaxFps;
	private Boolean oldVsync;

	private PacketReplaySession(PacketRecordingReader reader, int hz, boolean eager,
			ReplayWorldHost worldHost, Runnable completionCallback) throws IOException {
		this.reader = reader;
		this.eager = eager;
		this.periodMicros = Math.max(1L, 1_000_000L / Math.max(1, hz));
		this.completionCallback = completionCallback;
		this.worldHost = worldHost;
		this.next = reader.next();
		while (next != null && next.phase() != PacketRecordingReader.Phase.CONFIGURATION
				&& next.phase() != PacketRecordingReader.Phase.PLAY) next = reader.next();
		this.playOriginMicros = next == null ? 0L : next.timeMicros();
		this.estimatedDurationMicros = reader.estimatedEndMicros() < 0L
				? -1L : Math.max(1L, reader.estimatedEndMicros() - playOriginMicros);
		this.progressToast = eager ? new ReplayProgressToast(reader.source().getFileName().toString()) : null;
		this.eof = next == null;
	}

	public static PacketReplaySession open(Path inbox, int hz, boolean eager, MinecraftClient mc,
			Runnable completionCallback) throws IOException {
		PacketRecordingReader reader = PacketRecordingReader.openNext(inbox);
		if (reader == null) return null;
		int recorded = reader.header().protocolVersion;
		int current = SharedConstants.getProtocolVersion();
		if (recorded != current) {
			reader.close();
			throw new IOException("packet protocol " + recorded + " does not match this client (" + current + ")");
		}
		ReplayWorldHost worldHost;
		try {
			worldHost = new ReplayWorldHost(mc, reader.header().playerName);
		} catch (Throwable t) {
			reader.close();
			throw new IOException("cannot create client-only replay world", t);
		}
		PacketReplaySession session;
		try {
			session = new PacketReplaySession(reader, hz, eager, worldHost, completionCallback);
		} catch (Throwable t) {
			worldHost.close();
			if (t instanceof IOException io) throw io;
			throw new IOException("cannot initialize packet replay", t);
		}
		ReplayOutboundGuard.setActive(true);
		if (eager) session.removeFrameLimit(mc);
		OpenCrafterLink.LOGGER.info("[open-crafter-link] replaying packet recording {} (player={}, version={}, eager={})",
				reader.source(), reader.header().playerName, reader.header().minecraftVersion, eager);
		return session;
	}

	public SampleSource actions() { return actions; }
	public boolean eager() { return eager; }
	public boolean captureReady() { return captureReady; }
	public long captureWallStartNs() { return captureWallStartNs; }
	public long replayTimeForOffset(long offsetMicros) { return captureOriginMicros + offsetMicros; }
	public Path source() { return reader.source(); }
	ReplayWorldHost worldHost() { return worldHost; }
	public synchronized boolean completedNaturally() { return naturalComplete && !failed; }

	/**
	 * Advance login/chunk bootstrap independently of RGBD capture. Eager capture cannot wait for a world
	 * frame while vanilla's loading screen is itself waiting for later chunk packets in the recording.
	 */
	public void onClientTick(MinecraftClient mc) {
		Runnable callback = null;
		synchronized (this) {
			if (closed) return;
			// End of the client tick: vanilla has just ticked (and locally rotated) any ridden vehicle.
			ReplayVehicleAnchor.reassert(mc);
			if (captureReady) return;
			bootstrapMicros += 1_000_000L; // consume one recorded second per client tick while loading
			advanceTo(bootstrapMicros, mc);
			actions.refreshObservation(mc);
			boolean loading = mc.currentScreen instanceof LevelLoadingScreen;
			if (sawGameJoin && !loading && mc.player != null && mc.world != null) {
				captureOriginMicros = bootstrapMicros;
				captureWallStartNs = System.nanoTime();
				actions.sampleAt(captureOriginMicros); // discard bootstrap-only edge actions/pulses
				captureReady = true;
				notifyAll();
				OpenCrafterLink.LOGGER.info("[open-crafter-link] packet world ready at {} us; starting dataset timeline",
						captureOriginMicros);
			} else if ((eof && !sawGameJoin) || failed) {
				failed = true;
				completionPending = false;
				callback = completionCallback;
			}
		}
		if (callback != null) callback.run();
	}

	/**
	 * Render-thread hook immediately before vanilla calculates its client ticks. In eager mode this
	 * opens the next output window, applies its packets, latches the observation that belongs to it, and
	 * advances Minecraft's clock by the configured sample period.
	 *
	 * <p>When the pipeline is saturated — {@link #MAX_IN_FLIGHT} captures already travelling, typically
	 * because a downstream stage is slower than rendering — no window opens and the returned timestamp is
	 * unchanged, so the frame about to be drawn cannot tick animations twice. Rather than spend a whole
	 * GPU pass on a frame that produces nothing, the thread waits briefly for a sample to retire; the
	 * sampler notifies as soon as one does, so the wait is normally far shorter than its ceiling.
	 */
	public long prepareRenderTime(long wallTimeMillis, MinecraftClient mc) {
		long timeMillis;
		synchronized (this) {
			if (closed || !eager || !captureReady) return wallTimeMillis;
			renderCalls++;
			if (!virtualClockStarted) {
				virtualClockStarted = true;
				virtualClockBaseMillis = wallTimeMillis;
				virtualClockElapsedMicros = 0L;
			}
			if (openWindow == null && inFlight >= MAX_IN_FLIGHT && !completionPending && !failed) {
				try {
					wait(BACKPRESSURE_WAIT_MS); // frameConsumed notifies; this releases the lock meanwhile
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			if (canOpenWindow()) {
				long datasetTime = (nextSeq + 1L) * periodMicros;
				long target = captureOriginMicros + datasetTime;
				advanceTo(target, mc);
				actions.refreshObservation(mc);
				// Latch the observation here, on the render thread, with the world exactly as this frame
				// will draw it — later windows will have moved the world on before the sampler runs.
				openWindow = new SampleWindow(nextSeq, datasetTime, target,
						actions.sampleAt(target), actions.currentInventory());
				captureClaimed = false;
				if (eof && target >= lastRecordMicros) finalSeq = nextSeq;
				nextSeq++;
				windowsOpened++;
				virtualClockElapsedMicros = datasetTime;
			}
			timeMillis = virtualClockBaseMillis + virtualClockElapsedMicros / 1_000L;
		}
		return timeMillis;
	}

	/** Whether a fresh sample window may be opened. Caller holds the lock. */
	private boolean canOpenWindow() {
		return openWindow == null && !completionPending && !failed
				&& finalSeq < 0L && inFlight < MAX_IN_FLIGHT;
	}

	/** Render-thread world hook. Eager packet advancement already happened before client ticking. */
	public void onRenderStart(MinecraftClient mc) {
		Runnable callback = null;
		synchronized (this) {
			if (closed) return;
			if (!captureReady) return;
			if (completionPending) {
				completionPending = false; // invoke exactly once
				callback = completionCallback;
			} else if (!eager) {
				long target = captureOriginMicros
						+ Math.max(0L, (System.nanoTime() - captureWallStartNs) / 1_000L);
				advanceTo(target, mc);
				actions.refreshObservation(mc);
				if (eof && target >= lastRecordMicros + periodMicros) {
					naturalComplete = true;
					completionPending = true;
					callback = completionCallback;
					completionPending = false;
				}
			}
		}
		if (callback != null) callback.run();
	}

	private void updateProgress(long processedMicros, long processedSamples) {
		if (progressToast == null) return;
		long remainingMicros = estimatedDurationMicros < 0L
				? -1L : Math.max(1L, estimatedDurationMicros - captureOriginMicros);
		float progress = remainingMicros > 0L
				? (float)Math.min(processedMicros, remainingMicros) / (float)remainingMicros
				: 0.0F; // unindexed standalone recordings have no known denominator
		double elapsedSeconds = Math.max(1L, System.nanoTime() - captureWallStartNs) / 1_000_000_000.0;
		progressToast.setProgress(progress, processedSamples / elapsedSeconds);
	}

	private void advanceTo(long targetMicros, MinecraftClient mc) {
		while (!failed && next != null && replayTime(next) <= targetMicros) {
			PacketRecordingReader.Rec rec = next;
			long recMicros = replayTime(rec);
			lastRecordMicros = Math.max(lastRecordMicros, recMicros);
			try {
				// Bring predicted client-only visuals up to this packet before applying it, preserving
				// ordering when STOP_DESTROY_BLOCK or an S2C block update lands on the same timestamp.
				playerProjector.advanceTo(recMicros, mc);
				if (rec.gap()) {
					OpenCrafterLink.LOGGER.warn("[open-crafter-link] packet recording contains a capture gap at {} us", recMicros);
				} else if (rec.phase() == PacketRecordingReader.Phase.CONFIGURATION
						|| rec.phase() == PacketRecordingReader.Phase.PLAY) {
					decodeAndApply(rec, recMicros, mc);
				}
				next = reader.next();
				eof = next == null;
			} catch (Throwable t) {
				failed = true;
				eof = true;
				next = null;
				OpenCrafterLink.LOGGER.error("[open-crafter-link] packet replay failed at {} us", rec.timeMicros(), t);
				completionPending = true;
			}
		}
		playerProjector.advanceTo(targetMicros, mc);
	}

	private long replayTime(PacketRecordingReader.Rec rec) {
		return Math.max(0L, rec.timeMicros() - playOriginMicros);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private void decodeAndApply(PacketRecordingReader.Rec rec, long replayMicros, MinecraftClient mc) {
		if (rec.direction() == PacketRecordingReader.Direction.S2C) {
			if (reader.isDisconnect(rec)) {
				// A capture that ended with the player being disconnected records the server's final
				// DISCONNECT. Applying it unloads the replay world before the timeline renders its last
				// sample, which would strand an otherwise complete input in the inbox.
				OpenCrafterLink.LOGGER.info("[open-crafter-link] recorded disconnect at {} us ends the packet stream", replayMicros);
				return;
			}
			worldHost.acceptS2c(rec.data());
			if (rec.phase() == PacketRecordingReader.Phase.PLAY && mc.world != null && mc.player != null) {
				sawGameJoin = true;
			}
		} else if (rec.phase() == PacketRecordingReader.Phase.PLAY) {
			ClientPlayNetworkHandler handler = mc.getNetworkHandler();
			if (handler == null) return;
			if (c2sState == null) c2sState = PlayStateFactories.C2S.bind(
					RegistryByteBuf.makeFactory(handler.getRegistryManager()), () -> true);
			Packet packet = c2sState.codec().decode(io.netty.buffer.Unpooled.wrappedBuffer(rec.data()));
			// Classify against the pre-click inventory, then reproduce vanilla's local prediction so
			// camera and hand state are correct before the next RGBD capture.
			actions.accept(packet, replayMicros, mc);
			ReplayOutboundGuard.runIsolated(() -> playerProjector.accept(packet, replayMicros, mc));
		}
	}

	/** Permit exactly one GPU capture for each eager sample window; returns that window's seq. */
	public synchronized long claimCapture() {
		if (!eager || closed || openWindow == null || captureClaimed) return EagerCaptureGate.NO_CAPTURE;
		captureClaimed = true;
		return openWindow.seq();
	}

	/** Return a claim when the render seam had to abandon the GPU slot before issuing the copies. */
	public synchronized void releaseCapture() {
		captureClaimed = false; // the window stays open and is retried on a later frame
	}

	/**
	 * Both read-back copies for {@code seq} are in the GL command stream. They can no longer observe
	 * anything but this window, so the window is retired for rendering and the next one may open on the
	 * following frame while these pixels are still in flight.
	 */
	public synchronized void captureIssued(long seq) {
		if (openWindow == null || openWindow.seq() != seq) return;
		issued.put(seq, openWindow);
		openWindow = null;
		captureClaimed = false;
		inFlight++;
	}

	/** The window a committed capture belongs to, or null if it was cancelled. Sampler thread. */
	public synchronized SampleWindow windowFor(long seq) {
		return issued.get(seq);
	}

	/** Called only after the corresponding fresh RGBD frame is safely queued to the dataset writer. */
	public synchronized void frameConsumed(long seq) {
		SampleWindow consumed = issued.remove(seq);
		if (consumed == null) return;
		inFlight--;
		updateProgress(consumed.timeMicros(), seq + 1L);
		// Sequence numbers are consumed in order, so the final window being consumed means the pipeline
		// is empty — every earlier sample is already on the writer's queue.
		if (seq == finalSeq) naturalComplete = true;
		if (seq == finalSeq || failed) completionPending = true;
		notifyAll();
	}

	/**
	 * A committed capture was thrown away before its pixels reached the CPU (the read-back ring was torn
	 * down under it, e.g. the framebuffer was resized mid-replay). The sample is gone, so the session
	 * cannot be archived as a faithful encode of its input — fail it loudly and let the batch halt with
	 * the input still in the inbox rather than write a dataset with a silent hole in it.
	 */
	public synchronized void captureCancelled(long seq) {
		if (issued.remove(seq) == null) return;
		inFlight--;
		failed = true;
		completionPending = true;
		OpenCrafterLink.LOGGER.error("[open-crafter-link] eager capture for sample {} was abandoned; failing the replay", seq);
		notifyAll();
	}

	private void removeFrameLimit(MinecraftClient mc) {
		oldMaxFps = mc.options.getMaxFps().getValue();
		oldVsync = mc.options.getEnableVsync().getValue();
		mc.options.getEnableVsync().setValue(false);
		mc.options.getMaxFps().setValue(260);
	}

	private void restoreFrameLimit(MinecraftClient mc) {
		if (!mc.isOnThread()) {
			if (mc.isRunning()) mc.execute(() -> restoreFrameLimit(mc));
			return; // on JVM shutdown no restoration is needed; the process is exiting
		}
		if (oldVsync != null) mc.options.getEnableVsync().setValue(oldVsync);
		if (oldMaxFps != null) mc.options.getMaxFps().setValue(oldMaxFps);
		// Eager replay may have run far ahead of wall time. Re-anchor vanilla before returning to
		// menus; otherwise its next delta is negative and client ticking can stall for that lead time.
		if (virtualClockStarted && mc.getRenderTickCounter() instanceof RenderTickCounter.Dynamic dynamic) {
			RenderTickCounterDynamicAccessor clock = (RenderTickCounterDynamicAccessor)(Object)dynamic;
			long now = Util.getMeasuringTimeMs();
			clock.ocl$setLastTimeMillis(now);
			clock.ocl$setTimeMillis(now);
			clock.ocl$setDynamicDeltaTicks(0.0F);
			clock.ocl$setFixedDeltaTicks(0.0F);
			clock.ocl$setTickProgress(0.0F);
			virtualClockStarted = false;
		}
		oldVsync = null; oldMaxFps = null;
	}

	@Override public void close() {
		synchronized (this) {
			closed = true;
			if (eager && windowsOpened > 0) {
				// The efficiency of the eager pipeline in one number: render passes per emitted sample.
				// 1.0 means every rendered frame produced a sample; anything well above it means the
				// render thread is waiting on a slower stage (usually the dataset writer).
				OpenCrafterLink.LOGGER.info("[open-crafter-link] eager replay: {} samples from {} render passes ({} renders/sample)",
						windowsOpened, renderCalls, String.format("%.2f", (double)renderCalls / (double)windowsOpened));
			}
			notifyAll();
		}
		playerProjector.close(MinecraftClient.getInstance());
		ReplayOutboundGuard.setActive(false);
		if (progressToast != null) progressToast.hide();
		restoreFrameLimit(MinecraftClient.getInstance());
		try { reader.close(); }
		catch (IOException e) { OpenCrafterLink.LOGGER.warn("[open-crafter-link] failed closing packet recording", e); }
	}

	/** Move a successfully encoded input into replay/done, collision-safely. */
	public void archive() throws IOException {
		Path source = source();
		Path done = source.getParent().resolve("done");
		Files.createDirectories(done);
		Path target = done.resolve(source.getFileName());
		for (int i = 1; Files.exists(target); i++) target = done.resolve(source.getFileName() + "." + i);
		try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
		catch (AtomicMoveNotSupportedException e) { Files.move(source, target); }
		OpenCrafterLink.LOGGER.info("[open-crafter-link] archived encoded packet recording to {}", target);
	}
}
