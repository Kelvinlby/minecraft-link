package mod.kelvinlby.recorder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class PacketRecordingReaderTest {
	@TempDir Path temp;

	@Test
	void pendingProbeIgnoresTheDoneFolder() throws Exception {
		assertFalse(PacketRecordingReader.hasPending(temp));
		Files.createDirectories(temp.resolve("done/already-processed"));
		assertFalse(PacketRecordingReader.hasPending(temp));
		Path waiting = Files.createDirectories(temp.resolve("waiting"));
		assertFalse(PacketRecordingReader.hasPending(temp));
		Files.write(waiting.resolve("0000.mcrec"), new byte[]{1});
		assertTrue(PacketRecordingReader.hasPending(temp));
	}

	@Test
	void readsInterleavedStoredRecordsAtAbsoluteTimes() throws Exception {
		Path session = temp.resolve("session");
		Files.createDirectories(session);
		byte[] payload = records();
		String header = "{\"formatVersion\":1,\"protocolVersion\":777,\"minecraftVersion\":\"test\","
				+ "\"playerName\":\"Alex\",\"codecId\":0}";

		writeSegment(session, header, payload, 1_000L);

		try (PacketRecordingReader reader = PacketRecordingReader.openNext(temp)) {
			assertNotNull(reader);
			assertEquals(777, reader.header().protocolVersion);
			PacketRecordingReader.Rec first = reader.next();
			assertEquals(PacketRecordingReader.Direction.S2C, first.direction());
			assertEquals(PacketRecordingReader.Phase.PLAY, first.phase());
			assertEquals(1_000L, first.timeMicros());
			assertArrayEquals(new byte[]{0x01, 0x02}, first.data());

			PacketRecordingReader.Rec second = reader.next();
			assertEquals(PacketRecordingReader.Direction.C2S, second.direction());
			assertEquals(1_250L, second.timeMicros());
			assertArrayEquals(new byte[]{0x03}, second.data());
			assertNull(reader.next()); // a crash-truncated segment without a trailer is recoverable
		}
	}

	@Test
	void streamsACompletedTarRecordingWithoutExtraction() throws Exception {
		Path staging = temp.resolve("staging");
		Files.createDirectories(staging);
		byte[] payload = records();
		String header = "{\"formatVersion\":1,\"protocolVersion\":777,\"minecraftVersion\":\"test\","
				+ "\"playerName\":\"Alex\",\"codecId\":0}";
		writeSegment(staging, header, payload, 1_000L);
		byte[] segment = Files.readAllBytes(staging.resolve("0000.mcrec"));
		String index = "{\"segments\":[{\"file\":\"0000.mcrec\",\"lastMicros\":1250}]}";
		Path archive = temp.resolve("2026-08-28_Alex.tar");
		writeTar(archive, index.getBytes(StandardCharsets.UTF_8), segment);
		Files.delete(staging.resolve("0000.mcrec"));
		Files.delete(staging);

		assertTrue(PacketRecordingReader.hasPending(temp));
		try (PacketRecordingReader reader = PacketRecordingReader.openNext(temp)) {
			assertNotNull(reader);
			assertEquals(archive, reader.source());
			assertEquals(1_250L, reader.estimatedEndMicros());
			assertEquals(1_000L, reader.next().timeMicros());
			assertEquals(1_250L, reader.next().timeMicros());
			assertNull(reader.next());
		}
		assertFalse(Files.exists(temp.resolve("session.json")));
		assertFalse(Files.exists(temp.resolve("0000.mcrec")));
	}

	@Test
	void identifiesTheSessionEndingDisconnectFromTheProtocolTable() throws Exception {
		Path inbox = temp.resolve("inbox");
		Path session = inbox.resolve("session");
		Files.createDirectories(session);
		String header = "{\"formatVersion\":1,\"protocolVersion\":777,\"minecraftVersion\":\"test\","
				+ "\"playerName\":\"Alex\",\"codecId\":0,\"protocolTable\":"
				+ "{\"PLAY/S2C\":{\"32\":\"DISCONNECT\",\"33\":\"ENTITY_RELATIVE_MOVE\"},"
				+ "\"PLAY/C2S\":{\"32\":\"PLAYER_INPUT\"}}}";

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		bytes.write(8); writeVar(bytes, 0); writeVar(bytes, 1); bytes.write(33);   // S2C move
		bytes.write(9); writeVar(bytes, 0); writeVar(bytes, 1); bytes.write(32);   // C2S sharing the id
		bytes.write(8); writeVar(bytes, 0); writeVar(bytes, 1); bytes.write(32);   // S2C disconnect
		writeSegment(session, header, bytes.toByteArray(), 0L);

		try (PacketRecordingReader reader = PacketRecordingReader.openNext(inbox)) {
			assertNotNull(reader);
			assertFalse(reader.isDisconnect(reader.next()));
			assertFalse(reader.isDisconnect(reader.next())); // ids are direction-scoped
			assertTrue(reader.isDisconnect(reader.next()));
		}
	}

	@Test
	void withoutAProtocolTableNoRecordIsTreatedAsADisconnect() throws Exception {
		Path inbox = temp.resolve("inbox");
		Path session = inbox.resolve("session");
		Files.createDirectories(session);
		String header = "{\"formatVersion\":1,\"protocolVersion\":777,\"minecraftVersion\":\"test\","
				+ "\"playerName\":\"Alex\",\"codecId\":0}";

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		bytes.write(8); writeVar(bytes, 0); writeVar(bytes, 1); bytes.write(32);
		writeSegment(session, header, bytes.toByteArray(), 0L);

		try (PacketRecordingReader reader = PacketRecordingReader.openNext(inbox)) {
			assertNotNull(reader);
			assertFalse(reader.isDisconnect(reader.next()));
		}
	}

	private static void writeSegment(Path session, String header, byte[] payload, long blockTime) throws Exception {
		CRC32 crc = new CRC32(); crc.update(payload);
		ByteArrayOutputStream file = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(file);
		out.write("MCREC1\n".getBytes(StandardCharsets.US_ASCII));
		out.writeInt(header.getBytes(StandardCharsets.UTF_8).length);
		out.write(header.getBytes(StandardCharsets.UTF_8));
		out.writeByte(0xB1);
		out.writeInt(payload.length);
		out.writeInt(payload.length);
		out.writeInt((int) crc.getValue());
		out.writeLong(blockTime);
		out.write(payload);
		Files.write(session.resolve("0000.mcrec"), file.toByteArray());
	}

	private static byte[] records() throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		// flags: PLAY=4 shifted by one. First record delta is always ignored by the format.
		bytes.write(8); writeVar(bytes, 0); writeVar(bytes, 2); bytes.write(new byte[]{1, 2});
		bytes.write(9); writeVar(bytes, 250); writeVar(bytes, 1); bytes.write(3);
		return bytes.toByteArray();
	}

	private static void writeTar(Path archive, byte[] index, byte[] segment) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		writeTarMember(bytes, "session.json", index);
		writeTarMember(bytes, "0000.mcrec", segment);
		bytes.write(new byte[1024]);
		Files.write(archive, bytes.toByteArray());
	}

	private static void writeTarMember(ByteArrayOutputStream out, String name, byte[] payload) throws Exception {
		byte[] header = new byte[512];
		byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(nameBytes, 0, header, 0, nameBytes.length);
		putOctal(header, 100, 8, 0644);
		putOctal(header, 108, 8, 0);
		putOctal(header, 116, 8, 0);
		putOctal(header, 124, 12, payload.length);
		putOctal(header, 136, 12, 0);
		java.util.Arrays.fill(header, 148, 156, (byte)' ');
		header[156] = '0';
		byte[] magic = "ustar\0".getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(magic, 0, header, 257, magic.length);
		int checksum = 0;
		for (byte value : header) checksum += value & 255;
		byte[] checksumBytes = String.format("%06o", checksum).getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(checksumBytes, 0, header, 148, checksumBytes.length);
		header[154] = 0;
		header[155] = ' ';
		out.write(header);
		out.write(payload);
		int remainder = payload.length % 512;
		if (remainder != 0) out.write(new byte[512 - remainder]);
	}

	private static void putOctal(byte[] target, int offset, int length, long value) {
		byte[] text = String.format("%0" + (length - 1) + "o", value).getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(text, 0, target, offset, length - 1);
		target[offset + length - 1] = 0;
	}

	private static void writeVar(ByteArrayOutputStream out, long value) {
		do {
			int b = (int) (value & 127); value >>>= 7;
			out.write(value == 0 ? b : b | 128);
		} while (value != 0);
	}
}
