package mod.kelvinlby.link;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class VisionCaptureTest {
	private static final float NEAR = 0.05f;
	private static final float HAND_FAR = 100.0f;

	@Test
	void handDepthOverlaysWorldAndUsesTheWorldProjection() {
		float worldFar = 512.0f;
		float worldDistance = 20.0f;
		float handDistance = 0.75f;
		byte[] world = floats(rawDepth(worldDistance, worldFar), rawDepth(worldDistance, worldFar));
		byte[] hand = floats(1.0f, rawDepth(handDistance, HAND_FAR));

		VisionCapture.overlayHandDepth(world, hand, worldFar);

		ByteBuffer merged = ByteBuffer.wrap(world).order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(rawDepth(worldDistance, worldFar), merged.getFloat(0));
		assertEquals(rawDepth(handDistance, worldFar), merged.getFloat(Float.BYTES), 1.0e-6f);
	}

	private static float rawDepth(float distance, float far) {
		float ndc = (far + NEAR - 2.0f * NEAR * far / distance) / (far - NEAR);
		return (ndc + 1.0f) * 0.5f;
	}

	private static byte[] floats(float... values) {
		ByteBuffer bytes = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
		for (float value : values) {
			bytes.putFloat(value);
		}
		return bytes.array();
	}
}
