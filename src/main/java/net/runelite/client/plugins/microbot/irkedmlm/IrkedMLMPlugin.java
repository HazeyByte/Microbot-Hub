package net.runelite.client.plugins.microbot.irkedmlm;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        authors = {"Mocrosoft"},
        version = IrkedMLMPlugin.version,
        minClientVersion = "2.1.0",
        iconUrl = "https://chsami.github.io/Microbot-Hub/IrkedMLMPlugin/assets/icon.png",
        cardUrl = "https://chsami.github.io/Microbot-Hub/IrkedMLMPlugin/assets/card.png",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class IrkedMLMPlugin extends Plugin {

    public static final String version = "1.0.0";

    private static final String CHAT_SACK_WILL_BE_FULL = "your sack will be full";
    private static final String CHAT_SACK_IS_FULL = "your sack is full";
    private static final Pattern GOLDEN_NUGGET_COUNT =
            Pattern.compile("find (\\d+) golden nuggets?", Pattern.CASE_INSENSITIVE);

    @Inject
    private IrkedMLMConfig config;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private EventBus eventBus;
    @Inject
    private IrkedMLMOverlay IrkedMLMOverlay;
    @Inject
    private IrkedMLMAreaOverlay IrkedMLMAreaOverlay;
    @Inject
    private IrkedMLMScript IrkedMLMScript;

    @Getter
    private final List<WorldPoint> blacklistedCrates = new ArrayList<>();

    private EventBus.Subscriber chatMessageSubscriber;

    @Provides
    IrkedMLMConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(IrkedMLMConfig.class);
    }

    @Override
    protected void startUp() {
        log.info("Starting Motherload Mine v{} — sack size: {}", version, config.sackSize());
        if (overlayManager != null) {
            overlayManager.add(IrkedMLMOverlay);
            overlayManager.add(IrkedMLMAreaOverlay);
        }
        // Manual registration avoids LambdaConversionException with @Subscribe on some builds.
        chatMessageSubscriber = eventBus.register(ChatMessage.class, this::onChatMessage, 0.0f);
        IrkedMLMScript.run();
        log.info("Motherload Mine startup complete");
    }

    @Override
    protected void shutDown() {
        log.info("Shutting down Motherload Mine");
        IrkedMLMScript.shutdown();
        if (chatMessageSubscriber != null) {
            eventBus.unregister(chatMessageSubscriber);
            chatMessageSubscriber = null;
        }
        if (overlayManager != null) {
            overlayManager.remove(IrkedMLMOverlay);
            overlayManager.remove(IrkedMLMAreaOverlay);
        }
        blacklistedCrates.clear();
        log.info("Motherload Mine shutdown complete");
    }

    private void onChatMessage(ChatMessage event) {
        if (event.getType() != ChatMessageType.GAMEMESSAGE) {
            return;
        }
        String msg = event.getMessage();
        String lower = msg.toLowerCase();

        if (lower.contains(CHAT_SACK_WILL_BE_FULL) || lower.contains(CHAT_SACK_IS_FULL)) {
            IrkedMLMScript.setSackIsFull(true);
            return;
        }

        if (lower.contains("golden nugget")) {
            IrkedMLMScript.addGainedNuggets(parseGoldenNuggetCount(msg));
        }
    }

    static int parseGoldenNuggetCount(String message) {
        if (message == null || message.isEmpty()) {
            return 1;
        }
        String lower = message.toLowerCase();
        if (lower.contains("a golden nugget")) {
            return 1;
        }
        Matcher matcher = GOLDEN_NUGGET_COUNT.matcher(lower);
        if (matcher.find()) {
            try {
                return Math.max(1, Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        return 1;
    }
}
