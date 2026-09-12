package net.runelite.client.plugins.microbot.blastoisefurnace;


import net.runelite.api.Skill;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.blastoisefurnace.enums.State;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;
import java.time.Duration;

public class BlastoiseFurnaceOverlay extends OverlayPanel {
    private static final Color ACCENT = new Color(255, 128, 0);   // furnace orange
    private static final Color VALUE = new Color(220, 220, 220);

    private final BlastoiseFurnacePlugin plugin;

    @Inject
    BlastoiseFurnaceOverlay(BlastoiseFurnacePlugin plugin) {
        super(plugin);
        this.plugin = plugin;
        this.setPosition(OverlayPosition.TOP_LEFT);
        this.setNaughty();
    }

    public Dimension render(Graphics2D graphics) {
        try {
            BlastoiseFurnaceScript script = plugin.blastoiseFurnaceScript;
            panelComponent.setPreferredSize(new Dimension(200, 0));
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Blastoise Furnace")
                    .color(ACCENT)
                    .build());
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("v" + BlastoiseFurnacePlugin.version)
                    .color(Color.GRAY)
                    .build());
            panelComponent.getChildren().add(LineComponent.builder().build());

            State state = script.state;
            line("State", prettyState(state), stateColor(state));

            long xpGained = Microbot.getClient().getSkillExperience(Skill.SMITHING) - script.sessionStartSmithXp;
            long elapsedMs = Math.max(1, System.currentTimeMillis() - script.sessionStartMs);
            long xpPerHour = xpGained * 3600000L / elapsedMs;
            int coffer = Microbot.getVarbitValue(VarbitID.BLAST_FURNACE_COFFER);

            line("Bars made", String.format("%,d", script.barsMade), VALUE);
            line("Smithing xp", String.format("%,d", xpGained), VALUE);
            line("Xp / hr", String.format("%,d", xpPerHour), VALUE);
            line("Coffer", String.format("%,d", coffer), coffer > 0 ? new Color(120, 220, 120) : Color.RED);
            line("Runtime", formatDuration(elapsedMs), VALUE);
        } catch (Exception ex) {
            Microbot.logStackTrace(this.getClass().getSimpleName(), ex);
        }

        return super.render(graphics);
    }

    private void line(String left, String right, Color rightColor) {
        panelComponent.getChildren().add(LineComponent.builder()
                .left(left)
                .right(right)
                .rightColor(rightColor)
                .build());
    }

    private String prettyState(State state) {
        switch (state) {
            case WALK_TO_FURNACE: return "Travelling";
            case BANKING:         return "Banking";
            case LOADING:         return "Loading";
            case WAITING:         return "Smelting";
            case COLLECTING:      return "Collecting";
            default:              return state.name();
        }
    }

    private Color stateColor(State state) {
        switch (state) {
            case WALK_TO_FURNACE: return Color.LIGHT_GRAY;
            case BANKING:         return new Color(120, 190, 255);
            case LOADING:         return ACCENT;
            case WAITING:         return Color.YELLOW;
            case COLLECTING:      return new Color(120, 220, 120);
            default:              return Color.WHITE;
        }
    }

    private String formatDuration(long ms) {
        Duration d = Duration.ofMillis(ms);
        return String.format("%02d:%02d:%02d", d.toHours(), d.toMinutesPart(), d.toSecondsPart());
    }
}
