package mod.kelvinlby.link;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Regenerates the golden {@code .bin} byte files under {@code protocol/golden/} from the fixtures in
 * {@code cases.json}, using {@link BinaryCodec} as the reference encoder. Run via {@code ./gradlew
 * genGolden} after adding or changing a fixture; commit the regenerated {@code .bin} files.
 *
 * <p>{@code BinaryCodec} has no OCLO/OCLV <i>decoder</i> (the mod only encodes those), so the generated
 * bytes are the canonical output that the Python side asserts it can decode, and that the Java golden
 * test asserts {@code BinaryCodec} re-encodes to. Args: {@code <cases.json> <output-dir>}.
 */
public final class GenGolden {
	private GenGolden() {}

	public static void main(String[] args) throws IOException {
		if (args.length != 2) {
			System.err.println("usage: GenGolden <cases.json> <output-dir>");
			System.exit(2);
		}
		Path casesJson = Path.of(args[0]);
		Path outDir = Path.of(args[1]);
		Files.createDirectories(outDir);

		GoldenVectors.Cases cases = GoldenVectors.load(casesJson);
		int written = 0;
		for (GoldenVectors.OcloCase c : cases.oclo()) {
			write(outDir.resolve(c.bin()), BinaryCodec.encodeOutbound(c.snapshot()));
			written++;
		}
		for (GoldenVectors.OcliCase c : cases.ocli()) {
			write(outDir.resolve(c.bin()), encodeInbound(c.movement(), c.action()));
			written++;
		}
		for (GoldenVectors.OclvCase c : cases.oclv()) {
			write(outDir.resolve(c.bin()), BinaryCodec.encodeVision(c.frame()));
			written++;
		}
		System.out.println("genGolden: wrote " + written + " .bin files to " + outDir);
	}

	/** Encode an OCLI frame from its two parts. Delegates to the shared test-only encoder in TestEncoders. */
	static byte[] encodeInbound(InboundInstruction move, InventoryAction action) {
		return TestEncoders.encodeInbound(move, action);
	}

	private static void write(Path path, byte[] bytes) throws IOException {
		Files.write(path, bytes);
	}
}
