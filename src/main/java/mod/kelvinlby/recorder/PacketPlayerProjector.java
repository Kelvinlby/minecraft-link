package mod.kelvinlby.recorder;

import mod.kelvinlby.OpenCrafterLink;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityPose;
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
 */
final class PacketPlayerProjector {
	private static final long TICK_MICROS = 50_000L;
	private BlockPos breakingPos;
	private float breakingProgress;
	private long lastBreakingTickMicros;

	void accept(Packet<?> packet, long replayMicros, MinecraftClient mc) {
		ClientPlayerEntity player = mc.player;
		if (player == null) return;

		try {
			if (packet instanceof PlayerMoveC2SPacket p) {
				projectMove(p, player);
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
			} else if (packet instanceof PlayerInteractItemC2SPacket p) {
				projectInteractItem(p, player);
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
			case RELEASE_USE_ITEM -> player.clearActiveItem();
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

	private static void projectInteractItem(PlayerInteractItemC2SPacket packet, ClientPlayerEntity player) {
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
	}

	private static void drop(ClientPlayerEntity player, boolean entireStack) {
		ItemStack dropped = player.getInventory().dropSelectedItem(entireStack);
		if (!dropped.isEmpty()) player.swingHand(Hand.MAIN_HAND, false);
	}
}
