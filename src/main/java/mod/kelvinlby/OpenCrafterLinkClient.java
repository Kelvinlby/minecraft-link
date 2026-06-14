package mod.kelvinlby;

import mod.kelvinlby.link.TickDriver;
import mod.kelvinlby.link.ZmqBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Client entry point. Starts the ZMQ bridge, registers the per-tick driver on {@code END_CLIENT_TICK},
 * and tears the bridge down on shutdown.
 */
public class OpenCrafterLinkClient implements ClientModInitializer {
	private final ZmqBridge bridge = new ZmqBridge();

	@Override
	public void onInitializeClient() {
		bridge.start();

		TickDriver driver = new TickDriver(bridge);
		ClientTickEvents.END_CLIENT_TICK.register(driver::onEndClientTick);

		// Tear down on normal client stop, plus a JVM shutdown hook as a belt-and-suspenders for
		// crashes that skip the lifecycle event.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> bridge.stop());
		Runtime.getRuntime().addShutdownHook(new Thread(bridge::stop, "ocl-shutdown"));

		OpenCrafterLink.LOGGER.info("[open-crafter-link] client initialized");
	}
}
