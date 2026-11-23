package com.anonymous.beyondthehorizon.client;

import com.anonymous.beyondthehorizon.client.ui.LodConfigScreen;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Opens the Beyond the Terrain settings from Mod Menu. Only loaded when Mod Menu is installed. */
public class BeyondTheHorizonModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return LodConfigScreen::new;
    }
}
