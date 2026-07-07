package mod.kelvinlby.recorder;

import mod.kelvinlby.OpenCrafterLink;
import mod.kelvinlby.link.VisionFrame;

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
	private final ActionReader actions;
	private final DatasetWriter writer;

	private final BlockingQueue<Sample> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
	private volatile boolean running;
	private Thread clockThread;
	private Thread writerThread;

	private final AtomicLong written = new AtomicLong();
	private final AtomicLong dropped = new AtomicLong();
	private final AtomicLong repeated = new AtomicLong();

	public Sampler(int hz, ActionReader actions, DatasetWriter writer) {
		this.hz = Math.max(1, hz);
		this.actions = actions;
		this.writer = writer;
	}

	/** Open the writer and start the clock + writer threads. */
	public void start() throws java.io.IOException {
		writer.open();
		running = true;

		writerThread = new Thread(this::writerLoop, "ocl-recorder-writer");
		clockThread = new Thread(this::clockLoop, "ocl-recorder-clock");
		writerThread.setDaemon(true);
		clockThread.setDaemon(true);
		writerThread.start();
		clockThread.start();
	}

	/** Stop the clock, drain remaining queued samples, and finalize the dataset files. Idempotent. */
	public void stop() {
		if (!running) {
			return;
		}
		running = false;

		joinQuietly(clockThread);   // no more samples produced after this returns
		// Poison the writer so it drains what's queued then exits.
		queue.offer(POISON);
		joinQuietly(writerThread);
		clockThread = null;
		writerThread = null;

		writer.close(written.get(), dropped.get(), repeated.get());
		OpenCrafterLink.LOGGER.info("[open-crafter-link] recording stopped: {} samples ({} dropped, {} repeated)",
				written.get(), dropped.get(), repeated.get());
	}

	/** Fixed-deadline clock: latch one aligned sample per period, pack it, and enqueue (dropping if full). */
	private void clockLoop() {
		long periodNs = 1_000_000_000L / hz;
		long seq = 0;
		long next = System.nanoTime();
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

			Sample sample = new Sample(seq++, System.nanoTime(), frame, actions.current(), repeat);
			if (queue.offer(sample)) {
				if (repeat) {
					repeated.incrementAndGet();
				}
			} else {
				dropped.incrementAndGet(); // writer is behind; drop rather than stall the clock
			}
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

	private static void joinQuietly(Thread t) {
		if (t == null) {
			return;
		}
		try {
			t.join(2000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Sentinel enqueued by {@link #stop()} to make the writer drain-and-exit. */
	private static final Sample POISON = new Sample(-1, -1, null, null, false);
}
