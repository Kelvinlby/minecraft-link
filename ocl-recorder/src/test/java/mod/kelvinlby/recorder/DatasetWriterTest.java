package mod.kelvinlby.recorder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mod.kelvinlby.link.InventoryState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class DatasetWriterTest {
	@Test
	void actionLineRecordsRemainingAir() {
		ActionSet action = new ActionSet(
				false, false, false, false, false, false, false, false, false,
				0, 0.0f, 0.0f, 18.0f, 16, 87, 4, List.of());
		PackedFrame vision = new PackedFrame(1, 1, 0.05f, 128.0f, new byte[3], new short[1]);
		Sample sample = new Sample(0, 1, vision, action, InventoryState.EMPTY, false);

		JsonObject row = JsonParser.parseString(DatasetWriter.formatActionLine(sample, true)).getAsJsonObject();

		assertEquals(87, row.get("air").getAsInt());
	}
}
