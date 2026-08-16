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
 * State transitions are driven by the Script polling the chat buffer itself ({@code pollChat}); an
 * @Subscribe here throws LambdaConversionException for this sideloaded plugin, so no event wiring.
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
    public static final String version = "0.7.0";

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
