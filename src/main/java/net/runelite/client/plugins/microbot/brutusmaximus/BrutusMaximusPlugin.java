package net.runelite.client.plugins.microbot.brutusmaximus;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;
import java.awt.event.MouseEvent;

@PluginDescriptor(
    name = PluginConstants.DEFAULT_PREFIX + "Brutus Maximus",
    description = "Automates Brutus encounter with food resupply, travel, dodging and loot.",
    tags = {"microbot", "boss", "combat", "cowboss"},
    version = BrutusMaximusPlugin.version,
    minClientVersion = "2.0.61",
    enabledByDefault = PluginConstants.DEFAULT_ENABLED,
    isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class BrutusMaximusPlugin extends Plugin {
    public static final String version = "1.2.88";

    @Inject
    private BrutusMaximusConfig config;

    @Inject
    private BrutusMaximusScript script;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private BrutusMaximusOverlay overlay;

    @Inject
    private MouseManager mouseManager;

    @Inject
    private EventBus eventBus;

    private EventBus.Subscriber gameTickSubscriber;
    private EventBus.Subscriber animationChangedSubscriber;
    private EventBus.Subscriber hitsplatAppliedSubscriber;
    private EventBus.Subscriber npcSpawnedSubscriber;
    private EventBus.Subscriber npcDespawnedSubscriber;

    private final MouseAdapter mouseAdapter = new MouseAdapter() {
        @Override
        public MouseEvent mousePressed(MouseEvent e) {
            if (overlay.handleMousePressed(e.getPoint())) {
                e.consume();
            }
            return e;
        }
    };

    @Provides
    @SuppressWarnings("unused")
    BrutusMaximusConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(BrutusMaximusConfig.class);
    }

    @Override
    protected void startUp() throws AWTException {
        overlayManager.add(overlay);
        mouseManager.registerMouseListener((MouseListener) mouseAdapter);
        gameTickSubscriber = eventBus.register(GameTick.class, script::onGameTick, 0f);
        animationChangedSubscriber = eventBus.register(AnimationChanged.class, script::onAnimationChanged, 0f);
        hitsplatAppliedSubscriber = eventBus.register(HitsplatApplied.class, script::onHitsplatApplied, 0f);
        npcSpawnedSubscriber = eventBus.register(NpcSpawned.class, script::onNpcSpawned, 0f);
        npcDespawnedSubscriber = eventBus.register(NpcDespawned.class, script::onNpcDespawned, 0f);
        script.run(config);
        log.info("Starting BrutusMaximus v{}", version);
    }

    @Override
    protected void shutDown() {
        script.shutdown();
        unregister(gameTickSubscriber);
        unregister(animationChangedSubscriber);
        unregister(hitsplatAppliedSubscriber);
        unregister(npcSpawnedSubscriber);
        unregister(npcDespawnedSubscriber);
        gameTickSubscriber = null;
        animationChangedSubscriber = null;
        hitsplatAppliedSubscriber = null;
        npcSpawnedSubscriber = null;
        npcDespawnedSubscriber = null;
        mouseManager.unregisterMouseListener((MouseListener) mouseAdapter);
        overlayManager.remove(overlay);
        log.info("Shutting down BrutusMaximus");
    }

    private void unregister(EventBus.Subscriber subscriber) {
        if (subscriber != null) {
            eventBus.unregister(subscriber);
        }
    }

}
