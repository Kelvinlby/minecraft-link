package mod.kelvinlby.recorder;

import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
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

		actions.accept(new HandSwingC2SPacket(Hand.MAIN_HAND), 200L, null);
		ActionSet attack = actions.sampleAt(200L);
		assertTrue(attack.attack());
		assertFalse(attack.interact(), "an earlier instantaneous item use must not remain held");

		ActionSet idle = actions.sampleAt(300L);
		assertFalse(idle.attack());
		assertFalse(idle.interact());
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
}
