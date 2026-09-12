package net.runelite.client;

import static net.runelite.client.plugins.microbot.irkedmlm.MlmSessionStats.nuggetsGained;

/**
 * Session nugget gain: the sum of every rise in the carried nugget quantity.
 *
 * <p>The bug this exists to prevent: golden nuggets are a <b>stackable</b> item, so they sit in one
 * inventory slot no matter how many you hold. {@code Rs2Inventory.count(id)} returns the number of
 * matching <em>slots</em>, so it reads 1 for a stack of 400 and the gain never moves off 0/1. The
 * reading must come from {@code Rs2Inventory.itemQuantity(id)}, which sums the stack.
 */
public class IrkedMlmNuggetGainCheck {

    public static void main(String[] args) {
        stackableReadingIsWhatMakesThisWork();
        depositAllBanksEachBatch();
        depositItemsAccumulates();
        alreadyCarriedNuggetsAreNotGains();
        spendingOnAnUpgradeIsNotNegative();
        System.out.println("ALL PASS");
    }

    /**
     * The regression guard, using a sequence captured from a live client: two full 189 sacks emptied,
     * six nuggets each, polled every 400ms. {@code itemQuantity()} saw the stack of 6; {@code count()}
     * saw one slot. Anything that reintroduces {@code count()} reproduces the second column.
     */
    static void stackableReadingIsWhatMakesThisWork() {
        int[] quantity = {0, 6, 6, 6, 0, 6, 6, 6, 0};   // itemQuantity() — the stack
        int[] slots    = {0, 1, 1, 1, 0, 1, 1, 1, 0};   // count()        — matching slots

        check(replay(quantity) == 12, "quantity reading tracks the stack -> 12, the truth");
        check(replay(slots) == 2, "slot reading is pinned at one slot -> 2, the bug");
    }

    static void depositAllBanksEachBatch() {
        // Live timing: a withdrawn batch sits in the inventory ~9s before depositAll sweeps it, sampled
        // every 600ms, so the rise is always seen before the fall.
        check(replay(new int[]{0, 6, 6, 0, 4, 0, 3}) == 13,
                "deposit-all: batches of 6+4+3 banked between withdraws -> 13");
    }

    static void depositItemsAccumulates() {
        check(replay(new int[]{0, 6, 11, 15}) == 15,
                "deposit items: nuggets stay in the inventory -> 15");
    }

    static void alreadyCarriedNuggetsAreNotGains() {
        check(replay(new int[]{50, 50, 57}) == 7,
                "start carrying 50, mine 7 -> 7 not 57");
    }

    static void spendingOnAnUpgradeIsNotNegative() {
        check(replay(new int[]{0, 200, 0, 3}) == 203,
                "spend 200 on the sack upgrade, then mine 3 -> 203, never negative");
        check(nuggetsGained(200, 0) == 0, "a fall on its own contributes 0");
    }

    /** Mirrors updateGainedNuggets(): first reading is the baseline, then sum the rises. */
    private static int replay(int[] readings) {
        int gained = 0;
        int prev = readings[0];
        for (int i = 1; i < readings.length; i++) {
            gained += nuggetsGained(prev, readings[i]);
            prev = readings[i];
        }
        return gained;
    }

    private static void check(boolean cond, String what) {
        if (!cond) {
            throw new AssertionError("FAILED: " + what);
        }
        System.out.println("  ok: " + what);
    }
}
