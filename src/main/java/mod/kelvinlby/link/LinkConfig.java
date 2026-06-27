package mod.kelvinlby.link;

/**
 * Tunable endpoints and constants for the link. Vision tunables and (optionally) the endpoints are
 * overridable at launch via system properties (e.g. {@code -Docl.pubEndpoint=tcp://*:6000}) so they
 * can be changed without recompiling.
 *
 * <p>The three ZMQ endpoints are resolved into an immutable {@link Endpoints} at bridge-start time
 * (see {@link mod.kelvinlby.config.OclConfig#toEndpoints()}); the {@code ocl.*Endpoint} system
 * properties, when set, pin them to a fixed override that wins over the in-game settings screen.
 *
 * <p>The mod <b>binds</b> its outbound PUB socket (it is the stable, long-lived endpoint that the
 * controller connects to) and <b>connects</b> its inbound SUB socket to the controller's PUB.
 */
public final class LinkConfig {
	private LinkConfig() {}

	/** Default canonical TCP ports, mirrored in {@code tools/README.md}'s wire table. */
	public static final int TELEMETRY_PORT = 5557;
	public static final int INSTRUCTION_PORT = 5558;
	public static final int VISION_PORT = 5559;

	/**
	 * The three ZMQ endpoint strings the bridge binds/connects. Resolved per bridge start, so a settings
	 * change can rebuild it and restart the bridge on new endpoints.
	 *
	 * @param pub      outbound telemetry — the mod BINDs here; the controller SUB-connects
	 * @param sub      inbound instructions — the mod SUB-connects here; the controller BINDs its PUB
	 * @param visPub   outbound RGBD vision — the mod BINDs a second PUB here; the controller SUB-connects
	 */
	public record Endpoints(String pub, String sub, String visPub) {}

	/**
	 * Optional launch-time overrides of the three endpoints. When a property is set it wins over the
	 * in-game settings screen; when unset ({@code null}) the runtime derives the endpoint from the
	 * configured TCP URL. See {@link mod.kelvinlby.config.OclConfig#toEndpoints()}.
	 */
	public static final String PUB_ENDPOINT_OVERRIDE = System.getProperty("ocl.pubEndpoint");
	public static final String SUB_ENDPOINT_OVERRIDE = System.getProperty("ocl.subEndpoint");
	public static final String VIS_PUB_ENDPOINT_OVERRIDE = System.getProperty("ocl.visPubEndpoint");

	/** Receive timeout for the SUB poll loop (ms). Keeps {@code stop()} responsive. */
	public static final int RECV_POLL_MS = 5;

	/**
	 * Optional launch-time override of the published RGBD frame's target (downsampled) resolution. When
	 * either property is set it wins over the in-game settings screen ({@code OclConfig.cameraWidth/Height});
	 * when unset ({@code null}) the runtime falls back to the configured camera resolution. The full
	 * framebuffer is captured on the render thread and nearest-neighbour downsampled to this size before
	 * going on the wire — keeping the payload small enough to sustain &gt;20&nbsp;Hz.
	 */
	public static final Integer VISION_TARGET_W = Integer.getInteger("ocl.visionWidth");
	public static final Integer VISION_TARGET_H = Integer.getInteger("ocl.visionHeight");

	/** Cap on capture rate (Hz). The render thread typically runs faster; captures above this are skipped. */
	public static final int VISION_MAX_HZ = Integer.getInteger("ocl.visionMaxHz", 40);

	/**
	 * When true, RGB is box-averaged over each downsample block instead of nearest-neighbour sampled.
	 * Depth is always nearest (averaging non-linear depth across edges is meaningless).
	 */
	public static final boolean VISION_BOX_FILTER = Boolean.getBoolean("ocl.visionBoxFilter");
}
