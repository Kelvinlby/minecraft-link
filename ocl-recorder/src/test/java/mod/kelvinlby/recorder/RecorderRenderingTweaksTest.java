package mod.kelvinlby.recorder;

import mod.kelvinlby.recorder.config.RecorderConfig.EffectOverride;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecorderRenderingTweaksTest {
	@Test
	void darknessModesAdjustGammaWithoutChangingGameplayState() {
		assertEquals(0.5F, RecorderRenderingTweaks.gammaWithDarkness(0.8F, 0.3F, EffectOverride.AS_RECORDED));
		assertEquals(0.8F, RecorderRenderingTweaks.gammaWithDarkness(0.8F, 0.3F, EffectOverride.FORCE_DISABLED));
		assertEquals(0.0F, RecorderRenderingTweaks.gammaWithDarkness(0.8F, 0.3F, EffectOverride.FORCE_ENABLED));
	}

	@Test
	void forcedDarknessUsesVanillasFullStrengthPulse() {
		assertEquals(0.45F, RecorderRenderingTweaks.fullDarknessPulse(0, 0.0F));
		assertEquals(0.0F, RecorderRenderingTweaks.fullDarknessPulse(40, 0.0F), 0.00001F);
	}
}
