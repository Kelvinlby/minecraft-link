package mod.kelvinlby.recorder;

import mod.kelvinlby.recorder.config.RecorderConfig;
import mod.kelvinlby.recorder.config.RecorderConfig.EffectOverride;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.MathHelper;

/** Recorder-only render overrides. They alter captured pixels, never the player's status effects. */
public final class RecorderRenderingTweaks {
	private static Double originalGamma;
	private static boolean synced;
	private static boolean lastGammaOverride;
	private static double lastGamma;
	private static EffectOverride lastNightVision;
	private static EffectOverride lastDarkness;

	private RecorderRenderingTweaks() {}

	/** Apply persistent preview settings and invalidate the lightmap exactly when they change. */
	public static void sync(MinecraftClient client) {
		if (client == null || client.options == null) return;
		RecorderConfig config = RecorderConfig.get().normalize();
		if (config.overrideGamma) {
			if (originalGamma == null) originalGamma = client.options.getGamma().getValue();
			// Vanilla validates its option to 0..1; the lightmap mixin carries the recorder's extended
			// 0..10 value into rendering. Keeping vanilla clamped avoids persisting an invalid option.
			client.options.getGamma().setValue(Math.min(1.0, config.recordingGamma));
		} else {
			restoreGamma(client);
		}

		boolean changed = !synced || lastGammaOverride != config.overrideGamma
				|| Double.compare(lastGamma, config.recordingGamma) != 0
				|| lastNightVision != config.nightVisionOverride || lastDarkness != config.darknessOverride;
		lastGammaOverride = config.overrideGamma;
		lastGamma = config.recordingGamma;
		lastNightVision = config.nightVisionOverride;
		lastDarkness = config.darknessOverride;
		synced = true;
		if (changed && client.gameRenderer != null) client.gameRenderer.getLightmapTextureManager().tick();
	}

	/** Put the user's ordinary Minecraft gamma back before options are persisted or the override is disabled. */
	public static void restoreGamma(MinecraftClient client) {
		if (client != null && client.options != null && originalGamma != null) {
			client.options.getGamma().setValue(originalGamma);
		}
		originalGamma = null;
	}

	public static float nightVision(float vanilla, MinecraftClient client) {
		if (client.player == null) return vanilla;
		return switch (RecorderConfig.get().nightVisionOverride) {
			case AS_RECORDED -> vanilla;
			case FORCE_ENABLED -> 1.0F;
			case FORCE_DISABLED -> client.player.hasStatusEffect(StatusEffects.NIGHT_VISION)
					? client.player.hasStatusEffect(StatusEffects.CONDUIT_POWER)
							? client.player.getUnderwaterVisibility() : 0.0F
					: vanilla;
		};
	}

	public static float darkness(float vanilla, float tickProgress, MinecraftClient client) {
		if (client.player == null) return vanilla;
		return switch (RecorderConfig.get().darknessOverride) {
			case AS_RECORDED -> vanilla;
			case FORCE_DISABLED -> 0.0F;
			case FORCE_ENABLED -> fullDarknessPulse(client.player.age, tickProgress);
		};
	}

	public static float gamma(float vanilla, float tickProgress, MinecraftClient client) {
		if (client.player == null) return vanilla;
		RecorderConfig config = RecorderConfig.get();
		EffectOverride darkness = config.darknessOverride;
		if (!config.overrideGamma && darkness == EffectOverride.AS_RECORDED) return vanilla;

		float gamma = config.overrideGamma ? (float)config.recordingGamma
				: client.options.getGamma().getValue().floatValue();
		float scale = client.options.getDarknessEffectScale().getValue().floatValue();
		float fade = client.player.getEffectFadeFactor(StatusEffects.DARKNESS, tickProgress) * scale;
		return gammaWithDarkness(gamma, fade, darkness);
	}

	/** Status-effect presence as seen by rendering code; the entity's real effect map is unchanged. */
	public static boolean hasRenderingEffect(LivingEntity entity, RegistryEntry<StatusEffect> effect) {
		boolean vanilla = entity.hasStatusEffect(effect);
		RecorderConfig config = RecorderConfig.get();
		EffectOverride override = effect.equals(StatusEffects.NIGHT_VISION) ? config.nightVisionOverride
				: effect.equals(StatusEffects.DARKNESS) ? config.darknessOverride : EffectOverride.AS_RECORDED;
		return switch (override) {
			case AS_RECORDED -> vanilla;
			case FORCE_ENABLED -> true;
			case FORCE_DISABLED -> false;
		};
	}

	public static float nightVisionStrength(LivingEntity entity, float tickProgress) {
		return switch (RecorderConfig.get().nightVisionOverride) {
			case FORCE_ENABLED -> 1.0F;
			case FORCE_DISABLED -> 0.0F;
			case AS_RECORDED -> GameRenderer.getNightVisionStrength(entity, tickProgress);
		};
	}

	public static EffectOverride darknessOverride() {
		return RecorderConfig.get().darknessOverride;
	}

	public static boolean blindnessOrDarkness(boolean vanilla, Camera camera) {
		if (!(camera.getFocusedEntity() instanceof LivingEntity living)) return vanilla;
		return switch (darknessOverride()) {
			case AS_RECORDED -> vanilla;
			case FORCE_ENABLED -> true;
			case FORCE_DISABLED -> living.hasStatusEffect(StatusEffects.BLINDNESS);
		};
	}

	static float fullDarknessPulse(int age, float tickProgress) {
		return Math.max(0.0F, MathHelper.cos((age - tickProgress) * (float)Math.PI * 0.025F) * 0.45F);
	}

	static float gammaWithDarkness(float gamma, float recordedFade, EffectOverride darkness) {
		float fade = switch (darkness) {
			case AS_RECORDED -> recordedFade;
			case FORCE_ENABLED -> 1.0F;
			case FORCE_DISABLED -> 0.0F;
		};
		return Math.max(0.0F, gamma - fade);
	}
}
