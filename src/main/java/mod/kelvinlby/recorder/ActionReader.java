package mod.kelvinlby.recorder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.network.ClientPlayerEntity;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads the <b>human player's real inputs</b> each client tick into an {@link ActionSet}, so the
 * dataset recorder can log demonstrations. This is the mirror image of {@code InputDriver}: where the
 * driver <em>writes</em> the {@link net.minecraft.client.option.KeyBinding#pressed} field to control
 * the player from engine commands, this <em>reads</em> those same fields to capture what the human is
 * doing.
 *
 * <p>Reading the raw {@code pressed} field (access-widened, same as the driver relies on) captures the
 * true held state of sneak/sprint/attack/use regardless of the user's sticky-toggle option — exactly
 * the state vanilla physics acts on this tick. Rotation and hotbar slot come from the same accessors
 * {@code TickDriver.buildSnapshot} uses.
 *
 * <p>Must run on the client thread ({@code KeyBinding.pressed} and player state are only safe there).
 * The latest {@link ActionSet} is published into a single-slot holder that the recorder's
 * {@code Sampler} thread reads, so the sampler never touches Minecraft state directly.
 */
public final class ActionReader {

	/** Latest observed action state. Client tick thread writes; sampler thread reads. */
	private final AtomicReference<ActionSet> latest = new AtomicReference<>(ActionSet.NEUTRAL);

	/** Client tick: snapshot the human's current input into the holder. No-op when out of world. */
	public void onClientTick(MinecraftClient mc) {
		ClientPlayerEntity player = mc.player;
		if (player == null || mc.world == null) {
			return; // keep the last value; the sampler will repeat it (marked as a repeat by no fresh frame)
		}
		latest.set(read(mc.options, player));
	}

	/** The most recently observed action state (never null; starts at {@link ActionSet#NEUTRAL}). */
	public ActionSet current() {
		return latest.get();
	}

	private static ActionSet read(GameOptions opts, ClientPlayerEntity player) {
		return new ActionSet(
				opts.forwardKey.pressed,
				opts.backKey.pressed,
				opts.leftKey.pressed,
				opts.rightKey.pressed,
				opts.jumpKey.pressed,
				opts.sprintKey.pressed,
				opts.sneakKey.pressed,
				opts.attackKey.pressed,
				opts.useKey.pressed,
				player.getInventory().getSelectedSlot(),
				player.getYaw(),
				player.getPitch());
	}
}
