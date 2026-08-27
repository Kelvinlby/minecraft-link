package mod.kelvinlby.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Access to the connection slot vanilla normally fills when joining a server. */
@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {
	@Accessor("integratedServerConnection")
	ClientConnection openCrafterLink$getConnection();

	@Accessor("integratedServerConnection")
	void openCrafterLink$setConnection(ClientConnection connection);
}
