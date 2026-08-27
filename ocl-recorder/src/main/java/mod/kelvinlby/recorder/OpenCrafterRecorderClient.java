package mod.kelvinlby.recorder;

import mod.kelvinlby.OpenCrafterLink;
import mod.kelvinlby.OpenCrafterLinkClient;
import mod.kelvinlby.link.CaptureGateRegistry;
import mod.kelvinlby.recorder.config.RecorderConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;

/** Optional recorder entrypoint. No recorder callbacks or mixins exist when this mod is absent. */
public final class OpenCrafterRecorderClient implements ClientModInitializer {
	private static final Recorder RECORDER = new Recorder();

	public static Recorder recorder() {
		return RECORDER;
	}

	public static void syncConfig() {
		RecorderConfig cfg = RecorderConfig.get().normalize();
		RECORDER.syncTo(cfg.recordDataset, cfg.autoReplay, cfg.eagerPacketEncoding,
				cfg.quitWhenReplayFinished, cfg.recordSampleHz, cfg.toVideoSettings());
	}

	@Override public void onInitializeClient() {
		CaptureGateRegistry.install(RECORDER.captureGate());
		syncConfig();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (RECORDER.shouldReadLiveActions()) RECORDER.actionReader().onClientTick(client);
			RECORDER.onClientTick(client);
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> RECORDER.onWorldJoin());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> RECORDER.onWorldLeave());
		WorldRenderEvents.START_MAIN.register(ctx -> RECORDER.onWorldRenderStart(MinecraftClient.getInstance()));

		OpenCrafterLinkClient.registerBeforeShutdown(() -> {
			RECORDER.shutdown();
			CaptureGateRegistry.uninstall(RECORDER.captureGate());
		});
		OpenCrafterLink.LOGGER.info("[open-crafter-recorder] client initialized");
	}
}
