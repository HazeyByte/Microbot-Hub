package net.runelite.client.plugins.microbot.blastoisefurnace;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;


@PluginDescriptor(
        name = "BlastoiseFurnace",
        description = "Storm's BlastoiseFurnace plugin",
        tags = {"microbot", "smithing", "bar", "ore", "blast", "furnace"},
        authors = {"Storm"},
        version = BlastoiseFurnacePlugin.version,
        minClientVersion = "2.1.0",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL,
        iconUrl = "https://chsami.github.io/Microbot-Hub/BlastoiseFurnacePlugin/assets/icon.jpg",
        cardUrl = "https://chsami.github.io/Microbot-Hub/BlastoiseFurnacePlugin/assets/card.jpg"
)
@Slf4j
public class BlastoiseFurnacePlugin extends Plugin {
    final static String version = "1.5.1";
    @Inject
    private BlastoiseFurnaceConfig config;

    @Provides
    BlastoiseFurnaceConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(BlastoiseFurnaceConfig.class);
    }

    @Inject
    private OverlayManager overlayManager;
    @Inject
    private BlastoiseFurnaceOverlay blastoiseFurnaceOverlay;
    @Inject
    private BlastoiseFurnaceSceneOverlay blastoiseFurnaceSceneOverlay;

    @Inject
    BlastoiseFurnaceScript blastoiseFurnaceScript;

    @Override
    protected void startUp() throws AWTException {
        if (overlayManager != null) {
            overlayManager.add(blastoiseFurnaceOverlay);
            overlayManager.add(blastoiseFurnaceSceneOverlay);
        }
        blastoiseFurnaceScript.run();
    }

    @Subscribe
    public void onChatMessage(ChatMessage chatMessage) {
        if (chatMessage.getType() == ChatMessageType.GAMEMESSAGE) {
            if (chatMessage.getMessage().contains("The coal bag is now empty.")) {
                blastoiseFurnaceScript.coalBagEmpty = true;
            }
            if (chatMessage.getMessage().contains("The coal bag contains")) {
                blastoiseFurnaceScript.coalBagEmpty = false;
            }
        }
    }

    protected void shutDown() {
        blastoiseFurnaceScript.shutdown();
        overlayManager.remove(blastoiseFurnaceOverlay);
        overlayManager.remove(blastoiseFurnaceSceneOverlay);
    }
}
