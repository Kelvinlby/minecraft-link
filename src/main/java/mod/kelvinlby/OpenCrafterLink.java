package mod.kelvinlby;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared constants for the (client-only) mod. The actual initialization lives in
 * {@link OpenCrafterLinkClient}; this class only holds the mod id and logger.
 */
public final class OpenCrafterLink {
	public static final String MOD_ID = "open-crafter-link";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private OpenCrafterLink() {
	}
}
