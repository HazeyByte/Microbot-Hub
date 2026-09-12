package net.runelite.client.plugins.microbot.irkedmlm;

import static net.runelite.client.plugins.microbot.irkedmlm.MlmAssert.assertFalse;
import static net.runelite.client.plugins.microbot.irkedmlm.MlmAssert.assertTrue;

/**
 * Guards the repair gate: pay-dirt only freezes when BOTH wheels are broken, so a repair is only
 * needed when no wheel is running. Previously the bot repaired on any broken strut, which meant it
 * repaired while one wheel was still running (and while another player was fixing the other wheel).
 */
public class WaterwheelRepairGateTest {

    public static void main(String[] args) {
        WaterwheelRepairGateTest t = new WaterwheelRepairGateTest();
        t.repairsOnlyWhenNoWheelRunning();
        t.skipsRepairWhileAWheelStillRuns();
        t.nothingBrokenNeverRepairs();
        System.out.println("WaterwheelRepairGateTest: OK");
    }

    public void repairsOnlyWhenNoWheelRunning() {
        // Both wheels broken (no running wheel) + a strut to click -> repair.
        assertTrue(IrkedMLMScript.waterwheelNeedsRepair(0, 2));
        assertTrue(IrkedMLMScript.waterwheelNeedsRepair(0, 1));
    }

    public void skipsRepairWhileAWheelStillRuns() {
        // One wheel running: water flows, hopper processes -> no repair even though a strut is broken.
        assertFalse(IrkedMLMScript.waterwheelNeedsRepair(1, 1));
        assertFalse(IrkedMLMScript.waterwheelNeedsRepair(2, 0));
    }

    public void nothingBrokenNeverRepairs() {
        assertFalse(IrkedMLMScript.waterwheelNeedsRepair(0, 0));
    }
}
