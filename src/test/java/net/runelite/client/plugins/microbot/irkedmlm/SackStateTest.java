package net.runelite.client.plugins.microbot.irkedmlm;

import static net.runelite.client.plugins.microbot.irkedmlm.MlmAssert.assertEquals;
import static net.runelite.client.plugins.microbot.irkedmlm.MlmAssert.assertFalse;
import static net.runelite.client.plugins.microbot.irkedmlm.MlmAssert.assertTrue;

class SackStateTest {

    public static void main(String[] args) {
        SackStateTest t = new SackStateTest();
        t.projectionActsAsFloorWhileVarbitLags();
        t.catchUpClearsProjectionOnExecutorPath();
        t.displayPathNeverClears();
        t.windowExpiryDropsProjection();
        t.fallbackTotalPrefersKnownThenPrePlusDeposited();
        t.clearPreDepositKeepsCompletedProjection();
        t.resetClearsEverything();
        t.noProjectionIsPassThrough();
        System.out.println("SackStateTest: OK");
    }

    /** During post-deposit lag the projection acts as a floor: if the live varbit still reads low,
     *  effective() returns the known-higher value so we don't under-estimate fullness. */
    void projectionActsAsFloorWhileVarbitLags() {
        SackState s = new SackState();
        long t0 = 1_000_000L;
        s.capturePreDeposit(160);
        int pre = s.consumePreDeposit(0);
        assertEquals(160, pre);
        s.applyProjection(20, 180, t0); // deposited 20, sack should be 180

        // varbit still lagging at 160 → floor to the known 180 (no clear on display path)
        assertEquals(180, s.effective(160, t0 + 1000, false));
        assertTrue(s.hasProjection(), "display read must not clear the projection");
    }

    /** Once the live varbit catches up, the executor path clears the projection and uses base. */
    void catchUpClearsProjectionOnExecutorPath() {
        SackState s = new SackState();
        long t0 = 1_000_000L;
        s.applyProjection(20, 180, t0);

        assertEquals(180, s.effective(180, t0 + 1000, true)); // base caught up
        assertFalse(s.hasProjection(), "projection cleared once varbit reaches known value");
    }

    /** The display path (mutate=false) must never clear, even after catch-up. */
    void displayPathNeverClears() {
        SackState s = new SackState();
        long t0 = 1_000_000L;
        s.applyProjection(20, 180, t0);

        s.effective(180, t0 + 1000, false);
        assertTrue(s.hasProjection(), "display path must leave state for the executor");
    }

    /** After the window expires the projection is dropped and base is returned. */
    void windowExpiryDropsProjection() {
        SackState s = new SackState();
        long t0 = 1_000_000L;
        s.applyProjection(20, 180, t0);

        long afterWindow = t0 + SackState.PROJECTION_WINDOW_MS + 1;
        assertEquals(160, s.effective(160, afterWindow, true));
        assertFalse(s.hasProjection());
        assertFalse(s.withinProjectionWindow(afterWindow));
    }

    /** varbit-reads-0 fallback: prefer the known post-deposit value, else pre+deposited. */
    void fallbackTotalPrefersKnownThenPrePlusDeposited() {
        SackState a = new SackState();
        a.applyProjection(20, 180, 1L);
        assertEquals(180, a.projectedFallbackTotal());

        SackState b = new SackState();
        b.capturePreDeposit(150);
        b.consumePreDeposit(0); // consumes to 0... so pre is gone
        b.applyProjection(20, 0, 1L); // known=0 → falls to pre(0)+deposited(20)
        assertEquals(20, b.projectedFallbackTotal());
    }

    /** Session reset clears only the in-flight pre-capture; a completed projection survives. */
    void clearPreDepositKeepsCompletedProjection() {
        SackState s = new SackState();
        s.applyProjection(20, 180, 5L);
        s.capturePreDeposit(170);

        s.clearPreDeposit();
        assertEquals(0, s.preDepositSackCount());
        assertTrue(s.hasProjection(), "completed projection must survive a session reset");
        assertEquals(180, s.knownAfterDeposit());
    }

    void resetClearsEverything() {
        SackState s = new SackState();
        s.applyProjection(20, 180, 5L);
        s.capturePreDeposit(170);
        s.reset();
        assertFalse(s.hasProjection());
        assertEquals(0, s.preDepositSackCount());
        assertEquals(0, s.lastDepositTimestamp());
    }

    /** No projection → effective() is a pass-through of base regardless of mutate. */
    void noProjectionIsPassThrough() {
        SackState s = new SackState();
        assertEquals(42, s.effective(42, 999L, true));
        assertEquals(42, s.effective(42, 999L, false));
    }
}
