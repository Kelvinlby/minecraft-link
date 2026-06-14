package mod.kelvinlby.link;

/**
 * Tunable endpoints and constants for the link. All values are overridable at launch via system
 * properties (e.g. {@code -Docl.pubEndpoint=tcp://*:6000}) so endpoints can be changed without
 * recompiling.
 *
 * <p>The mod <b>binds</b> its outbound PUB socket (it is the stable, long-lived endpoint that the
 * controller connects to) and <b>connects</b> its inbound SUB socket to the controller's PUB.
 */
public final class LinkConfig {
	private LinkConfig() {}

	/** Outbound telemetry: Minecraft -&gt; controller. We BIND here; the controller SUB-connects. */
	public static final String PUB_ENDPOINT = System.getProperty("ocl.pubEndpoint", "tcp://*:5557");

	/** Inbound instructions: controller -&gt; Minecraft. We SUB-connect here; the controller BINDs its PUB. */
	public static final String SUB_ENDPOINT = System.getProperty("ocl.subEndpoint", "tcp://localhost:5558");

	/** Receive timeout for the SUB poll loop (ms). Keeps {@code stop()} responsive. */
	public static final int RECV_POLL_MS = 5;

	/** When false, vision is published as an empty vector (vis_w = vis_h = 0). */
	public static final boolean VISION_ENABLED = Boolean.getBoolean("ocl.vision");

	/** Dummy vision resolution used while {@link #VISION_ENABLED} is true but real readback is not wired in. */
	public static final int VISION_DUMMY_W = Integer.getInteger("ocl.visionWidth", 16);
	public static final int VISION_DUMMY_H = Integer.getInteger("ocl.visionHeight", 16);
}
