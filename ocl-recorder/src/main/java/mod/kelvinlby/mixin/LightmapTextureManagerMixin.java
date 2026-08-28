package mod.kelvinlby.mixin;

import com.mojang.blaze3d.buffers.Std140Builder;
import mod.kelvinlby.recorder.RecorderRenderingTweaks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rewrites only the lightmap values uploaded for recorder frames; gameplay state remains untouched. */
@Mixin(LightmapTextureManager.class)
abstract class LightmapTextureManagerMixin {
	@Shadow @Final private MinecraftClient client;
	@Unique private float ocl$tickProgress;

	@Inject(method = "update", at = @At("HEAD"))
	private void ocl$captureTickProgress(float tickProgress, CallbackInfo ci) {
		ocl$tickProgress = tickProgress;
	}

	@ModifyArg(method = "update", index = 0, at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/buffers/Std140Builder;putFloat(F)Lcom/mojang/blaze3d/buffers/Std140Builder;",
			ordinal = 3))
	private float ocl$overrideNightVision(float vanilla) {
		return RecorderRenderingTweaks.nightVision(vanilla, client);
	}

	@ModifyArg(method = "update", index = 0, at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/buffers/Std140Builder;putFloat(F)Lcom/mojang/blaze3d/buffers/Std140Builder;",
			ordinal = 4))
	private float ocl$overrideDarkness(float vanilla) {
		return RecorderRenderingTweaks.darkness(vanilla, ocl$tickProgress, client);
	}

	@ModifyArg(method = "update", index = 0, at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/buffers/Std140Builder;putFloat(F)Lcom/mojang/blaze3d/buffers/Std140Builder;",
			ordinal = 6))
	private float ocl$overrideGamma(float vanilla) {
		return RecorderRenderingTweaks.gamma(vanilla, ocl$tickProgress, client);
	}
}
