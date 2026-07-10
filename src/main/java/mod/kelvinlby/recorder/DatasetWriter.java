package mod.kelvinlby.recorder;

import mod.kelvinlby.OpenCrafterLink;
import mod.kelvinlby.link.InventoryAction;
import mod.kelvinlby.link.InventoryState;
import mod.kelvinlby.link.SlotAddress;
import mod.kelvinlby.link.SlotGroupState;
import mod.kelvinlby.link.SlotInfo;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferUShort;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Owns one recording session's on-disk output and writes each {@link Sample} to it. Called only from
 * the recorder's single writer thread (see {@link Recorder}), so no field here is touched concurrently
 * and the encoding runs off the sampler's clock.
 *
 * <p>Layout under {@code <gameDir>/open-crafter-link/<session>/}:
 * <ul>
 *   <li>{@code rgb.mp4} — H.264/H.265 encoded by a piped system {@code ffmpeg} (GPU when available;
 *       see {@link FfmpegEncoder}), one frame per sample, fps = the recorder's sample rate. The MP4
 *       is fragmented, so it survives a crash mid-session. When no usable ffmpeg exists the session
 *       records without video (the settings screen warns about this up front); {@code ffmpeg.log}
 *       holds the encoder's stderr.</li>
 *   <li>{@code depth.png.zip} — a ZIP holding one 16-bit grayscale PNG per sample, named
 *       {@code 000000.png}, {@code 000001.png}, … in sample order. Each pixel is the frame's
 *       normalized depth ({@code distance/far}, 0..1) quantized to uint16 via
 *       {@code round(clamp(depth,0,1) * 65535)} (the quantization happens at packing time — see
 *       {@link PackedFrame}); recover absolute blocks as {@code value/65535*far}. PNG's per-scanline
 *       filtering compresses the smooth depth image far better than raw float32, and 16 bits
 *       (≈ far/65536 resolution) is effectively lossless for the render. The manifest and each
 *       {@code actions.jsonl} line record width/height and near/far.</li>
 *   <li>{@code actions.jsonl} — one JSON object per line per sample: seqno, timestamp, every action
 *       field, the frame's near/far (so absolute depth is recoverable), and {@code frameRepeated}.</li>
 *   <li>{@code manifest.json} — session metadata + final counts, written on {@link #close(long, long, long)}.</li>
 * </ul>
 *
 * <p>The first sample fixes the frame dimensions (and spawns the video encoder); subsequent frames of
 * a different size (a resolution change mid-session) are skipped for video/depth but still logged,
 * keeping the streams rectangular. Odd dimensions are padded to even inside ffmpeg (H.264/5 require it).
 */
public final class DatasetWriter {

	private final Path dir;
	private final int fps;
	private final FfmpegEncoder.Settings video;
	private final long startEpochMs;

	private FfmpegEncoder rgbEncoder;
	/** Set once the first frame decided the video question, so we only attempt the spawn once. */
	private boolean videoDecided;
	private ZipOutputStream depthZip; // one 16-bit grayscale PNG per sample
	private Writer actionsOut;        // actions.jsonl

	/** Monotonic index for depth PNG entry names; only advanced for size-matching frames. */
	private int depthFrameIndex;

	/** Fixed once the first frame arrives; later frames of a different size are skipped for video/depth. */
	private int width = -1;
	private int height = -1;

	/** 16-bit grayscale image reused for depth PNG encoding, plus a direct handle to its backing array. */
	private BufferedImage depthImage;
	private short[] depthPixels;

	public DatasetWriter(Path dir, int fps, FfmpegEncoder.Settings video) {
		this.dir = dir;
		this.fps = Math.max(1, fps);
		this.video = video;
		this.startEpochMs = System.currentTimeMillis();
	}

	/** The session folder name (its timestamp), e.g. for the save toast. */
	public String sessionName() {
		return dir.getFileName().toString();
	}

	/** Create the session directory and open the output streams. Call once before {@link #write}. */
	public void open() throws IOException {
		Files.createDirectories(dir);
		// PNG is already DEFLATE-compressed internally, so store entries uncompressed (STORED) rather
		// than paying a second, futile DEFLATE pass over them.
		depthZip = new ZipOutputStream(
				new BufferedOutputStream(Files.newOutputStream(dir.resolve("depth.png.zip"))));
		depthZip.setMethod(ZipOutputStream.STORED);
		actionsOut = Files.newBufferedWriter(dir.resolve("actions.jsonl"), StandardCharsets.UTF_8);
		OpenCrafterLink.LOGGER.info("[open-crafter-link] recording to {}", dir);
	}

	/** Append one aligned sample. Video + depth are written only when the frame matches the fixed size. */
	public void write(Sample s) throws IOException {
		PackedFrame v = s.vision();
		if (width < 0) {
			width = v.width();
			height = v.height();
			allocScratch();
		}
		if (!videoDecided) {
			videoDecided = true;
			// Spawned here — on the writer thread, dimensions now known — so the binary/encoder probes
			// never stall the sampler clock or the game threads. Null (no ffmpeg / no usable encoder)
			// has already been logged and leaves a video-less session.
			rgbEncoder = FfmpegEncoder.open(dir.resolve("rgb.mp4"), dir.resolve("ffmpeg.log"),
					width, height, fps, video);
		}
		boolean sizeOk = v.width() == width && v.height() == height;
		if (sizeOk) {
			if (rgbEncoder != null) {
				rgbEncoder.writeFrame(v.rgb24());
			}
			writeDepth(v);
		}
		writeActionLine(s, sizeOk);
	}

	/**
	 * Append the frame's uint16 depth as a 16-bit grayscale PNG entry to {@code depth.png.zip}.
	 * Entries are {@code STORED} (PNG carries its own DEFLATE), so we encode the PNG into memory
	 * first to know its size + CRC before opening the ZIP entry.
	 */
	private void writeDepth(PackedFrame v) throws IOException {
		System.arraycopy(v.depth16(), 0, depthPixels, 0, depthPixels.length);
		ByteArrayOutputStream pngBytes = new ByteArrayOutputStream(width * height * 2);
		ImageIO.write(depthImage, "png", pngBytes);
		byte[] png = pngBytes.toByteArray();

		CRC32 crc = new CRC32();
		crc.update(png);
		ZipEntry entry = new ZipEntry(String.format("%06d.png", depthFrameIndex++));
		entry.setMethod(ZipEntry.STORED);
		entry.setSize(png.length);
		entry.setCompressedSize(png.length);
		entry.setCrc(crc.getValue());
		depthZip.putNextEntry(entry);
		depthZip.write(png);
		depthZip.closeEntry();
	}

	private void writeActionLine(Sample s, boolean sizeOk) throws IOException {
		ActionSet a = s.action();
		PackedFrame v = s.vision();
		// Compact hand-built JSON — no dependency needed and the fields are all scalar.
		StringBuilder sb = new StringBuilder(256);
		sb.append('{')
				.append("\"seqno\":").append(s.seqno())
				.append(",\"t_ns\":").append(s.timestampNs())
				.append(",\"front\":").append(a.front())
				.append(",\"back\":").append(a.back())
				.append(",\"left\":").append(a.left())
				.append(",\"right\":").append(a.right())
				.append(",\"jump\":").append(a.jump())
				.append(",\"sprint\":").append(a.sprint())
				.append(",\"sneak\":").append(a.sneak())
				.append(",\"attack\":").append(a.attack())
				.append(",\"interact\":").append(a.interact())
				.append(",\"slot\":").append(a.selectedSlot())
				.append(",\"yaw\":").append(fmt(a.yaw()))
				.append(",\"pitch\":").append(fmt(a.pitch()))
				.append(",\"health\":").append(fmt(a.health()))
				.append(",\"food\":").append(a.food())
				.append(",\"xp_level\":").append(a.xpLevel())
				.append(",\"near\":").append(fmt(v.near()))
				.append(",\"far\":").append(fmt(v.far()))
				.append(",\"frame_repeated\":").append(s.frameRepeated())
				.append(",\"frame_present\":").append(sizeOk)
				.append(",\"inventory\":");
		appendInventory(sb, a.inventoryActions());
		sb.append(",\"inventory_state\":");
		appendInventoryState(sb, s.inventory());
		sb.append('}').append('\n');
		actionsOut.write(sb.toString());
	}

	/**
	 * Append the full inventory snapshot as a JSON array of groups, each
	 * {@code {"group":"hotbar","registry_id":null,"slots":[{"item":"minecraft:dirt","count":3,"enabled":true}, …]}}.
	 * {@code registry_id} is the container id for the extension group and {@code null} otherwise; an empty slot is
	 * {@code {"item":null,"count":0,"enabled":true}}. Groups appear in canonical order and always include the
	 * virtual cursor/discard groups, matching {@link InventoryMapper#readInventory}.
	 */
	private static void appendInventoryState(StringBuilder sb, InventoryState state) {
		sb.append('[');
		List<SlotGroupState> groups = (state == null) ? List.of() : state.groups();
		for (int g = 0; g < groups.size(); g++) {
			if (g > 0) {
				sb.append(',');
			}
			SlotGroupState group = groups.get(g);
			sb.append("{\"group\":\"").append(group.group().name().toLowerCase(Locale.ROOT)).append('"')
					.append(",\"registry_id\":");
			appendJsonString(sb, group.registryId());
			sb.append(",\"slots\":[");
			List<SlotInfo> slots = group.slots();
			for (int i = 0; i < slots.size(); i++) {
				if (i > 0) {
					sb.append(',');
				}
				SlotInfo info = slots.get(i);
				sb.append("{\"item\":");
				appendJsonString(sb, info.item());
				sb.append(",\"count\":").append(info.count())
						.append(",\"enabled\":").append(info.enabled()).append('}');
			}
			sb.append("]}");
		}
		sb.append(']');
	}

	/** Append a JSON string literal (escaping {@code "} and {@code \}), or {@code null} for a null value. */
	private static void appendJsonString(StringBuilder sb, String value) {
		if (value == null) {
			sb.append("null");
			return;
		}
		sb.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == '"' || c == '\\') {
				sb.append('\\');
			}
			sb.append(c);
		}
		sb.append('"');
	}

	/** Append the sample's inventory actions as a JSON array (empty when there were none this period). */
	private static void appendInventory(StringBuilder sb, List<InventoryAction> actions) {
		sb.append('[');
		for (int i = 0; i < actions.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			appendAction(sb, actions.get(i));
		}
		sb.append(']');
	}

	/**
	 * Append one inventory action: its op plus the operands the op uses — {@code a} for the single/dual ops,
	 * {@code b} only for {@code SWAP}, {@code slots} only for {@code DISTRIBUTE}. Unused operands are
	 * {@code null}/{@code []} so every element has the same shape.
	 */
	private static void appendAction(StringBuilder sb, InventoryAction action) {
		sb.append("{\"op\":\"").append(action.op().name().toLowerCase(Locale.ROOT)).append('"')
				.append(",\"a\":");
		appendAddress(sb, action.a());
		sb.append(",\"b\":");
		appendAddress(sb, action.b());
		sb.append(",\"slots\":[");
		List<SlotAddress> slots = action.slots();
		for (int i = 0; i < slots.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			appendAddress(sb, slots.get(i));
		}
		sb.append("]}");
	}

	/** Append a slot address as {@code {"group":"hotbar","index":3}}, or {@code null} if absent. */
	private static void appendAddress(StringBuilder sb, SlotAddress addr) {
		if (addr == null) {
			sb.append("null");
			return;
		}
		sb.append("{\"group\":\"").append(addr.group().name().toLowerCase(Locale.ROOT)).append('"')
				.append(",\"index\":").append(addr.index()).append('}');
	}

	/**
	 * Finalize the session: flush and close all streams and write {@code manifest.json}. Safe to call
	 * once; best-effort on each stream so one failure doesn't leak the others.
	 *
	 * @param samples  total samples written
	 * @param dropped  samples the sampler dropped because the writer queue was full
	 * @param repeated samples whose frame was a repeat of the previous one
	 * @return null when everything closed cleanly, else the first failure (short, human-readable) —
	 *         surfaced to the player in the save toast
	 */
	public String close(long samples, long dropped, long repeated) {
		String error = null;
		if (rgbEncoder != null) {
			error = rgbEncoder.close(); // EOF on stdin makes ffmpeg finalize the MP4
		}
		error = firstError(error, closeQuietly(depthZip, "depth.png.zip"));
		error = firstError(error, closeQuietly(actionsOut, "actions.jsonl"));
		error = firstError(error, writeManifest(samples, dropped, repeated));
		return error;
	}

	private static String firstError(String current, String next) {
		return current != null ? current : next;
	}

	private String writeManifest(long samples, long dropped, long repeated) {
		boolean hasVideo = rgbEncoder != null;
		String codec = (video.codec() == FfmpegEncoder.Codec.H265) ? "hevc" : "h264";
		String json = "{\n"
				+ "  \"schema_version\": 5,\n"
				+ "  \"start_epoch_ms\": " + startEpochMs + ",\n"
				+ "  \"sample_hz\": " + fps + ",\n"
				+ "  \"width\": " + width + ",\n"
				+ "  \"height\": " + height + ",\n"
				+ "  \"rgb\": " + (hasVideo ? "\"rgb.mp4\"" : "null") + ",\n"
				+ "  \"rgb_codec\": " + (hasVideo ? "\"" + codec + "\"" : "null") + ",\n"
				+ "  \"rgb_encoder\": " + (hasVideo ? "\"" + rgbEncoder.encoderName() + "\"" : "null") + ",\n"
				+ "  \"depth\": \"depth.png.zip\",\n"
				+ "  \"depth_format\": \"zip of 16-bit grayscale PNG per frame (000000.png..), top-left origin, value = round(distance/far * 65535); recover blocks as value/65535*far\",\n"
				+ "  \"actions\": \"actions.jsonl\",\n"
				+ "  \"inventory_format\": \"each actions.jsonl line has an 'inventory' array of {op, a, b, slots}: op is move/pick/put/swap/drop/distribute/collect; a and b are {group, index} slot addresses (b only for swap, where a is the hotbar/off-hand slot and b the hovered slot); slots is the target list for distribute; unused operands are null/[]\",\n"
				+ "  \"inventory_state_format\": \"each actions.jsonl line has an 'inventory_state' array of groups {group, registry_id, slots[]}: group is hotbar/offhand/armor/inventory/cursor/discard or 'extension'; registry_id is the container id for the extension group (else null); each slot is {item, count, enabled} with item a registry id or null when empty. This is the observed screen contents that tick (matches the live link's outbound inventory)\",\n"
				+ "  \"samples\": " + samples + ",\n"
				+ "  \"dropped\": " + dropped + ",\n"
				+ "  \"repeated\": " + repeated + ",\n"
				+ "  \"inventory_actions_dropped\": " + InventoryActionTap.droppedCount() + "\n"
				+ "}\n";
		try {
			Files.writeString(dir.resolve("manifest.json"), json, StandardCharsets.UTF_8);
			return null;
		} catch (IOException e) {
			OpenCrafterLink.LOGGER.error("[open-crafter-link] failed to write manifest.json", e);
			return "failed to write manifest.json: " + e.getMessage();
		}
	}

	private void allocScratch() {
		depthImage = new BufferedImage(width, height, BufferedImage.TYPE_USHORT_GRAY);
		depthPixels = ((DataBufferUShort) depthImage.getRaster().getDataBuffer()).getData();
	}

	/** Format a float compactly, mapping the non-finite sentinels to JSON null. */
	private static String fmt(float f) {
		if (Float.isNaN(f) || Float.isInfinite(f)) {
			return "null";
		}
		return Float.toString(f);
	}

	private static String closeQuietly(java.io.Closeable c, String what) {
		if (c == null) {
			return null;
		}
		try {
			c.close();
			return null;
		} catch (IOException e) {
			OpenCrafterLink.LOGGER.error("[open-crafter-link] failed to close {}", what, e);
			return "failed to close " + what + ": " + e.getMessage();
		}
	}
}
