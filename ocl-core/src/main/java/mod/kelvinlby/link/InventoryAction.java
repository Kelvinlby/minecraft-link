package mod.kelvinlby.link;

import mod.kelvinlby.link.generated.Protocol;

import java.util.List;

/**
 * A single discrete inventory command from the controller, carried on the OCLI stream alongside (but
 * decoupled from) the movement instruction. Unlike movement — a held level that conflates correctly —
 * an action is an <b>edge event</b> that must not be dropped, so the bridge routes it to a non-conflating
 * FIFO queue ({@link LinkBridge#takeAction}) rather than the conflating movement inbox.
 *
 * <p>Executed by {@link InputDriver} via {@code interactionManager.clickSlot}. See {@link Op} for the
 * click each opcode maps to.
 *
 * @param op    the action; {@link Op#NONE} means "movement-only OCLI frame, no action"
 * @param a     the primary slot; for {@link Op#SWAP} the first of the two swapped slots
 * @param b     the second slot, used only by {@link Op#SWAP} (ignored otherwise)
 * @param slots the target slot list, used only by {@link Op#DISTRIBUTE} (empty otherwise)
 */
public record InventoryAction(Op op, SlotAddress a, SlotAddress b, List<SlotAddress> slots) {

	public InventoryAction {
		slots = (slots == null) ? List.of() : List.copyOf(slots);
	}

	/** Convenience for the single/dual-operand ops (no slot list). */
	public InventoryAction(Op op, SlotAddress a, SlotAddress b) {
		this(op, a, b, List.of());
	}

	/**
	 * The action vocabulary. Wire ids are explicit and stable.
	 *
	 * <ul>
	 *   <li>{@link #MOVE} — quick-move (shift-click / {@code QUICK_MOVE}); no-op on the discard slot.</li>
	 *   <li>{@link #PICK} — left-click ({@code PICKUP} button 0); on discard, throws the whole cursor stack.</li>
	 *   <li>{@link #PUT} — right-click ({@code PICKUP} button 1); on discard, throws a single item.</li>
	 *   <li>{@link #SWAP} — number-key swap ({@code SWAP}); both slots non-cursor/non-discard, at least one
	 *       hotbar.</li>
	 *   <li>{@link #DROP} — the vanilla drop key on one item: with no screen open, drops one item from the
	 *       selected hotbar stack (ignoring the addressed slot, exactly like pressing Q); with a screen
	 *       open, drops one item from the addressed slot ({@code THROW} button 0).</li>
	 *   <li>{@link #DISTRIBUTE} — a left-click drag: divides the cursor stack evenly across the
	 *       {@link #slots} list, emitted as the vanilla 3-stage {@code QUICK_CRAFT} sequence in one tick.</li>
	 *   <li>{@link #COLLECT} — the sweep of a vanilla double-click: gathers all matching stacks onto the
	 *       cursor, emitted as the vanilla {@code PICKUP_ALL} click alone. It assumes the matching stack is
	 *       already on the cursor (put there by a preceding {@link #PICK}), mirroring the second click of a
	 *       double-click; a full double-click is therefore recorded and replayed as {@code pick} then
	 *       {@code collect}.</li>
	 * </ul>
	 */
	public enum Op {
		NONE(Protocol.OP_NONE),
		MOVE(Protocol.OP_MOVE),
		PICK(Protocol.OP_PICK),
		PUT(Protocol.OP_PUT),
		SWAP(Protocol.OP_SWAP),
		DROP(Protocol.OP_DROP),
		DISTRIBUTE(Protocol.OP_DISTRIBUTE),
		COLLECT(Protocol.OP_COLLECT);

		private final int wireId;

		Op(int wireId) {
			this.wireId = wireId;
		}

		public int wireId() {
			return wireId;
		}

		/** Resolve an op from its wire id, defaulting to {@link #NONE} on anything unknown. */
		public static Op fromWireId(int wireId) {
			for (Op op : values()) {
				if (op.wireId == wireId) {
					return op;
				}
			}
			return NONE;
		}
	}

	/** No action — the neutral value carried by a movement-only OCLI frame. */
	public static final InventoryAction NONE = new InventoryAction(Op.NONE, null, null, List.of());
}
