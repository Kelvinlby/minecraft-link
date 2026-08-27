package mod.kelvinlby.link;

import mod.kelvinlby.OpenCrafterLink;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@link LinkBridge} over plain {@code AF_UNIX} domain sockets (JEP 380, built into Java 21 — no native
 * library, no ZeroMQ), with the {@code u32-LE length + payload} framing from {@link AbstractLinkBridge}
 * around the {@link BinaryCodec} bytes. All the conflating slots, worker threads, vision conversion, and
 * frame plumbing live in the base; this class supplies only the three UDS channel-open hooks and the
 * bound-file teardown.
 *
 * <p>Why not ZeroMQ over UDS: JeroMQ's {@code ipc://} is TCP-emulated (not real {@code AF_UNIX}) and
 * cannot interoperate with the controller's genuine {@code AF_UNIX}; a real ZMTP-over-UDS would need a
 * native libzmq JNI binding. The link only does <b>conflated, fire-and-forget streaming of
 * self-contained binary messages</b>, which the length prefix reproduces exactly.
 *
 * <p>Roles: the mod is the <b>server</b> for telemetry and vision (it binds a {@link ServerSocketChannel}
 * and the controller connects), and the <b>client</b> for instructions (it connects to the controller's
 * server). Each of the three streams is its own socket path.
 */
public final class UdsBridge extends AbstractLinkBridge {

	private volatile LinkConfig.UdsEndpoints endpoints;

	/** Start the worker threads on the given UDS endpoints. Sockets are created inside the threads that own them. */
	public synchronized void start(LinkConfig.UdsEndpoints endpoints) {
		if (running) {
			return;
		}
		this.endpoints = endpoints;
		startWorkers();
		OpenCrafterLink.LOGGER.info("[open-crafter-link] UDS bridge started: TEL {} | INSTR {} | VIS {}",
				endpoints.telemetry(), endpoints.instruction(), endpoints.vision());
	}

	/** Restart the worker threads on new endpoints. No-op'ing {@link #stop()} keeps this safe if not running. */
	public synchronized void restart(LinkConfig.UdsEndpoints endpoints) {
		stop();
		start(endpoints);
	}

	@Override
	protected String threadPrefix() {
		return "uds";
	}

	@Override
	protected ServerSocketChannel openTelemetryServer() throws IOException {
		return bind(endpoints.telemetry());
	}

	@Override
	protected ServerSocketChannel openVisionServer() throws IOException {
		return bind(endpoints.vision());
	}

	@Override
	protected SocketChannel openInstructionClient() throws IOException {
		Path path = endpoints.instruction();
		while (running) {
			if (Files.exists(path)) {
				try {
					SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
					ch.connect(UnixDomainSocketAddress.of(path));
					return ch;
				} catch (IOException e) {
					// server not accepting yet — fall through and retry
				}
			}
			parkBackoff();
		}
		return null; // stopped while waiting to connect
	}

	@Override
	protected void onStopped() {
		// Our two server sockets bound files; remove them so a later start() can bind cleanly.
		deleteQuietly(endpoints.telemetry());
		deleteQuietly(endpoints.vision());
		OpenCrafterLink.LOGGER.info("[open-crafter-link] UDS bridge stopped");
	}

	/** Bind a fresh {@code AF_UNIX} server socket at {@code path}, clearing any stale bound file first. */
	private static ServerSocketChannel bind(Path path) throws IOException {
		deleteQuietly(path);
		ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
		server.bind(UnixDomainSocketAddress.of(path));
		return server;
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// best-effort; bind will surface a real problem
		}
	}
}
