package mod.kelvinlby.mixin;

import mod.kelvinlby.OpenCrafterLinkClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures first-person depth before GameRenderer clears it for GUI rendering. */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Inject(method = "renderWorld", at = @At("TAIL"))
	private void ocl$captureFirstPersonDepth(RenderTickCounter tickCounter, CallbackInfo ci) {
		OpenCrafterLinkClient.onFirstPersonRenderEnd();
	}
}
