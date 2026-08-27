package mod.kelvinlby.link;

/**
 * Presentation payload for one slot in a {@link SlotGroupState}. Immutable, so it is safely published
 * from the tick thread (which reads live {@code ItemStack}s) to the bridge worker thread (which encodes
 * it) through the existing {@link OutboundSnapshot} hand-off.
 *
 * @param item    Minecraft item registry id (e.g. {@code "minecraft:ender_pearl"}), or {@code null} for
 *                an empty slot
 * @param count   stack size; {@code 1} for a non-empty unstackable item, {@code 0} for an empty slot
 * @param enabled whether the slot is interactable — {@code Slot.isEnabled()}. Normally {@code true};
 *                {@code false} for a toggled-off auto-crafter slot. Virtual slots (cursor/discard) are
 *                always {@code true}.
 */
public record SlotInfo(String item, int count, boolean enabled) {

	/** An empty slot: no item, count 0, enabled. */
	public static final SlotInfo EMPTY = new SlotInfo(null, 0, true);
}
