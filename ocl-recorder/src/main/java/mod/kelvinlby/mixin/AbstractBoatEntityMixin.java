package mod.kelvinlby.mixin;

import mod.kelvinlby.recorder.ReplayVehicleAnchor;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps replayed boat input alive long enough for vanilla to advance its paddle animation. */
@Mixin(AbstractBoatEntity.class)
public class AbstractBoatEntityMixin {
	@Inject(
			method = "tick",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/entity/vehicle/AbstractBoatEntity;updatePaddles()V"
			)
	)
	private void openCrafterLink$restoreReplayPaddles(CallbackInfo ci) {
		ReplayVehicleAnchor.preparePaddleTick((AbstractBoatEntity)(Object)this);
	}
}
