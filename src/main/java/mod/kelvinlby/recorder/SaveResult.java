package mod.kelvinlby.recorder;

/**
 * Outcome of finalizing one recording session, produced by {@link Sampler#stop} and shown to the
 * player by {@link SaveToast}.
 *
 * @param samples  total samples written to disk
 * @param dropped  samples the sampler dropped because the writer queue was full
 * @param repeated samples whose frame was a repeat of the previous one
 * @param error    null when everything (video finalize, streams, manifest) closed cleanly; else a
 *                 short human-readable description of the first failure
 */
public record SaveResult(long samples, long dropped, long repeated, String error) {

	public boolean ok() {
		return error == null;
	}
}
