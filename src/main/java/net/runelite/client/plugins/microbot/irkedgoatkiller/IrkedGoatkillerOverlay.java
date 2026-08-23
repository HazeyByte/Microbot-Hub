package net.runelite.client.plugins.microbot.irkedgoatkiller;

import net.runelite.api.Skill;
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

            // --- Hunter XP block (rendered on the client thread, so reading the client is safe) ---
            xpBlock();

            line("Task", script.task.toString());
            line("Pit", script.pitLabel + "  " + script.grabsSinceLine + "/" + script.pitCapacity);
            line("Inventory", (28 - Rs2Inventory.emptySlotCount()) + "/28");
            line("Grabs", Integer.toString(script.grabs.get()));
            line("Clears", Integer.toString(script.clears.get()));
            line("Furs banked", Integer.toString(script.fursBanked.get()));
            line("Runtime", runtime());
            if (!script.stopReason.isEmpty()) {
                colored("Stopped", Color.RED, script.stopReason, Color.RED);
            }

            IrkedGoatkillerConfig cfg = script.config;
            if (cfg != null && cfg.debugOverlay() && !script.debugSummary.isEmpty()) {
                panelComponent.getChildren().add(TitleComponent.builder().text("— targets —").color(GREY).build());
                for (String row : script.debugSummary.split("\n")) {
                    Color c = row.contains("VALID") || row.startsWith("TARGET") ? GREEN
                            : row.contains("OTHER_PLAYER") ? GOLD
                            : row.contains("NO TARGET") ? Color.RED : GREY;
                    panelComponent.getChildren().add(LineComponent.builder().left(row).leftColor(c).build());
                }
            }
        } catch (Exception ex) {
            Microbot.logStackTrace(getClass().getSimpleName(), ex);
        }
        return super.render(graphics);
    }

    private static final Color GREEN = new Color(0x4CD964);
    private static final Color CYAN  = new Color(0x35C4E0);
    private static final Color GOLD  = new Color(0xFFC94D);
    private static final Color GREY  = new Color(0xB0B0B0);

    /** Sexy Hunter-XP readout: current level, XP gained (+levels), and XP/hr, colour-coded. */
    private void xpBlock() {
        long startXp = script.startHunterXp;
        if (startXp <= 0) {   // not captured yet (pre-login)
            line("Hunter", "—");
            return;
        }
        int curXp = Microbot.getClient().getSkillExperience(Skill.HUNTER);
        int curLvl = Microbot.getClient().getRealSkillLevel(Skill.HUNTER);
        int lvlGain = curLvl - script.startHunterLevel;
        long gained = curXp - startXp;
        long ms = System.currentTimeMillis() - script.startMs;
        long perHr = ms > 1000 ? gained * 3_600_000L / ms : 0;

        String lvlRight = lvlGain > 0
                ? script.startHunterLevel + " → " + curLvl + "  (+" + lvlGain + ")"
                : Integer.toString(curLvl);
        colored("Hunter lvl", GOLD, lvlRight, lvlGain > 0 ? GREEN : Color.WHITE);
        colored("XP gained", GREY, "+" + String.format("%,d", gained), GREEN);
        colored("XP/hr", GREY, String.format("%,d", perHr), CYAN);
    }

    private void colored(String left, Color lc, String right, Color rc) {
        panelComponent.getChildren().add(LineComponent.builder()
                .left(left).leftColor(lc).right(right).rightColor(rc).build());
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
