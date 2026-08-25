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
		CRC32 crc = new CRC32(); crc.update(payload);
		String header = "{\"formatVersion\":1,\"protocolVersion\":777,\"minecraftVersion\":\"test\","
				+ "\"playerName\":\"Alex\",\"codecId\":0}";

		ByteArrayOutputStream file = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(file);
		out.write("MCREC1\n".getBytes(StandardCharsets.US_ASCII));
		out.writeInt(header.getBytes(StandardCharsets.UTF_8).length);
		out.write(header.getBytes(StandardCharsets.UTF_8));
		out.writeByte(0xB1);
		out.writeInt(payload.length);
		out.writeInt(payload.length);
		out.writeInt((int) crc.getValue());
		out.writeLong(1_000L);
		out.write(payload);
		Files.write(session.resolve("0000.mcrec"), file.toByteArray());

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

	private static byte[] records() throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		// flags: PLAY=4 shifted by one. First record delta is always ignored by the format.
		bytes.write(8); writeVar(bytes, 0); writeVar(bytes, 2); bytes.write(new byte[]{1, 2});
		bytes.write(9); writeVar(bytes, 250); writeVar(bytes, 1); bytes.write(3);
		return bytes.toByteArray();
	}

	private static void writeVar(ByteArrayOutputStream out, long value) {
		do {
			int b = (int) (value & 127); value >>>= 7;
			out.write(value == 0 ? b : b | 128);
		} while (value != 0);
	}
}
