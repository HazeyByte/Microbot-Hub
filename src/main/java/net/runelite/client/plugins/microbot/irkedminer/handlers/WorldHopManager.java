/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.runelite.api.Skill
 *  net.runelite.client.plugins.microbot.Microbot
 *  net.runelite.client.plugins.microbot.globval.enums.InterfaceTab
 *  net.runelite.client.plugins.microbot.util.player.Rs2Player
 *  net.runelite.client.plugins.microbot.util.tabs.Rs2Tab
 *  net.runelite.http.api.worlds.World
 *  net.runelite.http.api.worlds.WorldResult
 *  net.runelite.http.api.worlds.WorldType
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 */
package net.runelite.client.plugins.microbot.irkedminer.handlers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.globval.enums.InterfaceTab;
import net.runelite.client.plugins.microbot.irkedminer.IrkedMinerConfig;
import net.runelite.client.plugins.microbot.irkedminer.IrkedMinerState;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldResult;
import net.runelite.http.api.worlds.WorldType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorldHopManager {
    private static final Logger log = LoggerFactory.getLogger(WorldHopManager.class);
    private static final int MAX_CONSECUTIVE_HOPS = 10;
    private static final int WORLD_HOP_COOLDOWN_SECONDS = 8;
    private static final int POST_HOP_SETTLE_MS = 300;
    private static final int HOP_COMPLETION_TIMEOUT_MS = 10000;
    private static final int MAX_HOP_FAILURES_BEFORE_RECOVERY = 3;
    private static final long HOP_FAILURE_RETRY_DELAY_MS = 2000L;
    private static final int MAX_EXCLUDED_WORLDS = 20;
    private static final int RECENT_WORLD_MEMORY = 6;
    private static final long TAB_SWITCH_RETRY_MS = 450L;
    private static final long TAB_SWITCH_HARD_TIMEOUT_MS = 8000L;
    private static final int MAX_WORLD_POPULATION = 900;
    private static final int TARGET_WORLD_POPULATION = 450;
    private static final Pattern SKILL_TOTAL_PATTERN = Pattern.compile("(\\d{3,4})\\s*(?:skill\\s*total|total\\s*level|total)", 2);
    private static final EnumSet<WorldType> DISALLOWED_WORLD_TYPES = EnumSet.of(WorldType.PVP, new WorldType[]{WorldType.HIGH_RISK, WorldType.BOUNTY, WorldType.LAST_MAN_STANDING, WorldType.QUEST_SPEEDRUNNING, WorldType.BETA_WORLD, WorldType.DEADMAN, WorldType.PVP_ARENA, WorldType.TOURNAMENT, WorldType.FRESH_START_WORLD, WorldType.SEASONAL});
    private final IrkedMinerConfig config;
    private final Consumer<IrkedMinerState> changeState;
    private final Supplier<String> selectedOreNameSupplier;
    private final Supplier<String> selectedOreKeySupplier;
    private final Runnable refreshCache;
    private final Runnable resetOreRetries;
    private long lastWorldHopTime = 0L;
    private int worldHops = 0;
    private int hopsSinceMining = 0;
    private int consecutiveHopFailures = 0;
    private long lastHopFailureAtMs = 0L;
    private HopPhase hopPhase = HopPhase.IDLE;
    private int hopStartWorld = -1;
    private int hopTargetWorld = -1;
    private long hopAttemptStartedAtMs = 0L;
    private long hopVerifyDeadlineAtMs = 0L;
    private long hopSettleUntilMs = 0L;
    private long tabSwitchHardDeadlineAtMs = 0L;
    private long lastTabSwitchAttemptAtMs = 0L;
    private final Set<Integer> excludedWorlds = new HashSet<Integer>();
    private final ArrayDeque<Integer> recentWorldHistory = new ArrayDeque();
    private final Set<Integer> recentWorldSet = new HashSet<Integer>();
    private final Random random = new Random();

    public WorldHopManager(IrkedMinerConfig config, Consumer<IrkedMinerState> changeState, Supplier<String> selectedOreNameSupplier, Supplier<String> selectedOreKeySupplier, Runnable refreshCache, Runnable resetOreRetries) {
        this.config = config;
        this.changeState = changeState;
        this.selectedOreNameSupplier = selectedOreNameSupplier;
        this.selectedOreKeySupplier = selectedOreKeySupplier;
        this.refreshCache = refreshCache;
        this.resetOreRetries = resetOreRetries;
    }

    public void reset() {
        this.lastWorldHopTime = 0L;
        this.worldHops = 0;
        this.hopsSinceMining = 0;
        this.consecutiveHopFailures = 0;
        this.lastHopFailureAtMs = 0L;
        this.resetHopPhase();
        this.excludedWorlds.clear();
        this.recentWorldHistory.clear();
        this.recentWorldSet.clear();
    }

    public void onMiningProgress() {
        this.hopsSinceMining = 0;
        this.consecutiveHopFailures = 0;
    }

    public void cancelPendingHop() {
        if (this.hopPhase != HopPhase.IDLE) {
            this.resetHopPhase();
        }
    }

    public boolean isWorldHopOnCooldown() {
        if (this.isRunitePriorityHopping()) {
            return false;
        }
        long cooldownMs = 8000L;
        if (this.lastWorldHopTime == 0L) {
            return false;
        }
        return System.currentTimeMillis() - this.lastWorldHopTime < cooldownMs;
    }

    private boolean isRunitePriorityHopping() {
        if (this.selectedOreKeySupplier == null) {
            return false;
        }
        String oreKey = this.selectedOreKeySupplier.get();
        return oreKey != null && oreKey.equalsIgnoreCase("runite");
    }

    public void executeWorldHopping() {
        long now = System.currentTimeMillis();
        if (this.hopPhase == HopPhase.VERIFYING) {
            this.handleHopVerification(now);
            return;
        }
        if (this.hopPhase == HopPhase.SETTLING) {
            this.handlePostHopSettle(now);
            return;
        }
        if (Microbot.isHopping()) {
            Microbot.status = "Client already hopping...";
            return;
        }
        if (this.lastHopFailureAtMs > 0L && now - this.lastHopFailureAtMs < 2000L) {
            Microbot.status = "Retrying world hop...";
            return;
        }
        if (this.hopsSinceMining >= 10) {
            log.error("Reached max consecutive world hops ({}), stopping", (Object)10);
            Microbot.log((String)("Unable to find " + this.selectedOreNameSupplier.get() + " after " + this.hopsSinceMining + " consecutive world hops. Stopping."));
            this.changeState.accept(IrkedMinerState.STOP);
            return;
        }
        if (this.isWorldHopOnCooldown()) {
            long waitTime = 8000L - (System.currentTimeMillis() - this.lastWorldHopTime);
            Microbot.status = String.format("Hop cooldown: %ds remaining", Math.max(0L, waitTime / 1000L));
            return;
        }
        if (Microbot.getClient().getLocalPlayer() != null && (Microbot.getClient().getLocalPlayer().isInteracting() || Rs2Player.isMoving())) {
            Microbot.status = "Preparing to hop...";
            return;
        }
        int startWorld = Microbot.getClient().getWorld();
        if (startWorld <= 0) {
            Microbot.status = "Unable to read current world";
            return;
        }
        int targetWorld = this.selectTargetWorld(startWorld);
        if (targetWorld <= 0) {
            log.warn("No eligible target world found, entering recovery");
            this.changeState.accept(IrkedMinerState.RECOVER);
            return;
        }
        log.info("Attempting world hop: {} -> {}", (Object)startWorld, (Object)targetWorld);
        this.hopStartWorld = startWorld;
        this.hopTargetWorld = targetWorld;
        this.hopAttemptStartedAtMs = now;
        this.hopVerifyDeadlineAtMs = now + 10000L;
        this.hopPhase = HopPhase.VERIFYING;
        try {
            Microbot.hopToWorld((int)targetWorld);
        }
        catch (Exception ex) {
            log.error("Error starting world hop to {}: {}", new Object[]{targetWorld, ex.getMessage(), ex});
            this.onHopFailure(now, "start-failed");
        }
    }

    private void handleHopVerification(long now) {
        int currentWorld = Microbot.getClient().getWorld();
        if (currentWorld > 0 && currentWorld != this.hopStartWorld) {
            ++this.worldHops;
            ++this.hopsSinceMining;
            this.consecutiveHopFailures = 0;
            this.lastHopFailureAtMs = 0L;
            this.lastWorldHopTime = now;
            this.resetOreRetries.run();
            this.rememberRecentWorld(currentWorld);
            this.excludedWorlds.add(this.hopStartWorld);
            this.trimExcludedWorlds();
            log.info("Successfully hopped to world {}", (Object)currentWorld);
            this.hopPhase = HopPhase.SETTLING;
            this.hopSettleUntilMs = now + 300L;
            this.tabSwitchHardDeadlineAtMs = this.hopSettleUntilMs + 8000L;
            this.lastTabSwitchAttemptAtMs = 0L;
            return;
        }
        if (now >= this.hopVerifyDeadlineAtMs) {
            this.onHopFailure(now, "verify-timeout");
            return;
        }
        Microbot.status = "Hopping worlds...";
    }

    private void handlePostHopSettle(long now) {
        if (now < this.hopSettleUntilMs) {
            Microbot.status = "Stabilizing after hop...";
            return;
        }
        if (!this.ensureInventoryTab(now)) {
            Microbot.status = "Finalizing hop...";
            return;
        }
        this.refreshCache.run();
        long totalDuration = Math.max(0L, now - this.hopAttemptStartedAtMs);
        log.info("Post-hop timings: settle={}ms total={}ms (delegating rock reacquire to FIND_TARGET)", (Object)300, (Object)totalDuration);
        this.resetHopPhase();
        this.changeState.accept(IrkedMinerState.FIND_TARGET);
    }

    private boolean ensureInventoryTab(long now) {
        if (this.isInventoryTabActive()) {
            return true;
        }
        if (now - this.lastTabSwitchAttemptAtMs >= 450L) {
            this.lastTabSwitchAttemptAtMs = now;
            this.attemptInventoryTabSwitch();
        }
        if (this.isInventoryTabActive()) {
            return true;
        }
        if (now < this.tabSwitchHardDeadlineAtMs) {
            Microbot.status = "Switching to inventory...";
            return false;
        }
        log.warn("Inventory tab not confirmed after {}ms post-hop; entering recovery", (Object)8000L);
        this.resetHopPhase();
        this.changeState.accept(IrkedMinerState.RECOVER);
        return false;
    }

    private void attemptInventoryTabSwitch() {
        if (this.isInventoryTabActive()) {
            return;
        }
        boolean switchedByHotkey = false;
        try {
            switchedByHotkey = Rs2Tab.switchToInventoryTab();
        }
        catch (Exception exception) {
            // empty catch block
        }
        if (!this.isInventoryTabActive()) {
            try {
                Rs2Tab.switchTo((InterfaceTab)InterfaceTab.INVENTORY);
            }
            catch (Exception exception) {
                // empty catch block
            }
        }
        if (this.config.enableDebugLogging() && !this.isInventoryTabActive() && !switchedByHotkey) {
            log.debug("Inventory tab switch attempt not yet confirmed");
        }
    }

    private boolean isInventoryTabActive() {
        try {
            if (Rs2Tab.isCurrentTab((InterfaceTab)InterfaceTab.INVENTORY)) {
                return true;
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
        try {
            return Rs2Tab.getCurrentTab() == InterfaceTab.INVENTORY;
        }
        catch (Exception ignored) {
            return false;
        }
    }

    private void onHopFailure(long now, String reason) {
        this.excludedWorlds.add(this.hopTargetWorld);
        this.trimExcludedWorlds();
        ++this.consecutiveHopFailures;
        this.lastHopFailureAtMs = now;
        if (this.consecutiveHopFailures >= 3) {
            log.warn("World hop failed {} times ({}), entering recovery", (Object)this.consecutiveHopFailures, (Object)reason);
            this.consecutiveHopFailures = 0;
            this.resetHopPhase();
            this.changeState.accept(IrkedMinerState.RECOVER);
            return;
        }
        log.warn("World hop failed ({}) [{}/{}], will retry", new Object[]{reason, this.consecutiveHopFailures, 3});
        this.resetHopPhase();
    }

    private void resetHopPhase() {
        this.hopPhase = HopPhase.IDLE;
        this.hopStartWorld = -1;
        this.hopTargetWorld = -1;
        this.hopAttemptStartedAtMs = 0L;
        this.hopVerifyDeadlineAtMs = 0L;
        this.hopSettleUntilMs = 0L;
        this.tabSwitchHardDeadlineAtMs = 0L;
        this.lastTabSwitchAttemptAtMs = 0L;
    }

    private void rememberRecentWorld(int world) {
        if (this.recentWorldSet.add(world)) {
            this.recentWorldHistory.addLast(world);
            while (this.recentWorldHistory.size() > 6) {
                Integer removed = this.recentWorldHistory.removeFirst();
                if (removed == null) continue;
                this.recentWorldSet.remove(removed);
            }
        }
    }

    private void trimExcludedWorlds() {
        if (this.excludedWorlds.size() <= 20) {
            return;
        }
        Iterator<Integer> it = this.excludedWorlds.iterator();
        for (int removeCount = this.excludedWorlds.size() - 20; it.hasNext() && removeCount > 0; --removeCount) {
            it.next();
            it.remove();
        }
    }

    private int selectTargetWorld(int startWorld) {
        FilterStats strictStats;
        int myTotalLevel = this.getPlayerTotalLevel();
        WorldResult worldResult = null;
        if (Microbot.getWorldService() != null) {
            worldResult = Microbot.getWorldService().getWorlds();
        }
        if (worldResult == null || worldResult.getWorlds() == null || worldResult.getWorlds().isEmpty()) {
            log.warn("World service unavailable; cannot apply safe world filters, skipping hop attempt");
            return -1;
        }
        List worlds = worldResult.getWorlds();
        List<World> strictCandidates = this.collectEligibleWorlds(worlds, startWorld, myTotalLevel, true, strictStats = new FilterStats());
        if (!strictCandidates.isEmpty()) {
            return this.pickCandidateWorld(strictCandidates);
        }
        FilterStats relaxedStats = new FilterStats();
        List<World> relaxedCandidates = this.collectEligibleWorlds(worlds, startWorld, myTotalLevel, false, relaxedStats);
        if (!relaxedCandidates.isEmpty()) {
            if (this.config.enableDebugLogging()) {
                log.info("No eligible worlds after recent/excluded filters; reusing older worlds this hop");
            }
            return this.pickCandidateWorld(relaxedCandidates);
        }
        log.warn("No eligible hop worlds after filtering (current={}, excluded={}, recent={}, nonMembers={}, pop/full={}, special={}, totalLevel={})", new Object[]{strictStats.rejectedCurrent, strictStats.rejectedExcluded, strictStats.rejectedRecent, strictStats.rejectedMembers, strictStats.rejectedPopulation, strictStats.rejectedSpecial, strictStats.rejectedTotal});
        return -1;
    }

    private List<World> collectEligibleWorlds(List<World> worlds, int startWorld, int myTotalLevel, boolean applyHistoryFilters, FilterStats stats) {
        ArrayList<World> candidates = new ArrayList<World>();
        for (World world : worlds) {
            if (world == null) continue;
            int worldId = world.getId();
            if (worldId <= 0 || worldId == startWorld) {
                ++stats.rejectedCurrent;
                continue;
            }
            if (applyHistoryFilters && this.excludedWorlds.contains(worldId)) {
                ++stats.rejectedExcluded;
                continue;
            }
            if (applyHistoryFilters && this.recentWorldSet.contains(worldId)) {
                ++stats.rejectedRecent;
                continue;
            }
            if (!this.isMemberWorld(world)) {
                ++stats.rejectedMembers;
                continue;
            }
            if (!this.hasValidPopulation(world)) {
                ++stats.rejectedPopulation;
                continue;
            }
            if (this.isDisallowedWorldType(world)) {
                ++stats.rejectedSpecial;
                continue;
            }
            int requiredTotalLevel = this.getRequiredTotalLevel(world);
            if (requiredTotalLevel > 0 && (myTotalLevel <= 0 || myTotalLevel < requiredTotalLevel)) {
                ++stats.rejectedTotal;
                continue;
            }
            candidates.add(world);
        }
        return candidates;
    }

    private int pickCandidateWorld(List<World> candidates) {
        candidates.sort(Comparator.comparingInt(world -> Math.abs(450 - Math.max(0, world.getPlayers()))));
        int selectionPool = Math.min(8, candidates.size());
        World selected = candidates.get(this.random.nextInt(selectionPool));
        log.info("Selected hop world {} with population {} (target ~{})", new Object[]{selected.getId(), Math.max(0, selected.getPlayers()), 450});
        return selected.getId();
    }

    private int getPlayerTotalLevel() {
        if (Microbot.getClient() == null || Microbot.getClient().getLocalPlayer() == null) {
            return -1;
        }
        int totalLevel = 0;
        for (Skill skill : Skill.values()) {
            int level;
            if (skill == Skill.OVERALL || (level = Microbot.getClient().getRealSkillLevel(skill)) <= 0) continue;
            totalLevel += level;
        }
        return totalLevel > 0 ? totalLevel : -1;
    }

    private boolean isMemberWorld(World world) {
        EnumSet types = world.getTypes();
        return types != null && types.contains(WorldType.MEMBERS);
    }

    private boolean hasValidPopulation(World world) {
        int population = world.getPlayers();
        return population >= 0 && population < 900;
    }

    private boolean isDisallowedWorldType(World world) {
        String activity;
        EnumSet types = world.getTypes();
        if (types != null) {
            if (types.stream().anyMatch(DISALLOWED_WORLD_TYPES::contains)) {
                return true;
            }
        }
        if ((activity = world.getActivity()) == null || activity.isBlank()) {
            return false;
        }
        String normalized = activity.toLowerCase(Locale.ROOT);
        return normalized.contains("pvp") || normalized.contains("deadman") || normalized.contains("high risk") || normalized.contains("bounty") || normalized.contains("last man standing") || normalized.contains("tournament") || normalized.contains("speedrunning") || normalized.contains("beta") || normalized.contains("fresh start");
    }

    private int getRequiredTotalLevel(World world) {
        String activity = world.getActivity();
        if (activity == null || activity.isBlank()) {
            return -1;
        }
        Matcher matcher = SKILL_TOTAL_PATTERN.matcher(activity);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            }
            catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    public long getLastWorldHopTime() {
        return this.lastWorldHopTime;
    }

    public int getWorldHops() {
        return this.worldHops;
    }

    private static final class FilterStats {
        private int rejectedCurrent;
        private int rejectedExcluded;
        private int rejectedRecent;
        private int rejectedMembers;
        private int rejectedPopulation;
        private int rejectedSpecial;
        private int rejectedTotal;

        private FilterStats() {
        }
    }

    private static enum HopPhase {
        IDLE,
        VERIFYING,
        SETTLING;

    }
}

