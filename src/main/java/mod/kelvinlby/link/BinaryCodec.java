package mod.kelvinlby.link;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Compact little-endian wire framing for the link's three message types. This stands in for ArrowIPC:
 * the payloads are a handful of scalars ({@code OCLO}/{@code OCLI}) plus the RGBD vision frame
 * ({@code OCLV}), for which the full Apache Arrow Java stack would be disproportionate overhead. All
 * methods are pure and run on the bridge worker threads, never on the tick thread.
 *
 * <h2>Outbound — Minecraft -&gt; controller ("OCLO")</h2>
 * Small per-tick control telemetry followed by the current screen's inventory. Vision is <b>not</b> here
 * — it has its own {@code OCLV} stream.
 * <pre>
 *   magic   : 4 bytes  "OCLO"
 *   version : u8       2
 *   yaw     : f32
 *   pitch   : f32
 *   slot    : i32                     (0..8)
 *   health  : f32                     half-heart points (0..20)
 *   food    : i32                     hunger/food level (0..20)
 *   xpLevel : i32                     experience level
 *   -- inventory section (variable length; self-delimiting) --
 *   groupCount : u8
 *   per group:
 *     groupOpcode : u8               (SlotGroup.wireId)
 *     nameLen     : u16              UTF-8 byte length of the extension registry id; 0 for other groups
 *     nameBytes   : nameLen bytes    (present only when nameLen&gt;0)
 *     slotCount   : u16
 *     per slot:
 *       itemLen : u16                UTF-8 byte length of the item id; 0 = empty/null item
 *       itemBytes : itemLen bytes    ("minecraft:xxx"; present only when itemLen&gt;0)
 *       count   : i16
 *       flags   : u8                 bit0 = enabled
 * </pre>
 *
 * <h2>Inbound — controller -&gt; Minecraft ("OCLI")</h2>
 * Movement (a held level, conflated at the inbox) plus an optional discrete inventory action (an edge
 * event, routed to a non-conflating queue by the bridge).
 * <pre>
 *   magic   : 4 bytes  "OCLI"
 *   version : u8       2
 *   move    : u8 bitmask  bit0 front, 1 back, 2 left, 3 right, 4 jump, 5 sprint, 6 sneak
 *   slot    : i32         -1 = no change, else clamp 0..8
 *   action  : u8 bitmask  bit0 attack, bit1 interact
 *   yaw     : f32         NaN = no change
 *   pitch   : f32         NaN = no change
 *   -- inventory action (fixed 7 bytes) --
 *   invOp   : u8          0 NONE, 1 MOVE, 2 PICK, 3 PUT, 4 SWAP, 5 DROP, 6 DISTRIBUTE, 7 COLLECT
 *   aGroup  : u8          SlotGroup.wireId of operand a
 *   aIndex  : i16
 *   bGroup  : u8          SWAP only (else 0)
 *   bIndex  : i16         SWAP only (else 0)
 *   -- variable slot list (present only when invOp == DISTRIBUTE) --
 *   slotCount : u8
 *   per slot:
 *     group : u8          SlotGroup.wireId
 *     index : i16
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

	static final byte VERSION = 2;

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

	/**
	 * Encode a telemetry snapshot as an {@code OCLO} message: the fixed control prefix (header + yaw +
	 * pitch + slot + health + food + xpLevel) followed by the variable-length inventory section. Sized in
	 * one measuring pass so a single {@link ByteBuffer} suffices.
	 */
	public static byte[] encodeOutbound(OutboundSnapshot s) {
		InventoryState inv = (s.inventory() != null) ? s.inventory() : InventoryState.EMPTY;

		// Pre-encode strings once (also gives us their byte lengths for sizing) to avoid encoding twice.
		int prefix = HEADER + 4 /* yaw */ + 4 /* pitch */ + 4 /* slot */
				+ 4 /* health */ + 4 /* food */ + 4 /* xpLevel */;
		int size = prefix + 1 /* groupCount */;
		List<byte[]> nameBytes = new ArrayList<>();
		List<byte[]> itemBytes = new ArrayList<>();
		for (SlotGroupState group : inv.groups()) {
			byte[] name = utf8(group.registryId());
			nameBytes.add(name);
			size += 1 /* opcode */ + 2 /* nameLen */ + name.length + 2 /* slotCount */;
			for (SlotInfo slot : group.slots()) {
				byte[] item = utf8(slot.item());
				itemBytes.add(item);
				size += 2 /* itemLen */ + item.length + 2 /* count */ + 1 /* flags */;
			}
		}

		ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
		buf.put(MAGIC_OUT);
		buf.put(VERSION);
		buf.putFloat(s.yaw());
		buf.putFloat(s.pitch());
		buf.putInt(s.selectedSlot());
		buf.putFloat(s.health());
		buf.putInt(s.food());
		buf.putInt(s.xpLevel());

		buf.put((byte) inv.groups().size());
		int nameIdx = 0;
		int itemIdx = 0;
		for (SlotGroupState group : inv.groups()) {
			byte[] name = nameBytes.get(nameIdx++);
			buf.put((byte) group.group().wireId());
			buf.putShort((short) name.length);
			buf.put(name);
			buf.putShort((short) group.slots().size());
			for (SlotInfo slot : group.slots()) {
				byte[] item = itemBytes.get(itemIdx++);
				buf.putShort((short) item.length);
				buf.put(item);
				buf.putShort((short) slot.count());
				buf.put((byte) (slot.enabled() ? 1 : 0));
			}
		}
		return buf.array();
	}

	/** UTF-8 bytes for a possibly-null string; empty array for null (encodes as a zero length prefix). */
	private static byte[] utf8(String s) {
		return (s == null) ? EMPTY_BYTES : s.getBytes(StandardCharsets.UTF_8);
	}

	private static final byte[] EMPTY_BYTES = new byte[0];

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
	 * A decoded OCLI frame's two independent parts: the movement level (conflated by the bridge) and the
	 * discrete inventory action (routed to the bridge's non-conflating action queue). {@link #NEUTRAL}
	 * pairs the neutral movement with {@link InventoryAction#NONE}.
	 */
	public record InboundMessage(InboundInstruction movement, InventoryAction action) {
		public static final InboundMessage NEUTRAL =
				new InboundMessage(InboundInstruction.NEUTRAL, InventoryAction.NONE);
	}

	/** Fixed movement payload size after the header: move(1)+slot(4)+action(1)+yaw(4)+pitch(4). */
	private static final int MOVE_PAYLOAD = 1 + 4 + 1 + 4 + 4;
	/** Fixed inventory-action payload size: invOp(1)+aGroup(1)+aIndex(2)+bGroup(1)+bIndex(2). */
	private static final int ACTION_PAYLOAD = 1 + 1 + 2 + 1 + 2;

	/**
	 * Decode an OCLI frame into its movement and inventory-action parts. Defensive: returns
	 * {@link InboundMessage#NEUTRAL} on any malformed payload (bad magic/version/length) rather than
	 * throwing, so the receiver loop never dies. A frame that carries only the movement payload (no action
	 * suffix) decodes with {@link InventoryAction#NONE}.
	 */
	public static InboundMessage decodeInbound(byte[] bytes) {
		if (bytes == null || bytes.length < HEADER + MOVE_PAYLOAD) {
			return InboundMessage.NEUTRAL;
		}
		if (bytes[0] != MAGIC_IN[0] || bytes[1] != MAGIC_IN[1]
				|| bytes[2] != MAGIC_IN[2] || bytes[3] != MAGIC_IN[3]
				|| bytes[4] != VERSION) {
			return InboundMessage.NEUTRAL;
		}

		ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		buf.position(HEADER);

		int move = buf.get() & 0xFF;
		int slot = buf.getInt();
		int action = buf.get() & 0xFF;
		float yaw = buf.getFloat();
		float pitch = buf.getFloat();

		InboundInstruction movement = new InboundInstruction(
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

		InventoryAction invAction = InventoryAction.NONE;
		if (bytes.length >= HEADER + MOVE_PAYLOAD + ACTION_PAYLOAD) {
			InventoryAction.Op op = InventoryAction.Op.fromWireId(buf.get() & 0xFF);
			SlotAddress a = readAddress(buf);
			SlotAddress b = readAddress(buf);
			List<SlotAddress> slots = List.of();
			if (op == InventoryAction.Op.DISTRIBUTE && buf.remaining() >= 1) {
				int slotCount = buf.get() & 0xFF;
				// Each entry is 3 bytes (group u8 + index i16); stop early if the frame is truncated.
				int available = buf.remaining() / 3;
				int n = Math.min(slotCount, available);
				List<SlotAddress> list = new ArrayList<>(n);
				for (int i = 0; i < n; i++) {
					list.add(readAddress(buf));
				}
				slots = list;
			}
			if (op != InventoryAction.Op.NONE) {
				invAction = new InventoryAction(op, a, b, slots);
			}
		}
		return new InboundMessage(movement, invAction);
	}

	/** Read a (groupOpcode u8, index i16) slot address; group null on an unknown opcode (defensive). */
	private static SlotAddress readAddress(ByteBuffer buf) {
		SlotGroup group = SlotGroup.fromWireId(buf.get() & 0xFF);
		int index = buf.getShort();
		return new SlotAddress(group, index);
	}
}
