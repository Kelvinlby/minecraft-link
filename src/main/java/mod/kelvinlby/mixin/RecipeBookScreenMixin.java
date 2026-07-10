package mod.kelvinlby.mixin;

import mod.kelvinlby.config.OclConfig;
import mod.kelvinlby.recorder.InventoryActionTap;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.RecipeBookScreen;
import net.minecraft.client.gui.screen.recipebook.RecipeBookWidget;
import net.minecraft.client.gui.widget.TexturedButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps recorded crafting demonstrations "manual" by neutralizing the recipe book while a dataset session
 * is live. A single recipe-book click auto-fills the crafting grid — vanilla emits it as one opaque action
 * rather than the sequence of stack placements the recorder wants — so allowing it would pollute the
 * dataset. {@link RecipeBookScreen} is the shared superclass of the two screens that carry a recipe book
 * for a crafting grid — the 2×2 player-inventory ({@code InventoryScreen}) and the crafting table
 * ({@code CraftingScreen}) — so hooking it here covers both and nothing else (furnace/smithing/etc. use
 * different screens).
 *
 * <p>Gated on {@link InventoryActionTap#isActive()} (the same static "a session is recording right now"
 * flag the other recorder mixins use) AND the user-facing {@link OclConfig#disableRecipeBookWhileRecording}
 * toggle, read live so flipping it in the settings screen takes effect the next time a screen lays out. When
 * the gate is off — not recording, or the setting disabled — this is a no-op and the recipe book behaves
 * exactly as vanilla.
 */
@Mixin(RecipeBookScreen.class)
public abstract class RecipeBookScreenMixin {

	@Shadow
	private RecipeBookWidget<?> recipeBook;

	/**
	 * {@code addRecipeBook} runs at the tail of {@code init()} and builds the recipe-book toggle button, so
	 * this fires on every (re)layout — the right moment to reflect the current recording state. When gated,
	 * grey out that button (leaving it visible but inert) and snap the book shut if it was open. With
	 * {@code active = false} the button's press action never fires, so the book cannot be reopened via it
	 * while gated.
	 *
	 * <p>The button is constructed inline and handed straight to {@code addDrawableChild} — it is never a
	 * local variable, so it can't be captured with {@code @Local}. Instead we find it among the screen's
	 * children: it is the only {@link TexturedButtonWidget} these two screens add, so a type match is
	 * unambiguous.
	 */
	@Inject(method = "addRecipeBook", at = @At("TAIL"))
	private void openCrafterLink$gateRecipeBook(CallbackInfo ci) {
		if (!InventoryActionTap.isActive() || !OclConfig.get().disableRecipeBookWhileRecording) {
			return;
		}
		for (Element child : ((Screen) (Object) this).children()) {
			if (child instanceof TexturedButtonWidget button) {
				button.active = false; // grey out; leave visible = true so its slot stays laid out
			}
		}
		if (recipeBook.isOpen()) {
			recipeBook.toggleOpen(); // public; avoids widening the protected setOpen
		}
	}
}
