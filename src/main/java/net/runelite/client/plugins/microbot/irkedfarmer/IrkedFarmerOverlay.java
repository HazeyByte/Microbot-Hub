package net.runelite.client.plugins.microbot.irkedfarmer;

import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.irkedfarmer.service.FarmDue;
import net.runelite.client.plugins.microbot.irkedfarmer.task.TaskScheduler;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.FarmingWorld;
import net.runelite.client.plugins.timetracking.Tab;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;

public class IrkedFarmerOverlay extends OverlayPanel {

    private final IrkedFarmerConfig config;
    private final FarmingWorld farmingWorld;
    private final ClientThread clientThread;
    private final ConfigManager configManager;

    // FarmDue predicts on the client thread, so cache instead of recomputing on every render frame.
    private static final long DUE_CHECK_INTERVAL_MS = 5000;
    private long lastDueCheckMs;
    private String treeStatus = "?";
    private String fruitStatus = "?";
    private String herbStatus = "?";

    @Inject
    IrkedFarmerOverlay(IrkedFarmerPlugin plugin, IrkedFarmerConfig config, FarmingWorld farmingWorld,
                        ClientThread clientThread, ConfigManager configManager) {
        super(plugin);
        this.config = config;
        this.farmingWorld = farmingWorld;
        this.clientThread = clientThread;
        this.configManager = configManager;
        setPosition(OverlayPosition.TOP_LEFT);
        setNaughty();
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            panelComponent.setPreferredSize(new Dimension(220, 0));
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("irkedFarmer")
                    .color(Color.ORANGE)
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Status:")
                    .right(TaskScheduler.status)
                    .build());

            refreshDueStatusIfStale();
            addDueLine("Trees", config.treeRun(), treeStatus);
            addDueLine("Fruit", config.fruitTreeRun(), fruitStatus);
            addDueLine("Herbs", config.herbRun(), herbStatus);
        } catch (Exception ex) {
            Microbot.logStackTrace(this.getClass().getSimpleName(), ex);
        }
        return super.render(graphics);
    }

    private void addDueLine(String label, boolean enabled, String status) {
        if (!enabled) return;
        panelComponent.getChildren().add(LineComponent.builder().left(label + ":").right(status).build());
    }

    /** No per-patch growth-stage timer yet (would need FarmingHandler's exact tick math) — ready/growing only. */
    private void refreshDueStatusIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastDueCheckMs < DUE_CHECK_INTERVAL_MS) {
            return;
        }
        lastDueCheckMs = now;
        if (config.treeRun()) {
            treeStatus = FarmDue.anyReady(farmingWorld, clientThread, configManager, Tab.TREE) ? "ready" : "growing";
        }
        if (config.fruitTreeRun()) {
            fruitStatus = FarmDue.anyReady(farmingWorld, clientThread, configManager, Tab.FRUIT_TREE) ? "ready" : "growing";
        }
        if (config.herbRun()) {
            herbStatus = FarmDue.anyReady(farmingWorld, clientThread, configManager, Tab.HERB) ? "ready" : "growing";
        }
    }
}
