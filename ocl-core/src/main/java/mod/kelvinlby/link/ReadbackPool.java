package mod.kelvinlby.link;

import java.util.concurrent.ArrayBlockingQueue;

/**
 * Recycles the per-plane byte arrays a read-back is copied into on the render thread, so steady-state
 * capture allocates nothing there. Each plane is ~1.3 MB at the default 768&times;432 — more than half
 * a G1 region on a typical heap, i.e. a humongous allocation — and three are needed per capture, so
 * allocating them fresh at the capture rate is what used to drive frequent GC cycles whose pauses
 * stop the game threads too.
 *
 * <p>The render thread {@link #take}s, the bridge's vision worker {@link #give}s back once the planes
 * have been packed into a {@link VisionFrame}. Arrays of the wrong length (the camera resolution
 * changed) are dropped on take. Bounded: a burst beyond the capacity (deep eager replay queues)
 * simply allocates and the surplus is left to the GC.
 */
final class ReadbackPool {
	private ReadbackPool() {}

	/** Enough for the render-side ring plus a few frames queued to the worker, three planes each. */
	private static final ArrayBlockingQueue<byte[]> FREE = new ArrayBlockingQueue<>(24);

	/** An array of exactly {@code length} bytes, recycled when one is available. Contents are stale. */
	static byte[] take(int length) {
		byte[] buf;
		while ((buf = FREE.poll()) != null) {
			if (buf.length == length) {
				return buf;
			}
		}
		return new byte[length];
	}

	/** Return an array for reuse. The caller must not touch it afterwards. */
	static void give(byte[] buf) {
		if (buf != null) {
			FREE.offer(buf);
		}
	}
}
