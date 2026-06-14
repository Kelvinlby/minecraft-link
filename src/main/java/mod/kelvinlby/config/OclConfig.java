package mod.kelvinlby.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import mod.kelvinlby.OpenCrafterLink;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * User-editable settings, persisted as JSON in the Fabric config directory. This is the data the YACL
 * screen ({@link OclConfigScreen}) binds to. It is intentionally a plain mutable holder — the fields
 * are public so the screen's getter/setter lambdas stay trivial.
 *
 * <p>Note: these values are not yet consumed by the ZMQ bridge / vision pipeline; wiring them into the
 * runtime is a separate step.
 */
public class OclConfig {

	/** Transport the link uses to reach the controller. */
	public enum Transport {
		UNIX_DOMAIN_SOCKET,
		TCP
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH =
			FabricLoader.getInstance().getConfigDir().resolve("open-crafter-link.json");

	private static OclConfig instance;

	// --- Link ---
	public Transport transport = Transport.UNIX_DOMAIN_SOCKET;
	public String socketPath = defaultSocketPath();
	public String tcpUrl = "tcp://127.0.0.1";

	// --- Sensors / Vision ---
	public int fov = 110;
	public int visionWidth = 16;
	public int visionHeight = 16;

	/** Shared singleton so the Mod Menu factory and any future runtime reader see the same state. */
	public static OclConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	/** Reads the config file if present; on any error (missing/corrupt) returns defaults. */
	private static OclConfig load() {
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH)) {
				OclConfig loaded = GSON.fromJson(reader, OclConfig.class);
				if (loaded != null) {
					return loaded;
				}
			} catch (IOException | RuntimeException e) {
				OpenCrafterLink.LOGGER.warn("[open-crafter-link] failed to read config, using defaults", e);
			}
		}
		return new OclConfig();
	}

	/** Writes the current values as pretty JSON. */
	public void save() {
		try (Writer writer = Files.newBufferedWriter(PATH)) {
			GSON.toJson(this, writer);
		} catch (IOException e) {
			OpenCrafterLink.LOGGER.error("[open-crafter-link] failed to write config", e);
		}
	}

	private static String defaultSocketPath() {
		return Path.of(System.getProperty("java.io.tmpdir"), "open-crafter-link.sock").toString();
	}
}
