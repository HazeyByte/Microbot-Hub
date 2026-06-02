package net.runelite.client.plugins.microbot.motherloadmine;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
        name = PluginConstants.MOCROSOFT + "Motherlode Mine",
        description = "A bot that mines paydirt in the motherlode mine",
        tags = {"paydirt", "mine", "motherlode", "mlm", "motherload"},
        authors = { "Mocrosoft" },
        version = "1.9.4",
        minClientVersion = "2.1.0",
        iconUrl = "https://chsami.github.io/Microbot-Hub/MotherloadMinePlugin/assets/icon.png",
        cardUrl = "https://chsami.github.io/Microbot-Hub/MotherloadMinePlugin/assets/card.png",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class MotherloadMinePlugin extends Plugin {

    public static final String version = "1.9.4";

    @Inject
    private MotherloadMineConfig config;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private EventBus eventBus;

    @Inject
    private MotherloadMineOverlay motherloadMineOverlay;
    @Inject
    private MotherloadMineScript motherloadMineScript;

    @Getter
    private final List<WorldPoint> blacklistedCrates = new ArrayList<>();
    private EventBus.Subscriber chatMessageSubscriber;

    /**
     * Guice provider for MotherloadMineConfig. Called by the injection framework,
     * not directly by plugin code — hence the IDE "never used" warning is expected.
     */
    @Provides
    MotherloadMineConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(MotherloadMineConfig.class);
    }

    @Override
    protected void startUp() {
        log.info("Starting Motherload Mine plugin v{} — sack size: {}", version, config.sackSize());
        if (overlayManager != null) {
            overlayManager.add(motherloadMineOverlay);
        }
        // Manual event registration to avoid LambdaConversionException with @Subscribe
        // Using register(Class, Consumer, priority) API - store subscriber for unregister
        chatMessageSubscriber = eventBus.register(ChatMessage.class, this::onChatMessage, 0.0f);
        motherloadMineScript.run();
        log.info("Motherload Mine startup complete");
    }

    @Override
    public void shutDown() {
        log.info("Starting Motherload Mine shutdown");
        motherloadMineScript.shutdown();
        if (chatMessageSubscriber != null) {
            eventBus.unregister(chatMessageSubscriber);
        }
        if (overlayManager != null) {
            overlayManager.remove(motherloadMineOverlay);
        }
        blacklistedCrates.clear();
        log.info("Motherload Mine shutdown complete");
    }

    private void onChatMessage(ChatMessage event) {
        if (event.getType() != ChatMessageType.GAMEMESSAGE) return;

        String msg = event.getMessage().toLowerCase();
        if (msg.contains("your sack will be full") || msg.contains("your sack is full")) {
            motherloadMineScript.setSackIsFull(true);
        }
    }
}

