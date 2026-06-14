package mod.kelvinlby.mixin.client;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the two private "click" code paths on {@link MinecraftClient} so the link can trigger
 * them the most vanilla way possible. {@code doAttack()}/{@code doItemUse()} are exactly what a
 * real mouse button press invokes: they route through the interaction manager, respect attack/use
 * cooldowns, and emit the normal packets a vanilla player produces.
 *
 * <p>This is an {@code @Invoker} (no injected behavior, no overwrites) — it only makes the existing
 * private methods callable, so it cannot be flagged as awkward or non-vanilla.
 */
@Mixin(MinecraftClient.class)
public interface MinecraftClientInvoker {
	@Invoker("doAttack")
	boolean openCrafterLink$doAttack();

	@Invoker("doItemUse")
	void openCrafterLink$doItemUse();
}
