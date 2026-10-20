package mod.kelvinlby.link;

/**
 * The transport-agnostic surface the game threads and {@link mod.kelvinlby.OpenCrafterLinkClient}
 * use to talk to the controller, so the concrete transport ({@link TcpBridge} over plain TCP,
 * {@link UdsBridge} over plain {@code AF_UNIX}) can be swapped without touching the drivers.
 *
 * <p>Every implementation owns its own worker threads and JVM-side single-slot conflating queues
 * (newest payload wins, stale ones dropped); the game threads only ever call these O(1),
 * non-blocking hand-off methods, never a socket directly. Starting is transport-specific (each
 * impl takes its own endpoint record), so it is <b>not</b> on this interface — the client
 * constructs the concrete type, calls its typed {@code start(...)}, then holds it as a
 * {@code LinkBridge}.
 */
public interface LinkBridge {

	/** Stop the worker threads and tear down sockets. Idempotent. */
	void stop();

	/** Tick thread: hand off the latest telemetry. O(1), non-blocking, conflating (newest wins). */
	void publish(OutboundSnapshot snapshot);

	/**
	 * Tick thread: peek the latest movement instruction, or {@code null} if none has ever arrived.
	 * Non-destructive — the same instruction is returned on every tick until a newer one replaces it in
	 * the conflating slot. {@link mod.kelvinlby.link.InputDriver} is responsible for tracking staleness
	 * (holding the last instruction across a bounded number of ticks without a fresh one, then releasing).
	 */
	InboundInstruction takeLatest();

	/**
	 * Tick thread: dequeue the next pending inventory action, or {@code null} if the queue is empty.
	 * Unlike {@link #takeLatest}, actions are <b>not</b> conflated — every action a controller sends is
	 * delivered (FIFO), so a burst of actions issued while movement streams at ~30 Hz is never dropped.
	 * Callers drain in a loop each tick.
	 */
	InventoryAction takeAction();

	/**
	 * Whether a controller is currently connected to the telemetry stream. Game threads use this to
	 * skip building snapshots nobody will receive. Non-blocking.
	 */
	boolean hasTelemetryClient();

	/**
	 * Whether a controller is currently connected to the vision stream. {@link VisionCapture} skips
	 * the GPU read-back entirely while neither this nor a local {@link VisionFrameBus} consumer wants
	 * frames. Non-blocking.
	 */
	boolean hasVisionClient();

	/**
	 * Render thread: hand off raw, already-downsampled framebuffer bytes. O(1), non-blocking. The
	 * worker merges the hand depth, packs colour, and linearizes depth off the render thread. The bridge
	 * takes ownership of the three arrays and recycles them once converted (see {@link ReadbackPool}).
	 *
	 * <p>Ordinary capture is conflating (newest wins): a live controller wants the freshest frame and
	 * nothing else. A frame carrying an eager-replay sample sequence number
	 * ({@code seq >= 0}) instead travels a bounded FIFO lane, because a dataset must receive every
	 * captured window in order — see {@link mod.kelvinlby.link.VisionFrameBus}.
	 *
	 * @param rgba interleaved RGBA8, {@code w*h*4} bytes
	 * @param depth world DEPTH32 native-float bytes, {@code w*h*4} bytes
	 * @param handDepth first-person DEPTH32 native-float bytes captured after Minecraft's hand-pass
	 *                  depth clear, {@code w*h*4} bytes (see {@link VisionCapture#overlayHandDepth})
	 * @param seq eager-replay sample sequence number, or {@link EagerCaptureGate#NO_CAPTURE}
	 */
	void enqueueVisionRaw(int w, int h, float near, float far, byte[] rgba, byte[] depth, byte[] handDepth,
			long seq);
}
