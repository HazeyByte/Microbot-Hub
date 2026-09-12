package net.runelite.client.plugins.microbot.irkedmlm;

/**
 * Pure helper for sack projection decisions. The live script owns the mutable
 * inventory/client state; this class keeps the arithmetic testable.
 */
public final class SackTracker {
    private SackTracker() {
    }

    public static DepositProjection projectDeposit(int preSackCount, int initialPayDirt, int residualPayDirt, int maxSackSize, boolean alreadyFullFlagged) {
        int deposited = Math.max(0, initialPayDirt - residualPayDirt);
        int projected = preSackCount + deposited;
        return new DepositProjection(deposited, projected, maxSackSize > 0 && projected >= maxSackSize || alreadyFullFlagged);
    }

    public static int effectiveSackCount(int baseCount, int knownAfterLastDeposit, int justDeposited, long lastDepositTimestampMs, long nowMs, long projectionWindowMs) {
        if (justDeposited <= 0 || lastDepositTimestampMs <= 0) {
            return baseCount;
        }
        if (nowMs - lastDepositTimestampMs >= projectionWindowMs) {
            return baseCount;
        }
        if (baseCount >= knownAfterLastDeposit) {
            return baseCount;
        }
        return Math.max(baseCount, knownAfterLastDeposit);
    }

    public static final class DepositProjection {
        private final int deposited;
        private final int projectedSackCount;
        private final boolean sackFullAfterDeposit;

        private DepositProjection(int deposited, int projectedSackCount, boolean sackFullAfterDeposit) {
            this.deposited = deposited;
            this.projectedSackCount = projectedSackCount;
            this.sackFullAfterDeposit = sackFullAfterDeposit;
        }

        public int getDeposited() {
            return deposited;
        }

        public int getProjectedSackCount() {
            return projectedSackCount;
        }

        public boolean isSackFullAfterDeposit() {
            return sackFullAfterDeposit;
        }
    }
}
