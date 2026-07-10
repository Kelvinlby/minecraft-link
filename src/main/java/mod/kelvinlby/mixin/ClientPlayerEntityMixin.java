package mod.kelvinlby.mixin;

import mod.kelvinlby.recorder.InventoryActionTap;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

	@Inject(method = "dropSelectedItem", at = @At("HEAD"))
	private void openCrafterLink$recordDropKey(boolean entireStack, CallbackInfoReturnable<Boolean> cir) {
		InventoryActionTap.observeDropKey((ClientPlayerEntity) (Object) this);
	}
}
