package mod.kelvinlby.recorder;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.BoatPaddleStateC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.network.packet.s2c.common.CommonPingS2CPacket;
import net.minecraft.network.packet.s2c.common.KeepAliveS2CPacket;

/** Prevents replayed client state from leaking newly generated gameplay packets to the host world. */
public final class ReplayOutboundGuard {
	private ReplayOutboundGuard() {}

	private static final ThreadLocal<Integer> isolatedDepth = ThreadLocal.withInitial(() -> 0);
	private static volatile boolean active;
	private static volatile ClientConnection replayConnection;

	static void setActive(boolean value) {
		active = value;
		if (!value) isolatedDepth.remove();
	}

	static void setReplayConnection(ClientConnection connection) { replayConnection = connection; }

	static void runIsolated(Runnable action) {
		isolatedDepth.set(isolatedDepth.get() + 1);
		try {
			action.run();
		} finally {
			int next = isolatedDepth.get() - 1;
			if (next == 0) isolatedDepth.remove();
			else isolatedDepth.set(next);
		}
	}

	/** True while a packet timeline owns the local player's rendered state. */
	public static boolean isActive() {
		return active;
	}

	/** Called by the connection mixin before vanilla queues an outbound packet. */
	public static boolean shouldSuppress(Packet<?> packet) {
		if (!active) return false;
		// Applying an S2C packet can synchronously generate confirmations and movement responses.
		// C2S projection can likewise invoke item helpers that send. Neither belongs on the host link.
		if (isolatedDepth.get() > 0) return true;
		// Vanilla continues ticking the projected player between replay frames. Never let those newly
		// synthesized coordinates reach the real/fake world that merely hosts the replay client. A
		// ridden vehicle emits its own position and paddle state from that same tick.
		return packet instanceof PlayerMoveC2SPacket
				|| packet instanceof VehicleMoveC2SPacket
				|| packet instanceof BoatPaddleStateC2SPacket;
	}

	/**
	 * The host connection exists only to keep Minecraft in a playable world. Its world/player updates
	 * must not compete with the recorded S2C timeline; liveness probes are retained so it stays open.
	 */
	public static boolean shouldSuppressInbound(ClientConnection connection, Packet<?> packet) {
		if (!active || connection.getSide() != NetworkSide.CLIENTBOUND) return false;
		if (connection == replayConnection) return false;
		return !(packet instanceof KeepAliveS2CPacket) && !(packet instanceof CommonPingS2CPacket);
	}
}
