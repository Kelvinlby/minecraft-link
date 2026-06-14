package mod.kelvinlby.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Exposes the YACL settings screen through Mod Menu's config (gear) button. */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return OclConfigScreen::create;
	}
}
