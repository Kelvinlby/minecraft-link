package mod.kelvinlby.link;

/**
 * Optional extension point for code that owns sequenced vision captures. The core always gives
 * {@link VisionCapture} the stable forwarding gate; an optional dependent mod may install a live
 * delegate without making the core depend on that mod or relying on Fabric entrypoint order.
 */
public final class CaptureGateRegistry {
	private CaptureGateRegistry() {}

	private static final EagerCaptureGate NONE = new EagerCaptureGate() {
		@Override public boolean active() { return false; }
		@Override public long claim() { return NO_CAPTURE; }
		@Override public void commit(long seq) {}
		@Override public void release() {}
		@Override public void cancel(long seq) {}
	};

	private static volatile EagerCaptureGate delegate = NONE;

	private static final EagerCaptureGate FORWARDING = new EagerCaptureGate() {
		@Override public boolean active() { return delegate.active(); }
		@Override public long claim() { return delegate.claim(); }
		@Override public void commit(long seq) { delegate.commit(seq); }
		@Override public void release() { delegate.release(); }
		@Override public void cancel(long seq) { delegate.cancel(seq); }
	};

	public static EagerCaptureGate forwardingGate() {
		return FORWARDING;
	}

	public static synchronized void install(EagerCaptureGate gate) {
		if (gate == null) throw new IllegalArgumentException("capture gate must not be null");
		if (delegate != NONE && delegate != gate) {
			throw new IllegalStateException("an eager capture provider is already installed");
		}
		delegate = gate;
	}

	public static synchronized void uninstall(EagerCaptureGate gate) {
		if (delegate == gate) delegate = NONE;
	}
}
