package mod.kelvinlby.link;

/**
 * The recorder's side of the eager-replay capture handshake, as {@link VisionCapture} needs it.
 *
 * <p>During eager packet replay the render loop is driven by a virtual clock: replay opens one
 * <em>sample window</em> (a fixed dataset timestamp with the world advanced to it), renders it, and
 * must capture exactly one RGBD frame from that render. This interface is how the capture code asks
 * for permission and reports what it did with it:
 *
 * <ol>
 *   <li>{@link #claim()} — "I have a free read-back slot and I am about to capture the frame for the
 *       currently open window." Returns that window's sample sequence number, or {@link #NO_CAPTURE}
 *       if no window is open (or one has already been claimed this frame).</li>
 *   <li>{@link #commit(long)} — both GPU copies for that sequence number have been <b>issued</b>. In
 *       GL's in-order command stream the copies are now guaranteed to observe that window's contents,
 *       so replay is free to advance the world to the next window on the very next frame; the pixels
 *       are still in flight. This is what lets rendering and read-back pipeline instead of the render
 *       thread re-rendering the same scene while waiting for the CPU to catch up.</li>
 *   <li>{@link #release()} — the claim was taken but the capture could not be issued (the HUD seam
 *       never ran, the framebuffer changed under us). The window stays open and is retried.</li>
 *   <li>{@link #cancel(long)} — a capture was committed but its pixels will never arrive (the
 *       read-back ring was torn down under it). Replay drops that sample rather than waiting forever.</li>
 * </ol>
 *
 * <p>Every method is called on the render thread.
 */
public interface EagerCaptureGate {

	/** Sentinel sequence number meaning "this capture does not belong to an eager sample window". */
	long NO_CAPTURE = -1L;

	/** Whether eager replay currently owns the timeline; false for ordinary rate-throttled capture. */
	boolean active();

	/** Claim the open window's single capture, or {@link #NO_CAPTURE} if there is nothing to capture. */
	long claim();

	/** Both read-back copies for {@code seq} are issued; the window may be retired for rendering. */
	void commit(long seq);

	/** Give a claim back without capturing; the window stays open for a later frame. */
	void release();

	/** The committed capture for {@code seq} was abandoned before its pixels reached the CPU. */
	void cancel(long seq);
}
