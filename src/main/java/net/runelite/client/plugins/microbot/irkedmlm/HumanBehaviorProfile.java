package net.runelite.client.plugins.microbot.irkedmlm;

import net.runelite.client.plugins.microbot.util.math.Rs2Random;

/**
 * Centralised timing/odds profile for MLM-specific human-like behaviour.
 */
public final class HumanBehaviorProfile {
    private HumanBehaviorProfile() {
    }

    public static int pauseDelayMs(boolean humanLike, int minMs, int maxMs, boolean urgent) {
        if (!humanLike) {
            return 0;
        }
        int quickChance = urgent ? 65 : 32;
        if (Rs2Random.between(0, 100) < quickChance) {
            int qMin = Math.max(30, minMs / 2);
            int qMax = Math.min(220, (minMs + maxMs) / 2);
            if (qMax < qMin) {
                qMax = qMin + 30;
            }
            return Rs2Random.between(qMin, qMax);
        }
        return Rs2Random.between(minMs, maxMs);
    }

    public static long ladderInteractionCooldownMs(boolean humanLike) {
        return humanLike ? Rs2Random.between(500, 1_200) : 300L;
    }

    /**
     * Delay after a Climb-up/down click before the state machine re-evaluates. Kept mostly short
     * (the actual climb + floor-height update is covered by the animation guard elsewhere) with a
     * rare long tail so the descent doesn't feel like it "hangs" every time. Previously this was
     * top-heavy (1.1–4.6s) which, combined with a 5s animation guard, made ladder trips crawl.
     */
    public static long postLadderSettleDelayMs(boolean humanLike) {
        if (!humanLike) {
            return Rs2Random.between(250, 600);
        }

        int roll = Rs2Random.between(0, 100);
        if (roll < 60) {
            return Rs2Random.between(400, 900);
        }
        if (roll < 88) {
            return Rs2Random.between(900, 1800);
        }
        return Rs2Random.between(1800, 3500);
    }

    public static long repairPostLadderSettleDelayMs(boolean humanLike) {
        if (!humanLike) {
            return Rs2Random.between(250, 500);
        }

        // Mostly short — after descending to repair, a human is ready to go straight to the crate/wheel.
        int roll = Rs2Random.between(0, 100);
        if (roll < 65) {
            return Rs2Random.between(350, 800);
        }
        if (roll < 90) {
            return Rs2Random.between(800, 1500);
        }
        return Rs2Random.between(1500, 2500);
    }
}
