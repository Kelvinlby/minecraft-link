package mod.kelvinlby.recorder;

import mod.kelvinlby.OpenCrafterLink;
import mod.kelvinlby.link.VisionFrame;
import org.jcodec.api.awt.AWTSequenceEncoder;

import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Owns one recording session's on-disk output and writes each {@link Sample} to it. Called only from
 * the recorder's single writer thread (see {@link Recorder}), so no field here is touched concurrently
 * and the heavy H.264 encode runs off the sampler's clock.
 *
 * <p>Layout under {@code <gameDir>/open-crafter-link/<session>/}:
 * <ul>
 *   <li>{@code rgb.mp4} — H.264 (JCodec), one frame per sample, fps = the recorder's sample rate.
 *       RGB comes from {@link VisionFrame#rgb()} (float 0..1, top-left origin).</li>
 *   <li>{@code depth.f32.zz} — a single zlib ({@link Deflater}) stream of the raw float32 depth planes
 *       concatenated, {@code width*height} little-endian floats per sample, in sample order. Lossless
 *       (no quantization). The header line of {@code actions.jsonl}'s manifest records width/height.</li>
 *   <li>{@code actions.jsonl} — one JSON object per line per sample: seqno, timestamp, every action
 *       field, the frame's near/far (so absolute depth is recoverable), and {@code frameRepeated}.</li>
 *   <li>{@code manifest.json} — session metadata + final counts, written on {@link #close(long, long, long)}.</li>
 * </ul>
 *
 * <p>The first sample fixes the frame dimensions; subsequent frames of a different size (a resolution
 * change mid-session) are skipped for video/depth but still logged, keeping the streams rectangular.
 * H.264 requires even dimensions, so odd width/height are padded by one pixel in the video only.
 */
public final class DatasetWriter {

	private final Path dir;
	private final int fps;
	private final long startEpochMs;

	private AWTSequenceEncoder rgbEncoder;
	private OutputStream depthOut;   // DeflaterOutputStream over a buffered file stream
	private Writer actionsOut;       // actions.jsonl

	/** Fixed once the first frame arrives; later frames of a different size are skipped for video/depth. */
	private int width = -1;
	private int height = -1;

	/** Reused per-frame scratch so we don't reallocate the BufferedImage/byte[] every sample. */
	private BufferedImage frameImage;
	private int[] pixelScratch;
	private byte[] depthScratch;

	public DatasetWriter(Path dir, int fps) {
		this.dir = dir;
		this.fps = Math.max(1, fps);
		this.startEpochMs = System.currentTimeMillis();
	}

	/** Create the session directory and open the three output streams. Call once before {@link #write}. */
	public void open() throws IOException {
		Files.createDirectories(dir);
		rgbEncoder = AWTSequenceEncoder.createSequenceEncoder(dir.resolve("rgb.mp4").toFile(), fps);
		depthOut = new DeflaterOutputStream(
				new BufferedOutputStream(Files.newOutputStream(dir.resolve("depth.f32.zz"))),
				new Deflater(Deflater.DEFAULT_COMPRESSION), 64 * 1024);
		actionsOut = Files.newBufferedWriter(dir.resolve("actions.jsonl"), StandardCharsets.UTF_8);
		OpenCrafterLink.LOGGER.info("[open-crafter-link] recording to {}", dir);
	}

	/** Append one aligned sample. Video + depth are written only when the frame matches the fixed size. */
	public void write(Sample s) throws IOException {
		VisionFrame v = s.vision();
		if (width < 0) {
			width = v.width();
			height = v.height();
			allocScratch();
		}
		boolean sizeOk = v.width() == width && v.height() == height;
		if (sizeOk) {
			encodeRgb(v);
			writeDepth(v);
		}
		writeActionLine(s, sizeOk);
	}

	private void encodeRgb(VisionFrame v) throws IOException {
		float[] rgb = v.rgb();
		int[] px = pixelScratch;
		for (int i = 0, p = 0; i < px.length; i++, p += 3) {
			int r = clamp8(rgb[p]);
			int g = clamp8(rgb[p + 1]);
			int b = clamp8(rgb[p + 2]);
			px[i] = (r << 16) | (g << 8) | b;
		}
		frameImage.setRGB(0, 0, width, height, px, 0, width);
		rgbEncoder.encodeImage(frameImage);
	}

	/** Write the frame's depth plane as {@code width*height} little-endian float32 into the deflate stream. */
	private void writeDepth(VisionFrame v) throws IOException {
		float[] depth = v.depth();
		ByteBuffer bb = ByteBuffer.wrap(depthScratch).order(ByteOrder.LITTLE_ENDIAN);
		for (float d : depth) {
			bb.putFloat(d);
		}
		depthOut.write(depthScratch, 0, depth.length * 4);
	}

	private void writeActionLine(Sample s, boolean sizeOk) throws IOException {
		ActionSet a = s.action();
		VisionFrame v = s.vision();
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
				.append(",\"near\":").append(fmt(v.near()))
				.append(",\"far\":").append(fmt(v.far()))
				.append(",\"frame_repeated\":").append(s.frameRepeated())
				.append(",\"frame_present\":").append(sizeOk)
				.append('}')
				.append('\n');
		actionsOut.write(sb.toString());
	}

	/**
	 * Finalize the session: flush and close all streams and write {@code manifest.json}. Safe to call
	 * once; best-effort on each stream so one failure doesn't leak the others.
	 *
	 * @param samples  total samples written
	 * @param dropped  samples the sampler dropped because the writer queue was full
	 * @param repeated samples whose frame was a repeat of the previous one
	 */
	public void close(long samples, long dropped, long repeated) {
		try {
			if (rgbEncoder != null) {
				rgbEncoder.finish(); // flushes the moov atom — the mp4 is unplayable without this
			}
		} catch (IOException | RuntimeException e) {
			OpenCrafterLink.LOGGER.error("[open-crafter-link] failed to finalize rgb.mp4", e);
		}
		closeQuietly(depthOut, "depth.f32.zz");
		closeQuietly(actionsOut, "actions.jsonl");
		writeManifest(samples, dropped, repeated);
	}

	private void writeManifest(long samples, long dropped, long repeated) {
		String json = "{\n"
				+ "  \"schema_version\": 1,\n"
				+ "  \"start_epoch_ms\": " + startEpochMs + ",\n"
				+ "  \"sample_hz\": " + fps + ",\n"
				+ "  \"width\": " + width + ",\n"
				+ "  \"height\": " + height + ",\n"
				+ "  \"rgb\": \"rgb.mp4\",\n"
				+ "  \"rgb_codec\": \"h264\",\n"
				+ "  \"depth\": \"depth.f32.zz\",\n"
				+ "  \"depth_format\": \"deflate(float32-le, width*height per frame, top-left origin, normalized 0..1 = distance/far)\",\n"
				+ "  \"actions\": \"actions.jsonl\",\n"
				+ "  \"samples\": " + samples + ",\n"
				+ "  \"dropped\": " + dropped + ",\n"
				+ "  \"repeated\": " + repeated + "\n"
				+ "}\n";
		try {
			Files.writeString(dir.resolve("manifest.json"), json, StandardCharsets.UTF_8);
		} catch (IOException e) {
			OpenCrafterLink.LOGGER.error("[open-crafter-link] failed to write manifest.json", e);
		}
	}

	private void allocScratch() {
		frameImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		pixelScratch = new int[width * height];
		depthScratch = new byte[width * height * 4];
	}

	private static int clamp8(float f) {
		int v = Math.round(f * 255.0f);
		return v < 0 ? 0 : (Math.min(v, 255));
	}

	/** Format a float compactly, mapping the non-finite sentinels to JSON null. */
	private static String fmt(float f) {
		if (Float.isNaN(f) || Float.isInfinite(f)) {
			return "null";
		}
		return Float.toString(f);
	}

	private static void closeQuietly(java.io.Closeable c, String what) {
		if (c == null) {
			return;
		}
		try {
			c.close();
		} catch (IOException e) {
			OpenCrafterLink.LOGGER.error("[open-crafter-link] failed to close {}", what, e);
		}
	}
}
