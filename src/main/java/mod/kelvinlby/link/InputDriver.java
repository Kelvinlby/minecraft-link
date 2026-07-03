package mod.kelvinlby.link;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

/**
 * Drives the player by simulating keypresses on the real {@link KeyBinding} objects, run on
 * {@code START_CLIENT_TICK} — at the HEAD of {@code MinecraftClient.tick()}, before
 * {@code handleInputEvents()} and {@code world.tickEntities()}. Stamping here means the state is in
 * place for both attack/use handling and movement physics within the <em>same</em> tick.
 *
 * <p>This is the most vanilla way to control the player: instead of poking {@code playerInput} or
 * calling the private attack/use methods directly, we set the held state of the bindings the game
 * already polls every tick and let all of vanilla's downstream logic decide hold-vs-click. In
 * particular, a held attack key drives continuous block-breaking via
 * {@code MinecraftClient.handleBlockBreaking(... attackKey.isPressed() ...)} — exactly like a human
 * holding the mouse button — rather than one discrete swing per tick.
 *
 * <h2>Why the raw {@code pressed} field</h2>
 * Sneak, sprint, attack and use are {@code StickyKeyBinding}s whose {@code setPressed(true)}
 * <em>toggles</em> when the user's toggle option is on, which would flip a held key on/off each tick.
 * Writing the underlying {@code pressed} flag directly (made accessible via the access widener)
 * bypasses that toggle path so every driven key behaves as a true hold regardless of toggle settings.
 *
 * <h2>Takeover policy</h2>
 * The mod drives keys only while instructions flow. When the controller goes silent the keys the mod
 * was holding are released exactly once and ownership is dropped, after which the keyboard is left
 * untouched so a human can play manually. Rotation and slot are absolute and applied here too;
 * telemetry is published separately at end-of-tick by {@link TickDriver}.
 */
public final class InputDriver {
	private final LinkBridge bridge;

	/** Whether the mod is currently holding any driven key, so it can release them once on silence. */
	private boolean owningKeys;

	public InputDriver(LinkBridge bridge) {
		this.bridge = bridge;
	}

	public void onStartClientTick(MinecraftClient mc) {
		ClientPlayerEntity player = mc.player;
		if (player == null || mc.world == null) {
			owningKeys = false; // left the world; nothing held to release
			return;
		}

		InboundInstruction in = bridge.takeLatest();
		if (in == null) {
			// No fresh instruction: release once if we were driving, then go hands-off so a human can play.
			if (owningKeys) {
				stampMovement(mc.options, InboundInstruction.NEUTRAL);
				owningKeys = false;
			}
			return;
		}

		stampMovement(mc.options, in);
		owningKeys = true;
		applyRotation(player, in);
		applySlot(player, in);
	}

	/** Set the held state of every driven binding to the instruction's value (released for NEUTRAL). */
	private void stampMovement(GameOptions opts, InboundInstruction in) {
		opts.forwardKey.pressed = in.front();
		opts.backKey.pressed = in.back();
		opts.leftKey.pressed = in.left();
		opts.rightKey.pressed = in.right();
		opts.jumpKey.pressed = in.jump();
		opts.sneakKey.pressed = in.sneak();
		opts.sprintKey.pressed = in.sprint();
		// Held attack/use: a sustained press drives continuous breaking / item use downstream.
		opts.attackKey.pressed = in.attack();
		opts.useKey.pressed = in.interact();
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
}
