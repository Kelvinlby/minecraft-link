package mod.kelvinlby.link;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class BridgeLifecycleTest {
	@Test
	void stopClosesAcceptedVisionSocketAndWorker() throws Exception {
		int telemetry = freePort();
		int instruction = freePort();
		int vision = freePort();
		TcpBridge bridge = new TcpBridge();
		bridge.start(new LinkConfig.TcpEndpoints("127.0.0.1", "127.0.0.1", telemetry, instruction, vision));

		try (Socket peer = connectEventually(vision)) {
			peer.setReceiveBufferSize(1024);
			int pixels = 1024 * 1024;
			bridge.enqueueVisionRaw(1024, 1024, 0.05f, 256.0f,
					new byte[pixels * 4], new byte[pixels * 4], EagerCaptureGate.NO_CAPTURE);
			Thread.sleep(300L); // let the sender fill the peer's tiny receive window
			assertTimeoutPreemptively(Duration.ofSeconds(2), bridge::stop);
			Thread.sleep(50L);
			assertFalse(hasLiveThread("ocl-tcp-vision-sender"), "vision sender leaked after stop");
		} finally {
			bridge.stop();
		}
	}

	private static Socket connectEventually(int port) throws Exception {
		long deadline = System.nanoTime() + 3_000_000_000L;
		while (true) {
			try { return new Socket("127.0.0.1", port); }
			catch (java.io.IOException e) {
				if (System.nanoTime() >= deadline) throw e;
				Thread.sleep(20L);
			}
		}
	}

	private static boolean hasLiveThread(String name) {
		return Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.isAlive() && t.getName().equals(name));
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket()) {
			socket.bind(new InetSocketAddress("127.0.0.1", 0));
			return socket.getLocalPort();
		}
	}
}
