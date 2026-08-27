package mod.kelvinlby.recorder;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import mod.kelvinlby.OpenCrafterLink;
import mod.kelvinlby.mixin.MinecraftClientAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.client.world.ClientChunkLoadProgress;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.handler.NetworkStateTransitions;
import net.minecraft.network.packet.s2c.login.LoginSuccessS2CPacket;
import net.minecraft.network.state.LoginStates;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Client-only replay connection, following ReplayMod's EmbeddedChannel design. There is no server,
 * save, terrain generator, or server tick: recorded configuration packets build the registries and
 * the recorded game-join/chunk packets create and populate the renderable {@code ClientWorld}.
 */
final class ReplayWorldHost implements AutoCloseable {
	private final MinecraftClient client;
	private final ClientConnection connection;
	private final EmbeddedChannel channel;
	private Throwable pipelineFailure;
	private boolean closed;

	ReplayWorldHost(MinecraftClient client, String playerName) {
		this.client = client;
		this.connection = new ClientConnection(NetworkSide.CLIENTBOUND) {
			@Override public void exceptionCaught(ChannelHandlerContext context, Throwable error) {
				pipelineFailure = error;
			}
			@Override public void disconnect(DisconnectionInfo info) {
				// Both disconnect() overloads funnel here. While the recording is still being fed, nothing
				// may close this connection: vanilla would unload the client world mid-timeline and the
				// replay could never finish. The session ends by closing this host instead.
				if (closed) { super.disconnect(info); return; }
				OpenCrafterLink.LOGGER.warn("[open-crafter-link] ignoring replay connection disconnect: {}",
						info.reason().getString());
			}
		};
		this.channel = new EmbeddedChannel();
		channel.pipeline().addFirst("ocl_drop_outbound", new DropOutbound());
		channel.pipeline().addLast("inbound_config", new NetworkStateTransitions.InboundConfigurer());
		channel.pipeline().addLast("outbound_config", new NetworkStateTransitions.OutboundConfigurer());
		channel.pipeline().addLast("packet_handler", connection);
		channel.pipeline().fireChannelActive();

		ClientLoginNetworkHandler login = new ClientLoginNetworkHandler(connection, client, null,
				new TitleScreen(), false, Duration.ZERO, ignored -> {}, new ClientChunkLoadProgress(0L), null);
		connection.transitionInbound(LoginStates.S2C, login);
		connection.transitionOutbound(LoginStates.C2S);
		((MinecraftClientAccessor) client).openCrafterLink$setConnection(connection);

		String name = playerName == null || playerName.isBlank() ? client.getSession().getUsername() : playerName;
		UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
		// DecoderHandler normally installs this placeholder when it decodes a packet whose
		// transitionsNetworkState() is true. We synthesize login-success as an object, so mirror that
		// decoder-side half before the handler changes the connection to CONFIGURATION.
		channel.pipeline().replace("decoder", "inbound_config", new NetworkStateTransitions.InboundConfigurer());
		new LoginSuccessS2CPacket(new GameProfile(uuid, name)).apply(login);
		ReplayOutboundGuard.setReplayConnection(connection);
	}

	void acceptS2c(byte[] packet) {
		if (closed) return;
		channel.pipeline().fireChannelRead(Unpooled.wrappedBuffer(packet));
		channel.runPendingTasks();
		channel.runScheduledPendingTasks();
		if (pipelineFailure != null) {
			Throwable error = pipelineFailure;
			pipelineFailure = null;
			throw new IllegalStateException("recorded packet failed in replay connection", error);
		}
	}

	ClientConnection connection() { return connection; }

	@Override public void close() {
		if (closed) return;
		closed = true;
		ReplayOutboundGuard.setReplayConnection(null);
		MinecraftClientAccessor accessor = (MinecraftClientAccessor) client;
		if (accessor.openCrafterLink$getConnection() == connection) accessor.openCrafterLink$setConnection(null);
		channel.close();
		channel.finishAndReleaseAll();
	}

	/** Drop all client responses; the recording is the only authoritative packet stream. */
	private static final class DropOutbound extends ChannelOutboundHandlerAdapter {
		@Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
			promise.setSuccess();
		}
		@Override public void flush(ChannelHandlerContext context) {}
	}
}
