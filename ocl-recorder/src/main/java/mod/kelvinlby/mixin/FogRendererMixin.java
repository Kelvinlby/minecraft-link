package mod.kelvinlby.mixin;

import mod.kelvinlby.recorder.RecorderRenderingTweaks;
import net.minecraft.client.render.fog.FogRenderer;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.registry.entry.RegistryEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Applies effect overrides to fog color without touching the replayed player's effect state. */
@Mixin(FogRenderer.class)
abstract class FogRendererMixin {
	@Redirect(method = "getFogColor", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/entity/LivingEntity;hasStatusEffect(Lnet/minecraft/registry/entry/RegistryEntry;)Z"))
	private boolean ocl$overrideRenderingEffect(LivingEntity entity, RegistryEntry<StatusEffect> effect) {
		return RecorderRenderingTweaks.hasRenderingEffect(entity, effect);
	}

	@Redirect(method = "getFogColor", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/render/GameRenderer;getNightVisionStrength(Lnet/minecraft/entity/LivingEntity;F)F"))
	private float ocl$overrideNightVisionStrength(LivingEntity entity, float tickProgress) {
		return RecorderRenderingTweaks.nightVisionStrength(entity, tickProgress);
	}
}
