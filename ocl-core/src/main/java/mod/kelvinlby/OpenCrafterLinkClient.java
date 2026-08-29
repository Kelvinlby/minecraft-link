package mod.kelvinlby;

import mod.kelvinlby.config.OclConfig;
import mod.kelvinlby.link.CaptureGateRegistry;
import mod.kelvinlby.link.InputDriver;
import mod.kelvinlby.link.LinkBridge;
import mod.kelvinlby.link.LinkConfig;
import mod.kelvinlby.link.TcpBridge;
import mod.kelvinlby.link.TickDriver;
import mod.kelvinlby.link.UdsBridge;
import mod.kelvinlby.link.VisionCapture;
import mod.kelvinlby.vcam.VirtualCameraService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.util.Identifier;

import java.util.function.IntSupplier;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Client entry point. Starts the link bridge, registers the per-tick driver on {@code END_CLIENT_TICK},
 * registers the RGBD vision capturer on {@code WorldRenderEvents.END_MAIN} (when enabled), and tears
 * everything down on shutdown.
 */
public class OpenCrafterLinkClient implements ClientModInitializer {
	private static LinkBridge bridge;
	/** Active capture instance reached by the GameRenderer tail mixin. Render thread only. */
	private static VisionCapture visionCapture;
	private static final CopyOnWriteArrayList<Runnable> BEFORE_SHUTDOWN = new CopyOnWriteArrayList<>();

	/** Called by the render hook after first-person hands/items and before Minecraft's GUI depth clear. */
	public static void onFirstPersonRenderEnd() {
		VisionCapture capture = visionCapture;
		if (capture != null) {
			capture.onFirstPersonRenderEnd();
		}
	}

	/** Register optional dependent-mod cleanup that must run before vision and bridge teardown. */
	public static void registerBeforeShutdown(Runnable cleanup) {
		BEFORE_SHUTDOWN.add(cleanup);
	}

	private static void runBeforeShutdown() {
		for (Runnable cleanup : BEFORE_SHUTDOWN) {
			try { cleanup.run(); }
			catch (Throwable t) { OpenCrafterLink.LOGGER.error("[open-crafter-link] extension shutdown failed", t); }
		}
	}

	/**
	 * The virtual cameras. One per client; unlike the recorder this is not world-scoped — a preview feed
	 * that vanished on the pause menu would be useless to a streamer — so it runs whenever enabled.
	 */
	private static final VirtualCameraService virtualCamera = new VirtualCameraService();

	/** The virtual cameras, so the settings screen can {@code syncTo} their toggles on save. */
	public static VirtualCameraService virtualCamera() {
		return virtualCamera;
	}

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

	/**
	 * The current live bridge. The long-lived drivers and vision capturer hold a reference to this
	 * accessor (not the bridge itself) so a {@link #reloadLink()} swap — triggered by any settings save
	 * — is transparent to them; capturing the bridge directly would leave them writing to the stopped
	 * instance, silently killing telemetry, input, and vision until a full client restart.
	 */
	public static synchronized LinkBridge bridge() {
		return bridge;
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
				TcpBridge tcp = new TcpBridge();
				tcp.start(cfg.toEndpoints());
				return tcp;
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
		InputDriver inputDriver = new InputDriver(OpenCrafterLinkClient::bridge);
		ClientTickEvents.START_CLIENT_TICK.register(inputDriver::onStartClientTick);

		TickDriver driver = new TickDriver(OpenCrafterLinkClient::bridge);
		ClientTickEvents.END_CLIENT_TICK.register(driver::onEndClientTick);

		// Virtual cameras: publish the RGB/depth feeds as v4l2loopback webcams so any capture software
		// can read them. Synced once here so a toggle left enabled across restarts re-arms; no
		// JOIN/DISCONNECT hooks, since these are deliberately not world-scoped.
		virtualCamera.syncTo(cfg.virtualCameraRgb, cfg.virtualCameraDepth,
				cfg.cameraWidth, cfg.cameraHeight, LinkConfig.VISION_MAX_HZ, cfg.ffmpegPath);

		// Vision: copy world depth at END_MAIN, hand depth immediately after first-person rendering, and
		// colour at the first HUD element. Minecraft clears depth both before hands and before the GUI, so
		// VisionCapture merges the two depth passes captured on either side of the first clear. Frame
		// resolution comes live from the in-game
		// settings screen (OclConfig), so adjusting the camera sliders takes effect without restarting; the
		// ocl.visionWidth/Height launch properties, when set, pin it to a fixed override.
		IntSupplier visionW = (LinkConfig.VISION_TARGET_W != null)
				? LinkConfig.VISION_TARGET_W::intValue : () -> cfg.cameraWidth;
		IntSupplier visionH = (LinkConfig.VISION_TARGET_H != null)
				? LinkConfig.VISION_TARGET_H::intValue : () -> cfg.cameraHeight;
		final VisionCapture vision = new VisionCapture(OpenCrafterLinkClient::bridge, visionW, visionH,
				LinkConfig.VISION_MAX_HZ, LinkConfig.VISION_BOX_FILTER, CaptureGateRegistry.forwardingGate());
		visionCapture = vision;
		WorldRenderEvents.END_MAIN.register(ctx -> vision.onWorldRenderEnd());
		HudElementRegistry.addFirst(Identifier.of(OpenCrafterLink.MOD_ID, "vision_capture"),
				(context, tickCounter) -> vision.onHudRenderFirst());

		// Tear down on normal client stop. CLIENT_STOPPING runs on the render/main thread, so the GPU
		// buffers can be freed here directly — before the bridge threads are joined.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			runBeforeShutdown();
			virtualCamera.shutdown(); // release the loopback devices before the process exits
			vision.dispose();
			visionCapture = null;
			bridge().stop();
		});
		// JVM shutdown hook as a belt-and-suspenders for crashes that skip the lifecycle event. This
		// runs on an arbitrary thread with a possibly-dead GL context, so it touches the bridge only —
		// never the GPU (VisionCapture.dispose guards against off-render-thread frees anyway).
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			runBeforeShutdown();
			virtualCamera.shutdown(); // likewise: kills the ffmpeg children that hold /dev/videoN
			bridge().stop();
		}, "ocl-shutdown"));

		OpenCrafterLink.LOGGER.info("[open-crafter-link] client initialized");
	}
}
