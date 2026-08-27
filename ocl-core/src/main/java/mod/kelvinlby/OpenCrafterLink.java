package mod.kelvinlby;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Shared constants for the (client-only) mod. The actual initialization lives in
 * {@link OpenCrafterLinkClient}; this class only holds shared mod constants.
 */
public final class OpenCrafterLink {
	public static final String MOD_ID = "open-crafter-link";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Root for this mod's profile-scoped data; resolved lazily after Fabric has established the game dir. */
	public static Path profileDir() {
		return FabricLoader.getInstance().getGameDir().resolve(MOD_ID);
	}

	private OpenCrafterLink() {
	}
}
