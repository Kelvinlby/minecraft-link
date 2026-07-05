package mod.kelvinlby.recorder;

/**
 * A complete set of the player's actions at one instant, recorded by the dataset recorder. This is
 * the <b>full universe of actions Open Crafter can command</b> — it is kept one-to-one with
 * {@link mod.kelvinlby.link.InboundInstruction} (the engine&rarr;player command record) so a dataset
 * recorded from human play is directly comparable to what the engine would emit. If a field is ever
 * added to the command vocabulary, add it here (and to {@link ActionReader}) too.
 *
 * <p>Unlike {@code InboundInstruction}, this carries no "keep unchanged" sentinels: it is the
 * <em>observed</em> absolute state each sample, so {@code selectedSlot} is always a real {@code 0..8}
 * slot and {@code yaw}/{@code pitch} are always the player's actual rotation.
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
		float pitch) {

	/** All released, slot 0, zero rotation — the sample written before any input has been read. */
	public static final ActionSet NEUTRAL =
			new ActionSet(false, false, false, false, false, false, false, false, false, 0, 0.0f, 0.0f);
}
