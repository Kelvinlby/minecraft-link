package mod.kelvinlby.recorder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayProgressToastTest {
	@Test
	void statusShowsThroughputAndProcessedPercentage() {
		assertEquals("42.3 sample/s · 67.9%",
				ReplayProgressToast.formatStatus(42.34, 0.6786F));
	}

	@Test
	void statusClampsInvalidDisplayValues() {
		assertEquals("0.0 sample/s · 100.0%",
				ReplayProgressToast.formatStatus(-2.0, 1.5F));
	}
}
