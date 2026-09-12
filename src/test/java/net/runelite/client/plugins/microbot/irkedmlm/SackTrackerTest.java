package net.runelite.client.plugins.microbot.irkedmlm;

import static net.runelite.client.plugins.microbot.irkedmlm.MlmAssert.assertEquals;
import static net.runelite.client.plugins.microbot.irkedmlm.MlmAssert.assertFalse;
import static net.runelite.client.plugins.microbot.irkedmlm.MlmAssert.assertTrue;

class SackTrackerTest {

    public static void main(String[] args) {
        SackTrackerTest t = new SackTrackerTest();
        t.projectsFullSackAfterDeposit();
        t.projectsPartialSackAfterDeposit();
        t.usesKnownCountInsideProjectionWindow();
        t.fallsBackToLiveCountAfterProjectionWindow();
        System.out.println("SackTrackerTest: OK");
    }

    void projectsFullSackAfterDeposit() {
        SackTracker.DepositProjection projection = SackTracker.projectDeposit(100, 28, 20, 108, false);

        assertEquals(8, projection.getDeposited());
        assertEquals(108, projection.getProjectedSackCount());
        assertTrue(projection.isSackFullAfterDeposit());
    }

    void projectsPartialSackAfterDeposit() {
        SackTracker.DepositProjection projection = SackTracker.projectDeposit(50, 28, 0, 108, false);

        assertEquals(28, projection.getDeposited());
        assertEquals(78, projection.getProjectedSackCount());
        assertFalse(projection.isSackFullAfterDeposit());
    }

    void usesKnownCountInsideProjectionWindow() {
        int effective = SackTracker.effectiveSackCount(0, 108, 8, 1_000L, 10_000L, 30_000L);

        assertEquals(108, effective);
    }

    void fallsBackToLiveCountAfterProjectionWindow() {
        int effective = SackTracker.effectiveSackCount(12, 108, 8, 1_000L, 40_000L, 30_000L);

        assertEquals(12, effective);
    }
}
