package mod.kelvinlby.link;

/**
 * A screen-independent slot reference: a {@link SlotGroup} plus the group-local index (aligned with the
 * custom indexing described on {@link SlotGroup}). {@link InventoryMapper#resolveSlotId} turns this into
 * the actual vanilla {@code slot.id} in the current screen for {@link ClientPlayerInteractionManager}
 * clicks.
 *
 * @param group the group
 * @param index the group-local index (e.g. 0..8 for {@link SlotGroup#HOTBAR}; 0 for the single-slot groups)
 */
public record SlotAddress(SlotGroup group, int index) {
}
