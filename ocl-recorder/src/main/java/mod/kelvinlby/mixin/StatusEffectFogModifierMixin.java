package mod.kelvinlby.mixin;

import mod.kelvinlby.recorder.RecorderRenderingTweaks;
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.client.render.fog.DarknessEffectFogModifier;
import net.minecraft.client.render.fog.StatusEffectFogModifier;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets forced darkness participate in fog selection, or removes recorded darkness from it. */
@Mixin(StatusEffectFogModifier.class)
abstract class StatusEffectFogModifierMixin {
	@Inject(method = "shouldApply", at = @At("HEAD"), cancellable = true)
	private void ocl$overrideDarknessSelection(CameraSubmersionType submersionType, Entity entity,
			CallbackInfoReturnable<Boolean> cir) {
		if (!((Object)this instanceof DarknessEffectFogModifier) || !(entity instanceof LivingEntity)) return;
		switch (RecorderRenderingTweaks.darknessOverride()) {
			case FORCE_ENABLED -> cir.setReturnValue(true);
			case FORCE_DISABLED -> cir.setReturnValue(false);
			case AS_RECORDED -> { }
		}
	}
}
