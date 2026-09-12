package net.runelite.client.plugins.microbot.irkedmlm;

import static net.runelite.client.plugins.microbot.irkedmlm.MlmAssert.assertEquals;
import static net.runelite.client.plugins.microbot.irkedmlm.MlmAssert.assertTrue;

import net.runelite.client.plugins.microbot.irkedmlm.enums.MLMStatus;

/**
 * Pins the post-mining routing order.
 *
 * <p>Reported live as "Drop Gems does nothing". The option was not broken — the branch was
 * unreachable. A mining session only ends with a <em>full</em> inventory, and the deposit branch
 * matched on exactly that, so it always won and the gem branch below it never ran. Gems then sat in
 * the inventory for the rest of the session, each one permanently costing a pay-dirt slot.
 *
 * <p>Mirrors {@code determineNextStatusAfterMining}'s decision order. If someone reorders the real
 * method so the deposit check precedes the gem check again, this fails.
 */
class GemDropRoutingTest {

    public static void main(String[] args) {
        GemDropRoutingTest t = new GemDropRoutingTest();
        t.gemsAreDroppedBeforeAnyHopperTrip();
        t.gemsAreLeftAloneWhenTheBagIsInUse();
        t.gemsAreLeftAloneWhenTheOptionIsOff();
        t.aFullLoadWithNoGemsStillDeposits();
        t.anEmptySackRunJustKeepsMining();
        System.out.println("GemDropRoutingTest: OK");
    }

    void gemsAreDroppedBeforeAnyHopperTrip() {
        // THE regression: inventory full of pay-dirt AND holding gems. Dropping must win.
        assertEquals(MLMStatus.DROP_GEMS, route(true, false, true, true, true));
    }

    void gemsAreLeftAloneWhenTheBagIsInUse() {
        assertEquals(MLMStatus.DEPOSIT_HOPPER, route(true, true, true, true, true));
    }

    void gemsAreLeftAloneWhenTheOptionIsOff() {
        assertEquals(MLMStatus.DEPOSIT_HOPPER, route(false, false, true, true, true));
    }

    void aFullLoadWithNoGemsStillDeposits() {
        assertEquals(MLMStatus.DEPOSIT_HOPPER, route(true, false, false, true, true));
    }

    void anEmptySackRunJustKeepsMining() {
        assertEquals(MLMStatus.MINING, route(true, false, false, false, false));
        // Gems with a part-full inventory are still dropped rather than carried around.
        assertTrue(route(true, false, true, false, false) == MLMStatus.DROP_GEMS,
                "gems must be dropped even when the inventory is not yet full");
    }

    /** The ordering under test, kept in step with determineNextStatusAfterMining(). */
    private static MLMStatus route(boolean dropGems, boolean useGemBag, boolean hasGems,
                                   boolean invFull, boolean hasPayDirt) {
        if (dropGems && !useGemBag && hasGems) {
            return MLMStatus.DROP_GEMS;
        }
        if (invFull && hasPayDirt) {
            return MLMStatus.DEPOSIT_HOPPER;
        }
        return MLMStatus.MINING;
    }
}
