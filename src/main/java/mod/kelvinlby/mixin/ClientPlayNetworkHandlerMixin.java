package mod.kelvinlby.mixin;

import mod.kelvinlby.recorder.ReplayOutboundGuard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Makes a replayed game join create a player belonging to the recorded ClientWorld. */
@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {
	@Inject(method = "onGameJoin", at = @At("HEAD"))
	private void openCrafterLink$replaceHostPlayer(GameJoinS2CPacket packet, CallbackInfo ci) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (ReplayOutboundGuard.isActive() && client.isOnThread()) {
			client.setCameraEntity(null);
			client.player = null;
		}
	}
}
