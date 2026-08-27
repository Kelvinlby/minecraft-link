package mod.kelvinlby.recorder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveResultTest {
	@Test void archivalRequiresNoErrorsAndNoDrops() {
		assertTrue(new SaveResult(10, 0, 0, null).faithful());
		assertFalse(new SaveResult(9, 1, 0, null).faithful());
		assertFalse(new SaveResult(10, 0, 0, "write failed").faithful());
	}
}
