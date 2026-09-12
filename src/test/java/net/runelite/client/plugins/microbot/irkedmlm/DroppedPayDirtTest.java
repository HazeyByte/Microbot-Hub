package net.runelite.client.plugins.microbot.irkedmlm;

import static net.runelite.client.plugins.microbot.irkedmlm.MlmAssert.assertEquals;
import static net.runelite.client.plugins.microbot.irkedmlm.MlmAssert.assertTrue;

import net.runelite.api.coords.WorldPoint;

/**
 * Pins when dropped pay-dirt is worth going back for.
 *
 * <p>Reported live: with the sack full the bot dropped the pay-dirt it was carrying, emptied the
 * sack, then walked off and left the pile on the floor. That is several minutes of mining abandoned,
 * and no player would do it. The rules below are what stop the fix turning into its own problem —
 * chasing a pile that has despawned, or one the run has already walked away from.
 */
class DroppedPayDirtTest {

    private static final WorldPoint BOX = new WorldPoint(3759, 5665, 0);
    private static final long T0 = 5_000_000L;

    public static void main(String[] args) {
        DroppedPayDirtTest t = new DroppedPayDirtTest();
        t.aFreshPileIsWorthCollecting();
        t.nothingDroppedMeansNothingToCollect();
        t.aDespawnedPileIsWrittenOff();
        t.aPileIsNotChasedOnceWeHaveMovedOn();
        t.collectionNeedsSomewhereToPutIt();
        t.aStuckCollectionGivesUp();
        t.aSecondDropReplacesTheClaim();
        System.out.println("DroppedPayDirtTest: OK");
    }

    void aFreshPileIsWorthCollecting() {
        DroppedPayDirt d = new DroppedPayDirt();
        d.note(BOX, 27, T0);
        assertTrue(d.isPending(T0 + 1_000), "a pile just dropped must still be claimed");
        assertTrue(d.shouldCollect(T0 + 1_000, 27, true), "should go back for 27 pay-dirt at the drop site");
        assertEquals(27, d.getCount());
    }

    void nothingDroppedMeansNothingToCollect() {
        DroppedPayDirt d = new DroppedPayDirt();
        assertTrue(!d.isPending(T0), "no drop recorded, nothing pending");
        assertTrue(!d.shouldCollect(T0, 28, true), "must not hunt for a pile that was never dropped");
        d.note(BOX, 0, T0); // a drop that moved nothing
        assertTrue(!d.isPending(T0), "a zero-item drop must not create a claim");
    }

    void aDespawnedPileIsWrittenOff() {
        DroppedPayDirt d = new DroppedPayDirt();
        d.note(BOX, 27, T0);
        long late = T0 + DroppedPayDirt.CLAIM_TTL_MS + 1;
        assertTrue(d.isExpired(late), "past the TTL the pile has despawned");
        assertTrue(!d.shouldCollect(late, 27, true), "must not stand around waiting for a pile that is gone");
    }

    void aPileIsNotChasedOnceWeHaveMovedOn() {
        // Walking back across the mine for 27 pay-dirt costs more than it's worth and reads as a bot.
        DroppedPayDirt d = new DroppedPayDirt();
        d.note(BOX, 27, T0);
        assertTrue(!d.shouldCollect(T0 + 1_000, 27, false), "must not walk back from the mining spot");
    }

    void collectionNeedsSomewhereToPutIt() {
        DroppedPayDirt d = new DroppedPayDirt();
        d.note(BOX, 27, T0);
        assertTrue(!d.shouldCollect(T0 + 1_000, 0, true), "a full inventory cannot receive the pile");
    }

    void aStuckCollectionGivesUp() {
        // Unreachable pile, or pickups that never register: the run must not stall here.
        DroppedPayDirt d = new DroppedPayDirt();
        d.note(BOX, 27, T0);
        d.beginCollecting(T0 + 500);
        assertTrue(!d.collectTimedOut(T0 + 5_000), "gave up far too early");
        long stuck = T0 + 500 + DroppedPayDirt.COLLECT_TIMEOUT_MS + 1;
        assertTrue(d.collectTimedOut(stuck), "collection never timed out — the run would hang here");
        assertTrue(!d.shouldCollect(stuck, 27, true), "a timed-out collection must stop being attempted");
    }

    void aSecondDropReplacesTheClaim() {
        DroppedPayDirt d = new DroppedPayDirt();
        d.note(BOX, 27, T0);
        WorldPoint elsewhere = new WorldPoint(3748, 5672, 0);
        d.note(elsewhere, 12, T0 + 60_000);
        assertEquals(12, d.getCount());
        assertEquals(elsewhere, d.getWhere());
        // and the TTL restarts from the newer drop, not the old one
        assertTrue(d.isPending(T0 + 60_000 + 10_000), "the newer claim must be live");
    }
}
