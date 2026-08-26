package mod.kelvinlby.recorder;

import mod.kelvinlby.OpenCrafterLink;
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

/** One packet input being replayed into an already-live client world. */
final class PacketReplaySession implements AutoCloseable {
	public record SampleWindow(long seq, long timeMicros, long replayMicros) {}

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
	private boolean awaitingFrame;
	private boolean captureClaimed;
	private boolean finalWindow;
	private boolean completionPending;
	private boolean naturalComplete;
	private boolean sawGameJoin;
	private volatile boolean captureReady;
	private long bootstrapMicros;
	private long captureOriginMicros;
	private volatile long captureWallStartNs;
	private long nextSeq;
	private SampleWindow activeWindow;
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
	 * opens exactly one output window, applies its packets, and advances Minecraft's clock by the
	 * configured sample period. Repeated renders while the encoder owns that window see the same
	 * timestamp, so they cannot accidentally tick animations twice.
	 */
	public long prepareRenderTime(long wallTimeMillis, MinecraftClient mc) {
		synchronized (this) {
			if (closed || !eager || !captureReady) return wallTimeMillis;
			if (!virtualClockStarted) {
				virtualClockStarted = true;
				virtualClockBaseMillis = wallTimeMillis;
				virtualClockElapsedMicros = 0L;
			}
			if (!completionPending && !awaitingFrame) {
				long datasetTime = (nextSeq + 1L) * periodMicros;
				long target = captureOriginMicros + datasetTime;
				advanceTo(target, mc);
				actions.refreshObservation(mc);
				updateProgress(target);
				activeWindow = new SampleWindow(nextSeq, datasetTime, target);
				awaitingFrame = true;
				captureClaimed = false;
				finalWindow = eof && target >= lastRecordMicros;
				virtualClockElapsedMicros = datasetTime;
				notifyAll();
			}
			return virtualClockBaseMillis + virtualClockElapsedMicros / 1_000L;
		}
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

	private void updateProgress(long replayMicros) {
		if (progressToast == null) return;
		if (estimatedDurationMicros > 0L) {
			progressToast.setProgress((float)Math.min(replayMicros, estimatedDurationMicros)
					/ (float)estimatedDurationMicros);
		} else {
			progressToast.setProgress(0.0F); // unindexed standalone recordings have no known denominator
		}
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

	/** Sampler-thread wait for the eager frame boundary opened by {@link #onRenderStart}. */
	public synchronized SampleWindow awaitWindow(long afterSeq) throws InterruptedException {
		while (!closed && (activeWindow == null || activeWindow.seq() <= afterSeq || !awaitingFrame)) wait(100L);
		return closed ? null : activeWindow;
	}

	/** Permit exactly one GPU capture for each eager sample window. */
	public synchronized boolean claimCapture() {
		if (!eager || !awaitingFrame || captureClaimed || closed) return false;
		captureClaimed = true;
		return true;
	}

	/** Return a claim when the render seam had to abandon the GPU slot before publishing a frame. */
	public synchronized void releaseCapture() {
		if (awaitingFrame) captureClaimed = false;
	}

	/** Called only after the corresponding fresh RGBD frame is safely queued to the dataset writer. */
	public synchronized void frameConsumed(long seq) {
		if (!awaitingFrame || activeWindow == null || activeWindow.seq() != seq) return;
		awaitingFrame = false;
		nextSeq = seq + 1L;
		if (finalWindow) naturalComplete = true;
		if (finalWindow || failed) completionPending = true;
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
		synchronized (this) { closed = true; notifyAll(); }
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
