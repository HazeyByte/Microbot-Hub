package net.runelite.client.plugins.microbot.irkedfarmer;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.plugins.microbot.irkedfarmer.model.Preset;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;

/**
 * irkedFarmer — AIO farming plugin. A queue of independent farming runs (tree / fruit / hardwood /
 * herb / birdhouse), each preparing its own ≤28-slot inventory, banked between runs.
 *
 * Consolidates and will replace farmtreerun + herbrun + birdhouseruns. See
 * docs/superpowers/specs/2026-07-26-irkedfarmer-design.md.
 */
@PluginDescriptor(
        name = PluginConstants.IRKED + "irkedFarmer",
        description = "AIO farming — tree, fruit, hardwood, herb and birdhouse runs in one queue",
        tags = {"farming", "aio", "tree run", "herb run", "birdhouse"},
        authors = {"irked"},
        version = IrkedFarmerPlugin.version,
        minClientVersion = "2.1.0",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class IrkedFarmerPlugin extends Plugin {
    public static final String version = "0.9.6";

    @Inject
    private IrkedFarmerConfig config;

    @Provides
    IrkedFarmerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(IrkedFarmerConfig.class);
    }

    @Inject
    private IrkedFarmerScript script;

    @Inject
    private OverlayManager overlayManager;
    @Inject
    private IrkedFarmerOverlay overlay;

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

    /** Preset picker ticks/unticks the 5 activity toggles; CUSTOM leaves them alone. */
    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!IrkedFarmerConfig.GROUP.equals(event.getGroup()) || !"preset".equals(event.getKey())) {
            return;
        }
        Preset preset = config.preset();
        if (preset == Preset.CUSTOM) {
            return;
        }
        setConfig("treeRun", preset.trees);
        setConfig("fruitTreeRun", preset.fruit);
        setConfig("hardwoodRun", preset.hardwood);
        setConfig("herbRun", preset.herbs);
        setConfig("birdhouseRun", preset.birdhouses);
    }

    private static <T> void setConfig(String key, T value) {
        Microbot.getConfigManager().setConfiguration(IrkedFarmerConfig.GROUP, key, value);
    }
}
