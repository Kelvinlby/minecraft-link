package mod.kelvinlby.link;

import mod.kelvinlby.config.OclConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
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
 * calling the private attack/use methods directly, we drive the real {@link KeyBinding}s the game
 * already polls every tick, firing a press/release edge exactly where a human key event would and
 * letting all of vanilla's downstream logic decide hold-vs-click from there. A held attack key drives
 * continuous block-breaking via {@code MinecraftClient.handleBlockBreaking(... attackKey.isPressed()
 * ...)}; a single press/release edge queues one {@code KeyBinding.wasPressed()} — polled by
 * {@code doAttack}/{@code doItemUse}/{@code doItemPick} — so a discrete click still swings/uses once,
 * exactly like a human tap. See {@link #applyEdge} for the edge-detection itself.
 *
 * <h2>Why edge-triggered, not level-polled</h2>
 * Sneak, sprint, attack and use are {@code StickyKeyBinding}s whose {@code setPressed(true)}
 * <em>toggles</em> when the user's toggle option is on — calling it every tick while held would flip
 * the key on/off each tick. Bindings also only queue a click for {@code wasPressed()} via
 * {@code timesPressed}, which real input increments once per key-down, never while held. Comparing each
 * instruction's booleans against the previously-applied state and touching the binding only on an
 * actual transition (via the access-widened {@code pressed}/{@code timesPressed} fields) reproduces
 * both behaviours for free, instead of intercepting or bypassing vanilla's click/hold logic.
 *
 * <h2>Takeover policy</h2>
 * The mod drives keys only while instructions flow. Because the controller's send loop and the client
 * tick loop are independent, unsynchronized clocks, a tick can easily elapse with no <em>fresh</em>
 * instruction even while the controller is continuously holding a key — so the last known instruction
 * is re-applied (held) across such ticks rather than being treated as a release. Only once
 * {@link OclConfig#inputStalenessTicks} consecutive ticks pass without a fresh instruction is the
 * controller considered genuinely silent: the keys the mod was holding are released once and ownership
 * is dropped, after which the keyboard is left untouched so a human can play manually. Rotation and
 * slot are absolute and applied here too; telemetry is published separately at end-of-tick by
 * {@link TickDriver}.
 */
public final class InputDriver {
	/** Resolved live each tick so a bridge swap (settings save -&gt; reloadLink) doesn't orphan input. */
	private final Supplier<LinkBridge> bridge;

	/** Whether the mod is currently holding any driven key, so it can release them once on silence. */
	private boolean owningKeys;

	/** Most recent instruction (fresh or held-over), or {@code null} if none has arrived since world entry. */
	private InboundInstruction lastInstr;

	/** Consecutive ticks since {@link #lastInstr} was last replaced by a genuinely new instruction. */
	private int missedTicks;

	/**
	 * Held state we last applied to each driven binding, so {@link #stampMovement} can fire a real
	 * press/release edge only on a genuine transition rather than re-pressing every tick. Starts all
	 * {@code false}, matching a freshly-joined world where nothing is held.
	 */
	private boolean frontHeld, backHeld, leftHeld, rightHeld, jumpHeld, sneakHeld, sprintHeld,
			attackHeld, interactHeld;

	public InputDriver(Supplier<LinkBridge> bridge) {
		this.bridge = bridge;
	}

	public void onStartClientTick(MinecraftClient mc) {
		ClientPlayerEntity player = mc.player;
		if (player == null || mc.world == null) {
			owningKeys = false; // left the world; nothing held to release
			lastInstr = null;
			missedTicks = 0;
			// Vanilla resets every KeyBinding on world leave (KeyBinding.unpressAll), so our shadow of
			// "what's currently held" must reset too, or a rejoin would see a false hold and skip the
			// press edge for a binding that was still down when the world was left.
			frontHeld = backHeld = leftHeld = rightHeld = jumpHeld = sneakHeld = sprintHeld
					= attackHeld = interactHeld = false;
			return;
		}

		LinkBridge bridge = this.bridge.get();
		InboundInstruction fresh = bridge.takeLatest(); // non-destructive peek; same value until replaced
		if (fresh != null && fresh != lastInstr) {
			lastInstr = fresh;
			missedTicks = 0;
		} else if (lastInstr != null) {
			missedTicks++;
		}

		int limit = OclConfig.get().inputStalenessTicks;
		if (lastInstr != null && missedTicks <= limit) {
			// A non-inventory command that actually changes driven state must reach vanilla's
			// movement/look/attack handling, which is gated off while any Screen is open. Close it out
			// (exactly like a human pressing Escape) before driving keys, rather than silently
			// swallowing the command — but only when there's a real change to apply, so a screen the
			// controller left open on purpose isn't closed by an instruction that's a no-op repeat.
			if (mc.currentScreen != null && changesState(player, lastInstr)) {
				while (mc.currentScreen != null) {
					mc.currentScreen.close(); // Screen#close(), overridden by HandledScreen to send the
					// close-container packet — the same call vanilla's Escape key makes.
				}
			}
			// Fresh this tick, or held over within the staleness grace window: keep driving.
			stampMovement(mc.options, lastInstr);
			owningKeys = true;
			applyRotation(player, lastInstr);
			applySlot(player, lastInstr);
		} else if (owningKeys) {
			// Genuinely silent past the grace window: release once, then go hands-off.
			stampMovement(mc.options, InboundInstruction.NEUTRAL);
			owningKeys = false;
			lastInstr = null;
		}

		// Inventory actions ride the same OCLI stream but are delivered on a separate non-conflating queue,
		// so drain them independently of movement — a controller may send an action in a movement-free
		// frame. Draining all queued actions each tick applies a burst in order.
		InventoryAction action;
		while ((action = bridge.takeAction()) != null && action.op() != InventoryAction.Op.NONE) {
			executeAction(mc, player, action);
		}
	}

	/** Rotation deltas at or below this many degrees are camera jitter, not an intentional look command. */
	private static final float ROTATION_CLOSE_THRESHOLD_DEGREES = 1.0f;

	/**
	 * Whether applying {@code in} this tick is an intentional movement/look command that should close an
	 * open screen: a WASD or sneak/jump edge, or a rotation more than {@link
	 * #ROTATION_CLOSE_THRESHOLD_DEGREES} away from the player's current facing. Deliberately excludes
	 * sprint/attack/interact edges and hotbar slot changes — those don't need the screen gone to take
	 * effect meaningfully — and excludes small rotation deltas, since a live camera feed is essentially
	 * always reporting a slightly different angle tick to tick even when the controller isn't trying to
	 * look away from a menu.
	 */
	private boolean changesState(ClientPlayerEntity player, InboundInstruction in) {
		if (in.front() != frontHeld || in.back() != backHeld || in.left() != leftHeld
				|| in.right() != rightHeld || in.jump() != jumpHeld || in.sneak() != sneakHeld) {
			return true;
		}
		if (in.hasRotation()) {
			float yawDelta = MathHelper.wrapDegrees(in.yaw() - player.getYaw());
			float pitchDelta = in.pitch() - player.getPitch();
			if (Math.abs(yawDelta) > ROTATION_CLOSE_THRESHOLD_DEGREES
					|| Math.abs(pitchDelta) > ROTATION_CLOSE_THRESHOLD_DEGREES) {
				return true;
			}
		}
		return false;
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
				boolean isSelectedHotbar = action.a() != null && action.a().group() == SlotGroup.HOTBAR
						&& action.a().index() == player.getInventory().getSelectedSlot();
				if (mc.currentScreen == null && isSelectedHotbar) {
					// No screen, addressing the selected hotbar slot: exactly the vanilla drop key.
					player.dropSelectedItem(false);
				} else {
					// Any other addressed slot needs a THROW click on that slot id, which requires the
					// inventory screen to be open (matches the vanilla Q-on-hovered-slot path:
					// HandledScreen.keyPressed -> clickSlot THROW, button 0 = single item). Open it here if
					// it isn't already showing; the changesState-gated rule in onStartClientTick will close
					// it again once a real movement/look command arrives.
					if (mc.currentScreen == null) {
						mc.setScreen(new InventoryScreen(player));
					}
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

	/**
	 * Apply the instruction's held state to every driven binding as a real press/release edge, exactly
	 * like a human key event: unchanged-since-last-tick stays a hold (no-op), and only a transition
	 * calls {@code setPressed} + (on the press edge) bumps {@code timesPressed}. This is what makes the
	 * distinction between a discrete click and a sustained hold fall out of vanilla logic for free —
	 * {@code KeyBinding.wasPressed()} (polled by {@code doAttack}/{@code doItemUse}/{@code doItemPick})
	 * only ever fires on that edge, and {@code StickyKeyBinding.setPressed} only toggles correctly when
	 * called once per edge rather than every tick.
	 */
	private void stampMovement(GameOptions opts, InboundInstruction in) {
		frontHeld = applyEdge(opts.forwardKey, frontHeld, in.front());
		backHeld = applyEdge(opts.backKey, backHeld, in.back());
		leftHeld = applyEdge(opts.leftKey, leftHeld, in.left());
		rightHeld = applyEdge(opts.rightKey, rightHeld, in.right());
		jumpHeld = applyEdge(opts.jumpKey, jumpHeld, in.jump());
		sneakHeld = applyEdge(opts.sneakKey, sneakHeld, in.sneak());
		sprintHeld = applyEdge(opts.sprintKey, sprintHeld, in.sprint());
		attackHeld = applyEdge(opts.attackKey, attackHeld, in.attack());
		interactHeld = applyEdge(opts.useKey, interactHeld, in.interact());
	}

	/**
	 * Drive one binding to {@code wantPressed}, firing a press/release edge only when it differs from
	 * {@code wasHeld}. On the release-&gt;press edge this also increments the raw {@code timesPressed}
	 * queue, mirroring what {@code Keyboard}/{@code Mouse} do for a real key-down before calling
	 * {@code setPressed} — so a single controller click reads as a single {@code wasPressed()} exactly
	 * once, while a sustained hold keeps {@code isPressed()} true without re-queuing clicks.
	 */
	private boolean applyEdge(KeyBinding key, boolean wasHeld, boolean wantPressed) {
		if (wantPressed != wasHeld) {
			if (wantPressed) {
				key.timesPressed++;
			}
			key.setPressed(wantPressed);
		}
		return wantPressed;
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
