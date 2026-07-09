package mod.kelvinlby.link;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

import java.util.function.Supplier;

/**
 * Outbound half of the per-tick loop, run on {@code END_CLIENT_TICK}. It publishes a fresh telemetry
 * snapshot at the end of each tick so the snapshot reflects post-physics state. All inbound control
 * (keypress simulation, rotation, slot) is handled at {@code START_CLIENT_TICK} by {@link InputDriver};
 * keeping the publish at end-of-tick means the values the controller reads back are this tick's result.
 *
 * <p>All work here is lightweight; link I/O and encoding happen on the bridge's worker threads.
 */
public final class TickDriver {
	/** Resolved live each tick so a bridge swap (settings save -&gt; reloadLink) doesn't orphan telemetry. */
	private final Supplier<LinkBridge> bridge;

	public TickDriver(Supplier<LinkBridge> bridge) {
		this.bridge = bridge;
	}

	public void onEndClientTick(MinecraftClient mc) {
		ClientPlayerEntity player = mc.player;
		if (player == null || mc.world == null) {
			return; // not in a world; nothing to report
		}
		bridge.get().publish(buildSnapshot(player));
	}

	private OutboundSnapshot buildSnapshot(ClientPlayerEntity player) {
		// Vision is captured on the render thread and published on its own OCLV stream (see
		// VisionCapture), so it never rides this per-tick snapshot.
		return new OutboundSnapshot(
				player.getYaw(),
				player.getPitch(),
				player.getInventory().getSelectedSlot(),
				player.getHealth(),
				player.getHungerManager().getFoodLevel(),
				player.experienceLevel);
	}
}
