package mod.kelvinlby.recorder;

import mod.kelvinlby.link.InventoryAction;

import java.util.List;

/**
 * A complete set of the player's actions at one instant, recorded by the dataset recorder. This is
 * the <b>full universe of actions Open Crafter can command</b> — it is kept one-to-one with
 * {@link mod.kelvinlby.link.InboundInstruction} (the engine&rarr;player command record) plus the
 * discrete {@link InventoryAction}s that ride the same OCLI stream, so a dataset recorded from human
 * play is directly comparable to what the engine would emit. If a field is ever added to the command
 * vocabulary, add it here (and to {@link ActionReader}) too.
 *
 * <p>Unlike {@code InboundInstruction}, this carries no "keep unchanged" sentinels: it is the
 * <em>observed</em> absolute state each sample, so {@code selectedSlot} is always a real {@code 0..8}
 * slot and {@code yaw}/{@code pitch} are always the player's actual rotation.
 *
 * <p>{@code inventoryActions} is the (usually empty) list of slot clicks observed in this sample's
 * clock period — an <em>edge</em> stream, not a held level. Movement/look come from {@link ActionReader}
 * polling once per tick; the inventory actions are captured as they happen by
 * {@link InventoryActionTap} (via a {@code clickSlot} mixin) and attached to the sample by the sampler.
 *
 * @param front       forward key held (W)
 * @param back        back key held (S)
 * @param left        strafe-left key held (A)
 * @param right       strafe-right key held (D)
 * @param jump        jump key held
 * @param sprint      effective sprinting state (player is actually sprinting, not just key held)
 * @param sneak       effective sneaking state (player is actually sneaking/crouched, not just key held)
 * @param attack      attack / left-click held
 * @param interact    use / right-click held
 * @param selectedSlot main-hand hotbar slot (0..8)
 * @param yaw         look yaw in degrees
 * @param pitch       look pitch in degrees ([-90, 90])
 * @param health      current health in half-heart points (0..20)
 * @param food        current hunger/food level (0..20)
 * @param xpLevel     current experience level
 * @param inventoryActions discrete slot clicks observed this period, in order (empty if none)
 */
public record ActionSet(
		boolean front,
		boolean back,
		boolean left,
		boolean right,
		boolean jump,
		boolean sprint,
		boolean sneak,
		boolean attack,
		boolean interact,
		int selectedSlot,
		float yaw,
		float pitch,
		float health,
		int food,
		int xpLevel,
		List<InventoryAction> inventoryActions) {

	public ActionSet {
		inventoryActions = (inventoryActions == null) ? List.of() : List.copyOf(inventoryActions);
	}

	/** All released, slot 0, zero rotation, no inventory actions — written before any input is read. */
	public static final ActionSet NEUTRAL =
			new ActionSet(false, false, false, false, false, false, false, false, false, 0, 0.0f, 0.0f,
					0.0f, 0, 0, List.of());

	/** This action set with a different inventory-action list — used by the sampler to attach drained clicks. */
	public ActionSet withInventoryActions(List<InventoryAction> actions) {
		return new ActionSet(front, back, left, right, jump, sprint, sneak, attack, interact,
				selectedSlot, yaw, pitch, health, food, xpLevel, actions);
	}
}
