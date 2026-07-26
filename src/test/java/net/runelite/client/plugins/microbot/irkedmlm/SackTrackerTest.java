package net.runelite.client.plugins.microbot.irkedmlm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SackTrackerTest {
    @Test
    void projectsFullSackAfterDeposit() {
        SackTracker.DepositProjection projection = SackTracker.projectDeposit(100, 28, 20, 108, false);

        assertEquals(8, projection.getDeposited());
        assertEquals(108, projection.getProjectedSackCount());
        assertTrue(projection.isSackFullAfterDeposit());
    }

    @Test
    void projectsPartialSackAfterDeposit() {
        SackTracker.DepositProjection projection = SackTracker.projectDeposit(50, 28, 0, 108, false);

        assertEquals(28, projection.getDeposited());
        assertEquals(78, projection.getProjectedSackCount());
        assertFalse(projection.isSackFullAfterDeposit());
    }

    @Test
    void usesKnownCountInsideProjectionWindow() {
        int effective = SackTracker.effectiveSackCount(0, 108, 8, 1_000L, 10_000L, 30_000L);

        assertEquals(108, effective);
    }

    @Test
    void fallsBackToLiveCountAfterProjectionWindow() {
        int effective = SackTracker.effectiveSackCount(12, 108, 8, 1_000L, 40_000L, 30_000L);

        assertEquals(12, effective);
    }
}
