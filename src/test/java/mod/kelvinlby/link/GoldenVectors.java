package mod.kelvinlby.link;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared loader that turns {@code protocol/golden/cases.json} into the codec's fixture objects, so the
 * golden generator ({@link GenGolden}) and the golden test ({@code BinaryCodecGoldenTest}) build the
 * exact same inputs from the one authority. {@code cases.json} is the single source of the fixture
 * <i>values</i>; the {@code .bin} files it references are the byte output the {@link BinaryCodec}
 * reference encoder produced from them (regenerate with {@code ./gradlew genGolden}).
 *
 * <p>Floats in the JSON use exact float32-representable values so the encoded bytes are stable and can be
 * byte-compared against the Python side. Yaw/pitch may be the string {@code "NaN"} (no-change sentinel).
 */
public final class GoldenVectors {
	private GoldenVectors() {}

	private static final Gson GSON = new Gson();

	/** One OCLO fixture: its name, the .bin filename, and the snapshot to encode. */
	public record OcloCase(String name, String bin, OutboundSnapshot snapshot) {}

	/** One OCLI fixture: its name, the .bin filename, and both decoded parts to encode/expect. */
	public record OcliCase(String name, String bin, InboundInstruction movement, InventoryAction action) {}

	/** One OCLV fixture: its name, the .bin filename, and the frame to encode. */
	public record OclvCase(String name, String bin, VisionFrame frame) {}

	public record Cases(List<OcloCase> oclo, List<OcliCase> ocli, List<OclvCase> oclv) {}

	/** Parse {@code cases.json} at the given path into typed fixtures. */
	public static Cases load(Path casesJson) throws IOException {
		try (Reader r = Files.newBufferedReader(casesJson)) {
			JsonObject root = GSON.fromJson(r, JsonObject.class);
			return new Cases(
					parseOclo(root.getAsJsonArray("oclo")),
					parseOcli(root.getAsJsonArray("ocli")),
					parseOclv(root.getAsJsonArray("oclv")));
		}
	}

	private static List<OcloCase> parseOclo(JsonArray arr) {
		List<OcloCase> out = new ArrayList<>();
		for (JsonElement el : arr) {
			JsonObject o = el.getAsJsonObject();
			List<SlotGroupState> groups = new ArrayList<>();
			for (JsonElement ge : o.getAsJsonArray("inventory")) {
				JsonObject g = ge.getAsJsonObject();
				SlotGroup group = SlotGroup.valueOf(g.get("group").getAsString());
				String registryId = g.get("registry_id").getAsString();
				List<SlotInfo> slots = new ArrayList<>();
				for (JsonElement se : g.getAsJsonArray("slots")) {
					JsonObject s = se.getAsJsonObject();
					String item = s.get("item").isJsonNull() ? null : s.get("item").getAsString();
					slots.add(new SlotInfo(item, s.get("count").getAsInt(), s.get("enabled").getAsBoolean()));
				}
				groups.add(new SlotGroupState(group, registryId, slots));
			}
			OutboundSnapshot snap = new OutboundSnapshot(
					o.get("yaw").getAsFloat(), o.get("pitch").getAsFloat(), o.get("slot").getAsInt(),
					o.get("health").getAsFloat(), o.get("food").getAsInt(), o.get("xp_level").getAsInt(),
					new InventoryState(groups));
			out.add(new OcloCase(o.get("name").getAsString(), o.get("bin").getAsString(), snap));
		}
		return out;
	}

	private static List<OcliCase> parseOcli(JsonArray arr) {
		List<OcliCase> out = new ArrayList<>();
		for (JsonElement el : arr) {
			JsonObject o = el.getAsJsonObject();
			InboundInstruction move = new InboundInstruction(
					o.get("front").getAsBoolean(), o.get("back").getAsBoolean(),
					o.get("left").getAsBoolean(), o.get("right").getAsBoolean(),
					o.get("jump").getAsBoolean(), o.get("sprint").getAsBoolean(), o.get("sneak").getAsBoolean(),
					o.get("slot").getAsInt(), o.get("attack").getAsBoolean(), o.get("interact").getAsBoolean(),
					readFloat(o.get("yaw")), readFloat(o.get("pitch")));
			InventoryAction.Op op = InventoryAction.Op.valueOf(o.get("op").getAsString());
			InventoryAction action = InventoryAction.NONE;
			if (op != InventoryAction.Op.NONE) {
				SlotAddress a = readAddr(o.get("a"));
				SlotAddress b = readAddr(o.get("b"));
				List<SlotAddress> slots = new ArrayList<>();
				for (JsonElement se : o.getAsJsonArray("slots")) {
					slots.add(readAddr(se));
				}
				action = new InventoryAction(op, a, b, slots);
			}
			out.add(new OcliCase(o.get("name").getAsString(), o.get("bin").getAsString(), move, action));
		}
		return out;
	}

	private static List<OclvCase> parseOclv(JsonArray arr) {
		List<OclvCase> out = new ArrayList<>();
		for (JsonElement el : arr) {
			JsonObject o = el.getAsJsonObject();
			int w = o.get("w").getAsInt();
			int h = o.get("h").getAsInt();
			float[] rgb = readFloatArray(o.getAsJsonArray("rgb"));
			float[] depth = readFloatArray(o.getAsJsonArray("depth"));
			VisionFrame frame = new VisionFrame(w, h, o.get("near").getAsFloat(), o.get("far").getAsFloat(), rgb, depth);
			out.add(new OclvCase(o.get("name").getAsString(), o.get("bin").getAsString(), frame));
		}
		return out;
	}

	/** A JSON number or the string {@code "NaN"} → float (NaN is the no-change sentinel). */
	private static float readFloat(JsonElement el) {
		if (el.getAsJsonPrimitive().isString()) {
			return Float.NaN; // only "NaN" is used
		}
		return el.getAsFloat();
	}

	/** A {group,index} object or null → SlotAddress (null when the JSON value is null). */
	private static SlotAddress readAddr(JsonElement el) {
		if (el == null || el.isJsonNull()) {
			return null;
		}
		JsonObject o = el.getAsJsonObject();
		return new SlotAddress(SlotGroup.valueOf(o.get("group").getAsString()), o.get("index").getAsInt());
	}

	private static float[] readFloatArray(JsonArray arr) {
		float[] out = new float[arr.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = arr.get(i).getAsFloat();
		}
		return out;
	}
}
