package mod.kelvinlby.link;

/**
 * The canonical, screen-independent slot groups Open Crafter Link normalizes every Minecraft screen
 * into. Vanilla re-indexes {@code ScreenHandler.slots} per screen (a chest's slot 0 is a container
 * slot; the same physical hotbar slot has a different id in the inventory screen vs. an anvil), so the
 * controller can never address slots by their raw vanilla index. These groups + a per-group index give
 * a stable address that means the same thing in every screen.
 *
 * <p>Each constant carries an explicit {@link #wireId()} (not {@code ordinal()}) so the OCLO/OCLI wire
 * format stays stable if the enum is ever reordered. {@link InventoryMapper} is the single place that
 * maps between these groups and vanilla slot ids, in both directions.
 *
 * <ul>
 *   <li>{@link #HOTBAR} — the 9 hotbar slots, index 0..8 left-to-right.</li>
 *   <li>{@link #OFFHAND} — the single off-hand slot, index 0 only.</li>
 *   <li>{@link #ARMOR} — the 4 armor slots, index 0..3 head-to-feet.</li>
 *   <li>{@link #INVENTORY} — the 27 main inventory slots, index 0..26 left-to-right then top-to-bottom.</li>
 *   <li>{@link #CURSOR} — the stack currently held on the cursor, index 0 only (read-only; presentation
 *       reports it, no action targets it directly).</li>
 *   <li>{@link #DISCARD} — a <b>virtual</b> slot (index 0 only) equivalent to clicking outside the GUI:
 *       {@code pick} throws the whole cursor stack, {@code put} throws a single item. Has no item to read.</li>
 *   <li>{@link #EXTENSION} — the container-specific slots (chest, crafting grid, anvil, dispenser, …).
 *       On the wire this group carries the container's {@code ScreenHandler} registry id (e.g.
 *       {@code "minecraft:generic_9x3"}) rather than a user-facing name, since a chest renamed on an
 *       anvil would otherwise report a mutable custom name.</li>
 * </ul>
 */
public enum SlotGroup {
	HOTBAR(0),
	OFFHAND(1),
	ARMOR(2),
	INVENTORY(3),
	CURSOR(4),
	DISCARD(5),
	EXTENSION(6);

	private final int wireId;

	SlotGroup(int wireId) {
		this.wireId = wireId;
	}

	/** Stable wire opcode for this group; independent of {@code ordinal()}. */
	public int wireId() {
		return wireId;
	}

	/** Resolve a group from its {@link #wireId()}, or {@code null} if unknown (defensive decode). */
	public static SlotGroup fromWireId(int wireId) {
		for (SlotGroup g : values()) {
			if (g.wireId == wireId) {
				return g;
			}
		}
		return null;
	}
}
