package net.runelite.client.plugins.microbot.irkedmlm;

import ch.qos.logback.classic.Level;
import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import net.runelite.api.ChatMessageType;
import net.runelite.api.coords.WorldPoint;
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
        description = "A bot that mines paydirt in the motherlode mine",
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

    public static final String version = "1.4.0";

    private static final String CHAT_SACK_WILL_BE_FULL = "your sack will be full";
    private static final String CHAT_SACK_IS_FULL = "your sack is full";

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
    private EventBus.Subscriber varbitChangedSubscriber;

    @Provides
    IrkedMLMConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(IrkedMLMConfig.class);
    }

    /** Package logger for this plugin (script + all sessions inherit it by package name). */
    private static final String LOG_PACKAGE = "net.runelite.client.plugins.microbot.irkedmlm";

    /**
     * Debug Mode routes rich context (sub-state transitions, vein selection, sack projections, …) through
     * {@code log.debug} across the script and every session. Those only surface if this package's logger
     * is at DEBUG — the client defaults to INFO, so toggling the config alone was a silent no-op. Setting
     * the level here is the single point that makes every {@code log.debug} call visible. Applied on
     * startUp from the current config; toggling live requires restarting the plugin.
     */
    private void applyDebugLogLevel(boolean debug) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LOG_PACKAGE))
                .setLevel(debug ? Level.DEBUG : Level.INFO);
    }

    @Override
    protected void startUp() {
        applyDebugLogLevel(config.debugMode());
        log.info("Starting Motherload Mine v{} — sack size: {}", version, config.sackSize());
        if (overlayManager != null) {
            overlayManager.add(IrkedMLMOverlay);
            overlayManager.add(IrkedMLMAreaOverlay);
        }
        // Manual registration avoids LambdaConversionException with @Subscribe on some builds.
        chatMessageSubscriber = eventBus.register(ChatMessage.class, this::onChatMessage, 0.0f);
        varbitChangedSubscriber = eventBus.register(VarbitChanged.class, this::onVarbitChanged, 0.0f);
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
        if (varbitChangedSubscriber != null) {
            eventBus.unregister(varbitChangedSubscriber);
            varbitChangedSubscriber = null;
        }
        if (overlayManager != null) {
            overlayManager.remove(IrkedMLMOverlay);
            overlayManager.remove(IrkedMLMAreaOverlay);
        }
        blacklistedCrates.clear();
        applyDebugLogLevel(false); // restore default level so a DEBUG override doesn't leak past shutdown
        log.info("Motherload Mine shutdown complete");
    }

    private void onChatMessage(ChatMessage event) {
        ChatMessageType type = event.getType();
        String lower = event.getMessage().toLowerCase();

        // Golden-nugget totals are tracked from the inventory count in the script (monotonic, no
        // reliable chat message fires on sack withdraw), so chat parsing is only used for the
        // sack-full early warning here.
        if (type == ChatMessageType.GAMEMESSAGE
                && (lower.contains(CHAT_SACK_WILL_BE_FULL) || lower.contains(CHAT_SACK_IS_FULL))) {
            IrkedMLMScript.setSackIsFull(true);
        }
    }

    private void onVarbitChanged(VarbitChanged event) {
        if (event.getVarbitId() == IrkedMLMScript.SACK_COUNT_VARBIT) {
            IrkedMLMScript.onSackVarbitChanged(event.getValue());
        }
    }
}
