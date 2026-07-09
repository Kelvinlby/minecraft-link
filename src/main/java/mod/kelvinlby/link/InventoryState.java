package mod.kelvinlby.link;

import java.util.List;

/**
 * Immutable snapshot of the current screen's inventory, normalized into {@link SlotGroupState}s. Built
 * by {@link InventoryMapper#readInventory} on the tick thread and carried on the {@link OutboundSnapshot}
 * so it publishes to the controller as part of the per-tick OCLO stream (see {@link BinaryCodec}).
 *
 * <p>Which groups are present depends on the open screen: with no screen open the player's own screen
 * handler yields hotbar/offhand/armor/inventory plus the 2&times;2 crafting grid as the extension group;
 * with a container open the container's slots become the named extension group and the player groups are
 * still present (vanilla appends them after the container slots).
 *
 * @param groups the present groups, in canonical group order
 */
public record InventoryState(List<SlotGroupState> groups) {

	/** No groups — published when there is no player/world, so OCLO always encodes a well-formed section. */
	public static final InventoryState EMPTY = new InventoryState(List.of());

	public InventoryState {
		groups = List.copyOf(groups);
	}
}
