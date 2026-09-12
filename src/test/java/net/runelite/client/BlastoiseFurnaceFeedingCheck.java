package net.runelite.client;

import net.runelite.client.plugins.microbot.blastoisefurnace.BlastoiseFurnaceScript;

import static net.runelite.client.plugins.microbot.blastoisefurnace.BlastoiseFurnaceScript.ACTION_COAL_AND_GOLD;
import static net.runelite.client.plugins.microbot.blastoisefurnace.BlastoiseFurnaceScript.ACTION_COAL_AND_PRIMARY;
import static net.runelite.client.plugins.microbot.blastoisefurnace.BlastoiseFurnaceScript.ACTION_DOUBLE_COAL;
import static net.runelite.client.plugins.microbot.blastoisefurnace.BlastoiseFurnaceScript.ACTION_PRIMARY;

/**
 * Self-check for the Blast Furnace feeding math — the batch-threshold decision that used to be
 * buried inside dispatchStandard/dispatchHybrid, plus the potion-dose parser that had an
 * unguarded NPE. `batch` = how many full coal-bag loads of coal are already in the furnace
 * (furnace coal varbit / coal-bag capacity). Run as a plain main() (repo has no JUnit); fails
 * loudly if a threshold, boundary, or the null-guard regresses.
 */
public class BlastoiseFurnaceFeedingCheck {
    public static void main(String[] args) {
        standard();
        hybrid();
        doseParsing();
        System.out.println("BlastoiseFurnaceFeedingCheck: OK");
    }

    /** STEEL/MITHRIL use doubleCoalMax=0; ADAMANT/RUNITE use doubleCoalMax=2. Above 6 loads → primary only. */
    private static void standard() {
        // doubleCoalMax = 0 (steel / mithril)
        check(BlastoiseFurnaceScript.standardAction(0, 0) == ACTION_DOUBLE_COAL, "batch 0, max 0 → double coal");
        check(BlastoiseFurnaceScript.standardAction(1, 0) == ACTION_COAL_AND_PRIMARY, "batch 1, max 0 → coal+primary");
        check(BlastoiseFurnaceScript.standardAction(6, 0) == ACTION_COAL_AND_PRIMARY, "batch 6, max 0 → coal+primary (upper boundary)");
        check(BlastoiseFurnaceScript.standardAction(7, 0) == ACTION_PRIMARY, "batch 7, max 0 → primary only");

        // doubleCoalMax = 2 (adamant / runite)
        check(BlastoiseFurnaceScript.standardAction(2, 2) == ACTION_DOUBLE_COAL, "batch 2, max 2 → double coal (boundary)");
        check(BlastoiseFurnaceScript.standardAction(3, 2) == ACTION_COAL_AND_PRIMARY, "batch 3, max 2 → coal+primary");
        check(BlastoiseFurnaceScript.standardAction(6, 2) == ACTION_COAL_AND_PRIMARY, "batch 6, max 2 → coal+primary");
        check(BlastoiseFurnaceScript.standardAction(7, 2) == ACTION_PRIMARY, "batch 7, max 2 → primary only");
    }

    /** Hybrids: coal+gold while batch <= goldThreshold, else coal+primary. Thresholds: mith 0, adam 2, rune 3. */
    private static void hybrid() {
        check(BlastoiseFurnaceScript.hybridAction(0, 0) == ACTION_COAL_AND_GOLD, "batch 0, thr 0 → coal+gold");
        check(BlastoiseFurnaceScript.hybridAction(1, 0) == ACTION_COAL_AND_PRIMARY, "batch 1, thr 0 → coal+primary");
        check(BlastoiseFurnaceScript.hybridAction(3, 3) == ACTION_COAL_AND_GOLD, "batch 3, thr 3 → coal+gold (boundary)");
        check(BlastoiseFurnaceScript.hybridAction(4, 3) == ACTION_COAL_AND_PRIMARY, "batch 4, thr 3 → coal+primary");
    }

    /** getDoseFromName must return the (n) dose and — the bug this guards — NOT throw on a suffix-less name. */
    private static void doseParsing() {
        check(BlastoiseFurnaceScript.getDoseFromName("Stamina potion(4)") == 4, "dose (4) parsed");
        check(BlastoiseFurnaceScript.getDoseFromName("Stamina potion(1)") == 1, "dose (1) parsed");
        check(BlastoiseFurnaceScript.getDoseFromName("Coal") == 0, "no suffix → 0, no NPE");
        check(BlastoiseFurnaceScript.getBaseName("Stamina potion(4)").equals("Stamina potion"), "base name strips dose");
        check(BlastoiseFurnaceScript.getBaseName("Coal").equals("Coal"), "base name passthrough");
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("BlastoiseFurnaceFeedingCheck FAILED: " + msg);
        }
    }
}
