package mod.kelvinlby.recorder;

import mod.kelvinlby.OpenCrafterLink;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.BoatPaddleStateC2SPacket;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.util.Objects;

/**
 * Replays the client-side half of outgoing packets without sending them to the live connection.
 *
 * <p>A packet recording contains the position and inventory mutations that vanilla performed locally
 * immediately before transmitting C2S packets. A server therefore normally has no reason to echo those
 * mutations back to the same client. Applying only the recorded S2C stream leaves the replay camera,
 * first-person hands, and predicted block-breaking overlay stale, so this class performs those local
 * mutations at the original packet time.
 *
 * <p>While the player is riding, the position lives in {@code VehicleMoveC2SPacket} instead, and the
 * vehicle carries a rotation of its own; {@link ReplayVehicleAnchor} owns that half.
 */
final class PacketPlayerProjector {
	private static final long TICK_MICROS = 50_000L;
	private BlockPos breakingPos;
	private float breakingProgress;
	private long lastBreakingTickMicros;
	/** True only while replay, rather than a physical input event, owns the effective use-key level. */
	private boolean projectedUseKey;

	void accept(Packet<?> packet, long replayMicros, MinecraftClient mc) {
		ClientPlayerEntity player = mc.player;
		if (player == null) return;

		try {
			if (packet instanceof PlayerMoveC2SPacket p) {
				projectMove(p, player);
			} else if (packet instanceof VehicleMoveC2SPacket p) {
				projectVehicleMove(p, player);
			} else if (packet instanceof BoatPaddleStateC2SPacket p) {
				if (player.getControllingVehicle() instanceof AbstractBoatEntity boat) {
					boat.setPaddlesMoving(p.isLeftPaddling(), p.isRightPaddling());
				}
			} else if (packet instanceof PlayerInputC2SPacket p) {
				// isSneaking() and first-person pose decisions read this value directly.
				player.input.playerInput = p.input();
				if (p.input().sneak()) player.setPose(EntityPose.CROUCHING);
				else if (player.getPose() == EntityPose.CROUCHING) player.setPose(EntityPose.STANDING);
			} else if (packet instanceof UpdateSelectedSlotC2SPacket p) {
				int slot = p.getSelectedSlot();
				if (slot >= 0 && slot < 9) player.getInventory().setSelectedSlot(slot);
			} else if (packet instanceof ClickSlotC2SPacket p) {
				projectClick(p, player);
			} else if (packet instanceof CreativeInventoryActionC2SPacket p) {
				projectCreativeSlot(p, player);
			} else if (packet instanceof PlayerActionC2SPacket p) {
				projectPlayerAction(p, replayMicros, mc, player);
			} else if (packet instanceof HandSwingC2SPacket p) {
				// The two-argument LivingEntity method animates locally; ClientPlayerEntity's
				// network-sending override only exists for the one-argument overload.
				player.swingHand(p.getHand(), false);
			} else if (packet instanceof PlayerInteractBlockC2SPacket p) {
				projectInteractBlock(p, mc, player);
			} else if (packet instanceof PlayerInteractItemC2SPacket p) {
				projectInteractItem(p, mc, player);
			} else if (packet instanceof CloseHandledScreenC2SPacket p
					&& player.currentScreenHandler.syncId == p.getSyncId()) {
				player.closeScreen();
			}
		} catch (RuntimeException e) {
			// A malformed/stale C2S packet must not abort an otherwise usable recording. The following
			// authoritative S2C inventory update can still repair the projected state.
			OpenCrafterLink.LOGGER.warn("[open-crafter-link] could not project replayed {} onto the local player",
					packet.getPacketType(), e);
		}
	}

	/** Advance the client-predicted crack overlay on the same 20 TPS virtual clock as replay. */
	void advanceTo(long replayMicros, MinecraftClient mc) {
		// ClientPlayerEntity's active-item flag alone is not enough to preserve an eating, drawing,
		// blocking, charging, brushing, etc. animation. MinecraftClient.handleInputEvents() cancels it
		// on the following tick unless the real use KeyBinding is still pressed. Release our synthetic
		// level as soon as the projected use ends naturally, or an S2C update clears it.
		reconcileUseKey(mc);
		if (breakingPos == null || mc.world == null || mc.player == null) return;
		while (lastBreakingTickMicros + TICK_MICROS <= replayMicros && breakingPos != null) {
			lastBreakingTickMicros += TICK_MICROS;
			advanceBreakingTick(mc);
		}
		if (breakingPos != null && mc.world.getBlockState(breakingPos).isAir()) clearBreaking(mc);
	}

	void close(MinecraftClient mc) {
		if (!mc.isOnThread()) {
			if (mc.isRunning()) mc.execute(() -> close(mc));
			return;
		}
		clearBreaking(mc);
		releaseUseKey(mc);
		ReplayVehicleAnchor.clear();
	}

	private static void projectMove(PlayerMoveC2SPacket packet, ClientPlayerEntity player) {
		double x = packet.getX(player.getX());
		double y = packet.getY(player.getY());
		double z = packet.getZ(player.getZ());
		float yaw = packet.getYaw(player.getYaw());
		float pitch = packet.getPitch(player.getPitch());

		if (packet.changesPosition() && packet.changesLook()) {
			player.updatePositionAndAngles(x, y, z, yaw, pitch);
		} else if (packet.changesPosition()) {
			player.updatePosition(x, y, z);
		} else if (packet.changesLook()) {
			player.setAngles(yaw, pitch);
		}
		player.setOnGround(packet.isOnGround());
		player.horizontalCollision = packet.horizontalCollision();
		// Riding sends look separately from the vehicle's own transform, and the vehicle's tick would
		// rotate this freshly recorded look away again before the frame is drawn.
		ReplayVehicleAnchor.noteRiderLook(player);
	}

	private static void projectVehicleMove(VehicleMoveC2SPacket packet, ClientPlayerEntity player) {
		Entity vehicle = player.getRootVehicle();
		// A recorded vehicle move can outlive its dismount by a packet; the S2C passenger update that
		// ended the ride is authoritative, so there is nothing left to drive.
		if (vehicle == player) return;
		ReplayVehicleAnchor.anchor(vehicle, packet.position(), packet.yaw(), packet.pitch(),
				packet.onGround(), player);
	}

	private static void projectClick(ClickSlotC2SPacket packet, ClientPlayerEntity player) {
		if (player.currentScreenHandler.syncId != packet.syncId()) return;
		player.currentScreenHandler.onSlotClick(packet.slot(), packet.button(), packet.actionType(), player);
	}

	private static void projectCreativeSlot(CreativeInventoryActionC2SPacket packet, ClientPlayerEntity player) {
		int slot = packet.slot();
		if (slot >= 1 && slot <= 45) player.getInventory().setStack(slot, packet.stack().copy());
	}

	private void projectPlayerAction(PlayerActionC2SPacket packet, long replayMicros,
			MinecraftClient mc, ClientPlayerEntity player) {
		switch (packet.getAction()) {
			case START_DESTROY_BLOCK -> startBreaking(packet.getPos(), replayMicros, mc);
			case ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> clearBreaking(mc);
			case DROP_ITEM -> drop(player, false);
			case DROP_ALL_ITEMS -> drop(player, true);
			case RELEASE_USE_ITEM -> {
				player.clearActiveItem();
				releaseUseKey(mc);
			}
			default -> { }
		}
	}

	private void startBreaking(BlockPos pos, long replayMicros, MinecraftClient mc) {
		if (mc.world == null || mc.player == null) return;
		if (breakingPos != null && !breakingPos.equals(pos)) clearBreaking(mc);
		breakingPos = pos.toImmutable();
		breakingProgress = 0.0F;
		lastBreakingTickMicros = replayMicros;
		// Vanilla attackBlock() and handleBlockBreaking() both run in the input tick that emits START,
		// so the first visible damage increment belongs to the packet's own timestamp.
		advanceBreakingTick(mc);
	}

	private void advanceBreakingTick(MinecraftClient mc) {
		if (breakingPos == null || mc.world == null || mc.player == null) return;
		BlockState state = mc.world.getBlockState(breakingPos);
		if (state.isAir()) {
			clearBreaking(mc);
			return;
		}
		breakingProgress += state.calcBlockBreakingDelta(mc.player, mc.player.getEntityWorld(), breakingPos);
		// Keep stage 9 until the recorded STOP/ABORT or authoritative block removal arrives. Locally
		// deleting the block here could race the recorded S2C update when client/server timing differed.
		int stage = Math.min(9, Math.max(0, (int)(breakingProgress * 10.0F)));
		mc.world.setBlockBreakingInfo(mc.player.getId(), breakingPos, stage);
	}

	private void clearBreaking(MinecraftClient mc) {
		if (breakingPos != null && mc.world != null && mc.player != null) {
			mc.world.setBlockBreakingInfo(mc.player.getId(), breakingPos, -1);
		}
		breakingPos = null;
		breakingProgress = 0.0F;
		lastBreakingTickMicros = 0L;
	}

	private void projectInteractBlock(PlayerInteractBlockC2SPacket packet, MinecraftClient mc,
			ClientPlayerEntity player) {
		if (mc.interactionManager == null || mc.world == null) return;
		// Re-run vanilla's local prediction (block action followed by useOnBlock when appropriate).
		// The surrounding ReplayOutboundGuard isolation swallows the sequenced packet this produces.
		mc.interactionManager.interactBlock(player, packet.getHand(), packet.getBlockHitResult());
		reconcileUseKey(mc);
	}

	private void projectInteractItem(PlayerInteractItemC2SPacket packet, MinecraftClient mc,
			ClientPlayerEntity player) {
		player.setAngles(packet.getYaw(), packet.getPitch());
		if (player.isSpectator()) return;

		Hand hand = packet.getHand();
		ItemStack oldStack = player.getStackInHand(hand);
		if (player.getItemCooldownManager().isCoolingDown(oldStack)) return;

		// This is the mutation half of ClientPlayerInteractionManager.interactItem, excluding its
		// sequenced send. It distinguishes continuous uses (food, bows, shields) from instant uses and
		// also applies any predicted replacement stack before the frame is rendered.
		ActionResult result = oldStack.use(player.getEntityWorld(), player, hand);
		ItemStack newStack = result instanceof ActionResult.Success success
				? Objects.requireNonNullElseGet(success.getNewHandStack(), () -> player.getStackInHand(hand))
				: player.getStackInHand(hand);
		if (newStack != oldStack) player.setStackInHand(hand, newStack);
		reconcileUseKey(mc);
	}

	/** Mirror a continuous projected item use onto the effective input level vanilla polls each tick. */
	private void reconcileUseKey(MinecraftClient mc) {
		if (mc.player != null && mc.player.isUsingItem()) {
			// Write the effective level directly. Calling setPressed would incorrectly toggle a
			// StickyKeyBinding and queueing timesPressed would replay the interaction a second time.
			mc.options.useKey.pressed = true;
			projectedUseKey = true;
		} else {
			releaseUseKey(mc);
		}
	}

	private void releaseUseKey(MinecraftClient mc) {
		if (!projectedUseKey) return;
		mc.options.useKey.pressed = false;
		projectedUseKey = false;
	}

	private static void drop(ClientPlayerEntity player, boolean entireStack) {
		ItemStack dropped = player.getInventory().dropSelectedItem(entireStack);
		if (!dropped.isEmpty()) player.swingHand(Hand.MAIN_HAND, false);
	}
}
