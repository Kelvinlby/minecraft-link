package mod.kelvinlby.mixin;

import mod.kelvinlby.recorder.InventoryActionTap;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes every inventory slot click for the dataset recorder. {@code clickSlot} is the single vanilla choke
 * point through which all GUI slot interactions flow — normal clicks, shift-clicks, number-key/off-hand swaps,
 * double-clicks, drags, the drop key, and clicks outside the window — so a HEAD hook here captures the full
 * {@code (slotId, button, actionType)} of each, whether the click came from a human or from
 * {@code InputDriver} driving the player.
 *
 * <p>This is <b>observe-only</b>: it never cancels or mutates the click. The mapping to the stable
 * {@link InventoryActionTap} vocabulary (and reassembly of multi-call drags/double-clicks) happens off the
 * mixin, in {@link InventoryActionTap#observeClick}. When no recording session is active the tap short-circuits,
 * so this injection is a cheap no-op during normal play.
 */
@Mixin(ClientPlayerInteractionManager.class)
public class ClientPlayerInteractionManagerMixin {

	@Inject(method = "clickSlot", at = @At("HEAD"))
	private void openCrafterLink$recordSlotClick(int syncId, int slotId, int button, SlotActionType actionType,
			PlayerEntity player, CallbackInfo ci) {
		if (InventoryActionTap.isActive()) {
			InventoryActionTap.observeClick(player, slotId, button, actionType);
		}
	}
}
