package mod.kelvinlby.link;

/**
 * A single instruction from the controller. Lives for exactly one tick: the tick driver consumes it
 * and clears the slot, so a lagging controller never causes a stale instruction to be repeated.
 *
 * <p>The seven movement flags are independent booleans (e.g. forward + sprint may co-occur); strict
 * one-hot is not enforced. Sentinels mark "leave unchanged" for fields where snapping to a default
 * would be jarring when the controller merely lags: {@code selectedSlot < 0} means "keep current
 * slot", and {@code NaN} yaw/pitch means "keep current rotation". Movement and actions, by contrast,
 * default to released/false — that is the correct neutral when no fresh instruction arrives.
 */
public record InboundInstruction(
		boolean front,
		boolean back,
		boolean left,
		boolean right,
		boolean jump,
		boolean sprint,
		boolean sneak,
		int selectedSlot,
		boolean attack,
		boolean interact,
		float yaw,
		float pitch) {

	/** Applied when no fresh instruction arrived this tick: release everything, keep slot and rotation. */
	public static final InboundInstruction NEUTRAL = new InboundInstruction(
			false, false, false, false, false, false, false,
			-1, false, false, Float.NaN, Float.NaN);

	public boolean hasSlot() {
		return selectedSlot >= 0;
	}

	public boolean hasRotation() {
		return !Float.isNaN(yaw) && !Float.isNaN(pitch);
	}
}
