package mod.kelvinlby.recorder;

import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketActionInterpreterTest {
	@Test
	void instantaneousUseIsOneSampleAndDoesNotLatchAcrossAnAttack() {
		PacketActionInterpreter actions = new PacketActionInterpreter();
		actions.accept(new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, 1, 30.0F, 10.0F), 100L, null);

		ActionSet use = actions.sampleAt(100L);
		assertTrue(use.interact());
		assertFalse(use.attack());

		// Far enough after the use that the swing cannot be that use's own animation.
		actions.accept(new HandSwingC2SPacket(Hand.MAIN_HAND), 1_000_000L, null);
		ActionSet attack = actions.sampleAt(1_000_000L);
		assertTrue(attack.attack());
		assertFalse(attack.interact(), "an earlier instantaneous item use must not remain held");

		ActionSet idle = actions.sampleAt(1_100_000L);
		assertFalse(idle.attack());
		assertFalse(idle.interact());
	}

	@Test
	void clientPredictedUseSwingIsNotAnAttack() {
		// Chests, crafting tables, doors, trapdoors, buttons and block placement all swing the arm in
		// the same input tick that sent the interaction.
		PacketActionInterpreter actions = new PacketActionInterpreter();
		actions.accept(interactBlock(), 100L, null);
		actions.accept(new HandSwingC2SPacket(Hand.MAIN_HAND), 100L, null);

		ActionSet use = actions.sampleAt(100L);
		assertTrue(use.interact());
		assertFalse(use.attack(), "the use's own hand swing must not be phrased as an attack");
		assertFalse(actions.sampleAt(200L).attack());
	}

	@Test
	void serverAcknowledgedUseSwingIsNotAnAttack() {
		// A bed (ActionResult.SUCCESS_SERVER) swings only once the server's entity animation returns,
		// a round trip after the interaction.
		PacketActionInterpreter actions = new PacketActionInterpreter();
		actions.accept(interactBlock(), 100L, null);
		assertTrue(actions.sampleAt(100L).interact());

		actions.accept(new HandSwingC2SPacket(Hand.MAIN_HAND), 150_000L, null);
		assertFalse(actions.sampleAt(150_000L).attack(), "an echoed use swing must not be phrased as an attack");
	}

	@Test
	void dropSwingIsNotAnAttack() {
		PacketActionInterpreter actions = new PacketActionInterpreter();
		actions.accept(action(PlayerActionC2SPacket.Action.DROP_ITEM), 100L, null);
		actions.accept(new HandSwingC2SPacket(Hand.MAIN_HAND), 200L, null);

		ActionSet dropped = actions.sampleAt(200L);
		assertEquals(1, dropped.inventoryActions().size());
		assertFalse(dropped.attack());
	}

	@Test
	void onlyOneSwingPerUseIsCreditedToThatUse() {
		PacketActionInterpreter actions = new PacketActionInterpreter();
		actions.accept(interactBlock(), 100L, null);
		actions.accept(new HandSwingC2SPacket(Hand.MAIN_HAND), 100L, null);
		assertFalse(actions.sampleAt(100L).attack());

		// A second swing inside the same window has nothing left to explain it: it is a real click.
		actions.accept(new HandSwingC2SPacket(Hand.MAIN_HAND), 120_000L, null);
		assertTrue(actions.sampleAt(120_000L).attack());
	}

	@Test
	void swingWellAfterAUseIsStillAnAttack() {
		PacketActionInterpreter actions = new PacketActionInterpreter();
		actions.accept(interactBlock(), 100L, null);
		actions.sampleAt(100L);

		actions.accept(new HandSwingC2SPacket(Hand.MAIN_HAND), 1_000_000L, null);
		assertTrue(actions.sampleAt(1_000_000L).attack(), "a swing a second later cannot be the use's animation");
	}

	@Test
	void bareSwingIsAnAttackOnAir() {
		PacketActionInterpreter actions = new PacketActionInterpreter();
		actions.accept(new HandSwingC2SPacket(Hand.MAIN_HAND), 100L, null);
		ActionSet miss = actions.sampleAt(100L);
		assertTrue(miss.attack());
		assertFalse(miss.interact());
	}

	@Test
	void playerInputPacketReplacesEveryMovementKeyLevel() {
		PacketActionInterpreter actions = new PacketActionInterpreter();
		actions.accept(new PlayerInputC2SPacket(
				new PlayerInput(true, false, true, false, true, true, true)), 100L, null);
		ActionSet pressed = actions.sampleAt(100L);
		assertTrue(pressed.front());
		assertFalse(pressed.back());
		assertTrue(pressed.left());
		assertFalse(pressed.right());
		assertTrue(pressed.jump());
		assertTrue(pressed.sneak());
		assertTrue(pressed.sprint());

		actions.accept(new PlayerInputC2SPacket(PlayerInput.DEFAULT), 200L, null);
		ActionSet released = actions.sampleAt(200L);
		assertFalse(released.front());
		assertFalse(released.back());
		assertFalse(released.left());
		assertFalse(released.right());
		assertFalse(released.jump());
		assertFalse(released.sneak());
		assertFalse(released.sprint());
	}

	@Test
	void blockAttackIsHeldOnlyBetweenStartAndStopPackets() {
		PacketActionInterpreter actions = new PacketActionInterpreter();
		actions.accept(action(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK), 100L, null);
		assertTrue(actions.sampleAt(100L).attack());
		assertTrue(actions.sampleAt(150L).attack());

		actions.accept(action(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK), 200L, null);
		assertFalse(actions.sampleAt(200L).attack());
	}

	private static PlayerActionC2SPacket action(PlayerActionC2SPacket.Action action) {
		return new PlayerActionC2SPacket(action, BlockPos.ORIGIN, Direction.UP);
	}

	private static PlayerInteractBlockC2SPacket interactBlock() {
		return new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND,
				new BlockHitResult(Vec3d.ZERO, Direction.UP, BlockPos.ORIGIN, false), 1);
	}
}
