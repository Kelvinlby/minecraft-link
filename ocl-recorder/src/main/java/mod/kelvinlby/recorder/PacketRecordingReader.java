package mod.kelvinlby.recorder;

import com.github.luben.zstd.Zstd;
import com.google.gson.Gson;
import mod.kelvinlby.OpenCrafterLink;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Streaming reader for the {@code MCREC1} segment format written by the companion recorder plugin. */
public final class PacketRecordingReader implements Closeable {
	private static final byte[] MAGIC = "MCREC1\n".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] TRAILER = "MCRECEND".getBytes(StandardCharsets.US_ASCII);
	private static final int BLOCK = 0xB1;
	private static final int END = 0xEE;
	private static final int MAX_BLOCK = 256 * 1024 * 1024;
	private static final int TAR_BLOCK = 512;
	private static final int MAX_INDEX = 64 * 1024 * 1024;
	private static final Gson GSON = new Gson();

	public enum Direction { S2C, C2S }
	public enum Phase { HANDSHAKING, STATUS, LOGIN, CONFIGURATION, PLAY }
	public record Rec(Direction direction, Phase phase, boolean gap, long timeMicros, byte[] data) {}

	/** Only fields replay needs; unknown/new header keys are deliberately ignored by Gson. */
	public static final class Header {
		public int formatVersion;
		public int protocolVersion;
		public String minecraftVersion;
		public String playerName;
		public int codecId;
		public Map<String, Map<String, String>> protocolTable;
	}

	private final Path source;
	private final List<SegmentSource> segments;
	private int segmentIndex;
	private Segment current;
	private Header header;
	private final long estimatedEndMicros;
	/** Per-phase S2C {@code DISCONNECT} ids from the recorded protocol table; built on first use. */
	private EnumMap<Phase, Integer> disconnectIds;

	private PacketRecordingReader(Path source, PreparedSource prepared) throws IOException {
		this.source = source;
		this.segments = prepared.segments;
		this.estimatedEndMicros = prepared.estimatedEndMicros;
		advanceSegment();
		if (current == null) throw new IOException("recording has no readable segments: " + source);
		this.header = current.header;
		if (header.formatVersion > 1) throw new IOException("unsupported packet recording format " + header.formatVersion);
	}

	/** Find the oldest pending session (tar, legacy directory, or standalone segment) outside done/. */
	public static PacketRecordingReader openNext(Path inbox) throws IOException {
		List<Path> candidates = candidates(inbox);
		IOException last = null;
		for (Path candidate : candidates) {
			try {
				PreparedSource prepared = prepare(candidate);
				if (!prepared.segments.isEmpty()) return new PacketRecordingReader(candidate, prepared);
			} catch (IOException e) {
				last = e;
				OpenCrafterLink.LOGGER.warn("[open-crafter-link] skipping unreadable packet recording {}", candidate, e);
			}
		}
		if (last != null && candidates.size() == 1) throw last;
		return null;
	}

	/** Whether the replay inbox contains an unprocessed session, without opening or consuming it. */
	public static boolean hasPending(Path inbox) throws IOException {
		for (Path candidate : candidates(inbox)) {
			try {
				if (!prepare(candidate).segments.isEmpty()) return true;
			} catch (IOException e) {
				// It is still an unprocessed replay; let openNext report the precise read error.
				return true;
			}
		}
		return false;
	}

	private static List<Path> candidates(Path inbox) throws IOException {
		Files.createDirectories(inbox);
		try (var stream = Files.list(inbox)) {
			return stream.filter(p -> !p.getFileName().toString().equals("done"))
					.filter(p -> Files.isDirectory(p) || p.getFileName().toString().endsWith(".mcrec")
							|| p.getFileName().toString().endsWith(".tar"))
					.sorted(Comparator.comparingLong(PacketRecordingReader::modified).thenComparing(Path::toString))
					.toList();
		}
	}

	private static long modified(Path p) {
		try { return Files.getLastModifiedTime(p).toMillis(); }
		catch (IOException ignored) { return Long.MAX_VALUE; }
	}

	private static PreparedSource prepare(Path source) throws IOException {
		if (source.getFileName().toString().endsWith(".tar")) return prepareTar(source);
		if (!Files.isDirectory(source)) return new PreparedSource(List.of(fileSource(source)), -1L);
		Path index = source.resolve("session.json");
		if (Files.isRegularFile(index)) {
			Index parsed = GSON.fromJson(Files.readString(index), Index.class);
			if (parsed != null && parsed.segments != null) {
				List<SegmentSource> result = new ArrayList<>();
				for (IndexSegment segment : parsed.segments) {
					Path file = source.resolve(segment.file).normalize();
					if (!file.getParent().equals(source.normalize())) throw new IOException("segment escapes session folder");
					if (Files.isRegularFile(file)) result.add(fileSource(file));
				}
				if (!result.isEmpty()) return new PreparedSource(result, estimateEndMicros(parsed));
			}
		}
		try (var stream = Files.list(source)) {
			List<SegmentSource> result = stream.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().endsWith(".mcrec"))
					.sorted(Comparator.comparing(p -> p.getFileName().toString()))
					.map(PacketRecordingReader::fileSource).toList();
			return new PreparedSource(result, -1L);
		}
	}

	private static PreparedSource prepareTar(Path archive) throws IOException {
		List<TarMember> members = listTar(archive);
		Map<String, TarMember> byName = new HashMap<>();
		for (TarMember member : members) {
			if (byName.put(member.name, member) != null)
				throw new IOException("duplicate tar member " + member.name + " in " + archive);
		}
		TarMember indexMember = byName.get("session.json");
		if (indexMember == null) throw new IOException("tar has no session.json: " + archive);
		if (indexMember.size > MAX_INDEX) throw new IOException("session.json is too large in " + archive);
		Index index;
		try (InputStream in = openTarMember(archive, indexMember)) {
			byte[] json = in.readNBytes((int) indexMember.size);
			if (json.length != indexMember.size) throw new EOFException("truncated session.json in " + archive);
			try { index = GSON.fromJson(new String(json, StandardCharsets.UTF_8), Index.class); }
			catch (RuntimeException e) { throw new IOException("invalid session.json in " + archive, e); }
		}
		if (index == null || index.segments == null) throw new IOException("invalid session.json in " + archive);
		List<SegmentSource> result = new ArrayList<>();
		for (IndexSegment segment : index.segments) {
			if (!isSimpleName(segment.file)) throw new IOException("invalid segment name in " + archive);
			TarMember member = byName.get(segment.file);
			if (member == null) throw new IOException("tar is missing indexed segment " + segment.file + ": " + archive);
			result.add(tarSource(archive, member));
		}
		return new PreparedSource(result, estimateEndMicros(index));
	}

	private static boolean isSimpleName(String name) {
		return name != null && !name.isEmpty() && !name.contains("/") && !name.contains("\\")
				&& !name.equals(".") && !name.equals("..");
	}

	private static SegmentSource fileSource(Path file) {
		return new SegmentSource(file.toString(), () -> Files.newInputStream(file));
	}

	private static SegmentSource tarSource(Path archive, TarMember member) {
		return new SegmentSource(archive + "!" + member.name, () -> openTarMember(archive, member));
	}

	@FunctionalInterface private interface StreamOpener { InputStream open() throws IOException; }
	private record SegmentSource(String name, StreamOpener opener) {}
	private record PreparedSource(List<SegmentSource> segments, long estimatedEndMicros) {}
	private record TarMember(String name, long offset, long size) {}

	private static final class Index { List<IndexSegment> segments; }
	private static final class IndexSegment { String file; long lastMicros; }

	private static long estimateEndMicros(Index index) {
		long end = -1L;
		if (index != null && index.segments != null) {
			for (IndexSegment segment : index.segments) end = Math.max(end, segment.lastMicros);
		}
		return end;
	}

	/** Read the regular-file members emitted by the recorder's small ustar writer. */
	private static List<TarMember> listTar(Path archive) throws IOException {
		List<TarMember> members = new ArrayList<>();
		try (SeekableByteChannel channel = Files.newByteChannel(archive, StandardOpenOption.READ)) {
			long archiveSize = channel.size();
			ByteBuffer header = ByteBuffer.allocate(TAR_BLOCK);
			for (long position = 0; position + TAR_BLOCK <= archiveSize;) {
				header.clear();
				channel.position(position);
				if (!readFully(channel, header)) break;
				byte[] bytes = header.array();
				if (zeroBlock(bytes)) break;
				String name = cString(bytes, 0, 100);
				if (name.isEmpty()) throw new IOException("tar member has no name in " + archive);
				long size = parseTarOctal(bytes, 124, 12, archive);
				long offset = position + TAR_BLOCK;
				if (size < 0 || size > archiveSize - offset)
					throw new IOException("truncated tar member " + name + " in " + archive);
				byte type = bytes[156];
				if (type == 0 || type == '0') members.add(new TarMember(name, offset, size));
				long blocks = (size + TAR_BLOCK - 1) / TAR_BLOCK;
				if (blocks > (Long.MAX_VALUE - offset) / TAR_BLOCK)
					throw new IOException("implausible tar member size in " + archive);
				position = offset + blocks * TAR_BLOCK;
			}
		}
		return members;
	}

	private static InputStream openTarMember(Path archive, TarMember member) throws IOException {
		SeekableByteChannel channel = Files.newByteChannel(archive, StandardOpenOption.READ);
		try {
			channel.position(member.offset);
			return new BoundedInputStream(Channels.newInputStream(channel), member.size);
		} catch (IOException | RuntimeException e) {
			channel.close();
			throw e;
		}
	}

	private static boolean readFully(SeekableByteChannel channel, ByteBuffer buffer) throws IOException {
		while (buffer.hasRemaining()) if (channel.read(buffer) < 0) return false;
		return true;
	}

	private static boolean zeroBlock(byte[] bytes) {
		for (byte value : bytes) if (value != 0) return false;
		return true;
	}

	private static String cString(byte[] bytes, int offset, int length) {
		int end = offset;
		while (end < offset + length && bytes[end] != 0) end++;
		return new String(bytes, offset, end - offset, StandardCharsets.US_ASCII);
	}

	private static long parseTarOctal(byte[] bytes, int offset, int length, Path archive) throws IOException {
		if ((bytes[offset] & 0x80) != 0) throw new IOException("base-256 tar field is not supported: " + archive);
		long value = 0;
		for (int i = offset; i < offset + length; i++) {
			int c = bytes[i] & 255;
			if (c == 0 || c == ' ') continue;
			if (c < '0' || c > '7') throw new IOException("corrupt tar numeric field in " + archive);
			value = value * 8 + (c - '0');
		}
		return value;
	}

	private static final class BoundedInputStream extends InputStream {
		private final InputStream delegate;
		private long remaining;

		BoundedInputStream(InputStream delegate, long remaining) {
			this.delegate = delegate;
			this.remaining = remaining;
		}

		@Override public int read() throws IOException {
			if (remaining == 0) return -1;
			int value = delegate.read();
			if (value >= 0) remaining--;
			return value;
		}

		@Override public int read(byte[] bytes, int offset, int length) throws IOException {
			if (length == 0) return 0;
			if (remaining == 0) return -1;
			int read = delegate.read(bytes, offset, (int)Math.min(length, remaining));
			if (read > 0) remaining -= read;
			return read;
		}

		@Override public void close() throws IOException { delegate.close(); }
	}

	public Header header() { return header; }
	public Path source() { return source; }

	/** Last session-clock timestamp from session.json, or -1 for a standalone/unindexed input. */
	public long estimatedEndMicros() { return estimatedEndMicros; }

	/**
	 * Whether this record is the server's session-ending {@code DISCONNECT}. Replay must never apply
	 * one: vanilla would close the replay connection and unload the client world before the timeline
	 * reached its final sample, so the input could not complete (and would never be archived).
	 * Resolved from the recorded protocol table; unknown when the recorder omitted that table.
	 */
	public boolean isDisconnect(Rec rec) {
		if (rec.direction() != Direction.S2C) return false;
		Integer id = disconnectId(rec.phase());
		return id != null && id == packetId(rec.data());
	}

	private Integer disconnectId(Phase phase) {
		if (disconnectIds == null) {
			disconnectIds = new EnumMap<>(Phase.class);
			Map<String, Map<String, String>> table = header.protocolTable;
			if (table != null) {
				for (Phase p : Phase.values()) {
					Map<String, String> ids = table.get(p.name() + "/S2C");
					if (ids == null) continue;
					for (Map.Entry<String, String> entry : ids.entrySet()) {
						if (!"DISCONNECT".equalsIgnoreCase(entry.getValue())) continue;
						try { disconnectIds.put(p, Integer.valueOf(entry.getKey().trim())); }
						catch (NumberFormatException ignored) { /* a non-numeric table key is not an id */ }
						break;
					}
				}
			}
		}
		return disconnectIds.get(phase);
	}

	/** Leading varint packet id of a raw payload, or -1 when it is empty or malformed. */
	private static int packetId(byte[] data) {
		int value = 0;
		for (int i = 0, shift = 0; i < data.length && shift < 35; i++, shift += 7) {
			int b = data[i] & 255;
			value |= (b & 127) << shift;
			if ((b & 128) == 0) return value;
		}
		return -1;
	}

	public Rec next() throws IOException {
		while (current != null) {
			Rec rec = current.next();
			if (rec != null) return rec;
			current.close();
			current = null;
			advanceSegment();
		}
		return null;
	}

	private void advanceSegment() throws IOException {
		while (current == null && segmentIndex < segments.size()) {
			SegmentSource segment = segments.get(segmentIndex++);
			try { current = new Segment(segment); }
			catch (IOException e) {
				OpenCrafterLink.LOGGER.warn("[open-crafter-link] unreadable segment {}; continuing", segment.name, e);
			}
		}
	}

	@Override public void close() throws IOException {
		if (current != null) current.close();
		current = null;
	}

	private static final class Segment implements Closeable {
		private final InputStream in;
		private final Header header;
		private byte[] block;
		private int pos;
		private long blockTime;
		private boolean first;
		private boolean ended;

		Segment(SegmentSource source) throws IOException {
			in = new BufferedInputStream(source.opener.open(), 1 << 16);
			try {
				byte[] magic = in.readNBytes(MAGIC.length);
				if (!java.util.Arrays.equals(magic, MAGIC)) throw new IOException("bad MCREC1 magic: " + source.name);
				int len = readIntStrict(in);
				if (len < 0 || len > 64 * 1024 * 1024) throw new IOException("invalid header length " + len);
				byte[] json = in.readNBytes(len);
				if (json.length != len) throw new EOFException("truncated segment header");
				header = GSON.fromJson(new String(json, StandardCharsets.UTF_8), Header.class);
				if (header == null) throw new IOException("empty segment header");
			} catch (Throwable t) {
				in.close();
				if (t instanceof IOException io) throw io;
				throw new IOException("invalid segment header", t);
			}
		}

		Rec next() throws IOException {
			while (!ended) {
				if (block != null && pos < block.length) {
					int flags = readByte();
					long delta = readVarLong();
					int len = readVarInt();
					if (len < 0 || len > block.length - pos) throw new EOFException("record exceeds block");
					byte[] data = java.util.Arrays.copyOfRange(block, pos, pos + len);
					pos += len;
					if (!first) blockTime += delta;
					first = false;
					int phaseId = (flags & 0x0E) >>> 1;
					Phase phase = phaseId < Phase.values().length ? Phase.values()[phaseId] : Phase.HANDSHAKING;
					return new Rec((flags & 1) != 0 ? Direction.C2S : Direction.S2C,
							phase, (flags & 0x10) != 0, blockTime, data);
				}
				if (!readBlock()) return null;
			}
			return null;
		}

		private boolean readBlock() throws IOException {
			int marker = in.read();
			if (marker < 0) { ended = true; return false; } // crash-truncated tails retain valid prior blocks
			if (marker == END) {
				byte[] fixed = in.readNBytes(24);
				if (fixed.length == 24) {
					int nextLen = readInt(fixed, 20);
					if (nextLen >= 0 && nextLen <= 4096) {
						in.readNBytes(nextLen);
						in.readNBytes(TRAILER.length);
					}
				}
				ended = true;
				return false;
			}
			if (marker != BLOCK) throw new IOException("unexpected block marker 0x" + Integer.toHexString(marker));
			byte[] h = in.readNBytes(20);
			if (h.length != 20) { ended = true; return false; }
			int rawLen = readInt(h, 0), compressedLen = readInt(h, 4), expectedCrc = readInt(h, 8);
			if (rawLen < 0 || compressedLen < 0 || rawLen > MAX_BLOCK || compressedLen > MAX_BLOCK)
				throw new IOException("implausible packet block length");
			blockTime = readLong(h, 12);
			byte[] compressed = in.readNBytes(compressedLen);
			if (compressed.length != compressedLen) { ended = true; return false; }
			block = decompress(header.codecId, compressed, rawLen);
			CRC32 crc = new CRC32(); crc.update(block);
			if ((int) crc.getValue() != expectedCrc) throw new IOException("packet block CRC mismatch");
			pos = 0;
			first = true;
			return true;
		}

		private static byte[] decompress(int codec, byte[] src, int rawLen) throws IOException {
			if (codec == 0) {
				if (src.length != rawLen) throw new IOException("stored block length mismatch");
				return src;
			}
			if (codec == 2) {
				byte[] out = new byte[rawLen];
				long n = Zstd.decompress(out, src);
				if (Zstd.isError(n) || n != rawLen) throw new IOException("zstd block decode failed: " + Zstd.getErrorName(n));
				return out;
			}
			if (codec != 1) throw new IOException("unknown packet block codec " + codec);
			Inflater inflater = new Inflater();
			try {
				inflater.setInput(src);
				byte[] out = new byte[rawLen];
				int off = 0;
				while (off < rawLen) {
					int n = inflater.inflate(out, off, rawLen - off);
					if (n == 0) throw new IOException("deflate block ended at " + off + " of " + rawLen);
					off += n;
				}
				return out;
			} catch (DataFormatException e) { throw new IOException("invalid deflate packet block", e); }
			finally { inflater.end(); }
		}

		private int readByte() throws EOFException {
			if (pos >= block.length) throw new EOFException();
			return block[pos++] & 255;
		}
		private int readVarInt() throws IOException {
			int value = 0;
			for (int shift = 0; shift < 35; shift += 7) { int b = readByte(); value |= (b & 127) << shift; if ((b & 128) == 0) return value; }
			throw new IOException("varint too long");
		}
		private long readVarLong() throws IOException {
			long value = 0;
			for (int shift = 0; shift < 70; shift += 7) { int b = readByte(); value |= (long)(b & 127) << shift; if ((b & 128) == 0) return value; }
			throw new IOException("varlong too long");
		}
		@Override public void close() throws IOException { in.close(); }
	}

	private static int readIntStrict(InputStream in) throws IOException {
		byte[] b = in.readNBytes(4); if (b.length != 4) throw new EOFException(); return readInt(b, 0);
	}
	private static int readInt(byte[] b, int o) {
		return (b[o]&255)<<24 | (b[o+1]&255)<<16 | (b[o+2]&255)<<8 | b[o+3]&255;
	}
	private static long readLong(byte[] b, int o) {
		long v=0; for(int i=0;i<8;i++) v=v<<8|(b[o+i]&255L); return v;
	}
}
