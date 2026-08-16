package net.runelite.client.plugins.microbot.irkedgoatkiller;

import net.runelite.client.plugins.microbot.Microbot;
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
            panelComponent.setPreferredSize(new Dimension(200, 0));
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("irkedGoatkiller")
                    .color(Color.ORANGE)
                    .build());
            line("State", script.state);
            line("Grabs", Integer.toString(script.grabs.get()));
            line("Clears", Integer.toString(script.clears.get()));
            line("Furs banked", Integer.toString(script.fursBanked.get()));
            line("Runtime", runtime());
        } catch (Exception ex) {
            Microbot.logStackTrace(getClass().getSimpleName(), ex);
        }
        return super.render(graphics);
    }

    private void line(String left, String right) {
        panelComponent.getChildren().add(LineComponent.builder().left(left).right(right).build());
    }

    private String runtime() {
        if (script.startMs == 0) return "0:00";
        Duration d = Duration.ofMillis(System.currentTimeMillis() - script.startMs);
        return String.format("%d:%02d:%02d", d.toHours(), d.toMinutesPart(), d.toSecondsPart());
    }
}
