package net.runelite.client.plugins.microbot.varrockanvil;

import lombok.Setter;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.text.NumberFormat;
import java.util.Locale;

public class VarrockAnvilOverlay extends OverlayPanel {

    private final VarrockAnvilConfig config;

    @Setter
    private VarrockAnvilScript script;

    @Inject
    VarrockAnvilOverlay(VarrockAnvilPlugin plugin, VarrockAnvilConfig config) {
        super(plugin);
        setPosition(OverlayPosition.TOP_LEFT);
        panelComponent.setPreferredSize(new Dimension(220, 0));
        this.config = config;
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (script == null || !script.isRunning() || !config.sShowOverlay()) return null;

        panelComponent.getChildren().clear();
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);

        panelComponent.getChildren().add(TitleComponent.builder()
                .text(" STTS Varrock Anvil")
                .color(Color.ORANGE)
                .build());

        panelComponent.getChildren().add(LineComponent.builder()
                .left("Making")
                .right(script.getTargetName())
                .rightColor(Color.WHITE)
                .build());

        panelComponent.getChildren().add(LineComponent.builder()
                .left("Bar")
                .right(config.sBarType().toString())
                .rightColor(Color.WHITE)
                .build());

        VarrockAnvilScript.State state = script.getState();
        Color stateColor;
        if (state == null) {
            stateColor = Color.YELLOW;
        } else {
            switch (state) {
                case SMITHING:
                    stateColor = new Color(0, 200, 83);
                    break;
                case BANKING:
                    stateColor = Color.CYAN;
                    break;
                case RECOVERY:
                    stateColor = Color.RED;
                    break;
                default:
                    stateColor = Color.YELLOW;
            }
        }

        panelComponent.getChildren().add(LineComponent.builder()
                .left("State")
                .right(state != null ? state.name() : "-")
                .rightColor(stateColor)
                .build());

        panelComponent.getChildren().add(LineComponent.builder()
                .left("Runtime")
                .right(formatTime(script.getElapsedTime()))
                .rightColor(Color.WHITE)
                .build());

        Color xpColor = new Color(0, 200, 83);

        panelComponent.getChildren().add(LineComponent.builder()
                .left("XP Gained")
                .right(nf.format(script.getXpGained()))
                .rightColor(xpColor)
                .build());

        panelComponent.getChildren().add(LineComponent.builder()
                .left("XP / hr")
                .right(nf.format(script.getXpPerHour()))
                .rightColor(xpColor)
                .build());

        int made = script.getItemsMade();

        panelComponent.getChildren().add(LineComponent.builder()
                .left("Items Made")
                .right(nf.format(made))
                .rightColor(Color.WHITE)
                .build());

        double hours = script.getElapsedTime() / 3600000.0;
        int itemsPerHour = (hours > 0.001) ? (int) (made / hours) : 0;

        panelComponent.getChildren().add(LineComponent.builder()
                .left("Items / hr")
                .right(nf.format(itemsPerHour))
                .rightColor(Color.WHITE)
                .build());

        if (config.sDebug() && script.debug != null && !script.debug.isEmpty()) {
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Status")
                    .right(script.debug)
                    .rightColor(Color.LIGHT_GRAY)
                    .build());
        }

        return super.render(graphics);
    }

    private String formatTime(long ms) {
        long s = ms / 1000;
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }
}