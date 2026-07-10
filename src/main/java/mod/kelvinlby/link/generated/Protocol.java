package mod.kelvinlby.link.generated;

/**
 * GENERATED FROM protocol/protocol.json BY protocol/gen_constants.py — DO NOT EDIT BY HAND.
 *
 * <p>Shared OCLO/OCLI/OCLV wire constants — versions, movement/action bitmasks, slot-group
 * opcodes, and inventory-op opcodes. The Python side mirror is {@code pylib/src/ocl/_protocol.py},
 * generated from the same spec so the two languages can never silently disagree.
 */
public final class Protocol {
	private Protocol() {}

	public static final int VERSION = 2;
	public static final int VIS_VERSION = 1;

	// Movement bitmask (1 << position).
	public static final int M_FRONT = 1 << 0;
	public static final int M_BACK = 1 << 1;
	public static final int M_LEFT = 1 << 2;
	public static final int M_RIGHT = 1 << 3;
	public static final int M_JUMP = 1 << 4;
	public static final int M_SPRINT = 1 << 5;
	public static final int M_SNEAK = 1 << 6;

	// Action bitmask (1 << position).
	public static final int A_ATTACK = 1 << 0;
	public static final int A_INTERACT = 1 << 1;

	// Slot-group wire opcodes.
	public static final int G_HOTBAR = 0;
	public static final int G_OFFHAND = 1;
	public static final int G_ARMOR = 2;
	public static final int G_INVENTORY = 3;
	public static final int G_CURSOR = 4;
	public static final int G_DISCARD = 5;
	public static final int G_EXTENSION = 6;

	// Inventory-action wire opcodes.
	public static final int OP_NONE = 0;
	public static final int OP_MOVE = 1;
	public static final int OP_PICK = 2;
	public static final int OP_PUT = 3;
	public static final int OP_SWAP = 4;
	public static final int OP_DROP = 5;
	public static final int OP_DISTRIBUTE = 6;
	public static final int OP_COLLECT = 7;
}
