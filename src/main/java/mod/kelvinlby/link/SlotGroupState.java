package mod.kelvinlby.link;

import java.util.List;

/**
 * One slot group's presentation contents: the {@link SlotGroup}, its slots in canonical index order, and
 * — for {@link SlotGroup#EXTENSION} only — the container's {@code ScreenHandler} registry id (e.g.
 * {@code "minecraft:generic_9x3"}). Immutable (the slot list is defensively copied) so it can ride the
 * {@link OutboundSnapshot} hand-off from the tick thread to the encoder thread.
 *
 * @param group      the group
 * @param registryId container registry id for {@link SlotGroup#EXTENSION}; {@code null} for every other
 *                   group
 * @param slots      the group's slots, index-ordered (slot i is index i within this group)
 */
public record SlotGroupState(SlotGroup group, String registryId, List<SlotInfo> slots) {

	public SlotGroupState {
		slots = List.copyOf(slots);
	}
}
