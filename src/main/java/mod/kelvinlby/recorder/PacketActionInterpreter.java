package mod.kelvinlby.recorder;

import mod.kelvinlby.link.InventoryAction;
import mod.kelvinlby.link.InventoryMapper;
import mod.kelvinlby.link.InventoryState;
import mod.kelvinlby.link.SlotAddress;
import mod.kelvinlby.link.SlotGroup;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Converts decoded PLAY/C2S packets into the same absolute action vocabulary used for human datasets. */
final class PacketActionInterpreter implements SampleSource {
	private boolean front, back, left, right, jump, sprint, sneak;
	private boolean attackHeld, interactHeld;
	private boolean attackPulse, interactPulse;
	private int selectedSlot;
	private float yaw, pitch, health;
	private int food, air, xpLevel;
	private InventoryState inventory = InventoryState.EMPTY;
	private final List<TimedInventoryAction> inventoryActions = new ArrayList<>();
	private List<SlotAddress> dragSlots;
	private int dragButton = -1;

	private record TimedInventoryAction(long micros, InventoryAction action) {}

	/** Called on the client/render thread in exact packet order. */
	public synchronized void accept(Packet<?> packet, long micros, MinecraftClient mc) {
		if (packet instanceof PlayerInputC2SPacket p) {
			var input = p.input();
			front = input.forward(); back = input.backward(); left = input.left(); right = input.right();
			jump = input.jump(); sneak = input.sneak(); sprint = input.sprint();
		} else if (packet instanceof PlayerMoveC2SPacket p) {
			yaw = p.getYaw(yaw);
			pitch = p.getPitch(pitch);
		} else if (packet instanceof UpdateSelectedSlotC2SPacket p) {
			selectedSlot = Math.clamp(p.getSelectedSlot(), 0, 8);
		} else if (packet instanceof HandSwingC2SPacket) {
			attackPulse = true;
		} else if (packet instanceof PlayerInteractBlockC2SPacket) {
			interactPulse = true;
		} else if (packet instanceof PlayerInteractItemC2SPacket p) {
			// USE_ITEM is an edge, not evidence that the physical key remains down. Most item uses
			// (placing, buckets, flint and steel, etc.) are instantaneous and consequently have no
			// RELEASE_USE_ITEM packet. Continuous items are detected from the projected player's
			// active-item state in refreshObservation(), after this packet has been locally applied.
			interactPulse = true;
			yaw = p.getYaw(); pitch = p.getPitch();
		} else if (packet instanceof PlayerInteractEntityC2SPacket p) {
			p.handle(new PlayerInteractEntityC2SPacket.Handler() {
				@Override public void interact(net.minecraft.util.Hand hand) { interactPulse = true; }
				@Override public void interactAt(net.minecraft.util.Hand hand, net.minecraft.util.math.Vec3d pos) { interactPulse = true; }
				@Override public void attack() { attackPulse = true; }
			});
		} else if (packet instanceof PlayerActionC2SPacket p) {
			switch (p.getAction()) {
				case START_DESTROY_BLOCK -> attackHeld = true;
				case ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> attackHeld = false;
				case RELEASE_USE_ITEM -> interactHeld = false;
				case DROP_ITEM, DROP_ALL_ITEMS -> inventoryActions.add(new TimedInventoryAction(micros,
						new InventoryAction(InventoryAction.Op.DROP,
								new SlotAddress(SlotGroup.HOTBAR, selectedSlot), null)));
				case SWAP_ITEM_WITH_OFFHAND -> inventoryActions.add(new TimedInventoryAction(micros,
						new InventoryAction(InventoryAction.Op.SWAP,
								new SlotAddress(SlotGroup.HOTBAR, selectedSlot),
								new SlotAddress(SlotGroup.OFFHAND, 0))));
				default -> { }
			}
		} else if (packet instanceof ClickSlotC2SPacket p && mc.player != null) {
			if (p.actionType() == SlotActionType.QUICK_CRAFT) {
				acceptDrag(p, micros, mc);
			} else {
				InventoryAction action = InventoryMapper.classifyClick(mc.player, p.slot(), p.button(), p.actionType());
				if (action.op() != InventoryAction.Op.NONE) inventoryActions.add(new TimedInventoryAction(micros, action));
			}
		}
	}

	private void acceptDrag(ClickSlotC2SPacket packet, long micros, MinecraftClient mc) {
		int stage = ScreenHandler.unpackQuickCraftStage(packet.button());
		int button = ScreenHandler.unpackQuickCraftButton(packet.button());
		switch (stage) {
			case 0 -> { dragSlots = new ArrayList<>(); dragButton = button; }
			case 1 -> {
				SlotAddress address = InventoryMapper.addressOf(mc.player, packet.slot());
				if (dragSlots != null && address != null && address.group() != SlotGroup.CURSOR
						&& address.group() != SlotGroup.DISCARD && !dragSlots.contains(address)) dragSlots.add(address);
			}
			case 2 -> {
				if (dragSlots != null && !dragSlots.isEmpty()) {
					if (dragButton == 0) inventoryActions.add(new TimedInventoryAction(micros,
							new InventoryAction(InventoryAction.Op.DISTRIBUTE, null, null, dragSlots)));
					else if (dragButton == 1) for (SlotAddress address : dragSlots)
						inventoryActions.add(new TimedInventoryAction(micros,
								new InventoryAction(InventoryAction.Op.PUT, address, null)));
				}
				dragSlots = null; dragButton = -1;
			}
			default -> { }
		}
	}

	/** Refresh observation-only fields after all S2C packets at a replay boundary have been applied. */
	public synchronized void refreshObservation(MinecraftClient mc) {
		if (mc.player != null) {
			// This is the only reliable packet-derived distinction between an instantaneous right click
			// and a held use (food, bow, shield). It also clears the level when use finishes naturally,
			// even if no explicit RELEASE_USE_ITEM packet was captured.
			interactHeld = mc.player.isUsingItem();
			yaw = mc.player.getYaw();
			pitch = mc.player.getPitch();
			selectedSlot = mc.player.getInventory().getSelectedSlot();
			health = mc.player.getHealth();
			food = mc.player.getHungerManager().getFoodLevel();
			air = mc.player.getAir();
			xpLevel = mc.player.experienceLevel;
			inventory = InventoryMapper.readInventory(mc);
		}
	}

	@Override
	public synchronized ActionSet sampleAt(long offsetMicros) {
		List<InventoryAction> due = new ArrayList<>();
		for (Iterator<TimedInventoryAction> it = inventoryActions.iterator(); it.hasNext();) {
			TimedInventoryAction timed = it.next();
			if (timed.micros <= offsetMicros) { due.add(timed.action); it.remove(); }
		}
		ActionSet result = new ActionSet(front, back, left, right, jump, sprint, sneak,
				attackHeld || attackPulse, interactHeld || interactPulse, selectedSlot,
				yaw, pitch, health, food, air, xpLevel, due);
		attackPulse = false;
		interactPulse = false;
		return result;
	}

	@Override public synchronized InventoryState currentInventory() { return inventory; }
}
