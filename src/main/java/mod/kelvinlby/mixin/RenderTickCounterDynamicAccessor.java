package mod.kelvinlby.mixin;

import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Restores vanilla's wall-clock anchor after an accelerated replay clock is detached. */
@Mixin(RenderTickCounter.Dynamic.class)
public interface RenderTickCounterDynamicAccessor {
	@Accessor("lastTimeMillis") void ocl$setLastTimeMillis(long value);
	@Accessor("timeMillis") void ocl$setTimeMillis(long value);
	@Accessor("dynamicDeltaTicks") void ocl$setDynamicDeltaTicks(float value);
	@Accessor("fixedDeltaTicks") void ocl$setFixedDeltaTicks(float value);
	@Accessor("tickProgress") void ocl$setTickProgress(float value);
}
