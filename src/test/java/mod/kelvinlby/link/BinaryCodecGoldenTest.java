package mod.kelvinlby.link;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cross-language wire-protocol golden test (Java half). Encodes each fixture in {@code cases.json} with
 * the reference codec and asserts the bytes equal the committed {@code protocol/golden/*.bin}, and
 * decodes each OCLI golden back to its expected values. The Python half ({@code pylib/tests/test_golden.py})
 * decodes the same {@code .bin} files, so any offset/opcode/version drift on either side fails a build.
 *
 * <p>If a fixture legitimately changes, regenerate the bytes with {@code ./gradlew genGolden} and commit
 * the updated {@code .bin}. A change that <i>wasn't</i> intended shows up here as a byte mismatch.
 */
class BinaryCodecGoldenTest {

	private static final Path GOLDEN_DIR = Path.of("protocol", "golden");
	private static final Path CASES_JSON = GOLDEN_DIR.resolve("cases.json");

	@Test
	void ocloEncodingMatchesGolden() throws IOException {
		for (GoldenVectors.OcloCase c : GoldenVectors.load(CASES_JSON).oclo()) {
			assertArrayEquals(golden(c.bin()), BinaryCodec.encodeOutbound(c.snapshot()),
					"OCLO encoding drifted for " + c.name() + " — regenerate with ./gradlew genGolden if intended");
		}
	}

	@Test
	void oclvEncodingMatchesGolden() throws IOException {
		for (GoldenVectors.OclvCase c : GoldenVectors.load(CASES_JSON).oclv()) {
			assertArrayEquals(golden(c.bin()), BinaryCodec.encodeVision(c.frame()),
					"OCLV encoding drifted for " + c.name() + " — regenerate with ./gradlew genGolden if intended");
		}
	}

	@Test
	void ocliEncodingMatchesGolden() throws IOException {
		for (GoldenVectors.OcliCase c : GoldenVectors.load(CASES_JSON).ocli()) {
			assertArrayEquals(golden(c.bin()), TestEncoders.encodeInbound(c.movement(), c.action()),
					"OCLI encoding drifted for " + c.name() + " — regenerate with ./gradlew genGolden if intended");
		}
	}

	@Test
	void ocliDecodingMatchesFixtures() throws IOException {
		for (GoldenVectors.OcliCase c : GoldenVectors.load(CASES_JSON).ocli()) {
			BinaryCodec.InboundMessage decoded = BinaryCodec.decodeInbound(golden(c.bin()));
			assertEquals(c.movement(), decoded.movement(), "OCLI movement decode mismatch for " + c.name());

			// The action block is fixed-width on the wire, so `a`/`b` are always read but are only
			// *meaningful* per op: `a` for the single/dual-operand ops, `b` only for SWAP, `slots` only for
			// DISTRIBUTE (whose a/b are unused zero-addresses). Assert only the live fields, matching the
			// InventoryAction contract, so a zero-address in an unused slot isn't a spurious mismatch.
			InventoryAction want = c.action();
			InventoryAction got = decoded.action();
			assertEquals(want.op(), got.op(), "OCLI op decode mismatch for " + c.name());
			assertEquals(want.slots(), got.slots(), "OCLI slot-list decode mismatch for " + c.name());
			if (want.op() != InventoryAction.Op.NONE && want.op() != InventoryAction.Op.DISTRIBUTE) {
				assertEquals(want.a(), got.a(), "OCLI slot-a decode mismatch for " + c.name());
			}
			if (want.op() == InventoryAction.Op.SWAP) {
				assertEquals(want.b(), got.b(), "OCLI slot-b decode mismatch for " + c.name());
			}
		}
	}

	private static byte[] golden(String name) throws IOException {
		return Files.readAllBytes(GOLDEN_DIR.resolve(name));
	}
}
