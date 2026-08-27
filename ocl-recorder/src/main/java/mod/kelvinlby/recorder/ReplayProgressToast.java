package mod.kelvinlby.recorder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.client.toast.TutorialToast;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Persistent top-right progress toast for an eager packet replay. All calls are render-thread safe. */
final class ReplayProgressToast implements Toast {
	private static final Identifier TEXTURE = Identifier.ofVanilla("toast/tutorial");
	private static final int TEXT_WIDTH = 126;
	private static final int LINE_HEIGHT = 11;

	private final List<OrderedText> title;
	private final List<OrderedText> inputName;
	private List<OrderedText> status;
	private Visibility visibility = Visibility.SHOW;
	private long lastTime;
	private float lastProgress;
	private float progress;

	ReplayProgressToast(String inputName) {
		MinecraftClient mc = MinecraftClient.getInstance();
		TextRenderer textRenderer = mc.textRenderer;
		this.title = textRenderer.wrapLines(
				Text.literal("Eager Replay Encoding").withColor(Colors.PURPLE), TEXT_WIDTH);
		this.status = textRenderer.wrapLines(Text.literal(formatStatus(0.0, 0.0F)), TEXT_WIDTH);
		this.inputName = textRenderer.wrapLines(Text.literal(inputName), TEXT_WIDTH);
		mc.getToastManager().add(this);
	}

	void setProgress(float progress, double samplesPerSecond) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (!mc.isOnThread()) {
			if (mc.isRunning()) mc.execute(() -> setProgress(progress, samplesPerSecond));
			return;
		}
		this.progress = Math.clamp(progress, 0.0F, 1.0F);
		this.status = mc.textRenderer.wrapLines(
				Text.literal(formatStatus(samplesPerSecond, this.progress)), TEXT_WIDTH);
	}

	static String formatStatus(double samplesPerSecond, float progress) {
		return String.format(Locale.ROOT, "%.1f sample/s · %.1f%%",
				Math.max(0.0, samplesPerSecond), Math.clamp(progress, 0.0F, 1.0F) * 100.0F);
	}

	@Override
	public Visibility getVisibility() {
		return visibility;
	}

	@Override
	public void update(ToastManager manager, long time) {
		lastProgress = MathHelper.clampedLerp((float)(time - lastTime) / 100.0F, lastProgress, progress);
		lastTime = time;
	}

	@Override
	public int getHeight() {
		return 10 + Math.max(lines().size(), 2) * LINE_HEIGHT;
	}

	@Override
	public void draw(DrawContext context, TextRenderer textRenderer, long startTime) {
		List<OrderedText> lines = lines();
		int height = getHeight();
		context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, TEXTURE, 0, 0, getWidth(), height);
		TutorialToast.Type.MOVEMENT_KEYS.drawIcon(context, 6, 6);
		int textHeight = lines.size() * LINE_HEIGHT;
		int y = 7 + (Math.max(lines.size(), 2) * LINE_HEIGHT - textHeight) / 2;
		for (OrderedText line : lines) {
			context.drawText(textRenderer, line, 30, y, Colors.BLACK, false);
			y += LINE_HEIGHT;
		}

		int progressY = height - 4;
		context.fill(3, progressY, 157, progressY + 1, Colors.WHITE);
		int color = progress >= lastProgress ? -16755456 : -11206656;
		context.fill(3, progressY, (int)(3.0F + 154.0F * lastProgress), progressY + 1, color);
	}

	private List<OrderedText> lines() {
		List<OrderedText> lines = new ArrayList<>(title.size() + status.size() + inputName.size());
		lines.addAll(title);
		lines.addAll(status);
		lines.addAll(inputName);
		return lines;
	}

	void hide() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.isOnThread()) visibility = Visibility.HIDE;
		else if (mc.isRunning()) mc.execute(() -> visibility = Visibility.HIDE);
	}
}
