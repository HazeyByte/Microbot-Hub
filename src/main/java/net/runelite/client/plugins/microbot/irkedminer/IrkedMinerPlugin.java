/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.inject.Provides
 *  com.google.inject.Singleton
 *  javax.inject.Inject
 *  net.runelite.client.config.ConfigManager
 *  net.runelite.client.input.MouseAdapter
 *  net.runelite.client.input.MouseListener
 *  net.runelite.client.input.MouseManager
 *  net.runelite.client.plugins.Plugin
 *  net.runelite.client.plugins.PluginDescriptor
 *  net.runelite.client.plugins.microbot.Microbot
 *  net.runelite.client.ui.overlay.Overlay
 *  net.runelite.client.ui.overlay.OverlayManager
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 */
package net.runelite.client.plugins.microbot.irkedminer;

import com.google.inject.Provides;
import com.google.inject.Singleton;
import java.awt.event.MouseEvent;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.irkedminer.IrkedMinerConfig;
import net.runelite.client.plugins.microbot.irkedminer.IrkedMinerOverlay;
import net.runelite.client.plugins.microbot.irkedminer.IrkedMinerScript;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@PluginDescriptor(name="<html>[<font color=#b8f704>MB</font>] irkedMiner", description="Advanced mining plugin with state-based architecture", version="1.1.2", minClientVersion="2.1.0", authors={"irkedMATT"}, tags={"mining", "skill", "bot", "auto"}, iconUrl="https://i.imgur.com/placeholder.png", isExternal=true, enabledByDefault=false)
public class IrkedMinerPlugin
extends Plugin {
    private static final Logger log = LoggerFactory.getLogger(IrkedMinerPlugin.class);
    static final String version = "1.1.2";
    @Inject
    private IrkedMinerScript script;
    @Inject
    private IrkedMinerOverlay overlay;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private MouseManager mouseManager;
    @Inject
    private IrkedMinerConfig config;
    @Inject
    private ConfigManager configManager;
    private final MouseAdapter mouseAdapter = new MouseAdapter(){

        public MouseEvent mousePressed(MouseEvent e) {
            if (IrkedMinerPlugin.this.overlay.handleMousePressed(e.getPoint())) {
                e.consume();
            }
            return e;
        }
    };

    @Provides
    IrkedMinerConfig provideConfig(ConfigManager configManager) {
        return (IrkedMinerConfig)configManager.getConfig(IrkedMinerConfig.class);
    }

    public static boolean isPluginEnabled() {
        return true;
    }

    public void resetOverlayStats() {
        this.overlay.resetStats();
    }

    protected void startUp() {
        log.info("Starting irkedMiner v{}", (Object)version);
        this.normalizeInventoryModeConfig();
        this.overlayManager.add((Overlay)this.overlay);
        this.mouseManager.registerMouseListener((MouseListener)this.mouseAdapter);
        if (Microbot.isLoggedIn()) {
            this.resetOverlayStats();
        }
        try {
            if (this.script.start(this.config)) {
                log.info("irkedMiner started successfully");
            } else {
                log.warn("Failed to start irkedMiner script");
            }
        }
        catch (Exception ex) {
            log.error("Error starting Irked Miner", (Throwable)ex);
        }
    }

    protected void shutDown() {
        log.info("Shutting down Irked Miner");
        this.overlayManager.remove((Overlay)this.overlay);
        this.mouseManager.unregisterMouseListener((MouseListener)this.mouseAdapter);
        try {
            this.script.shutdown();
        }
        catch (Exception ex) {
            log.error("Error shutting down irkedMiner", (Throwable)ex);
        }
    }

    private void normalizeInventoryModeConfig() {
        if (this.config.useBank() && this.config.useDepositBox()) {
            this.configManager.setConfiguration("IrkedMiner", "useDepositBox", (Object)false);
            log.info("Both Use Bank and Use Deposit Box were enabled; disabled Use Deposit Box");
        }
    }
}

