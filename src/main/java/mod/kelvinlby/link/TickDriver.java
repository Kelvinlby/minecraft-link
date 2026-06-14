package mod.kelvinlby.link;

import mod.kelvinlby.mixin.client.MinecraftClientInvoker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.MathHelper;

/**
 * Per-tick logic, run on {@code END_CLIENT_TICK}. Each tick it applies the latest instruction to the
 * player (or a neutral one if the controller didn't send anything fresh) and publishes a fresh
 * telemetry snapshot. All work here is lightweight; ZMQ I/O and encoding happen on the bridge's
 * worker threads.
 */
public final class TickDriver {
	private final ZmqBridge bridge;

	public TickDriver(ZmqBridge bridge) {
		this.bridge = bridge;
	}

	public void onEndClientTick(MinecraftClient mc) {
		ClientPlayerEntity player = mc.player;
		if (player == null || mc.world == null) {
			return; // not in a world; nothing to control or report
		}

		// ---- INBOUND: apply the latest instruction (one-tick lifecycle) ----
		InboundInstruction in = bridge.takeLatest();
		if (in == null) {
			in = InboundInstruction.NEUTRAL; // no fresh instruction -> release, never repeat stale
		}
		applyMovement(player, in);
		applyRotation(player, in);
		applySlot(player, in);
		applyActions(mc, in);

		// ---- OUTBOUND: gather and hand off a fresh snapshot ----
		bridge.publish(buildSnapshot(mc, player));
	}

	/**
	 * Drive movement the vanilla way: assign the player's own {@link PlayerInput}. The record's
	 * argument order is (forward, backward, left, right, jump, sneak, sprint) — note sneak precedes
	 * sprint.
	 */
	private void applyMovement(ClientPlayerEntity player, InboundInstruction in) {
		player.input.playerInput = new PlayerInput(
				in.front(), in.back(), in.left(), in.right(),
				in.jump(), in.sneak(), in.sprint());
	}

	/** Set absolute rotation only when the controller sent a fresh value; clamp pitch to [-90, 90]. */
	private void applyRotation(ClientPlayerEntity player, InboundInstruction in) {
		if (!in.hasRotation()) {
			return;
		}
		float yaw = in.yaw();
		float pitch = MathHelper.clamp(in.pitch(), -90.0f, 90.0f);
		player.setYaw(yaw);
		player.setPitch(pitch);
		player.setHeadYaw(yaw);
		player.setBodyYaw(yaw);
	}

	/**
	 * Select a hotbar slot. Setting the field is enough: the interaction manager's tick detects the
	 * change and sends the vanilla {@code UpdateSelectedSlotC2SPacket} on its own.
	 */
	private void applySlot(ClientPlayerEntity player, InboundInstruction in) {
		if (!in.hasSlot()) {
			return;
		}
		player.getInventory().setSelectedSlot(MathHelper.clamp(in.selectedSlot(), 0, 8));
	}

	/** Trigger clicks through the game's own attack/use code paths (one discrete click per tick). */
	private void applyActions(MinecraftClient mc, InboundInstruction in) {
		MinecraftClientInvoker invoker = (MinecraftClientInvoker) mc;
		if (in.attack()) {
			invoker.openCrafterLink$doAttack();
		}
		if (in.interact()) {
			invoker.openCrafterLink$doItemUse();
		}
	}

	private OutboundSnapshot buildSnapshot(MinecraftClient mc, ClientPlayerEntity player) {
		int visW = 0;
		int visH = 0;
		if (LinkConfig.VISION_ENABLED) {
			int[] dim = VisionCapture.dimensions(mc);
			visW = dim[0];
			visH = dim[1];
		}
		// Pixels are left null here and synthesized (dummy) on the sender thread during encoding,
		// keeping the tick thread free of any heavy vision work.
		return new OutboundSnapshot(
				player.getYaw(),
				player.getPitch(),
				player.getInventory().getSelectedSlot(),
				visW,
				visH,
				null);
	}
}
