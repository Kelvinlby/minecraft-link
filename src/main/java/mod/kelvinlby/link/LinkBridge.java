package mod.kelvinlby.link;

/**
 * The transport-agnostic surface the game threads and {@link mod.kelvinlby.OpenCrafterLinkClient}
 * use to talk to the controller, so the concrete transport ({@link ZmqBridge} over ZMQ/TCP,
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

	/** Tick thread: consume-and-clear the latest instruction, or {@code null} if none arrived. */
	InboundInstruction takeLatest();

	/**
	 * Render thread: hand off raw, already-downsampled framebuffer bytes. O(1), non-blocking,
	 * conflating (newest wins). The worker does the float conversion + depth linearization off the
	 * render thread.
	 *
	 * @param rgba interleaved RGBA8, {@code w*h*4} bytes
	 * @param depth DEPTH32 native-float bytes, {@code w*h*4} bytes
	 */
	void enqueueVisionRaw(int w, int h, float near, float far, byte[] rgba, byte[] depth);
}
