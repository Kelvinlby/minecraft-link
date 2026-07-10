package mod.kelvinlby.link;

import mod.kelvinlby.OpenCrafterLink;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

/**
 * {@link LinkBridge} over plain TCP, for a controller on another machine. Identical wire behaviour to
 * {@link UdsBridge} — the same {@link AbstractLinkBridge} conflating slots, worker threads, vision
 * conversion, and {@code u32-LE length + payload} framing — differing only in the socket family
 * ({@code AF_INET} instead of {@code AF_UNIX}) and in having no bound file to clean up.
 *
 * <p>This replaces the former ZeroMQ transport: the link only ever did conflated, fire-and-forget
 * streaming to a single controller, which the framed client/server socket reproduces without ZMTP's
 * routing/identity/PUB-SUB (and without the JeroMQ dependency). Conflation is the JVM-side single-slot
 * mailbox that {@code ZMQ_CONFLATE} used to provide.
 *
 * <p>Roles mirror {@link UdsBridge}: the mod <b>binds</b> the telemetry and vision servers (on all
 * interfaces) and the controller connects; the mod <b>connects</b> to the controller's instruction
 * server at {@code host:instructionPort}.
 */
public final class TcpBridge extends AbstractLinkBridge {

	private volatile LinkConfig.TcpEndpoints endpoints;

	/** Start the worker threads on the given TCP endpoints. Sockets are created inside the threads that own them. */
	public synchronized void start(LinkConfig.TcpEndpoints endpoints) {
		if (running) {
			return;
		}
		this.endpoints = endpoints;
		startWorkers();
		OpenCrafterLink.LOGGER.info(
				"[open-crafter-link] TCP bridge started: TEL *:{} | INSTR {}:{} | VIS *:{}",
				endpoints.telemetryPort(), endpoints.host(), endpoints.instructionPort(), endpoints.visionPort());
	}

	/** Restart the worker threads on new endpoints. No-op'ing {@link #stop()} keeps this safe if not running. */
	public synchronized void restart(LinkConfig.TcpEndpoints endpoints) {
		stop();
		start(endpoints);
	}

	@Override
	protected String threadPrefix() {
		return "tcp";
	}

	@Override
	protected ServerSocketChannel openTelemetryServer() throws IOException {
		return bindAny(endpoints.telemetryPort());
	}

	@Override
	protected ServerSocketChannel openVisionServer() throws IOException {
		return bindAny(endpoints.visionPort());
	}

	@Override
	protected SocketChannel openInstructionClient() throws IOException {
		InetSocketAddress addr = new InetSocketAddress(endpoints.host(), endpoints.instructionPort());
		while (running) {
			try {
				SocketChannel ch = SocketChannel.open();
				ch.connect(addr);
				return ch;
			} catch (IOException e) {
				parkBackoff(); // controller's server not up yet — back off and retry
			}
		}
		return null; // stopped while waiting to connect
	}

	@Override
	protected void onStopped() {
		OpenCrafterLink.LOGGER.info("[open-crafter-link] TCP bridge stopped");
	}

	/** Bind a fresh TCP server socket on all interfaces at {@code port}, allowing quick rebinds. */
	private static ServerSocketChannel bindAny(int port) throws IOException {
		ServerSocketChannel server = ServerSocketChannel.open();
		server.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true);
		server.bind(new InetSocketAddress(port));
		return server;
	}
}
