package mod.kelvinlby.recorder;

import mod.kelvinlby.link.InventoryState;

/**
 * Supplies the action and inventory observation paired with a recorded video frame. Human recording
 * ignores {@code offsetMicros}; packet replay uses it as the authoritative session-clock boundary.
 */
public interface SampleSource {
	ActionSet sampleAt(long offsetMicros);

	InventoryState currentInventory();

	/** Human recording captures slot-click edges through {@link InventoryActionTap}; replay supplies its own. */
	default boolean drainLiveInventoryActions() {
		return false;
	}
}
