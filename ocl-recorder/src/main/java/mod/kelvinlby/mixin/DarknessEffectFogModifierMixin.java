package mod.kelvinlby.mixin;

import mod.kelvinlby.recorder.RecorderRenderingTweaks;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.fog.DarknessEffectFogModifier;
import net.minecraft.client.render.fog.FogData;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Supplies full-strength fog values when darkness is rendered without a real status instance. */
@Mixin(DarknessEffectFogModifier.class)
abstract class DarknessEffectFogModifierMixin {
	@Inject(method = "applyStartEndModifier", at = @At("HEAD"), cancellable = true)
	private void ocl$overrideDarknessFog(FogData data, Camera camera, ClientWorld world, float viewDistance,
			RenderTickCounter tickCounter, CallbackInfo ci) {
		switch (RecorderRenderingTweaks.darknessOverride()) {
			case FORCE_ENABLED -> {
				data.environmentalStart = 15.0F * 0.75F;
				data.environmentalEnd = 15.0F;
				data.skyEnd = 15.0F;
				data.cloudEnd = 15.0F;
				ci.cancel();
			}
			case FORCE_DISABLED -> ci.cancel();
			case AS_RECORDED -> { }
		}
	}

	@Inject(method = "applyDarknessModifier", at = @At("HEAD"), cancellable = true)
	private void ocl$overrideDarknessColor(LivingEntity entity, float darkness, float tickProgress,
			CallbackInfoReturnable<Float> cir) {
		switch (RecorderRenderingTweaks.darknessOverride()) {
			case FORCE_ENABLED -> cir.setReturnValue(1.0F);
			case FORCE_DISABLED -> cir.setReturnValue(darkness);
			case AS_RECORDED -> { }
		}
	}
}
