package mod.kelvinlby.link;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Compact little-endian wire framing for the link's vectors. This stands in for ArrowIPC: the
 * payloads here are a handful of scalars plus one (currently dummy) vision vector, for which the full
 * Apache Arrow Java stack would be disproportionate overhead. Both methods are pure and run on the
 * bridge worker threads, never on the tick thread.
 *
 * <h2>Outbound — Minecraft -&gt; controller ("OCLO")</h2>
 * <pre>
 *   magic   : 4 bytes  "OCLO"
 *   version : u8       1
 *   yaw     : f32
 *   pitch   : f32
 *   slot    : i32                     (0..8)
 *   vis_w   : i32
 *   vis_h   : i32
 *   vision  : vis_w*vis_h*3 x f32     row-major, interleaved RGB, normalized 0..1
 *                                     (empty when vision disabled -&gt; vis_w = vis_h = 0)
 * </pre>
 *
 * <h2>Inbound — controller -&gt; Minecraft ("OCLI")</h2>
 * <pre>
 *   magic   : 4 bytes  "OCLI"
 *   version : u8       1
 *   move    : u8 bitmask  bit0 front, 1 back, 2 left, 3 right, 4 jump, 5 sprint, 6 sneak
 *   slot    : i32         -1 = no change, else clamp 0..8
 *   action  : u8 bitmask  bit0 attack, bit1 interact
 *   yaw     : f32         NaN = no change
 *   pitch   : f32         NaN = no change
 * </pre>
 */
public final class BinaryCodec {
	private BinaryCodec() {}

	static final byte VERSION = 1;

	// "OCLO" / "OCLI" as big-endian-readable 4-byte tags (stored verbatim, order-independent).
	private static final byte[] MAGIC_OUT = {'O', 'C', 'L', 'O'};
	private static final byte[] MAGIC_IN = {'O', 'C', 'L', 'I'};

	// Movement bitmask layout.
	private static final int M_FRONT = 1, M_BACK = 1 << 1, M_LEFT = 1 << 2, M_RIGHT = 1 << 3,
			M_JUMP = 1 << 4, M_SPRINT = 1 << 5, M_SNEAK = 1 << 6;
	// Action bitmask layout.
	private static final int A_ATTACK = 1, A_INTERACT = 1 << 1;

	/** Header (magic + version) common to both directions. */
	private static final int HEADER = 4 + 1;

	/** Encode a telemetry snapshot. Vision pixels are written as-is, or synthesized as a dummy frame. */
	public static byte[] encodeOutbound(OutboundSnapshot s) {
		int w = s.visionWidth();
		int h = s.visionHeight();
		int pixels = Math.max(0, w * h * 3);

		float[] vision = s.visionRgb();
		if (vision == null && pixels > 0) {
			// Dummy frame: zeros. Real readback (see VisionCapture) fills this on the sender thread.
			vision = new float[pixels];
		}
		int visionLen = (vision == null) ? 0 : vision.length;

		int size = HEADER
				+ 4 /* yaw */ + 4 /* pitch */ + 4 /* slot */
				+ 4 /* vis_w */ + 4 /* vis_h */
				+ 4 * visionLen;

		ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
		buf.put(MAGIC_OUT);
		buf.put(VERSION);
		buf.putFloat(s.yaw());
		buf.putFloat(s.pitch());
		buf.putInt(s.selectedSlot());
		buf.putInt(w);
		buf.putInt(h);
		for (int i = 0; i < visionLen; i++) {
			buf.putFloat(vision[i]);
		}
		return buf.array();
	}

	/**
	 * Decode an instruction. Defensive: returns {@link InboundInstruction#NEUTRAL} on any malformed
	 * payload (bad magic/version/length) rather than throwing, so the receiver loop never dies.
	 */
	public static InboundInstruction decodeInbound(byte[] bytes) {
		if (bytes == null || bytes.length < HEADER + 1 + 4 + 1 + 4 + 4) {
			return InboundInstruction.NEUTRAL;
		}
		if (bytes[0] != MAGIC_IN[0] || bytes[1] != MAGIC_IN[1]
				|| bytes[2] != MAGIC_IN[2] || bytes[3] != MAGIC_IN[3]
				|| bytes[4] != VERSION) {
			return InboundInstruction.NEUTRAL;
		}

		ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		buf.position(HEADER);

		int move = buf.get() & 0xFF;
		int slot = buf.getInt();
		int action = buf.get() & 0xFF;
		float yaw = buf.getFloat();
		float pitch = buf.getFloat();

		return new InboundInstruction(
				(move & M_FRONT) != 0,
				(move & M_BACK) != 0,
				(move & M_LEFT) != 0,
				(move & M_RIGHT) != 0,
				(move & M_JUMP) != 0,
				(move & M_SPRINT) != 0,
				(move & M_SNEAK) != 0,
				slot,
				(action & A_ATTACK) != 0,
				(action & A_INTERACT) != 0,
				yaw,
				pitch);
	}
}
