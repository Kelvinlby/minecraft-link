package mod.kelvinlby.link;

import mod.kelvinlby.link.generated.Protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Test-only encoders for message directions the mod only ever <i>decodes</i>, so {@link BinaryCodec}
 * has no encoder for them. Currently just OCLI (controller → mod): the mod decodes it via
 * {@link BinaryCodec#decodeInbound}, so the golden generator/test needs a matching encoder to produce
 * and round-trip the OCLI {@code .bin} fixtures. The byte layout mirrors {@code BinaryCodec}'s OCLI
 * decode exactly; the authoritative controller-side encoder is pylib's {@code encode_instruction}.
 */
final class TestEncoders {
	private TestEncoders() {}

	private static final byte[] MAGIC_IN = {'O', 'C', 'L', 'I'};
	private static final byte VERSION = (byte) Protocol.VERSION;

	/** Encode an OCLI frame from its movement + inventory-action parts (mirrors {@link BinaryCodec#decodeInbound}). */
	static byte[] encodeInbound(InboundInstruction m, InventoryAction action) {
		int move = 0;
		move |= m.front() ? Protocol.M_FRONT : 0;
		move |= m.back() ? Protocol.M_BACK : 0;
		move |= m.left() ? Protocol.M_LEFT : 0;
		move |= m.right() ? Protocol.M_RIGHT : 0;
		move |= m.jump() ? Protocol.M_JUMP : 0;
		move |= m.sprint() ? Protocol.M_SPRINT : 0;
		move |= m.sneak() ? Protocol.M_SNEAK : 0;
		int act = (m.attack() ? Protocol.A_ATTACK : 0) | (m.interact() ? Protocol.A_INTERACT : 0);

		InventoryAction.Op op = (action != null) ? action.op() : InventoryAction.Op.NONE;
		boolean distribute = op == InventoryAction.Op.DISTRIBUTE;

		// header(5) + move payload(14) + action payload(7) + [1 + 3*n for DISTRIBUTE's slot list].
		// The 7-byte action block is ALWAYS present (matching pylib's encode_instruction); a
		// movement-only frame carries OP_NONE + zero operands. BinaryCodec.decodeInbound treats the
		// block as optional, so a frame with or without it decodes identically.
		int size = 5 + 14 + 7;
		if (distribute) {
			size += 1 + 3 * action.slots().size();
		}

		ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
		buf.put(MAGIC_IN);
		buf.put(VERSION);
		buf.put((byte) move);
		buf.putInt(m.selectedSlot());
		buf.put((byte) act);
		buf.putFloat(m.yaw());
		buf.putFloat(m.pitch());

		buf.put((byte) op.wireId());
		putAddr(buf, action != null ? action.a() : null);
		putAddr(buf, action != null ? action.b() : null);
		if (distribute) {
			buf.put((byte) action.slots().size());
			for (SlotAddress s : action.slots()) {
				putAddr(buf, s);
			}
		}
		return buf.array();
	}

	/** Write a (groupOpcode u8, index i16) address; (0,0) for a null address, matching the decoder's defaults. */
	private static void putAddr(ByteBuffer buf, SlotAddress a) {
		if (a == null || a.group() == null) {
			buf.put((byte) 0);
			buf.putShort((short) 0);
		} else {
			buf.put((byte) a.group().wireId());
			buf.putShort((short) a.index());
		}
	}
}
