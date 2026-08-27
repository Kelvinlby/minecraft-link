package mod.kelvinlby.mixin;

import mod.kelvinlby.recorder.OpenCrafterRecorderClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Makes eager replay advance vanilla client simulation from replay time instead of wall time. */
@Mixin(MinecraftClient.class)
abstract class MinecraftClientRenderMixin {
	@Unique private int ocl$clientTicksThisFrame;

	@Redirect(
			method = "render",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/RenderTickCounter$Dynamic;beginRenderTick(JZ)I")
	)
	private int ocl$useReplayClock(RenderTickCounter.Dynamic counter, long wallTimeMillis, boolean tick) {
		MinecraftClient client = (MinecraftClient)(Object)this;
		long timeMillis = OpenCrafterRecorderClient.recorder().prepareRenderTime(wallTimeMillis, client);
		return ocl$clientTicksThisFrame = counter.beginRenderTick(timeMillis, tick);
	}

	/** Texture animation is exceptionally ticked once per render, outside vanilla's client-tick loop. */
	@Redirect(
			method = "render",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/texture/TextureManager;tick()V")
	)
	private void ocl$tickTexturesAtReplayRate(TextureManager textures) {
		int count = OpenCrafterRecorderClient.recorder().eagerCaptureActive()
				? Math.max(1, ocl$clientTicksThisFrame) : 1;
		for (int i = 0; i < count; i++) textures.tick();
	}

	/** Vanilla caps catch-up at ten ticks/frame; low recording FPS can legitimately require more. */
	@ModifyConstant(method = "render", constant = @Constant(intValue = 10, ordinal = 0))
	private int ocl$allowAllReplayTicks(int vanillaLimit) {
		return OpenCrafterRecorderClient.recorder().eagerCaptureActive() ? Integer.MAX_VALUE : vanillaLimit;
	}
}
