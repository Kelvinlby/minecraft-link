package mod.kelvinlby.recorder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.TutorialToast;
import net.minecraft.text.Text;

/** Persistent top-right progress toast for an eager packet replay. All calls are render-thread safe. */
final class ReplayProgressToast {
	private final TutorialToast toast;

	ReplayProgressToast(String inputName) {
		MinecraftClient mc = MinecraftClient.getInstance();
		toast = new TutorialToast(mc.textRenderer, TutorialToast.Type.MOVEMENT_KEYS,
				Text.literal("Eager Replay Encoding"), Text.literal(inputName), true);
		mc.getToastManager().add(toast);
	}

	void setProgress(float progress) {
		toast.setProgress(Math.clamp(progress, 0.0F, 1.0F));
	}

	void hide() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.isOnThread()) toast.hide();
		else if (mc.isRunning()) mc.execute(toast::hide);
	}
}
