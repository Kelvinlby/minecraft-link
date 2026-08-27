package mod.kelvinlby.recorder;

import mod.kelvinlby.OpenCrafterLink;
import mod.kelvinlby.link.InventoryAction;
import mod.kelvinlby.link.VisionFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * The recorder's fixed-rate clock. A dedicated daemon thread fires every {@code 1/hz} seconds, latches
 * the newest RGBD frame ({@link VisionTap#latest()}) together with the current action set
 * ({@link ActionReader#current()}) into one {@link Sample}, and hands it to a second writer thread via
 * a bounded queue. A separate writer thread does the video encode + disk I/O so it never stalls the
 * clock. Frames are packed to their storage precision ({@link PackedFrame}) on the clock thread, so a
 * queued sample holds compact bytes, not raw floats.
 *
 * <h2>Why not reuse the streaming handoff</h2>
 * The link's vision path is conflating (drops frames under load), which is wrong for a recorder that
 * must emit exactly one aligned sample per clock tick. So the sampler owns its own clock and simply
 * reads the latest available frame. When no fresh frame exists (menu/paused), it repeats the previous
 * one and flags {@code frameRepeated}, keeping the stream 1:1 with a continuous clock.
 *
 * <h2>Steady rate</h2>
 * The clock uses <b>absolute deadlines</b> ({@code next += periodNs}) with {@link LockSupport#parkNanos}
 * rather than {@code sleep(period)}, so per-tick scheduling error does not accumulate and the long-run
 * rate stays locked to the target even under jitter. If the writer falls behind (queue full), the
 * sample is dropped and counted rather than blocking the clock.
 */
public final class Sampler {

	/**
	 * Bounded so a stalled writer applies backpressure as drops, not memory growth. Sized to absorb
	 * scheduling jitter only — a deep queue would just pin megabytes of frames behind a writer that is
	 * not keeping up anyway (a 256-deep queue of raw-float frames once pinned ~1.4 GB of heap and drove
	 * the whole system into memory pressure; the drop counter surfaces overflow instead).
	 */
	private static final int QUEUE_CAPACITY = 16;

	private final int hz;
	private final SampleSource actions;
	private final DatasetWriter writer;
	/** Non-null only for packet mode; provides its virtual clock and eager render handshake. */
	private final PacketReplaySession replay;

	private final BlockingQueue<Sample> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
	private volatile boolean running;
	private Thread clockThread;
	private Thread writerThread;

	private final AtomicLong written = new AtomicLong();
	private final AtomicLong dropped = new AtomicLong();
	private final AtomicLong repeated = new AtomicLong();
	/** Time the eager sampler spent waiting on a full writer queue — i.e. how writer-bound the run was. */
	private final AtomicLong writerBlockedNanos = new AtomicLong();
	private long startNs;

	public Sampler(int hz, ActionReader actions, DatasetWriter writer) {
		this(hz, actions, writer, null);
	}

	public Sampler(int hz, SampleSource actions, DatasetWriter writer, PacketReplaySession replay) {
		this.hz = Math.max(1, hz);
		this.actions = actions;
		this.writer = writer;
		this.replay = replay;
	}

	/** The session folder name (its timestamp), for the save toast. */
	public String sessionName() {
		return writer.sessionName();
	}

	/** Open the writer and start the clock + writer threads. */
	public void start() throws java.io.IOException {
		writer.open();
		running = true;
		startNs = System.nanoTime();

		writerThread = new Thread(this::writerLoop, "ocl-recorder-writer");
		clockThread = new Thread(this::clockLoop, "ocl-recorder-clock");
		writerThread.setDaemon(true);
		clockThread.setDaemon(true);
		writerThread.start();
		clockThread.start();
	}

	/** Save-progress observer for {@link #stop}; called from the thread running the stop. */
	public interface ProgressListener {
		/**
		 * @param remainingSamples samples still queued for the writer
		 * @param finalizingVideo  true once the queue is drained and the video encoder is finalizing
		 */
		void onProgress(int remainingSamples, boolean finalizingVideo);
	}

	/**
	 * Stop the clock, drain <b>every</b> queued sample, and finalize the dataset files. Blocks until
	 * the data is fully on disk — run it off the game threads (see {@code Recorder.stopAsync}) unless
	 * the game is shutting down. The writer is waited for without a cap: an earlier version gave up
	 * after 2 s and closed the files while the writer thread was still writing them. Idempotent
	 * (subsequent calls return null).
	 *
	 * @param listener optional save-progress observer (drain countdown, then video finalize)
	 * @return the session's outcome, or null if this sampler was already stopped
	 */
	public synchronized SaveResult stop(ProgressListener listener) {
		if (!running) {
			return null;
		}
		running = false;

		LockSupport.unpark(clockThread); // cut the current park short instead of waiting out a period
		joinQuietly(clockThread, 2000);  // no more samples produced after this returns
		// Poison the writer so it drains what's queued then exits. The queue can be momentarily full;
		// keep offering while the writer makes room. (If the writer died, its liveness check ends this.)
		while (!queue.offer(POISON) && writerThread.isAlive()) {
			joinQuietly(writerThread, 50);
		}
		while (writerThread.isAlive()) {
			if (listener != null) {
				listener.onProgress(queue.size(), false);
			}
			joinQuietly(writerThread, 100);
		}
		clockThread = null;
		writerThread = null;

		if (listener != null) {
			listener.onProgress(0, true);
		}
		String error = writer.close(written.get(), dropped.get(), repeated.get());
		double elapsedSec = Math.max(1e-9, (System.nanoTime() - startNs) / 1e9);
		OpenCrafterLink.LOGGER.info(
				"[open-crafter-link] recording stopped: {} samples ({} dropped, {} repeated) in {}s"
						+ " = {} samples/s; sampler blocked on the writer for {}s",
				written.get(), dropped.get(), repeated.get(), String.format("%.1f", elapsedSec),
				String.format("%.1f", written.get() / elapsedSec),
				String.format("%.1f", writerBlockedNanos.get() / 1e9));
		return new SaveResult(written.get(), dropped.get(), repeated.get(), error);
	}

	/** Fixed-deadline clock: latch one aligned sample per period, pack it, and enqueue (dropping if full). */
	private void clockLoop() {
		if (replay != null && replay.eager()) {
			eagerClockLoop();
			return;
		}
		if (replay != null) {
			while (running && !replay.captureReady()) LockSupport.parkNanos(1_000_000L);
			if (!running) return;
		}
		long periodNs = 1_000_000_000L / hz;
		long seq = 0;
		long base = replay == null ? System.nanoTime() : replay.captureWallStartNs();
		long next = base;
		VisionFrame lastRaw = null;
		PackedFrame lastPacked = null;

		while (running) {
			next += periodNs;
			parkUntil(next);
			if (!running) {
				break;
			}

			VisionFrame fresh = VisionTap.latest();
			// The tap conflates but never clears on read, so "fresh" is detected by identity: the same
			// object as last tick means no new frame was converted since (menu/paused/low fps).
			boolean repeat = fresh == null || fresh == lastRaw;
			PackedFrame frame;
			if (repeat) {
				frame = lastPacked; // share the previous packed instance — no re-pack, no extra memory
			} else {
				frame = PackedFrame.of(fresh);
				lastRaw = fresh;
				lastPacked = frame;
			}
			if (frame == null) {
				// No frame ever captured yet (e.g. still on the title screen) — nothing to record; the
				// clock keeps ticking but we don't emit a sample until the first frame exists.
				continue;
			}

			// Attach the discrete slot clicks observed since the last tick — an edge stream drained
			// non-conflatingly (see InventoryActionTap), unlike the polled movement/look state.
			long offsetMicros = Math.max(0L, (next - base) / 1_000L);
			long actionMicros = replay == null ? offsetMicros : replay.replayTimeForOffset(offsetMicros);
			ActionSet action = actions.sampleAt(actionMicros);
			if (actions.drainLiveInventoryActions()) {
				List<InventoryAction> inv = new ArrayList<>(2);
				InventoryActionTap.drainInto(inv);
				if (!inv.isEmpty()) action = action.withInventoryActions(inv);
			}

			long timestamp = replay == null ? System.nanoTime() : offsetMicros * 1_000L;
			Sample sample = new Sample(seq++, timestamp, frame, action,
					actions.currentInventory(), repeat);
			if (queue.offer(sample)) {
				if (repeat) {
					repeated.incrementAndGet();
				}
			} else {
				dropped.incrementAndGet(); // writer is behind; drop rather than stall the clock
			}
		}
	}

	/**
	 * Virtual-clock sampler. Replay renders each sample window once and stamps the window's sequence
	 * number onto the capture, so several windows can be in flight through the GPU read-back ring at
	 * once; frames arrive here in sample order on {@link VisionTap#takeEager} and are paired back up
	 * with their window by that number. Blocking on a full writer queue is intentional: eager mode is
	 * lossless and simply lets encoding backpressure regulate replay throughput — the block propagates
	 * upstream as windows that stop opening.
	 *
	 * <p>The action set and inventory were latched on the render thread when the window opened; reading
	 * them here would sample a world that has already moved on to later windows.
	 */
	private void eagerClockLoop() {
		long lastSeq = -1L;
		while (running) {
			VisionTap.SeqFrame fresh;
			try {
				fresh = VisionTap.takeEager(100L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			if (!running) return;
			if (fresh == null) continue; // nothing rendered yet this interval; re-check the stop flag

			if (fresh.seq() <= lastSeq) {
				// The whole path from the read-back ring to here is FIFO, so this cannot happen; if it ever
				// does, the dataset's video timeline would be silently scrambled — refuse the frame instead.
				OpenCrafterLink.LOGGER.error("[open-crafter-link] eager frame {} arrived after {}; dropping it",
						fresh.seq(), lastSeq);
				continue;
			}
			PacketReplaySession.SampleWindow window = replay.windowFor(fresh.seq());
			if (window == null) {
				// Its window was cancelled (the read-back ring went away under it); replay has already
				// failed the session, so drop the orphan frame rather than mis-pairing it.
				OpenCrafterLink.LOGGER.warn("[open-crafter-link] no sample window for eager frame {}", fresh.seq());
				continue;
			}

			PackedFrame frame = PackedFrame.of(fresh.frame());
			Sample sample = new Sample(window.seq(), window.timeMicros() * 1_000L, frame, window.action(),
					window.inventory(), false);
			long blockedFrom = System.nanoTime();
			boolean enqueued = false;
			while (running && !enqueued) {
				try { enqueued = queue.offer(sample, 100L, TimeUnit.MILLISECONDS); }
				catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
			}
			if (!enqueued) return;
			writerBlockedNanos.addAndGet(System.nanoTime() - blockedFrom);
			lastSeq = window.seq();
			replay.frameConsumed(window.seq());
		}
	}

	/** Writer thread: drain samples and persist them, off the clock. Exits on the poison pill. */
	private void writerLoop() {
		try {
			while (true) {
				Sample s = queue.poll(200, TimeUnit.MILLISECONDS);
				if (s == null) {
					if (!running && queue.isEmpty()) {
						return; // stopped and drained
					}
					continue;
				}
				if (s == POISON) {
					drainRemaining();
					return;
				}
				persist(s);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void drainRemaining() {
		Sample s;
		while ((s = queue.poll()) != null) {
			if (s == POISON) {
				continue;
			}
			persist(s);
		}
	}

	private void persist(Sample s) {
		try {
			writer.write(s);
			written.incrementAndGet();
		} catch (Throwable t) {
			OpenCrafterLink.LOGGER.error("[open-crafter-link] failed to write sample {}", s.seqno(), t);
		}
	}

	private void parkUntil(long deadlineNs) {
		long wait;
		while (running && (wait = deadlineNs - System.nanoTime()) > 0) {
			LockSupport.parkNanos(wait);
		}
	}

	private static void joinQuietly(Thread t, long millis) {
		if (t == null) {
			return;
		}
		try {
			t.join(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Sentinel enqueued by {@link #stop} to make the writer drain-and-exit. */
	private static final Sample POISON = new Sample(-1, -1, null, null, null, false);
}
