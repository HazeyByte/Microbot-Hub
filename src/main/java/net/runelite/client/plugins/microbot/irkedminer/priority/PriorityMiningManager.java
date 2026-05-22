/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 */
package net.runelite.client.plugins.microbot.irkedminer.priority;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.irkedminer.IrkedMinerConfig;
import net.runelite.client.plugins.microbot.irkedminer.data.Ores;
import net.runelite.client.plugins.microbot.irkedminer.handlers.RockSelector;
import net.runelite.client.plugins.microbot.irkedminer.priority.BatchTierOption;
import net.runelite.client.plugins.microbot.irkedminer.priority.PriorityMiningSubState;
import net.runelite.client.plugins.microbot.irkedminer.priority.PrioritySelection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PriorityMiningManager {
    private static final Logger log = LoggerFactory.getLogger(PriorityMiningManager.class);
    private static final long RATE_LIMIT_INVALID_MS = 30000L;
    private static final long RATE_LIMIT_NO_TARGETS_MS = 10000L;
    private static final long RATE_LIMIT_BANKING_MS = 5000L;
    private static final long LOCKED_TIER_MISS_CONFIRM_MS = 300L;
    private static final long PREEMPT_SCAN_INTERVAL_MS = 600L;
    private static final long PREEMPT_MIN_LOCK_HOLD_MS = 2500L;
    private static final long PREEMPT_CONFIRM_MS = 250L;
    private final IrkedMinerConfig config;
    private PriorityMiningSubState subState = PriorityMiningSubState.PM_DISABLED;
    private List<Ores> effectivePriorities = Collections.emptyList();
    private boolean priorityConfigValid;
    private boolean locked;
    private int lockedTier = -1;
    private Ores lockedOre;
    private long lockedTierMissingSinceMs = 0L;
    private long lockAcquiredAtMs = 0L;
    private long lastPreemptScanAtMs = 0L;
    private int preemptCandidateTier = -1;
    private Ores preemptCandidateOre;
    private long preemptCandidateSinceMs = 0L;
    private String invalidReason = "";
    private Ores fallbackOre;
    private final Map<String, Long> rateLimitedLogs = new ConcurrentHashMap<String, Long>();

    public PriorityMiningManager(IrkedMinerConfig config) {
        this.config = config;
    }

    public void reset(Ores fallbackOre) {
        this.fallbackOre = fallbackOre;
        this.clearLock();
        this.refreshConfigState();
    }

    public void refreshConfigState() {
        Ores ores = this.fallbackOre = this.config.targetOre() != null ? this.config.targetOre() : this.fallbackOre;
        if (!this.config.enablePriorityMining()) {
            this.clearLock();
            this.effectivePriorities = Collections.emptyList();
            this.priorityConfigValid = false;
            this.invalidReason = "";
            this.subState = PriorityMiningSubState.PM_DISABLED;
            return;
        }
        ParseResult parseResult = this.parsePrioritiesFromTierDropdowns();
        this.effectivePriorities = parseResult.effectivePriorities;
        this.priorityConfigValid = parseResult.valid;
        this.invalidReason = parseResult.reason;
        if (!this.priorityConfigValid) {
            if (this.locked) {
                this.clearLock();
            }
            this.subState = PriorityMiningSubState.PM_INVALID;
            this.logRateLimited("pm.invalid", 30000L, "Batch mining mode config is invalid: {}. Falling back to default target ore.", this.invalidReason);
            return;
        }
        if (this.subState == PriorityMiningSubState.PM_BANK_RESET) {
            this.subState = this.locked ? PriorityMiningSubState.PM_LOCKED : PriorityMiningSubState.PM_UNLOCKED;
            return;
        }
        this.subState = this.locked ? PriorityMiningSubState.PM_LOCKED : PriorityMiningSubState.PM_UNLOCKED;
    }

    public PrioritySelection resolveForFindTarget(RockSelector rockSelector) {
        this.refreshConfigState();
        if (!this.isPriorityOperational()) {
            return PrioritySelection.disabled();
        }
        if (this.locked) {
            return this.resolveLockedSelection(rockSelector);
        }
        return this.resolveUnlockedSelection(rockSelector, 0, false);
    }

    public void onInventoryFullTransition() {
        if (this.isPriorityOperational()) {
            this.logRateLimited("pm.inventoryFull", 5000L, "Inventory full -> banking", new Object[0]);
        }
    }

    public void onBankingSuccess() {
        if (!this.config.enablePriorityMining()) {
            return;
        }
        if (this.locked) {
            log.info("Banking complete -> lock reset");
        } else if (this.isPriorityOperational()) {
            this.logRateLimited("pm.bankReset.unlocked", 5000L, "Banking complete -> lock reset", new Object[0]);
        }
        this.clearLock();
        this.subState = PriorityMiningSubState.PM_BANK_RESET;
    }

    public String getOverlayModeLabel() {
        switch (this.subState) {
            case PM_INVALID: {
                return "INVALID";
            }
            case PM_DISABLED: {
                return "OFF";
            }
        }
        return "ON";
    }

    public String getOverlayLockedTierLabel() {
        if (!this.isPriorityOperational()) {
            return "-";
        }
        if (!this.locked || this.lockedOre == null || this.lockedTier < 0) {
            return "-";
        }
        return "P" + (this.lockedTier + 1) + " - " + this.stripRockSuffix(this.lockedOre.getName());
    }

    public boolean isPriorityOperational() {
        return this.config.enablePriorityMining() && this.priorityConfigValid;
    }

    public boolean isLocked() {
        return this.locked;
    }

    public int getLockedTier() {
        return this.lockedTier;
    }

    public Ores getLockedOre() {
        return this.lockedOre;
    }

    public String getInvalidReason() {
        return this.invalidReason;
    }

    private PrioritySelection resolveLockedSelection(RockSelector rockSelector) {
        if (!this.locked || this.lockedOre == null || this.lockedTier < 0) {
            return this.resolveUnlockedSelection(rockSelector, 0, false);
        }
        PrioritySelection preemptSelection = this.tryPreemptToHigherTier(rockSelector);
        if (preemptSelection != null) {
            return preemptSelection;
        }
        Rs2TileObjectModel rock = this.findFastTierTarget(rockSelector, this.lockedOre);
        if (rock != null) {
            this.lockedTierMissingSinceMs = 0L;
            this.subState = PriorityMiningSubState.PM_LOCKED;
            return PrioritySelection.target(this.lockedOre, rock);
        }
        long now = System.currentTimeMillis();
        if (this.lockedTierMissingSinceMs == 0L) {
            this.lockedTierMissingSinceMs = now;
            return PrioritySelection.noTargets();
        }
        if (now - this.lockedTierMissingSinceMs < 300L) {
            return PrioritySelection.noTargets();
        }
        int exhaustedTier = this.lockedTier;
        Ores exhaustedOre = this.lockedOre;
        this.clearLock();
        log.info("Priority exhausted: P{} - {}", (Object)(exhaustedTier + 1), (Object)this.stripRockSuffix(exhaustedOre.getName()));
        return this.resolveUnlockedSelection(rockSelector, exhaustedTier + 1, true);
    }

    private PrioritySelection tryPreemptToHigherTier(RockSelector rockSelector) {
        if (rockSelector == null || this.lockedTier <= 0 || this.effectivePriorities.isEmpty()) {
            this.clearPreemptCandidate();
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - this.lockAcquiredAtMs < 2500L) {
            return null;
        }
        if (this.lastPreemptScanAtMs != 0L && now - this.lastPreemptScanAtMs < 600L) {
            return null;
        }
        this.lastPreemptScanAtMs = now;
        int targetTier = -1;
        Ores targetOre = null;
        Rs2TileObjectModel targetRock = null;
        int upperBound = Math.min(this.lockedTier, this.effectivePriorities.size());
        for (int i = 0; i < upperBound; ++i) {
            Ores ore = this.effectivePriorities.get(i);
            Rs2TileObjectModel rock = this.findFastTierTarget(rockSelector, ore);
            if (rock == null) continue;
            targetTier = i;
            targetOre = ore;
            targetRock = rock;
            break;
        }
        if (targetTier < 0 || targetOre == null || targetRock == null) {
            this.clearPreemptCandidate();
            return null;
        }
        if (this.preemptCandidateTier != targetTier || this.preemptCandidateOre != targetOre) {
            this.preemptCandidateTier = targetTier;
            this.preemptCandidateOre = targetOre;
            this.preemptCandidateSinceMs = now;
            return null;
        }
        if (now - this.preemptCandidateSinceMs < 250L) {
            return null;
        }
        int previousTier = this.lockedTier;
        Ores previousOre = this.lockedOre;
        this.lockToTier(targetTier, targetOre);
        log.info("Priority preempted upward: P{} - {} -> P{} - {}", new Object[]{previousTier + 1, this.stripRockSuffix(previousOre != null ? previousOre.getName() : "Unknown"), targetTier + 1, this.stripRockSuffix(targetOre.getName())});
        return PrioritySelection.target(targetOre, targetRock);
    }

    private PrioritySelection resolveUnlockedSelection(RockSelector rockSelector, int startTierInclusive, boolean tierAdvance) {
        int start;
        if (this.effectivePriorities.isEmpty()) {
            this.logRateLimited("pm.noTargets.empty", 10000L, "No batch-priority ores found; executing fallback behavior", new Object[0]);
            this.subState = PriorityMiningSubState.PM_UNLOCKED;
            return PrioritySelection.noTargets();
        }
        for (int i = start = Math.max(0, startTierInclusive); i < this.effectivePriorities.size(); ++i) {
            Ores ore = this.effectivePriorities.get(i);
            Rs2TileObjectModel rock = this.findFastTierTarget(rockSelector, ore);
            if (rock == null) continue;
            this.lockToTier(i, ore);
            if (tierAdvance) {
                log.info("Priority advanced: P{} - {}", (Object)(i + 1), (Object)this.stripRockSuffix(ore.getName()));
            } else {
                log.info("Lock acquired: P{} - {}", (Object)(i + 1), (Object)this.stripRockSuffix(ore.getName()));
            }
            return PrioritySelection.target(ore, rock);
        }
        this.logRateLimited("pm.noTargets", 10000L, "No batch-priority ores found; executing fallback behavior", new Object[0]);
        this.subState = PriorityMiningSubState.PM_UNLOCKED;
        return PrioritySelection.noTargets();
    }

    private void lockToTier(int tier, Ores ore) {
        this.locked = true;
        this.lockedTier = tier;
        this.lockedOre = ore;
        this.lockedTierMissingSinceMs = 0L;
        this.lockAcquiredAtMs = System.currentTimeMillis();
        this.clearPreemptCandidate();
        this.subState = PriorityMiningSubState.PM_LOCKED;
    }

    private void clearLock() {
        this.locked = false;
        this.lockedTier = -1;
        this.lockedOre = null;
        this.lockedTierMissingSinceMs = 0L;
        this.lockAcquiredAtMs = 0L;
        this.lastPreemptScanAtMs = 0L;
        this.clearPreemptCandidate();
    }

    private void clearPreemptCandidate() {
        this.preemptCandidateTier = -1;
        this.preemptCandidateOre = null;
        this.preemptCandidateSinceMs = 0L;
    }

    private Rs2TileObjectModel findFastTierTarget(RockSelector rockSelector, Ores ore) {
        if (rockSelector == null || ore == null) {
            return null;
        }
        Rs2TileObjectModel rock = rockSelector.findNearestRockFastForOreKey(ore.getKey());
        if (rock != null) {
            return rock;
        }
        return rockSelector.findNearestRockForOreKey(ore.getKey());
    }

    private ParseResult parsePrioritiesFromTierDropdowns() {
        BatchTierOption[] tiers;
        LinkedHashSet<Ores> deduped = new LinkedHashSet<Ores>();
        for (BatchTierOption tier : tiers = new BatchTierOption[]{this.config.batchTier1(), this.config.batchTier2(), this.config.batchTier3(), this.config.batchTier4()}) {
            if (tier == null || tier.getOre() == null) continue;
            deduped.add(tier.getOre());
        }
        if (deduped.isEmpty()) {
            return ParseResult.invalid("no priority ores selected");
        }
        return ParseResult.valid(List.copyOf(deduped));
    }

    private String stripRockSuffix(String name) {
        if (name == null) {
            return "Unknown";
        }
        String trimmed = name.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.endsWith(" rocks")) {
            return trimmed.substring(0, trimmed.length() - " rocks".length()).trim();
        }
        if (lower.endsWith(" rock")) {
            return trimmed.substring(0, trimmed.length() - " rock".length()).trim();
        }
        return trimmed;
    }

    private void logRateLimited(String key, long intervalMs, String message, Object ... args) {
        long now = System.currentTimeMillis();
        Long previous = this.rateLimitedLogs.get(key);
        if (previous != null && now - previous < intervalMs) {
            return;
        }
        this.rateLimitedLogs.put(key, now);
        log.info(message, args);
    }

    public PriorityMiningSubState getSubState() {
        return this.subState;
    }

    public List<Ores> getEffectivePriorities() {
        return this.effectivePriorities;
    }

    public boolean isPriorityConfigValid() {
        return this.priorityConfigValid;
    }

    private static final class ParseResult {
        private final boolean valid;
        private final List<Ores> effectivePriorities;
        private final String reason;

        private ParseResult(boolean valid, List<Ores> effectivePriorities, String reason) {
            this.valid = valid;
            this.effectivePriorities = effectivePriorities;
            this.reason = reason;
        }

        private static ParseResult valid(List<Ores> priorities) {
            return new ParseResult(true, priorities, "");
        }

        private static ParseResult invalid(String reason) {
            return new ParseResult(false, Collections.emptyList(), reason);
        }
    }
}

