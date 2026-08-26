package mod.kelvinlby.mixin;

import mod.kelvinlby.recorder.ReplayVehicleAnchor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The riding counterpart to {@link ClientPlayerEntityMixin}'s physics freeze. A vehicle the replayed
 * player is riding is client-authoritative, so vanilla keeps simulating it locally — gravity, water,
 * rails, collisions — and drags it off the transform the recorded {@code VehicleMoveC2SPacket} stream
 * just placed it at. Only the anchored vehicle is affected; the guard is a volatile read that is
 * false outside replay.
 */
@Mixin(Entity.class)
public class EntityMixin {
	@Inject(method = "move", at = @At("HEAD"), cancellable = true)
	private void openCrafterLink$freezeReplayVehicle(MovementType movementType, Vec3d movement,
			CallbackInfo ci) {
		if (ReplayVehicleAnchor.isFrozen((Entity) (Object) this)) ci.cancel();
	}
}
