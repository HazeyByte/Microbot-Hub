package net.runelite.client.plugins.microbot.irkedmlm;

import ch.qos.logback.classic.Level;
import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
        name = PluginConstants.IRKED + "Motherlode Mine",
        description = "Mines pay-dirt at the Motherlode Mine: hopper deposits, sack emptying, water-wheel repair, gem bag, and pickaxe specials.",
        tags = {"paydirt", "mine", "motherlode", "mlm", "motherload"},
        authors = {"irkedMATT"},
        version = IrkedMLMPlugin.version,
        minClientVersion = "2.1.0",
        iconUrl = "https://chsami.github.io/Microbot-Hub/IrkedMLMPlugin/assets/icon.png",
        cardUrl = "https://chsami.github.io/Microbot-Hub/IrkedMLMPlugin/assets/card.png",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class IrkedMLMPlugin extends Plugin {

    public static final String version = "1.0.1";

    private static final String CHAT_SACK_WILL_BE_FULL = "your sack will be full";
    private static final String CHAT_SACK_IS_FULL = "your sack is full";

    @Inject
    private IrkedMLMConfig config;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private EventBus eventBus;
    @Inject
    private IrkedMLMOverlay overlay;
    @Inject
    private IrkedMLMAreaOverlay areaOverlay;
    @Inject
    private IrkedMLMScript script;

    private EventBus.Subscriber chatMessageSubscriber;
    private EventBus.Subscriber varbitChangedSubscriber;

    @Provides
    IrkedMLMConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(IrkedMLMConfig.class);
    }

    /** Package logger for this plugin (script + all sessions inherit it by package name). */
    private static final String LOG_PACKAGE = "net.runelite.client.plugins.microbot.irkedmlm";

    /**
     * The client's logger defaults to INFO, so every {@code log.debug} in this package would stay
     * invisible no matter what Debug Mode is set to. Raising the package level here is what makes the
     * config option actually do something. Applied on startUp; toggling it live needs a plugin restart.
     */
    private void applyDebugLogLevel(boolean debug) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LOG_PACKAGE))
                .setLevel(debug ? Level.DEBUG : Level.INFO);
    }

    @Override
    protected void startUp() {
        applyDebugLogLevel(config.debugMode());
        log.info("Starting Motherlode Mine v{} — sack size: {}", version, config.sackSize());
        if (overlayManager != null) {
            overlayManager.add(overlay);
            overlayManager.add(areaOverlay);
        }
        // Manual registration avoids LambdaConversionException with @Subscribe on some builds.
        chatMessageSubscriber = eventBus.register(ChatMessage.class, this::onChatMessage, 0.0f);
        varbitChangedSubscriber = eventBus.register(VarbitChanged.class, this::onVarbitChanged, 0.0f);
        script.run();
        log.info("Motherlode Mine startup complete");
    }

    @Override
    protected void shutDown() {
        log.info("Shutting down Motherlode Mine");
        script.shutdown();
        if (chatMessageSubscriber != null) {
            eventBus.unregister(chatMessageSubscriber);
            chatMessageSubscriber = null;
        }
        if (varbitChangedSubscriber != null) {
            eventBus.unregister(varbitChangedSubscriber);
            varbitChangedSubscriber = null;
        }
        if (overlayManager != null) {
            overlayManager.remove(overlay);
            overlayManager.remove(areaOverlay);
        }
        applyDebugLogLevel(false); // restore default level so a DEBUG override doesn't leak past shutdown
        log.info("Motherlode Mine shutdown complete");
    }

    private void onChatMessage(ChatMessage event) {
        ChatMessageType type = event.getType();
        String lower = event.getMessage().toLowerCase();

        // Golden-nugget totals are tracked from the inventory count in the script (monotonic, no
        // reliable chat message fires on sack withdraw), so chat parsing is only used for the
        // sack-full early warning here.
        if (type == ChatMessageType.GAMEMESSAGE
                && (lower.contains(CHAT_SACK_WILL_BE_FULL) || lower.contains(CHAT_SACK_IS_FULL))) {
            script.setSackIsFull(true);
        }
    }

    private void onVarbitChanged(VarbitChanged event) {
        if (event.getVarbitId() == IrkedMLMScript.SACK_COUNT_VARBIT) {
            script.onSackVarbitChanged(event.getValue());
        }
    }
}
