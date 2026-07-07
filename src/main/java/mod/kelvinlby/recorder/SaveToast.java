package mod.kelvinlby.recorder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * The in-game toast that tracks a recording session's finalize: "Saving recording…" with a live
 * countdown while queued samples drain and the video encoder finalizes, then "Recording saved"
 * (or a red failure with the reason — a session that silently loses data is exactly what this
 * exists to prevent). One {@code SystemToast.Type} instance is reused so repeated
 * {@link SystemToast#show} calls update the same toast in place rather than stacking new ones.
 *
 * <p>Called from the recorder's finalize thread; every UI touch is posted through
 * {@link MinecraftClient#execute} onto the render thread. Toasts render on menu screens too, so the
 * progress stays visible after leaving a world.
 */
final class SaveToast {

	/** Shared so successive updates replace the toast's text instead of adding a second toast. */
	private static final SystemToast.Type TYPE = new SystemToast.Type(5000L);

	/** The session folder name (timestamp), shown so the player knows which recording was saved. */
	private final String sessionName;

	SaveToast(String sessionName) {
		this.sessionName = sessionName;
	}

	/** Progress callback for {@link Sampler.ProgressListener}. */
	void progress(int remainingSamples, boolean finalizingVideo) {
		show(Text.literal("Saving recording…"),
				Text.literal(finalizingVideo
						? "finalizing video — " + sessionName
						: remainingSamples + " samples left — " + sessionName));
	}

	/** Terminal state: saved cleanly or failed (with the first failure's reason). */
	void done(SaveResult result) {
		if (result == null) {
			return; // session was already stopped elsewhere — nothing to report
		}
		if (result.ok()) {
			show(Text.literal("Recording saved"),
					Text.literal(result.samples() + " samples — " + sessionName));
		} else {
			show(Text.literal("Recording save FAILED").formatted(Formatting.RED),
					Text.literal(result.error()));
		}
	}

	private void show(Text title, Text description) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null) {
			return;
		}
		mc.execute(() -> SystemToast.show(mc.getToastManager(), TYPE, title, description));
	}
}
