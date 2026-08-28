package mod.kelvinlby.mixin;

import mod.kelvinlby.recorder.RecorderRenderingTweaks;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps sky rendering consistent with the recorder's darkness override. */
@Mixin(WorldRenderer.class)
abstract class WorldRendererMixin {
	@Inject(method = "hasBlindnessOrDarkness", at = @At("RETURN"), cancellable = true)
	private void ocl$overrideSkyDarkness(Camera camera, CallbackInfoReturnable<Boolean> cir) {
		cir.setReturnValue(RecorderRenderingTweaks.blindnessOrDarkness(cir.getReturnValue(), camera));
	}
}
