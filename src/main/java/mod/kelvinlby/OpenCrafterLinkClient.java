package mod.kelvinlby;

import mod.kelvinlby.config.OclConfig;
import mod.kelvinlby.link.LinkConfig;
import mod.kelvinlby.link.TickDriver;
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
	private static ZmqBridge bridge;

	/**
	 * Rebind the running bridge to the endpoints of the current config. Called from the settings screen
	 * after a save so a transport change takes effect without restarting the client. No-op before init.
	 */
	public static void reloadLink() {
		if (bridge != null) {
			bridge.restart(OclConfig.get().toEndpoints());
		}
	}

	@Override
	public void onInitializeClient() {
		OclConfig cfg = OclConfig.get();
		bridge = new ZmqBridge();
		bridge.start(cfg.toEndpoints());

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

		OpenCrafterLink.LOGGER.info("[open-crafter-link] client initialized (vision {})",
				vision != null ? "enabled" : "disabled");
	}
}
