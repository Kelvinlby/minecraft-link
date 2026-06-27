package mod.kelvinlby.link;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Compact little-endian wire framing for the link's three message types. This stands in for ArrowIPC:
 * the payloads are a handful of scalars ({@code OCLO}/{@code OCLI}) plus the RGBD vision frame
 * ({@code OCLV}), for which the full Apache Arrow Java stack would be disproportionate overhead. All
 * methods are pure and run on the bridge worker threads, never on the tick thread.
 *
 * <h2>Outbound — Minecraft -&gt; controller ("OCLO")</h2>
 * Small per-tick control telemetry. Vision is <b>not</b> here — it has its own {@code OCLV} stream.
 * <pre>
 *   magic   : 4 bytes  "OCLO"
 *   version : u8       1
 *   yaw     : f32
 *   pitch   : f32
 *   slot    : i32                     (0..8)
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
 *
 * <h2>Vision — Minecraft -&gt; controller ("OCLV")</h2>
 * A separate, frame-rate stream on its own PUB socket (see {@link LinkConfig.Endpoints#visPub()}), so the
 * large RGBD payload conflates independently of the small {@code OCLO} control telemetry.
 * <pre>
 *   magic   : 4 bytes  "OCLV"
 *   version : u8       1
 *   vis_w   : i32                     downsampled frame width
 *   vis_h   : i32                     downsampled frame height
 *   near    : f32                     eye-space near plane (blocks)
 *   far     : f32                     eye-space far plane (blocks)
 *   rgb     : vis_w*vis_h*3 x f32     row-major, top-left origin, interleaved RGB, normalized 0..1
 *   depth   : vis_w*vis_h   x f32     row-major, top-left origin, distance normalized 0..1
 *                                     (= linear eye-space distance / far, clamped to [0,1])
 * </pre>
 */
public final class BinaryCodec {
	private BinaryCodec() {}

	static final byte VERSION = 1;

	// "OCLO" / "OCLI" / "OCLV" as big-endian-readable 4-byte tags (stored verbatim, order-independent).
	private static final byte[] MAGIC_OUT = {'O', 'C', 'L', 'O'};
	private static final byte[] MAGIC_IN = {'O', 'C', 'L', 'I'};
	private static final byte[] MAGIC_VIS = {'O', 'C', 'L', 'V'};

	/** Vision wire format version (independent of {@link #VERSION}). */
	static final byte VIS_VERSION = 1;

	// Movement bitmask layout.
	private static final int M_FRONT = 1, M_BACK = 1 << 1, M_LEFT = 1 << 2, M_RIGHT = 1 << 3,
			M_JUMP = 1 << 4, M_SPRINT = 1 << 5, M_SNEAK = 1 << 6;
	// Action bitmask layout.
	private static final int A_ATTACK = 1, A_INTERACT = 1 << 1;

	/** Header (magic + version) common to both directions. */
	private static final int HEADER = 4 + 1;

	/** Encode a telemetry snapshot as an {@code OCLO} message: header + yaw + pitch + slot. */
	public static byte[] encodeOutbound(OutboundSnapshot s) {
		int size = HEADER + 4 /* yaw */ + 4 /* pitch */ + 4 /* slot */;

		ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
		buf.put(MAGIC_OUT);
		buf.put(VERSION);
		buf.putFloat(s.yaw());
		buf.putFloat(s.pitch());
		buf.putInt(s.selectedSlot());
		return buf.array();
	}

	/**
	 * Encode a downsampled RGBD vision frame as an {@code OCLV} message. The RGB plane
	 * ({@code w*h*3} floats) is written first, then the depth plane ({@code w*h} floats); both are
	 * row-major, top-left origin, normalized 0..1. Pure; runs on the vision sender thread.
	 */
	public static byte[] encodeVision(VisionFrame f) {
		int w = f.width();
		int h = f.height();
		int rgbLen = w * h * 3;
		int depthLen = w * h;

		int size = HEADER
				+ 4 /* vis_w */ + 4 /* vis_h */
				+ 4 /* near */ + 4 /* far */
				+ 4 * rgbLen + 4 * depthLen;

		ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
		buf.put(MAGIC_VIS);
		buf.put(VIS_VERSION);
		buf.putInt(w);
		buf.putInt(h);
		buf.putFloat(f.near());
		buf.putFloat(f.far());

		float[] rgb = f.rgb();
		for (int i = 0; i < rgbLen; i++) {
			buf.putFloat(rgb[i]);
		}
		float[] depth = f.depth();
		for (int i = 0; i < depthLen; i++) {
			buf.putFloat(depth[i]);
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
