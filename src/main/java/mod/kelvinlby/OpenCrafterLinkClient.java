package mod.kelvinlby;

import mod.kelvinlby.config.OclConfig;
import mod.kelvinlby.link.InputDriver;
import mod.kelvinlby.link.LinkBridge;
import mod.kelvinlby.link.LinkConfig;
import mod.kelvinlby.link.TickDriver;
import mod.kelvinlby.link.UdsBridge;
import mod.kelvinlby.link.VisionCapture;
import mod.kelvinlby.link.ZmqBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;

import java.util.function.IntSupplier;

/**
 * Client entry point. Starts the ZMQ bridge, registers the per-tick driver on {@code END_CLIENT_TICK},
 * registers the RGBD vision capturer on {@code WorldRenderEvents.END_MAIN} (when enabled), and tears
 * everything down on shutdown.
 */
public class OpenCrafterLinkClient implements ClientModInitializer {
	private static LinkBridge bridge;

	/**
	 * (Re)build the link for the current config and start it, tearing down any previous bridge first.
	 * Called at init and from the settings screen after a save, so both an endpoint change <b>and</b> a
	 * transport switch (TCP&harr;UDS, which needs a different bridge implementation) take effect without
	 * restarting the client. No-op'ing when there is no prior bridge keeps it safe at init.
	 */
	public static synchronized void reloadLink() {
		if (bridge != null) {
			bridge.stop();
		}
		bridge = buildAndStart(OclConfig.get());
	}

	/** Construct the bridge matching the config's transport and start it on the resolved endpoints. */
	private static LinkBridge buildAndStart(OclConfig cfg) {
		switch (cfg.transport) {
			case UDS -> {
				UdsBridge uds = new UdsBridge();
				uds.start(cfg.toUdsEndpoints());
				return uds;
			}
			default -> {
				ZmqBridge zmq = new ZmqBridge();
				zmq.start(cfg.toEndpoints());
				return zmq;
			}
		}
	}

	@Override
	public void onInitializeClient() {
		OclConfig cfg = OclConfig.get();
		bridge = buildAndStart(cfg);

		// Inbound control is stamped onto the real KeyBindings at the HEAD of the client tick (before
		// input events and entity ticking) so it takes effect this same tick; telemetry is published at
		// END so it reflects post-physics state.
		InputDriver inputDriver = new InputDriver(bridge);
		ClientTickEvents.START_CLIENT_TICK.register(inputDriver::onStartClientTick);

		TickDriver driver = new TickDriver(bridge);
		ClientTickEvents.END_CLIENT_TICK.register(driver::onEndClientTick);

		// Vision: capture the 3D world (incl. first-person hand) at END_MAIN, before the HUD draws.
		// Frame resolution comes live from the in-game settings screen (OclConfig), so adjusting the
		// camera sliders takes effect without restarting; the ocl.visionWidth/Height launch properties,
		// when set, pin it to a fixed override.
		IntSupplier visionW = (LinkConfig.VISION_TARGET_W != null)
				? LinkConfig.VISION_TARGET_W::intValue : () -> cfg.cameraWidth;
		IntSupplier visionH = (LinkConfig.VISION_TARGET_H != null)
				? LinkConfig.VISION_TARGET_H::intValue : () -> cfg.cameraHeight;
		final VisionCapture vision = new VisionCapture(bridge, visionW, visionH,
				LinkConfig.VISION_MAX_HZ, LinkConfig.VISION_BOX_FILTER);
		WorldRenderEvents.END_MAIN.register(ctx -> vision.onWorldRenderEnd());

		// Tear down on normal client stop. CLIENT_STOPPING runs on the render/main thread, so the GPU
		// buffers can be freed here directly — before the bridge threads are joined.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			vision.dispose();
			bridge.stop();
		});
		// JVM shutdown hook as a belt-and-suspenders for crashes that skip the lifecycle event. This
		// runs on an arbitrary thread with a possibly-dead GL context, so it touches the bridge only —
		// never the GPU (VisionCapture.dispose guards against off-render-thread frees anyway).
		Runtime.getRuntime().addShutdownHook(new Thread(bridge::stop, "ocl-shutdown"));

		OpenCrafterLink.LOGGER.info("[open-crafter-link] client initialized");
	}
}
