package mod.kelvinlby.link;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

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
	/** Resolved live each tick so a bridge swap (settings save -&gt; reloadLink) doesn't orphan input. */
	private final Supplier<LinkBridge> bridge;

	/** Whether the mod is currently holding any driven key, so it can release them once on silence. */
	private boolean owningKeys;

	public InputDriver(Supplier<LinkBridge> bridge) {
		this.bridge = bridge;
	}

	public void onStartClientTick(MinecraftClient mc) {
		ClientPlayerEntity player = mc.player;
		if (player == null || mc.world == null) {
			owningKeys = false; // left the world; nothing held to release
			return;
		}

		LinkBridge bridge = this.bridge.get();
		InboundInstruction in = bridge.takeLatest();
		if (in == null) {
			// No fresh movement: release once if we were driving, then go hands-off so a human can play.
			if (owningKeys) {
				stampMovement(mc.options, InboundInstruction.NEUTRAL);
				owningKeys = false;
			}
		} else {
			stampMovement(mc.options, in);
			owningKeys = true;
			applyRotation(player, in);
			applySlot(player, in);
		}

		// Inventory actions ride the same OCLI stream but are delivered on a separate non-conflating queue,
		// so drain them independently of movement — a controller may send an action in a movement-free
		// frame. Draining all queued actions each tick applies a burst in order.
		InventoryAction action;
		while ((action = bridge.takeAction()) != null && action.op() != InventoryAction.Op.NONE) {
			executeAction(mc, player, action);
		}
	}

	/**
	 * Execute one inventory action by simulating the corresponding vanilla slot click through the
	 * interaction manager (exactly the path a human mouse/number-key press takes). Runs on the client tick
	 * thread, where {@code clickSlot} is safe. Any unresolvable address is a silent no-op.
	 */
	private void executeAction(MinecraftClient mc, ClientPlayerEntity player, InventoryAction action) {
		ClientPlayerInteractionManager im = mc.interactionManager;
		if (im == null) {
			return;
		}
		ScreenHandler handler = player.currentScreenHandler;
		int syncId = handler.syncId;

		switch (action.op()) {
			case MOVE -> {
				if (action.a().group() == SlotGroup.DISCARD) {
					return; // quick-move on the virtual discard slot is a no-op
				}
				int slotId = InventoryMapper.resolveSlotId(player, action.a());
				if (slotId >= 0) {
					im.clickSlot(syncId, slotId, 0, SlotActionType.QUICK_MOVE, player);
				}
			}
			case PICK -> {
				if (action.a().group() == SlotGroup.DISCARD) {
					// Click outside the GUI: left-click throws the whole cursor stack.
					im.clickSlot(syncId, ScreenHandler.EMPTY_SPACE_SLOT_INDEX, 0, SlotActionType.PICKUP, player);
					return;
				}
				int slotId = InventoryMapper.resolveSlotId(player, action.a());
				if (slotId >= 0) {
					im.clickSlot(syncId, slotId, 0, SlotActionType.PICKUP, player); // left-click
				}
			}
			case PUT -> {
				if (action.a().group() == SlotGroup.DISCARD) {
					// Click outside the GUI: right-click throws a single item from the cursor.
					im.clickSlot(syncId, ScreenHandler.EMPTY_SPACE_SLOT_INDEX, 1, SlotActionType.PICKUP, player);
					return;
				}
				int slotId = InventoryMapper.resolveSlotId(player, action.a());
				if (slotId >= 0) {
					im.clickSlot(syncId, slotId, 1, SlotActionType.PICKUP, player); // right-click
				}
			}
			case SWAP -> executeSwap(im, player, syncId, action);
			case DISTRIBUTE -> executeDistribute(im, player, syncId, action);
			case COLLECT -> {
				if (action.a() != null && action.a().group() != SlotGroup.DISCARD
						&& action.a().group() != SlotGroup.CURSOR) {
					int slotId = InventoryMapper.resolveSlotId(player, action.a());
					if (slotId >= 0) {
						// A vanilla double-click: the first click picks the slot's stack up onto the cursor,
						// the second (double-click) sweeps all matching stacks onto it via PICKUP_ALL.
						im.clickSlot(syncId, slotId, 0, SlotActionType.PICKUP, player);
						im.clickSlot(syncId, slotId, 0, SlotActionType.PICKUP_ALL, player);
					}
				}
			}
			case DROP -> {
				if (mc.currentScreen == null) {
					// No screen: exactly the vanilla drop key — drop one item from the selected hotbar stack,
					// regardless of the addressed slot (matches MinecraftClient's dropKey handling).
					player.dropSelectedItem(false);
				} else {
					// Screen open: drop one item from the addressed slot — the vanilla Q-on-hovered-slot path
					// (HandledScreen.keyPressed -> clickSlot THROW, button 0 = single item).
					if (action.a() != null && action.a().group() != SlotGroup.DISCARD
							&& action.a().group() != SlotGroup.CURSOR) {
						int slotId = InventoryMapper.resolveSlotId(player, action.a());
						if (slotId >= 0) {
							im.clickSlot(syncId, slotId, 0, SlotActionType.THROW, player);
						}
					}
				}
			}
			case NONE -> { /* nothing */ }
		}
	}

	/**
	 * Swap two slots via a number-key {@code SWAP}: hover the non-hotbar slot and press the number key of
	 * the hotbar slot. Constraints (any violation = no-op): neither operand may be cursor or discard, and
	 * at least one must be a hotbar slot. When both are hotbar, hover {@code a} and press {@code b}'s key.
	 */
	private void executeSwap(ClientPlayerInteractionManager im, ClientPlayerEntity player, int syncId,
			InventoryAction action) {
		SlotAddress a = action.a();
		SlotAddress b = action.b();
		if (a == null || b == null) {
			return;
		}
		if (a.group() == SlotGroup.CURSOR || a.group() == SlotGroup.DISCARD
				|| b.group() == SlotGroup.CURSOR || b.group() == SlotGroup.DISCARD) {
			return;
		}
		boolean aHotbar = a.group() == SlotGroup.HOTBAR;
		boolean bHotbar = b.group() == SlotGroup.HOTBAR;
		if (!aHotbar && !bHotbar) {
			return; // at least one must be a hotbar slot
		}

		// The hovered slot is the non-hotbar one; if both are hotbar, hover a and press b's number.
		SlotAddress hovered = aHotbar && !bHotbar ? b : a;
		SlotAddress hotbar = (hovered == a) ? b : a;

		int hoveredSlotId = InventoryMapper.resolveSlotId(player, hovered);
		int hotbarButton = InventoryMapper.hotbarButton(hotbar);
		if (hoveredSlotId < 0 || hotbarButton < 0) {
			return;
		}
		im.clickSlot(syncId, hoveredSlotId, hotbarButton, SlotActionType.SWAP, player);
	}

	/**
	 * Distribute the cursor stack evenly across the addressed slots — the vanilla left-click drag. Emitted
	 * as the three-stage {@code QUICK_CRAFT} sequence (begin at -999, one add per target slot, end at -999)
	 * within a single tick, so no cross-tick dragging is needed. Button {@code 0} = left-drag (split evenly).
	 *
	 * <p>Vanilla requires the cursor to hold a stack and each target to accept it; the server applies its
	 * own checks, and any unresolvable/cursor/discard target is skipped. Nothing happens if fewer than one
	 * valid target remains after filtering.
	 */
	private void executeDistribute(ClientPlayerInteractionManager im, ClientPlayerEntity player, int syncId,
			InventoryAction action) {
		final int button = 0; // left-drag: distribute evenly
		List<Integer> slotIds = new ArrayList<>(action.slots().size());
		for (SlotAddress addr : action.slots()) {
			if (addr == null || addr.group() == SlotGroup.CURSOR || addr.group() == SlotGroup.DISCARD) {
				continue;
			}
			int slotId = InventoryMapper.resolveSlotId(player, addr);
			if (slotId >= 0 && !slotIds.contains(slotId)) { // vanilla drag visits each slot at most once
				slotIds.add(slotId);
			}
		}
		if (slotIds.isEmpty()) {
			return;
		}
		im.clickSlot(syncId, ScreenHandler.EMPTY_SPACE_SLOT_INDEX,
				ScreenHandler.packQuickCraftData(0, button), SlotActionType.QUICK_CRAFT, player); // begin
		for (int slotId : slotIds) {
			im.clickSlot(syncId, slotId,
					ScreenHandler.packQuickCraftData(1, button), SlotActionType.QUICK_CRAFT, player); // add slot
		}
		im.clickSlot(syncId, ScreenHandler.EMPTY_SPACE_SLOT_INDEX,
				ScreenHandler.packQuickCraftData(2, button), SlotActionType.QUICK_CRAFT, player); // end -> apply
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
