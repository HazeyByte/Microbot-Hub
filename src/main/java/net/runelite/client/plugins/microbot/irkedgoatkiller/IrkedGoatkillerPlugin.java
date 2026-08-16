package net.runelite.client.plugins.microbot.irkedgoatkiller;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;

/**
 * irkedGoatkiller — automates Wyrmscraig goat hunting (Hunter 60+, Sheep Herder). Telegrab only.
 *
 * State is read straight off the pit object each tick (its menu action Line/Check/Clear is the state);
 * chat is only polled by the Script for the "pit is full" line. No @Subscribe — it throws
 * LambdaConversionException for this sideloaded plugin, so there is no event wiring here.
 */
@PluginDescriptor(
        name = PluginConstants.IRKED + "irkedGoatkiller",
        description = "Wyrmscraig goat hunting — line the pit, telegrab goats, clear and repeat",
        tags = {"hunter", "goat", "wyrmscraig", "telegrab", "aio"},
        authors = {"irked"},
        version = IrkedGoatkillerPlugin.version,
        minClientVersion = "2.1.0",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class IrkedGoatkillerPlugin extends Plugin {
    public static final String version = "0.9.0";

    @Inject
    private IrkedGoatkillerConfig config;

    @Provides
    IrkedGoatkillerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(IrkedGoatkillerConfig.class);
    }

    @Inject
    private IrkedGoatkillerScript script;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private IrkedGoatkillerOverlay overlay;

    @Override
    protected void startUp() {
        if (overlayManager != null) {
            overlayManager.add(overlay);
        }
        script.run(config);
    }

    @Override
    protected void shutDown() {
        script.shutdown();
        if (overlayManager != null) {
            overlayManager.remove(overlay);
        }
    }
}
