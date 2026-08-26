package mod.kelvinlby.recorder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

/**
 * Holds the recorded transform of the vehicle a replayed player is riding.
 *
 * <p>While riding, vanilla stops putting the player's position in {@code PlayerMoveC2SPacket}: the
 * client sends only its look angles there and puts the boat/minecart's own position <em>and its own
 * rotation</em> in {@code VehicleMoveC2SPacket}. The vehicle, not the player, is therefore the thing
 * a replay has to drive, and it carries a yaw that is unrelated to where the rider is looking.
 *
 * <p>A ridden vehicle is client-authoritative, so the recorded S2C stream deliberately carries no
 * usable path for it. Vanilla's own client discards the server's tracker updates for it
 * ({@code Entity.isLogicalSideForUpdatingMovement}) and applies only the sparse absolute teleports
 * the server sends when it disagrees with the client — which is why replaying S2C alone left the
 * vehicle standing still and then jumping to wherever the ride ended.
 *
 * <p>Three things therefore have to hold once a recorded {@code VehicleMoveC2SPacket} is stamped
 * here. The vehicle is moved to the recorded transform at the packet's own timestamp; its local
 * physics is frozen ({@link #isFrozen} cancels {@code Entity.move} the same way replay already
 * cancels it for the player); and the transform is re-applied after every client tick, because
 * vehicle ticking still rotates both the vehicle and its riders locally — a boat adds its simulated
 * yaw velocity to its own yaw <em>and</em> to every passenger's.
 *
 * <p>All state is static because there is exactly one replayed client player, and it is only touched
 * from the client thread. {@link #vehicle} is volatile so the {@code Entity.move} hook, which runs
 * for every entity of every world, sees a consistent identity without taking a lock.
 */
public final class ReplayVehicleAnchor {
	private ReplayVehicleAnchor() {}

	private static volatile Entity vehicle;
	private static Vec3d pos = Vec3d.ZERO;
	private static float yaw;
	private static float pitch;
	private static boolean onGround;
	private static float riderYaw;
	private static float riderPitch;
	private static float riderHeadYaw;

	/** Adopt one recorded {@code VehicleMoveC2SPacket} and apply it immediately. */
	static void anchor(Entity vehicle, Vec3d pos, float yaw, float pitch, boolean onGround,
			ClientPlayerEntity rider) {
		ReplayVehicleAnchor.vehicle = vehicle;
		ReplayVehicleAnchor.pos = pos;
		ReplayVehicleAnchor.yaw = yaw;
		ReplayVehicleAnchor.pitch = pitch;
		ReplayVehicleAnchor.onGround = onGround;
		noteRiderLook(rider);
		apply(rider);
	}

	/**
	 * Latch the rider's recorded look. The recording sends it in its own packet just before the
	 * vehicle move, and the vehicle's tick would otherwise rotate it away before the frame is drawn.
	 */
	static void noteRiderLook(ClientPlayerEntity rider) {
		if (vehicle == null || rider == null) return;
		riderYaw = rider.getYaw();
		riderPitch = rider.getPitch();
		riderHeadYaw = rider.getHeadYaw();
	}

	/** Re-apply the anchored transform after vanilla has ticked the world. */
	static void reassert(MinecraftClient mc) {
		Entity anchored = vehicle;
		if (anchored == null) return;
		ClientPlayerEntity rider = mc.player;
		if (rider == null || anchored.isRemoved() || rider.getRootVehicle() != anchored) {
			clear();
			return;
		}
		apply(rider);
	}

	/** Called by the {@code Entity.move} hook: the recorded stream owns this entity's position. */
	public static boolean isFrozen(Entity entity) {
		return ReplayOutboundGuard.isActive() && entity == vehicle;
	}

	static void clear() {
		vehicle = null;
	}

	private static void apply(ClientPlayerEntity rider) {
		Entity anchored = vehicle;
		if (anchored == null) return;
		// Mirrors vanilla's own VehicleMoveS2CPacket handler: an in-flight interpolation would keep
		// dragging the vehicle off the recorded transform for the next few ticks.
		if (anchored.isInterpolating()) anchored.getInterpolator().clear();
		anchored.updatePositionAndAngles(pos.getX(), pos.getY(), pos.getZ(), yaw, pitch);
		anchored.setOnGround(onGround);
		reposition(anchored);
		if (rider != null) {
			rider.setYaw(riderYaw);
			rider.setPitch(riderPitch);
			rider.setHeadYaw(riderHeadYaw);
			rider.resetPosition();
		}
	}

	/**
	 * Carry passengers to the vehicle's new seat positions. Sample periods shorter than a tick would
	 * otherwise render riders — the replay camera included — one frame behind the vehicle.
	 */
	private static void reposition(Entity vehicle) {
		for (Entity passenger : vehicle.getPassengerList()) {
			float passengerYaw = passenger.getYaw();
			float passengerPitch = passenger.getPitch();
			float passengerHeadYaw = passenger.getHeadYaw();
			vehicle.updatePassengerPosition(passenger);
			// Keep the position half only. A boat also turns its riders by its locally simulated yaw
			// velocity, and every rider's true rotation is already in the recorded packet stream.
			passenger.setYaw(passengerYaw);
			passenger.setPitch(passengerPitch);
			passenger.setHeadYaw(passengerHeadYaw);
			passenger.resetPosition();
			reposition(passenger);
		}
	}
}
