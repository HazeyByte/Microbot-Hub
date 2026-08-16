package net.runelite.client.plugins.microbot.irkedgoatkiller;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.time.Duration;

public class IrkedGoatkillerOverlay extends OverlayPanel {

    private static final String HORN = "Goat horn";
    private static final String FUR = "Wyrmscraig goat fur";
    private static final String SPIKES = "Wooden spikes";

    private final IrkedGoatkillerScript script;

    @Inject
    IrkedGoatkillerOverlay(IrkedGoatkillerPlugin plugin, IrkedGoatkillerScript script) {
        super(plugin);
        this.script = script;
        setPosition(OverlayPosition.TOP_LEFT);
        setNaughty();
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            panelComponent.setPreferredSize(new Dimension(215, 0));
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("irkedGoatkiller")
                    .color(Color.ORANGE)
                    .build());

            IrkedGoatkillerConfig cfg = script.config;
            line("Task", script.task.toString());
            if (cfg != null) line("Side", cfg.standSide().toString());
            line("Pit", script.pitLabel);
            line("Valid goats", Integer.toString(script.validGoats));
            line("Route", script.route);

            // Live inventory (safe getters; harmless if called before login).
            line("Inventory", (28 - Rs2Inventory.emptySlotCount()) + "/28");
            line("Fur (inv)", Integer.toString(Rs2Inventory.count(FUR)));
            line("Horns", Integer.toString(Rs2Inventory.count(HORN)));
            line("Spikes", Integer.toString(Rs2Inventory.count(SPIKES)));

            if (cfg != null) {
                line("Pouch", cfg.furPouch().toString());
                line("Keep horns", onOff(cfg.keepGoatHorns()));
                line("Drop fur", onOff(cfg.dropGoatFur()));
                line("Drop everything", onOff(cfg.dropEverything()));
                line("Agility", onOff(cfg.useAgilityShortcut()));
            }

            line("Grabs", Integer.toString(script.grabs.get()));
            line("Clears", Integer.toString(script.clears.get()));
            line("Pit lines", Integer.toString(script.relines.get()));
            line("Furs banked", Integer.toString(script.fursBanked.get()));
            line("Horns dropped", Integer.toString(script.hornsDropped.get()));
            line("Fur dropped", Integer.toString(script.furDropped.get()));
            line("Spikes taken", Integer.toString(script.spikesTaken.get()));
            line("Recoveries", Integer.toString(script.recoveries.get()));
            line("Runtime", runtime());
            if (!script.stopReason.isEmpty()) {
                line("Stopped", script.stopReason);
            }
        } catch (Exception ex) {
            Microbot.logStackTrace(getClass().getSimpleName(), ex);
        }
        return super.render(graphics);
    }

    private void line(String left, String right) {
        panelComponent.getChildren().add(LineComponent.builder().left(left).right(right).build());
    }

    private static String onOff(boolean b) {
        return b ? "ON" : "OFF";
    }

    private String runtime() {
        if (script.startMs == 0) return "0:00";
        Duration d = Duration.ofMillis(System.currentTimeMillis() - script.startMs);
        return String.format("%d:%02d:%02d", d.toHours(), d.toMinutesPart(), d.toSecondsPart());
    }
}
