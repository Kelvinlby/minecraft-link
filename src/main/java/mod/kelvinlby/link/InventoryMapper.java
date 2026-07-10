package mod.kelvinlby.link;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The single place that maps between Open Crafter Link's stable {@link SlotGroup}+index addressing and
 * vanilla's per-screen {@code ScreenHandler.slots} ids — in <b>both</b> directions. Presentation
 * ({@link #readInventory}) and action resolution ({@link #resolveSlotId}) both run the same
 * {@link #classify} pass, so the two can never disagree about which vanilla slot a {@code (group, index)}
 * refers to.
 *
 * <p><b>Threading:</b> every method reads live {@code ScreenHandler}/{@code Slot}/{@code ItemStack} state
 * and so must run on the client tick thread. The returned {@link InventoryState} is immutable and safe to
 * publish to the bridge worker thread; the {@code int} results of {@link #resolveSlotId}/
 * {@link #hotbarButton} are consumed on the tick thread by {@link InputDriver}.
 *
 * <p><b>Classification rule (screen-independent):</b> iterate {@code handler.slots}; a slot backed by the
 * player's own {@link PlayerInventory} is bucketed by its backing index (0..8 hotbar, 9..35 inventory,
 * 36..39 armor with head-to-feet = {@code 39 - index}, 40 offhand); any other slot is an
 * {@link SlotGroup#EXTENSION} slot, ordered by appearance. This works for every screen because vanilla
 * appends the same player-inventory slots after a container's slots, and the no-screen 2&times;2 crafting
 * grid is backed by its own inventory (so it lands in the extension group).
 */
public final class InventoryMapper {
	private InventoryMapper() {}

	/** Registry id used for the no-screen 2x2 crafting grid, whose handler {@code getType()} is null. */
	private static final String CRAFTING_ID = "minecraft:crafting";

	/** PlayerInventory backing-index boundaries (see {@link PlayerInventory}). */
	private static final int HOTBAR_END = 9;     // 0..8
	private static final int INVENTORY_END = 36;  // 9..35
	private static final int ARMOR_END = 40;      // 36..39 (FEET=36 .. HEAD=39)
	private static final int OFFHAND_INDEX = 40;

	/**
	 * The result of classifying a screen: for each group, the slots in canonical index order, each paired
	 * with its vanilla {@code slot.id}. {@code extensionRegistryId} is the container registry id (or the
	 * crafting fallback) when an extension group is present.
	 */
	private record Classification(Map<SlotGroup, List<Slot>> groups, String extensionRegistryId) {}

	/**
	 * Classify the player's current screen handler into ordered per-group slot lists. Returns {@code null}
	 * if there is no player (caller should treat as empty).
	 */
	private static Classification classify(PlayerEntity player) {
		if (player == null) {
			return null;
		}
		ScreenHandler handler = player.currentScreenHandler;
		PlayerInventory pinv = player.getInventory();

		Map<SlotGroup, List<Slot>> groups = new EnumMap<>(SlotGroup.class);
		// Armor is filled by group index (0=head..3=feet) directly; pre-size so we can place by index.
		Slot[] armor = new Slot[4];
		List<Slot> extension = new ArrayList<>();

		for (Slot slot : handler.slots) {
			if (slot.inventory == pinv) {
				int i = slot.getIndex();
				if (i >= 0 && i < HOTBAR_END) {
					placeAt(groups, SlotGroup.HOTBAR, i, slot);
				} else if (i < INVENTORY_END) {
					placeAt(groups, SlotGroup.INVENTORY, i - HOTBAR_END, slot);
				} else if (i < ARMOR_END) {
					armor[ARMOR_END - 1 - i] = slot; // head..feet => 0..3
				} else if (i == OFFHAND_INDEX) {
					placeAt(groups, SlotGroup.OFFHAND, 0, slot);
				}
				// 41 body / 42 saddle: not part of the requested groups — ignore.
			} else {
				extension.add(slot);
			}
		}

		if (hasAny(armor)) {
			groups.put(SlotGroup.ARMOR, toList(armor));
		}
		String extensionId = null;
		if (!extension.isEmpty()) {
			groups.put(SlotGroup.EXTENSION, extension);
			extensionId = registryId(handler);
		}
		return new Classification(groups, extensionId);
	}

	/**
	 * Build the immutable {@link InventoryState} for the controller. Groups appear in the canonical
	 * {@link SlotGroup} declaration order. Always includes the virtual {@link SlotGroup#CURSOR} (the held
	 * stack) and {@link SlotGroup#DISCARD} (a placeholder — no item) so the controller sees a consistent
	 * shape.
	 */
	public static InventoryState readInventory(MinecraftClient mc) {
		PlayerEntity player = mc.player;
		Classification c = classify(player);
		if (c == null) {
			return InventoryState.EMPTY;
		}
		ScreenHandler handler = player.currentScreenHandler;

		List<SlotGroupState> out = new ArrayList<>();
		for (SlotGroup group : SlotGroup.values()) {
			switch (group) {
				case CURSOR -> out.add(new SlotGroupState(SlotGroup.CURSOR, null,
						List.of(infoFor(handler.getCursorStack(), true))));
				case DISCARD -> out.add(new SlotGroupState(SlotGroup.DISCARD, null,
						List.of(SlotInfo.EMPTY)));
				default -> {
					List<Slot> slots = c.groups().get(group);
					if (slots == null || slots.isEmpty()) {
						continue; // group not present in this screen
					}
					List<SlotInfo> infos = new ArrayList<>(slots.size());
					for (Slot slot : slots) {
						infos.add(slot == null
								? SlotInfo.EMPTY
								: new SlotInfo(itemId(slot.getStack()), count(slot.getStack()), slot.isEnabled()));
					}
					String registryId = (group == SlotGroup.EXTENSION) ? c.extensionRegistryId() : null;
					out.add(new SlotGroupState(group, registryId, infos));
				}
			}
		}
		return new InventoryState(out);
	}

	/**
	 * Resolve a {@link SlotAddress} to the vanilla {@code slot.id} to click in the player's current screen.
	 *
	 * @return the slot id; {@link ScreenHandler#EMPTY_SPACE_SLOT_INDEX} (-999) for {@link SlotGroup#DISCARD};
	 *         {@code -1} if the address is the cursor or is not present in the current screen
	 */
	public static int resolveSlotId(PlayerEntity player, SlotAddress addr) {
		if (addr == null) {
			return -1;
		}
		if (addr.group() == SlotGroup.DISCARD) {
			return ScreenHandler.EMPTY_SPACE_SLOT_INDEX;
		}
		if (addr.group() == SlotGroup.CURSOR) {
			return -1; // the cursor is not a clickable slot
		}
		Classification c = classify(player);
		if (c == null) {
			return -1;
		}
		List<Slot> slots = c.groups().get(addr.group());
		if (slots == null || addr.index() < 0 || addr.index() >= slots.size()) {
			return -1;
		}
		Slot slot = slots.get(addr.index());
		return slot == null ? -1 : slot.id;
	}

	/** The hotbar number-key (0..8) for a {@link SlotGroup#HOTBAR} address; {@code -1} otherwise. */
	public static int hotbarButton(SlotAddress addr) {
		if (addr == null || addr.group() != SlotGroup.HOTBAR || addr.index() < 0 || addr.index() > 8) {
			return -1;
		}
		return addr.index();
	}

	// ------------------------------------------------------------------ //
	// reverse mapping (vanilla click -> InventoryAction), for recording   //
	// ------------------------------------------------------------------ //

	/** Vanilla {@code SWAP} button id that swaps the hovered slot with the off-hand instead of a hotbar slot. */
	public static final int OFFHAND_SWAP_BUTTON = 40;

	/**
	 * Inverse of {@link #resolveSlotId}: turn a vanilla {@code slot.id} clicked in the player's current screen
	 * back into a stable {@link SlotAddress}, reusing the same {@link #classify} pass so the two directions can
	 * never disagree. {@code slotId == }{@link ScreenHandler#EMPTY_SPACE_SLOT_INDEX} (-999, a click outside the
	 * GUI) maps to {@link SlotGroup#DISCARD}. Returns {@code null} if the id is not a slot in the current screen
	 * (or there is no player). Runs on the client tick thread — see the class threading note.
	 */
	public static SlotAddress addressOf(PlayerEntity player, int slotId) {
		if (slotId == ScreenHandler.EMPTY_SPACE_SLOT_INDEX) {
			return new SlotAddress(SlotGroup.DISCARD, 0);
		}
		Classification c = classify(player);
		if (c == null) {
			return null;
		}
		for (Map.Entry<SlotGroup, List<Slot>> e : c.groups().entrySet()) {
			List<Slot> slots = e.getValue();
			for (int i = 0; i < slots.size(); i++) {
				Slot slot = slots.get(i);
				if (slot != null && slot.id == slotId) {
					return new SlotAddress(e.getKey(), i);
				}
			}
		}
		return null;
	}

	/**
	 * Map a single <em>stateless</em> vanilla slot click — the exact {@code (slotId, button, actionType)} passed
	 * to {@code ClientPlayerInteractionManager.clickSlot} — to the {@link InventoryAction} that would reproduce
	 * it, for the dataset recorder. This is the inverse of {@link InputDriver}'s drive path.
	 *
	 * <p>Handles the clicks that map to exactly one action: {@code QUICK_MOVE}&rarr;{@link InventoryAction.Op#MOVE},
	 * {@code PICKUP} button 0/1&rarr;{@link InventoryAction.Op#PICK}/{@link InventoryAction.Op#PUT},
	 * {@code SWAP}&rarr;{@link InventoryAction.Op#SWAP} (hotbar/off-hand vs. the hovered slot),
	 * {@code THROW}&rarr;{@link InventoryAction.Op#DROP}, and {@code PICKUP_ALL}&rarr;{@link InventoryAction.Op#COLLECT}
	 * (the double-click sweep). The multi-call sequences ({@code QUICK_CRAFT} drags, and the {@code PICKUP} that
	 * precedes a {@code PICKUP_ALL}) are reassembled by the caller ({@code InventoryActionTap}); {@code CLONE}
	 * (creative middle-click) and anything unrecognized return {@link InventoryAction#NONE}.
	 *
	 * @return the mapped action, or {@link InventoryAction#NONE} if this click has no representable action
	 */
	public static InventoryAction classifyClick(PlayerEntity player, int slotId, int button, SlotActionType type) {
		switch (type) {
			case QUICK_MOVE -> {
				SlotAddress a = addressOf(player, slotId);
				return a == null ? InventoryAction.NONE : new InventoryAction(InventoryAction.Op.MOVE, a, null);
			}
			case PICKUP -> {
				SlotAddress a = addressOf(player, slotId);
				if (a == null) {
					return InventoryAction.NONE;
				}
				InventoryAction.Op op = (button == 1) ? InventoryAction.Op.PUT : InventoryAction.Op.PICK;
				return new InventoryAction(op, a, null);
			}
			case PICKUP_ALL -> {
				SlotAddress a = addressOf(player, slotId);
				return a == null ? InventoryAction.NONE : new InventoryAction(InventoryAction.Op.COLLECT, a, null);
			}
			case THROW -> {
				SlotAddress a = addressOf(player, slotId);
				return a == null ? InventoryAction.NONE : new InventoryAction(InventoryAction.Op.DROP, a, null);
			}
			case SWAP -> {
				SlotAddress hovered = addressOf(player, slotId);
				SlotAddress hotbar = (button == OFFHAND_SWAP_BUTTON)
						? new SlotAddress(SlotGroup.OFFHAND, 0)
						: (button >= 0 && button <= 8 ? new SlotAddress(SlotGroup.HOTBAR, button) : null);
				if (hovered == null || hotbar == null) {
					return InventoryAction.NONE;
				}
				// InventoryAction's convention (matching InputDriver.executeSwap): a = the hotbar/off-hand slot,
				// b = the hovered slot.
				return new InventoryAction(InventoryAction.Op.SWAP, hotbar, hovered);
			}
			default -> {
				return InventoryAction.NONE; // CLONE, QUICK_CRAFT (handled by the tap), or unknown
			}
		}
	}

	// ------------------------------------------------------------------ //
	// helpers                                                            //
	// ------------------------------------------------------------------ //

	private static void placeAt(Map<SlotGroup, List<Slot>> groups, SlotGroup group, int index, Slot slot) {
		List<Slot> list = groups.computeIfAbsent(group, g -> new ArrayList<>());
		while (list.size() <= index) {
			list.add(null);
		}
		list.set(index, slot);
	}

	private static boolean hasAny(Slot[] arr) {
		for (Slot s : arr) {
			if (s != null) {
				return true;
			}
		}
		return false;
	}

	private static List<Slot> toList(Slot[] arr) {
		List<Slot> list = new ArrayList<>(arr.length);
		for (Slot s : arr) {
			list.add(s);
		}
		return list;
	}

	private static SlotInfo infoFor(ItemStack stack, boolean enabled) {
		return new SlotInfo(itemId(stack), count(stack), enabled);
	}

	private static String itemId(ItemStack stack) {
		return (stack == null || stack.isEmpty()) ? null : Registries.ITEM.getId(stack.getItem()).toString();
	}

	private static int count(ItemStack stack) {
		return (stack == null || stack.isEmpty()) ? 0 : stack.getCount();
	}

	/**
	 * The screen handler's registry id, or the crafting fallback for the synthetic player handler.
	 * {@code PlayerScreenHandler} is constructed with a null type, and {@link ScreenHandler#getType()}
	 * <b>throws</b> {@code UnsupportedOperationException} in that case (rather than returning null), so we
	 * catch it — the {@code type} field is {@code private}, so there is no non-throwing way to probe it
	 * without an access widener. That handler's only extension slots are the 2&times;2 crafting grid + result.
	 */
	private static String registryId(ScreenHandler handler) {
		ScreenHandlerType<?> type;
		try {
			type = handler.getType();
		} catch (UnsupportedOperationException noType) {
			return CRAFTING_ID;
		}
		return Registries.SCREEN_HANDLER.getId(type).toString();
	}
}
