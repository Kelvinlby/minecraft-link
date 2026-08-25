package mod.kelvinlby.mixin;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import mod.kelvinlby.recorder.ReplayOutboundGuard;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps packet replay observational: generated responses must not be sent to the host world. */
@Mixin(ClientConnection.class)
public class ClientConnectionMixin {
	@Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V",
			at = @At("HEAD"), cancellable = true)
	private void openCrafterLink$isolateReplayInbound(ChannelHandlerContext context, Packet<?> packet,
			CallbackInfo ci) {
		ClientConnection connection = (ClientConnection) (Object) this;
		if (ReplayOutboundGuard.shouldSuppressInbound(connection, packet)) ci.cancel();
	}

	@Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
			at = @At("HEAD"), cancellable = true)
	private void openCrafterLink$suppressReplayOutbound(Packet<?> packet, ChannelFutureListener listener,
			boolean flush, CallbackInfo ci) {
		if (ReplayOutboundGuard.shouldSuppress(packet)) ci.cancel();
	}
}
