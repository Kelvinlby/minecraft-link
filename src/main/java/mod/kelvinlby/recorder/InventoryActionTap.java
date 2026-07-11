package mod.kelvinlby.recorder;

import mod.kelvinlby.link.InventoryAction;
import mod.kelvinlby.link.InventoryMapper;
import mod.kelvinlby.link.SlotAddress;
import mod.kelvinlby.link.SlotGroup;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The recorder seam for the human's (and engine's) inventory slot clicks — the discrete-action mirror of
 * {@link VisionTap}. A single mixin on {@code ClientPlayerInteractionManager.clickSlot} calls
 * {@link #observeClick} at the HEAD of every slot click; this class maps each click to the stable
 * {@link InventoryAction} vocabulary (via {@link InventoryMapper#classifyClick}) and buffers the results in a
 * non-conflating FIFO that the recorder's {@code Sampler} drains once per fixed clock tick
 * ({@link #drainInto}). It captures clicks from any origin: because {@code clickSlot} is also the path
 * {@code InputDriver} drives, an engine-commanded action is recorded identically to a human one.
 *
 * <h2>Why a FIFO, not a per-tick latch</h2>
 * Movement/look are held levels that a per-tick poll captures correctly, but a slot click is an <b>edge
 * event</b> that must not be dropped or coalesced. So — exactly like the inbound {@code LinkBridge.takeAction}
 * queue — clicks go into an order-preserving queue and the sampler takes <em>all</em> that arrived in the
 * period. The queue is bounded with a drop-oldest safety valve; clicks are rare, so this should never trigger.
 *
 * <h2>Multi-call sequences reassembled here</h2>
 * A <b>drag</b> ({@code QUICK_CRAFT}) arrives as several {@code clickSlot} calls within one tick and is
 * reassembled into a single logical action so the dataset matches the engine's command vocabulary: stage 0
 * begins, stage 1 repeats once per dragged slot, stage 2 ends. On stage 2 a <em>left</em>-drag emits one
 * {@link InventoryAction.Op#DISTRIBUTE} carrying the slot list, a <em>right</em>-drag emits one
 * {@link InventoryAction.Op#PUT} per slot, and a middle-drag (creative clone) emits nothing.
 *
 * <p>A <b>double-click</b> gather is <em>not</em> reassembled: vanilla fires it as two genuinely separate
 * clicks spread across two mouse press/release cycles (the leading {@code PICKUP} that lifts the stack onto
 * the cursor, then the {@code PICKUP_ALL} sweep on release), so it is recorded 1:1 as vanilla emits it —
 * a {@code pick} followed by a {@code collect} on the same slot. {@link InventoryAction.Op#COLLECT} therefore
 * denotes only the sweep and assumes the cursor already holds the stack, mirroring {@code InputDriver}'s
 * replay of {@code collect} as a lone {@code PICKUP_ALL}.
 *
 * <h2>Threading</h2>
 * {@link #observeClick} runs only on the client tick thread, so the drag accumulator needs no locking. The
 * sampler thread only ever calls {@link #drainInto}; the deque is thread-safe. All state is static: one
 * client has one interaction manager and one recorder.
 */
public final class InventoryActionTap {
	private InventoryActionTap() {}

	/** Bounded like the bridges' action queue: clicks are rare, the cap only guards against a stalled sampler. */
	private static final int CAPACITY = 64;

	/** Mapped actions awaiting the sampler drain. Client thread appends; sampler thread drains. */
	private static final ConcurrentLinkedDeque<InventoryAction> QUEUE = new ConcurrentLinkedDeque<>();

	/** Whether a recording session is active, so the mixin can cheaply skip when nobody records. */
	private static volatile boolean active;

	/** Count of actions dropped because the queue overflowed (sampler stalled); surfaced in the manifest. */
	private static final AtomicLong dropped = new AtomicLong();

	// --- client-thread-only drag/double-click reassembly state ---

	/** Accumulated stage-1 slots of an in-progress drag; null when no drag is active. */
	private static List<SlotAddress> dragSlots;
	/** Drag button of the in-progress drag: 0 left (distribute), 1 right (one each), 2 middle (clone). */
	private static int dragButton = -1;

	/** Enable/disable the tap. Called by {@link Recorder} on session start/stop; resets partial state. */
	public static void setActive(boolean on) {
		active = on;
		if (!on) {
			QUEUE.clear();
			dragSlots = null;
			dragButton = -1;
		}
	}

	/** True while a recording session wants clicks — the mixin checks this before doing any work. */
	public static boolean isActive() {
		return active;
	}

	/**
	 * Observe a vanilla drop-key press ({@code PlayerEntity.dropSelectedItem}) that fires with no inventory
	 * screen open. This is the one drop path that never calls {@code clickSlot} — vanilla's Q-outside-a-GUI
	 * handling calls this directly — so {@link #observeClick} can never see it; the recorder needs a second,
	 * narrow tap on this method instead. Maps to the same {@link InventoryAction.Op#DROP} the in-screen THROW
	 * click produces, addressed at the currently selected hotbar slot to match {@link InventoryMapper}'s
	 * {@code classifyClick} convention for that op. No-op when the tap is inactive. Client tick thread only
	 * (the only thread {@code dropSelectedItem} runs on for the local player).
	 */
	public static void observeDropKey(PlayerEntity player) {
		if (!active || player == null) {
			return;
		}
		int selected = player.getInventory().getSelectedSlot();
		enqueue(new InventoryAction(InventoryAction.Op.DROP, new SlotAddress(SlotGroup.HOTBAR, selected), null));
	}

	/**
	 * Observe one vanilla slot click at the HEAD of {@code clickSlot} and buffer the {@link InventoryAction}(s)
	 * it maps to. Reads live screen state (via {@link InventoryMapper}) before the click applies, which is
	 * correct: the address names <em>where</em> the click landed, not the post-click contents. No-op when the
	 * tap is inactive. Client tick thread only.
	 */
	public static void observeClick(PlayerEntity player, int slotId, int button, SlotActionType type) {
		if (!active || player == null) {
			return;
		}
		if (type == SlotActionType.QUICK_CRAFT) {
			observeDrag(player, slotId, button);
			return;
		}
		InventoryAction action = InventoryMapper.classifyClick(player, slotId, button, type);
		if (action.op() == InventoryAction.Op.NONE) {
			return;
		}
		enqueue(action);
	}

	/**
	 * Run the 3-stage {@code QUICK_CRAFT} state machine. Stage 0 starts a fresh accumulator (recording the drag
	 * button), stage 1 appends the dragged slot, stage 2 emits the reassembled action(s) and clears the state.
	 */
	private static void observeDrag(PlayerEntity player, int slotId, int button) {
		int stage = ScreenHandler.unpackQuickCraftStage(button);
		int drag = ScreenHandler.unpackQuickCraftButton(button);
		switch (stage) {
			case 0 -> {
				dragSlots = new ArrayList<>();
				dragButton = drag;
			}
			case 1 -> {
				if (dragSlots != null) {
					SlotAddress a = InventoryMapper.addressOf(player, slotId);
					// Skip the cursor/discard placeholders and any unresolved slot; vanilla visits each once.
					if (a != null && a.group() != SlotGroup.CURSOR && a.group() != SlotGroup.DISCARD
							&& !dragSlots.contains(a)) {
						dragSlots.add(a);
					}
				}
			}
			case 2 -> {
				emitDrag();
				dragSlots = null;
				dragButton = -1;
			}
			default -> { /* unknown stage — ignore */ }
		}
	}

	/** Emit the finished drag: left-drag -> one DISTRIBUTE(slots); right-drag -> a PUT per slot; middle -> none. */
	private static void emitDrag() {
		if (dragSlots == null || dragSlots.isEmpty()) {
			return;
		}
		switch (dragButton) {
			case 0 -> enqueue(new InventoryAction(InventoryAction.Op.DISTRIBUTE, null, null, dragSlots));
			case 1 -> {
				for (SlotAddress a : dragSlots) {
					enqueue(new InventoryAction(InventoryAction.Op.PUT, a, null));
				}
			}
			default -> { /* middle-drag = creative clone — not recorded */ }
		}
	}

	/** Append an action, dropping the oldest (and counting it) if the bounded queue is full. */
	private static void enqueue(InventoryAction action) {
		while (QUEUE.size() >= CAPACITY) {
			if (QUEUE.pollFirst() == null) {
				break;
			}
			dropped.incrementAndGet();
		}
		QUEUE.addLast(action);
	}

	/** Sampler: move every buffered action, in order, into {@code out}. Returns how many were drained. */
	public static int drainInto(List<InventoryAction> out) {
		int n = 0;
		InventoryAction a;
		while ((a = QUEUE.pollFirst()) != null) {
			out.add(a);
			n++;
		}
		return n;
	}

	/** Total actions dropped due to overflow across the session; read once at finalize for the manifest. */
	public static long droppedCount() {
		return dropped.get();
	}

	/** Reset the drop counter at the start of a session. */
	public static void resetDropped() {
		dropped.set(0);
	}
}
