package mod.kelvinlby.mixin;

import mod.kelvinlby.recorder.InventoryActionTap;
import mod.kelvinlby.recorder.ReplayOutboundGuard;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes the vanilla drop key when pressed with no inventory screen open. {@code MinecraftClient}
 * calls {@code dropSelectedItem} directly from {@code handleInputEvents} in that case, bypassing
 * {@code ClientPlayerInteractionManager.clickSlot} entirely — the one drop path
 * {@link mod.kelvinlby.mixin.ClientPlayerInteractionManagerMixin} cannot see. This HEAD hook is the
 * matching second tap so the dataset recorder captures Q-while-walking the same as every other
 * inventory action.
 */
@Mixin(ClientPlayerEntity.class)
public class ClientPlayerEntityMixin {
	/** Recorded C2S positions are authoritative during replay; gravity/input/collisions may not move them. */
	@Inject(method = "move", at = @At("HEAD"), cancellable = true)
	private void openCrafterLink$freezeReplayPhysics(MovementType movementType, Vec3d movement,
			CallbackInfo ci) {
		if (ReplayOutboundGuard.isActive()) ci.cancel();
	}

	@Inject(method = "dropSelectedItem", at = @At("HEAD"))
	private void openCrafterLink$recordDropKey(boolean entireStack, CallbackInfoReturnable<Boolean> cir) {
		InventoryActionTap.observeDropKey((ClientPlayerEntity) (Object) this);
	}
}
