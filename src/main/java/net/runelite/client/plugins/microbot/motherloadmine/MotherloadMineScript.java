package net.runelite.client.plugins.microbot.motherloadmine;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.motherloadmine.enums.MLMMiningSpot;
import net.runelite.client.plugins.microbot.motherloadmine.enums.MLMSackSize;
import net.runelite.client.plugins.microbot.motherloadmine.enums.MLMStatus;
import net.runelite.client.plugins.microbot.motherloadmine.enums.Pickaxe;
import net.runelite.client.plugins.microbot.motherloadmine.session.HopperSession;
import net.runelite.client.plugins.microbot.motherloadmine.session.MiningSession;
import net.runelite.client.plugins.microbot.motherloadmine.session.RepairSession;
import net.runelite.client.plugins.microbot.motherloadmine.session.SackSession;
import net.runelite.client.plugins.microbot.motherloadmine.session.SessionSnapshot;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.antiban.enums.Activity;
import net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Gembag;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

@Slf4j
@Singleton
public class MotherloadMineScript extends Script {

    private static final int  SACK_LARGE_SIZE = 189;
    private static final int  SACK_SIZE        = 108;

    private static final int MAX_RECOVERY_ATTEMPTS = 5;

    /** Varbit ID for the amount of ore currently in the sack. */
    private static final int SACK_COUNT_VARBIT = 5558;

    /**
     * OSRS player 300 — special attack energy, scaled 0–1000 (i.e. 100% = 1000).
     */
    private static final int VARP_SPEC_ENERGY = 300;

    /**
     * Special-attack pickaxes and their minimum spec energy required (out of 1000).
     */
    private static final String[] DRAGON_INFERNAL_PICKAXES = {"dragon pickaxe", "infernal pickaxe"};
    private static final String[] CRYSTAL_PICKAXES         = {"crystal pickaxe"};
    private static final int SPEC_ENERGY_DRAGON_INFERNAL   = 500;
    private static final int SPEC_ENERGY_CRYSTAL           = 1000;

    /**
     * Minimum wall-clock time (ms) that must elapse before we allow to begin() to be
     * called again on the hopper or sack sessions.
     */
    private static final long LADDER_INTERACTION_COOLDOWN_MS = 5_000L;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    @Getter private MLMStatus     status     = MLMStatus.IDLE;
    @Getter private MLMMiningSpot miningSpot = null;

    /** Atomic reference so overlay always sees latest snapshot without stale refs. */
    private final AtomicReference<SessionSnapshot> snapshotRef = new AtomicReference<>(SessionSnapshot.empty());

    private int maxSackSize;

    private int tickBrokenStrutCount = 0;

    /**
     * Cached local player name to avoid background thread violations.
     */
    private String localPlayerName = "";

    /**
     * Set to true when a chat message indicates the sack is full or will be full.
     * Reset when the sack is confirmed empty.
     */
    private volatile boolean sackIsFullFlag = false;

    // -------------------------------------------------------------------------
    // Sessions
    // -------------------------------------------------------------------------

    private final MotherloadMinePlugin plugin;
    private final MotherloadMineConfig config;

    private final MiningSession  miningSession;
    private final HopperSession  hopperSession;
    private final SackSession    sackSession;
    private final RepairSession  repairSession;

    // -------------------------------------------------------------------------
    // Timing / counters
    // -------------------------------------------------------------------------

    private final AtomicLong    globalLastXpTime = new AtomicLong(0L);
    private final AtomicInteger recoveryAttempts = new AtomicInteger(0);

    private MLMStatus lastLoggedStatus = null;
    private int       lastMiningXp     = -1;
    private long      recoveryWaitUntil = 0L;

    /**
     * Timestamp of the last time we called begin() on either the hopper or sack
     * session. Used to enforce {@link #LADDER_INTERACTION_COOLDOWN_MS}.
     */
    private long lastLadderInteractionMs = 0L;

    private long lastSpecTime = 0L;
    private static final long SPEC_COOLDOWN_MS = 60_000L;
    /** Random variance added to spec cooldown so it's not robotic */
    private long specCooldownVarianceMs = 0L;
    /** After using spec, we need to re-click the vein */
    private boolean needPostSpecMiningResume = false;
    /** The vein we were mining before spec, for resumption */
    private WorldPoint preSpecVeinPoint = null;

    // -------------------------------------------------------------------------
    // Session tracking
    // -------------------------------------------------------------------------

    private long startTimeMs                   = 0L;
    @Getter private int startXp                = 0;
    private int  gainedNuggets                 = 0;
    private long lastHopperDepositTimestampMs  = 0L;
    private long totalValueGained              = 0L;

    /** Pay-dirt count that was just deposited in the last hopper session.
     *  Used to project sack fill without waiting for varbit lag. */
    private int  payDirtJustDeposited          = 0;

    private final java.util.Map<Integer, Integer> lastInventoryCounts = new java.util.HashMap<>();

    /**
     * Items tracked for session economy stats.
     */
    private static final java.util.List<Integer> TRACKED_ITEMS = java.util.Arrays.asList(
            ItemID.MOTHERLODE_NUGGET, ItemID.RUNITE_ORE, ItemID.ADAMANTITE_ORE,
            ItemID.MITHRIL_ORE, ItemID.GOLD_ORE, ItemID.COAL,
            ItemID.UNCUT_SAPPHIRE, ItemID.UNCUT_EMERALD, ItemID.UNCUT_RUBY, ItemID.UNCUT_DIAMOND,
            ItemID.PAYDIRT
    );

    // -------------------------------------------------------------------------
    // Per-ore session counters
    // -------------------------------------------------------------------------

    private int sessionRunite      = 0;
    private int sessionAdamantite  = 0;
    private int sessionMithril     = 0;
    private int sessionGold        = 0;
    private int sessionCoal        = 0;

    /** Maximum nuggets observed in inventory this session (robust count). */
    private int maxNuggetsSeen     = 0;

    // -------------------------------------------------------------------------
    // Mining spot shuffle timer
    // -------------------------------------------------------------------------

    private long lastSpotShuffleMs = 0L;
    private static final long SPOT_SHUFFLE_INTERVAL_MS = 300_000L; // 5 minutes
    private WorldPoint currentAnchor = null;

    // -------------------------------------------------------------------------
    // Gem bag tracking
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------

    @Inject
    public MotherloadMineScript(MotherloadMinePlugin plugin, MotherloadMineConfig config) {
        this.plugin = plugin;
        this.config = config;

        var tileCache = Microbot.getRs2TileObjectCache();
        this.miningSession = new MiningSession(tileCache, config);
        this.hopperSession = new HopperSession(tileCache, config);
        this.sackSession   = new SackSession(tileCache, config);
        this.repairSession = new RepairSession(tileCache);
    }

    // =========================================================================
    // Debug logging wrapper
    // =========================================================================

    private void debug(String msg) {
        if (config.debugMode()) {
            log.debug(msg);
        }
    }

    private void debug(String msg, Object... args) {
        if (config.debugMode()) {
            log.debug(msg, args);
        }
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    public boolean run() {
        log.info("Starting Motherload Mine script v{} (session-oriented)", MotherloadMinePlugin.version);
        initialise();
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(
                this::executeTask, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }

    private void initialise() {
        log.debug("Initialising MLM runtime state");
        configureAntibanSettings();

        startTimeMs                  = System.currentTimeMillis();
        if (startXp == 0) {
            int xp = Microbot.getClientThread().runOnClientThreadOptional(() -> Microbot.getClient().getSkillExperience(Skill.MINING)).orElse(0);
            if (xp > 0) {
                startXp = xp;
                log.info("MLM startXp initialised: {}", startXp);
            }
        }
        gainedNuggets                = 0;
        sessionRunite                = 0;
        sessionAdamantite            = 0;
        sessionMithril               = 0;
        sessionGold                  = 0;
        sessionCoal                  = 0;
        maxNuggetsSeen               = 0;
        miningSpot                   = null;
        lastLoggedStatus             = null;
        lastMiningXp                 = startXp;
        lastHopperDepositTimestampMs = 0L;
        totalValueGained             = 0L;
        recoveryAttempts.set(0);
        recoveryWaitUntil            = 0L;
        lastLadderInteractionMs      = 0L;
        lastSpotShuffleMs            = 0L;
        currentAnchor                = null;
        lastSpecTime                 = 0L;
        specCooldownVarianceMs       = Rs2Random.between(-5000, 10000); // -5s to +10s variance
        needPostSpecMiningResume     = false;
        preSpecVeinPoint             = null;

        localPlayerName = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            var lp = Microbot.getClient().getLocalPlayer();
            return (lp != null) ? lp.getName() : "";
        }).orElse("");

        lastInventoryCounts.clear();
        for (int itemId : TRACKED_ITEMS) {
            lastInventoryCounts.put(itemId, Rs2Inventory.count(itemId));
        }
        cacheItemPrices();

        updateSackSize();

        status = MLMStatus.IDLE;

        updateSnapshot();

        log.info("MLM Initialised — startXp={}, sackSize={}/{}, spot={}",
                startXp, currentSackCount(), maxSackSize, miningSpot);
    }

    private void configureAntibanSettings() {
        Rs2Antiban.resetAntibanSettings();
        Rs2Antiban.antibanSetupTemplates.applyMiningSetup();

        Rs2AntibanSettings.usePlayStyle = true;

        Rs2Antiban.setActivity(Activity.GENERAL_MINING);
        Rs2Antiban.setActivityIntensity(ActivityIntensity.MODERATE);

        Rs2AntibanSettings.dynamicIntensity = true;
        Rs2AntibanSettings.dynamicActivity = true;

        Rs2AntibanSettings.naturalMouse = true;
        Rs2AntibanSettings.simulateMistakes = true;
        Rs2AntibanSettings.simulateFatigue = true;

        Rs2AntibanSettings.moveMouseOffScreen = true;
        Rs2AntibanSettings.moveMouseOffScreenChance = 0.25;

        Rs2AntibanSettings.moveMouseRandomly = true;
        Rs2AntibanSettings.moveMouseRandomlyChance = 0.15;

        Rs2AntibanSettings.actionCooldownChance = 0.15;

        Rs2AntibanSettings.behavioralVariability = true;

        Rs2AntibanSettings.nonLinearIntervals = true;

        Rs2AntibanSettings.takeMicroBreaks = false;
        Rs2AntibanSettings.microBreakChance = 0.0;

        Rs2AntibanSettings.contextualVariability = true;

        log.info("MLM Antiban configured — usePlayStyle=true, dynamicIntensity=true, dynamicActivity=true");
    }

    // =========================================================================
    // Main loop
    // =========================================================================

    private void executeTask() {
        try {
            if (!super.run() || !Microbot.isLoggedIn()) {
                debug("Execution paused: script not runnable or player not logged in");
                resetAllSessions();
                return;
            }

            // Handle post-spec mining resumption first
            if (needPostSpecMiningResume) {
                handlePostSpecMining();
                return;
            }

            updateSessionStats();

            int currentXp = Microbot.getClientThread().runOnClientThreadOptional(() -> Microbot.getClient().getSkillExperience(Skill.MINING)).orElse(-1);
            if (lastMiningXp != -1 && currentXp > lastMiningXp) {
                globalLastXpTime.set(System.currentTimeMillis());
                miningSession.onMiningXp();
            }
            lastMiningXp = currentXp;

            if (Rs2AntibanSettings.actionCooldownActive) return;

            updateSackSize();
            tickBrokenStrutCount = queryBrokenStrutCount();

            if (!hasRequiredTools()) {
                Microbot.showMessage("Missing required tools (Pickaxe). Please ensure you have one.");
                log.warn("Missing required tools (Pickaxe), stopping plugin");
                Microbot.stopPlugin(plugin);
                return;
            }

            if (Rs2Gembag.isUnknown()) {
                Rs2Gembag.checkGemBag();
            }

            determineStatus();
            logStatusTransitionIfChanged();

            // -------------------------------------------------------------------------
            // PRIORITY 1 — tick active sessions
            // -------------------------------------------------------------------------

            if (!miningSession.isIdle()) {
                if (status != MLMStatus.MINING && !miningSession.isLocked() && !miningSession.isCommitted()) {
                    miningSession.reset();
                } else {
                    if (miningSpot == null) {
                        log.warn("[MLM] Mining session active but miningSpot is null — aborting session");
                        miningSession.reset();
                        status = MLMStatus.RECOVERY;
                        return;
                    }
                    int effectiveSackForMining = currentSackCount();
                    if (payDirtJustDeposited > 0) {
                        effectiveSackForMining += payDirtJustDeposited;
                        debug("[MLM] Passing projected sack count to MiningSession: {}/{}",
                                effectiveSackForMining, maxSackSize);
                    }
                    miningSession.tick(miningSpot, effectiveSackForMining, maxSackSize, globalLastXpTime);
                    if (miningSession.isMiningComplete() || miningSession.isFailed()) {
                        boolean failed = miningSession.isFailed();
                        miningSession.reset();
                        status = failed ? MLMStatus.RECOVERY : determineNextStatusAfterMining();
                    }
                    return;
                }
            }

            if (!sackSession.isIdle()) {
                sackSession.tick(currentSackCount(), maxSackSize);
                if (sackSession.isComplete() || sackSession.isFailed()) {
                    boolean    failed      = sackSession.isFailed();
                    WorldPoint returnPoint = sackSession.getReturnPoint();
                    sackSession.reset();
                    if (failed) {
                        log.warn("[MLM] Sack session failed - moving to recovery");
                        status = MLMStatus.RECOVERY;
                    } else {
                        if (returnPoint != null) Rs2Walker.walkFastCanvas(returnPoint);
                        status = MLMStatus.IDLE;
                    }
                }
                return;
            }

            if (!hopperSession.isIdle()) {
                hopperSession.tick();
                if (hopperSession.isComplete() || hopperSession.isFailed()) {
                    boolean wasComplete = hopperSession.isComplete();
                    hopperSession.reset();

                    if (wasComplete) {
                        handlePostDepositAudit();
                    } else {
                        if (isSackFull() || hasOreInInventory()) {
                            status = MLMStatus.EMPTY_SACK;
                        } else if (tickBrokenStrutCount >= 2 && isWaterwheelClear()) {
                            status = MLMStatus.FIXING_WATERWHEEL;
                        } else {
                            status = MLMStatus.RECOVERY;
                        }
                    }
                }
                return;
            }

            if (!repairSession.isIdle()) {
                repairSession.tick();
                if (repairSession.isComplete() || repairSession.isFailed()) {
                    repairSession.reset();
                    status = determineNextStatusAfterRepair();
                }
                return;
            }

            // -------------------------------------------------------------------------
            // Animation guard — only blocks starting new sessions while animating.
            // IDLE and MINING are allowed through so the state machine never stalls.
            // -------------------------------------------------------------------------
            if ((Rs2Player.isAnimating() || Rs2Player.isInteracting())
                    && status != MLMStatus.MINING && status != MLMStatus.IDLE) {
                return;
            }

            // -------------------------------------------------------------------------
            // PRIORITY 2 — dispatch
            // -------------------------------------------------------------------------
            dispatchByStatus();
        } catch (Exception e) {
            log.error("[MLM] executeTask crash", e);
        } finally {
            updateSnapshot();
        }
    }

    // =========================================================================
    // Special Attack
    // =========================================================================

    /**
     * Handles dragon/infernal/crystal pickaxe special attack.
     * Only activates when:
     * - Player is in a mining area (not walking to hopper/sack)
     * - Mining session is active or about to start
     * - Enough spec energy
     * - Cooldown has passed (with random variance)
     * After using spec, sets flag to resume mining since spec animation interrupts mining.
     */
    private void handlePickaxeSpec() {
        long now = System.currentTimeMillis();
        long effectiveCooldown = SPEC_COOLDOWN_MS + specCooldownVarianceMs;

        if (now - lastSpecTime < effectiveCooldown) {
            return;
        }

        // Only spec when we're actually mining or about to mine
        if (status != MLMStatus.MINING && status != MLMStatus.IDLE) {
            debug("[MLM] Spec skipped: not in mining area (status={})", status);
            return;
        }

        // Don't spec if we're not near mining veins
        if (!isInMiningArea()) {
            debug("[MLM] Spec skipped: not near mining veins");
            return;
        }

        int specEnergy = Microbot.getRs2PlayerStateCache().getVarpValue(VARP_SPEC_ENERGY);
        boolean specUsed = false;

        if (Rs2Equipment.isWearing(CRYSTAL_PICKAXES)) {
            if (specEnergy >= SPEC_ENERGY_CRYSTAL) {
                // 15% chance to skip this tick even if conditions met (human hesitation)
                if (Rs2Random.between(0, 100) < 15) {
                    debug("[MLM] Spec hesitation: crystal pickaxe, skipping this tick");
                    return;
                }
                preSpecVeinPoint = miningSession.getTargetVein();
                Rs2Combat.setSpecState(true, SPEC_ENERGY_CRYSTAL);
                lastSpecTime = now;
                specCooldownVarianceMs = Rs2Random.between(-5000, 15000); // -5s to +15s
                specUsed = true;
                log.info("[MLM] Crystal pickaxe spec activated (energy={}/1000)", specEnergy);
            }
        } else if (Rs2Equipment.isWearing(DRAGON_INFERNAL_PICKAXES)) {
            if (specEnergy >= SPEC_ENERGY_DRAGON_INFERNAL) {
                // 15% chance to skip this tick
                if (Rs2Random.between(0, 100) < 15) {
                    debug("[MLM] Spec hesitation: dragon/infernal pickaxe, skipping this tick");
                    return;
                }
                preSpecVeinPoint = miningSession.getTargetVein();
                Rs2Combat.setSpecState(true, SPEC_ENERGY_DRAGON_INFERNAL);
                lastSpecTime = now;
                specCooldownVarianceMs = Rs2Random.between(-5000, 15000);
                specUsed = true;
                log.info("[MLM] Dragon/Infernal pickaxe spec activated (energy={}/1000)", specEnergy);
            }
        }

        if (specUsed) {
            needPostSpecMiningResume = true;
            // Brief pause to let spec animation start
            sleep(Rs2Random.between(800, 1200));
        }
    }

    /**
     * After using special attack, the player stops mining.
     * We need to re-click the vein we were on, or find a new one if depleted.
     */
    private void handlePostSpecMining() {
        debug("[MLM] Post-spec: resuming mining at {}", preSpecVeinPoint);

        // Wait for spec animation to finish
        if (Rs2Player.isAnimating()) {
            debug("[MLM] Post-spec: waiting for animation to finish");
            sleep(200);
            return;
        }

        // Check if our pre-spec vein is still active
        if (preSpecVeinPoint == null) {
            needPostSpecMiningResume = false;
            return;
        }

        boolean clicked = Microbot.getRs2TileObjectCache().query()
                .withIds(ObjectID.MOTHERLODE_ORE_SINGLE, ObjectID.MOTHERLODE_ORE_LEFT, ObjectID.MOTHERLODE_ORE_MIDDLE, ObjectID.MOTHERLODE_ORE_RIGHT)
                .where(o -> o.getWorldLocation().equals(preSpecVeinPoint))
                .interact("Mine");

        if (clicked) {
            debug("[MLM] Post-spec: re-clicked original vein at {}", preSpecVeinPoint);
            needPostSpecMiningResume = false;
            preSpecVeinPoint = null;
            miningSession.onMiningXp(); // Reset XP timer
            return;
        }

        // Vein depleted or lost — let MiningSession find a new one
        debug("[MLM] Post-spec: original vein gone, finding new vein");
        needPostSpecMiningResume = false;
        preSpecVeinPoint = null;

        if (miningSession.isIdle()) {
            miningSession.begin();
        }
    }

    /**
     * Returns true if player is in a mining area (near ore veins).
     * Used to prevent spec activation while walking to hopper/sack.
     */
    private boolean isInMiningArea() {
        var playerLoc = Rs2Player.getWorldLocation();
        if (playerLoc == null || miningSpot == null) return false;

        // Check distance to mining spot anchors
        List<WorldPoint> anchors = miningSpot.getWorldPoint();
        if (anchors == null || anchors.isEmpty()) return false;

        for (WorldPoint anchor : anchors) {
            if (playerLoc.distanceTo(anchor) <= 10) {
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // Economy & Stats
    // =========================================================================

    private final java.util.Map<Integer, Integer> cachedItemPrices = new java.util.HashMap<>();

    private void cacheItemPrices() {
        Microbot.getClientThread().runOnClientThreadOptional(() -> {
            for (int itemId : TRACKED_ITEMS) {
                if (itemId != ItemID.PAYDIRT) {
                    cachedItemPrices.put(itemId, Microbot.getItemManager().getItemPrice(itemId));
                }
            }
            return null;
        });
    }

    private void updateSessionStats() {
        for (int itemId : TRACKED_ITEMS) {
            int currentCount = Rs2Inventory.count(itemId);
            int lastCount    = lastInventoryCounts.getOrDefault(itemId, 0);
            if (currentCount > lastCount) {
                int added = currentCount - lastCount;
                switch (itemId) {
                    case ItemID.MOTHERLODE_NUGGET:
                        gainedNuggets += added;
                        // Robust fallback: if inventory shows more than delta counted,
                        // use the inventory total (handles batch additions)
                        if (currentCount > maxNuggetsSeen) {
                            int missed = currentCount - maxNuggetsSeen;
                            if (missed > added) {
                                gainedNuggets += (missed - added);
                            }
                            maxNuggetsSeen = currentCount;
                        }
                        break;
                    case ItemID.RUNITE_ORE:
                        sessionRunite += added;
                        break;
                    case ItemID.ADAMANTITE_ORE:
                        sessionAdamantite += added;
                        break;
                    case ItemID.MITHRIL_ORE:
                        sessionMithril += added;
                        break;
                    case ItemID.GOLD_ORE:
                        sessionGold += added;
                        break;
                    case ItemID.COAL:
                        sessionCoal += added;
                        break;
                    default:
                        break;
                }
                if (itemId != ItemID.PAYDIRT) {
                    int price = cachedItemPrices.getOrDefault(itemId, 0);
                    totalValueGained += (long) price * added;
                }
            }
            lastInventoryCounts.put(itemId, currentCount);
        }
    }

    public void setSackIsFull(boolean full) {
        if (full && !sackIsFullFlag) {
            log.info("[MLM] Sack fullness flag set via external trigger (chat)");
        }
        this.sackIsFullFlag = full;
    }

    private boolean isSackFull() {
        if (sackIsFullFlag) return true;
        return maxSackSize > 0 && currentSackCount() >= maxSackSize;
    }

    // =========================================================================
    // Post-deposit audit
    // =========================================================================

    private void handlePostDepositAudit() {
        int residual = payDirtCount();
        int initial  = hopperSession.getInitialPayDirtCount();

        log.info("[MLM] Post-deposit audit: residual={} (started with {})", residual, initial);

        if (residual == 0) {
            lastHopperDepositTimestampMs = System.currentTimeMillis();
            hopperSession.resetRetryCount();

            int deposited = initial - residual;
            payDirtJustDeposited = deposited;

            int projectedSack = currentSackCount() + deposited;
            boolean willSackBeFull = projectedSack >= maxSackSize;

            log.info("[MLM] Projected sack after deposit: {}/{} (current={}, deposited={})",
                    projectedSack, maxSackSize, currentSackCount(), deposited);

            if (willSackBeFull || hasOreInInventory()) {
                log.info("[MLM] Sack will be full after deposit — going straight to empty sack");
                status = MLMStatus.EMPTY_SACK;
            } else if (Rs2Inventory.isFull() && payDirtCount() > 0) {
                status = MLMStatus.DEPOSIT_HOPPER;
            } else if (tickBrokenStrutCount >= 2 && isWaterwheelClear()) {
                status = MLMStatus.FIXING_WATERWHEEL;
            } else {
                status = MLMStatus.MINING;
            }
            return;
        }

        if (isSackFull() || sackIsFullFlag) {
            log.info("[MLM] Audit: {} pay-dirt remains, sack at capacity ({}/{}) — clearing sack",
                    residual, currentSackCount(), maxSackSize);

            if (!hasOreInInventory() && !hasGemsInInventory()) {
                log.info("[MLM] Audit: inventory is only pay-dirt — dropping to empty sack");
                dropAllPayDirt();
            } else {
                log.info("[MLM] Audit: valuable ores in inventory — emptying sack first");
            }
            status = MLMStatus.EMPTY_SACK;
            return;
        }

        if (tickBrokenStrutCount >= 2 && !Rs2Player.isAnimating()) {
            log.info("[MLM] Audit: {} pay-dirt remains, waterwheel broken — repairing first", residual);
            status = MLMStatus.FIXING_WATERWHEEL;
            return;
        }

        if (residual == initial) {
            if (hopperSession.incrementAndCheckRetryLimit()) {
                log.error("[MLM] Deposit failed {} times — entering recovery", HopperSession.MAX_DEPOSIT_RETRIES);
                hopperSession.resetRetryCount();
                status = MLMStatus.RECOVERY;
                return;
            }
            log.info("[MLM] Audit: deposit failed ({} / {} remain) — retrying ({}/{})",
                    residual, initial, hopperSession.getDepositRetryCount(), HopperSession.MAX_DEPOSIT_RETRIES);
            status = MLMStatus.DEPOSIT_HOPPER;
            return;
        }

        if (hopperSession.incrementAndCheckRetryLimit()) {
            log.error("[MLM] Partial deposit failed {} times — entering recovery", HopperSession.MAX_DEPOSIT_RETRIES);
            hopperSession.resetRetryCount();
            status = MLMStatus.RECOVERY;
            return;
        }
        log.info("[MLM] Audit: partial deposit ({} / {} remain) — retrying ({}/{})",
                residual, initial, hopperSession.getDepositRetryCount(), HopperSession.MAX_DEPOSIT_RETRIES);
        status = MLMStatus.DEPOSIT_HOPPER;
    }

    private void dropAllPayDirt() {
        int count = payDirtCount();
        if (count == 0) return;

        log.info("[MLM] Dropping {} pay-dirt to clear inventory for sack emptying", count);

        int dropped = 0;
        while (payDirtCount() > 0 && dropped < 28) {
            if (!Rs2Inventory.drop(ItemID.PAYDIRT)) {
                log.warn("[MLM] Failed to drop pay-dirt at count {}, breaking drop loop", dropped);
                break;
            }
            dropped++;
            sleep(Rs2Random.between(300, 600));
        }

        log.info("[MLM] Pay-dirt drop complete, dropped={}, remaining: {}", dropped, payDirtCount());
    }

    // =========================================================================
    // Status determination
    // =========================================================================

    private void determineStatus() {
        switch (status) {
            case IDLE:
            case RECOVERY:
            case FIXING_WATERWHEEL:
            case EMPTY_SACK:
            case DEPOSIT_HOPPER:
            case DROP_GEMS:
                return;
            default:
                break;
        }

        int effectiveSack = currentSackCount();
        if (payDirtJustDeposited > 0) {
            effectiveSack += payDirtJustDeposited;
        }
        boolean projectedFull = maxSackSize > 0 && effectiveSack >= maxSackSize;

        if (projectedFull || hasOreInInventory()) {
            status = MLMStatus.EMPTY_SACK;
            return;
        }

        if (Rs2Inventory.isFull() && payDirtCount() > 0) {
            status = MLMStatus.DEPOSIT_HOPPER;
            return;
        }

        // Repair check: both struts broken and not actively mining
        if (tickBrokenStrutCount >= 2 && isWaterwheelClear() && !Rs2Player.isAnimating()) {
            status = MLMStatus.FIXING_WATERWHEEL;
            return;
        }

        if (config.dropGems() && hasGemsInInventory()) {
            status = MLMStatus.DROP_GEMS;
        }
    }

    private MLMStatus determineNextStatusAfterMining() {
        int effectiveSackCount = currentSackCount();
        if (payDirtJustDeposited > 0) {
            effectiveSackCount += payDirtJustDeposited;
            debug("[MLM] Using projected sack count: {}/{} (varbit={}, justDeposited={})",
                    effectiveSackCount, maxSackSize, currentSackCount(), payDirtJustDeposited);
        }
        boolean sackFull = maxSackSize > 0 && effectiveSackCount >= maxSackSize;

        if (sackFull || hasOreInInventory())        return MLMStatus.EMPTY_SACK;
        if (Rs2Inventory.isFull() && payDirtCount() > 0) {
            return MLMStatus.DEPOSIT_HOPPER;
        }
        if (tickBrokenStrutCount >= 2 && isWaterwheelClear() && !Rs2Player.isAnimating()) {
            return MLMStatus.FIXING_WATERWHEEL;
        }
        if (config.dropGems() && hasGemsInInventory())   return MLMStatus.DROP_GEMS;
        return MLMStatus.MINING;
    }

    private MLMStatus determineNextStatusAfterRepair() {
        if (isSackFull() || hasOreInInventory())        return MLMStatus.EMPTY_SACK;
        if (Rs2Inventory.isFull() && payDirtCount() > 0) return MLMStatus.DEPOSIT_HOPPER;
        return MLMStatus.MINING;
    }

    // =========================================================================
    // Status dispatch
    // =========================================================================

    private void dispatchByStatus() {
        switch (status) {
            case IDLE:
                updateSackSize();
                if (isSackFull() || hasOreInInventory()) {
                    status = MLMStatus.EMPTY_SACK;
                    log.info("[MLM] IDLE eval -> EMPTY_SACK (sack={}/{})", currentSackCount(), maxSackSize);
                } else if (Rs2Inventory.isFull() && payDirtCount() > 0) {
                    status = MLMStatus.DEPOSIT_HOPPER;
                    log.info("[MLM] IDLE eval -> DEPOSIT_HOPPER");
                } else if (config.dropGems() && hasGemsInInventory()) {
                    status = MLMStatus.DROP_GEMS;
                } else {
                    status = MLMStatus.MINING;
                    handleMiningStatus();
                }
                break;
            case MINING:            handleMiningStatus();        break;
            case EMPTY_SACK:        handleEmptySackStatus();     break;
            case DEPOSIT_HOPPER:    handleDepositHopperStatus(); break;
            case FIXING_WATERWHEEL: handleRepairStatus();        break;
            case DROP_GEMS:         handleDropGemsStatus();      break;
            case RECOVERY:          handleRecoveryStatus();      break;
            default:                                             break;
        }
    }

    private void handleMiningStatus() {
        // Check spec BEFORE starting mining session
        handlePickaxeSpec();

        // If we just used spec, don't start a new mining session yet
        if (needPostSpecMiningResume) {
            debug("[MLM] Mining status: waiting for post-spec resume");
            return;
        }

        Rs2Antiban.setActivityIntensity(Rs2Antiban.getActivity().getActivityIntensity());

        if (config.useAntiCrash() && miningSpot != null && isPlayerAtMiningSpot()) {
            debug("[MLM] Anti-crash: player detected at mining spot, re-selecting");
            selectMiningSpotFromConfig();
        }

        if (miningSpot == null || shouldRefreshMiningSpot()) {
            selectMiningSpotFromConfig();
        }

        if (miningSpot == null) {
            log.warn("[MLM] Cannot start mining — no valid spot assigned");
            selectMiningSpotFromConfig();
            if (miningSpot == null) {
                log.error("[MLM] Spot selection failed — entering recovery");
                status = MLMStatus.RECOVERY;
                return;
            }
        }

        if (miningSession.isIdle()) {
            miningSession.begin();
        }
    }

    private boolean shouldRefreshMiningSpot() {
        if (config.miningArea() != miningSpot) return true;
        if (System.currentTimeMillis() - lastSpotShuffleMs > SPOT_SHUFFLE_INTERVAL_MS) {
            lastSpotShuffleMs = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    private boolean isPlayerAtMiningSpot() {
        if (currentAnchor == null) return false;
        String localName = localPlayerName;
        int others = Microbot.getRs2PlayerCache().query()
                .where(p -> {
                    String name = p.getName();
                    return name != null
                            && !name.equalsIgnoreCase(localName)
                            && p.getWorldLocation().distanceTo(currentAnchor) <= 2;
                })
                .count();
        if (others > 0) {
            debug("[MLM] Anti-crash: {} player(s) at anchor {}", others, currentAnchor);
        }
        return others > 0;
    }

    private void selectMiningSpotFromConfig() {
        MLMMiningSpot selected = config.miningArea();

        if (selected == null) {
            log.error("No mining area selected in config");
            Microbot.showMessage("MLM: Please select a mining area in the config.");
            return;
        }

        List<WorldPoint> anchorPoints = selected.getWorldPoint();
        if (anchorPoints == null || anchorPoints.isEmpty()) {
            log.error("Selected mining spot {} has no coordinates", selected);
            Microbot.showMessage("MLM: Invalid mining spot configuration.");
            return;
        }

        miningSpot = selected;
        List<WorldPoint> mutableAnchors = new java.util.ArrayList<>(anchorPoints);
        Collections.shuffle(mutableAnchors);

        WorldPoint newAnchor = mutableAnchors.get(0);
        if (currentAnchor != null && mutableAnchors.size() > 1) {
            for (WorldPoint wp : mutableAnchors) {
                if (!wp.equals(currentAnchor)) {
                    newAnchor = wp;
                    break;
                }
            }
        }
        currentAnchor = newAnchor;

        log.info("Selected mining spot: {} at anchor {}", miningSpot, currentAnchor);
    }

    private void handleEmptySackStatus() {
        if (hasOreInInventory() || currentSackCount() > 0 || sackIsFullFlag) {
            if (sackSession.isIdle() && canInteractWithLadder()) {
                lastLadderInteractionMs = System.currentTimeMillis();
                if (miningSpot == null) {
                    selectMiningSpotFromConfig();
                }
                sackSession.begin(miningSpot);
            }
        } else {
            setSackIsFull(false);
            payDirtJustDeposited = 0;
            status = MLMStatus.IDLE;
        }
    }

    private void handleDepositHopperStatus() {
        if (hopperSession.isIdle() && canInteractWithLadder()) {
            lastLadderInteractionMs = System.currentTimeMillis();

            if (Rs2Random.between(0, 100) < 5) {
                debug("[MLM] Pre-deposit inventory check");
                sleep(Rs2Random.between(200, 500));
            }

            long variance = Rs2Random.between(-200, 400);
            if (variance > 0) {
                sleep((int) variance);
            }

            hopperSession.begin();
        }
    }

    private boolean canInteractWithLadder() {
        long elapsed = System.currentTimeMillis() - lastLadderInteractionMs;
        return elapsed >= LADDER_INTERACTION_COOLDOWN_MS;
    }

    private void handleRepairStatus() {
        if (tickBrokenStrutCount == 1 && Rs2Random.between(0, 100) >= 10) {
            debug("[MLM] Skipping repair with 1 broken strut (90% chance)");
            repairSession.reset();
            status = determineNextStatusAfterRepair();
            return;
        }

        if (repairSession.isIdle()) {
            long noticeDelay = Rs2Random.between(1000, 3000);
            debug("[MLM] Repair notice delay: {}ms", noticeDelay);
            sleep((int) noticeDelay);
        }

        if (repairSession.canFastComplete()) {
            repairSession.reset();
            status = determineNextStatusAfterRepair();
        } else if (repairSession.isIdle()) {
            if (miningSpot == null) {
                selectMiningSpotFromConfig();
            }
            repairSession.begin();
        }
    }

    private void handleDropGemsStatus() {
        dropGems();
        status = MLMStatus.IDLE;
    }

    private void handleRecoveryStatus() {
        if (System.currentTimeMillis() < recoveryWaitUntil) return;

        if (isSackFull() || hasOreInInventory()) {
            log.info("[MLM] Recovery: ores detected in inventory/sack — prioritizing EMPTY_SACK");
            sackSession.reset();
            status = MLMStatus.EMPTY_SACK;
            return;
        }

        if (Rs2Inventory.isFull() && payDirtCount() > 0) {
            log.info("[MLM] Recovery: pay-dirt detected — prioritizing DEPOSIT_HOPPER");
            hopperSession.reset();
            status = MLMStatus.DEPOSIT_HOPPER;
            return;
        }

        int attempts = recoveryAttempts.incrementAndGet();
        log.info("[MLM] RECOVERY attempt {}/{}", attempts, MAX_RECOVERY_ATTEMPTS);

        if (attempts > MAX_RECOVERY_ATTEMPTS) {
            log.error("[MLM] Exceeded {} consecutive recovery attempts — stopping plugin.", MAX_RECOVERY_ATTEMPTS);
            Microbot.showMessage("MLM script stopped: too many consecutive recovery attempts.");
            Microbot.stopPlugin(plugin);
            return;
        }

        if (attempts == 1 && lastHopperDepositTimestampMs > 0
                && System.currentTimeMillis() - lastHopperDepositTimestampMs < 15000L) {
            debug("[MLM] Recovery: attempting hopper re-click before full recovery");
            hopperSession.reset();
            status = MLMStatus.DEPOSIT_HOPPER;
            return;
        }

        resetAllSessions();
        Rs2Walker.setTarget(null);

        if (attempts >= 2) {
            log.info("[MLM] Recovery attempt {} — walking to safe area", attempts);
            Rs2Walker.walkTo(new WorldPoint(3755, 5673, 0), 3);
            recoveryWaitUntil = System.currentTimeMillis() + 5000L;
            return;
        }

        if (Rs2Player.isAnimating() || Rs2Player.isMoving() || Rs2Player.isInteracting()) {
            recoveryWaitUntil = System.currentTimeMillis() + 1200L;
            return;
        }

        recoveryWaitUntil = System.currentTimeMillis() + 600L;
        payDirtJustDeposited = 0;
        status = MLMStatus.MINING;
    }

    // =========================================================================
    // Inventory & tools
    // =========================================================================

    private boolean hasRequiredTools() {
        boolean inInventory = Pickaxe.hasItem();
        boolean equipped = Rs2Equipment.isWearing("pickaxe");
        if (!inInventory && !equipped) {
            debug("[MLM] No pickaxe found in inventory or equipment");
        }
        return inInventory || equipped;
    }

    private void updateSackSize() {
        maxSackSize = (config.sackSize() == MLMSackSize.UPGRADED) ? SACK_LARGE_SIZE : SACK_SIZE;
        int live = currentSackCount();
        if (live > SACK_SIZE) {
            maxSackSize = SACK_LARGE_SIZE;
        }
    }

    private int currentSackCount() {
        int varbit = Microbot.getClientThread().runOnClientThreadOptional(() ->
                Microbot.getClient().getVarbitValue(SACK_COUNT_VARBIT)).orElse(0);

        debug("[MLM] Varbit 5558 read: {}", varbit);

        if (varbit == 0 && payDirtJustDeposited > 0 &&
                System.currentTimeMillis() - lastHopperDepositTimestampMs < 10000L) {
            debug("[MLM] Using projected sack count: {}", payDirtJustDeposited);
            return payDirtJustDeposited;
        }
        return varbit;
    }

    private int payDirtCount() {
        return Rs2Inventory.count(ItemID.PAYDIRT);
    }

    private boolean hasOreInInventory() {
        return Rs2Inventory.contains(
                ItemID.RUNITE_ORE, ItemID.ADAMANTITE_ORE, ItemID.MITHRIL_ORE,
                ItemID.GOLD_ORE, ItemID.COAL);
    }

    private boolean hasGemsInInventory() {
        return Rs2Inventory.contains(
                ItemID.UNCUT_SAPPHIRE, ItemID.UNCUT_EMERALD,
                ItemID.UNCUT_RUBY, ItemID.UNCUT_DIAMOND);
    }

    private void dropGems() {
        Rs2Inventory.drop(ItemID.UNCUT_SAPPHIRE);
        Rs2Inventory.drop(ItemID.UNCUT_EMERALD);
        Rs2Inventory.drop(ItemID.UNCUT_RUBY);
        Rs2Inventory.drop(ItemID.UNCUT_DIAMOND);
    }

    private int queryBrokenStrutCount() {
        return Microbot.getRs2TileObjectCache().query()
                .withId(ObjectID.MOTHERLODE_WHEEL_STRUT_BROKEN)
                .toList()
                .size();
    }

    private boolean isWaterwheelClear() {
        WorldPoint waterwheelArea = new WorldPoint(3747, 5672, 0);
        String localName = localPlayerName;
        int others = Microbot.getRs2PlayerCache().query()
                .where(p -> {
                    String name = p.getName();
                    return name != null
                            && !name.equalsIgnoreCase(localName)
                            && p.getWorldLocation().distanceTo(waterwheelArea) <= 5;
                })
                .count();
        if (others > 0) {
            debug("[MLM] {} other player(s) near waterwheel — repair deferred", others);
        }
        return others == 0;
    }

    // =========================================================================
    // Snapshot & overlay
    // =========================================================================

    private void updateSnapshot() {
        try {
            boolean returningToSpot = !sackSession.isIdle()
                    && sackSession.getReturnPoint() != null;

            int totalNuggets = Microbot.getClientThread().runOnClientThreadOptional(() ->
                    Rs2Inventory.count(ItemID.MOTHERLODE_NUGGET)).orElse(0);
            // Ensure gainedNuggets reflects max seen (robust count)
            if (totalNuggets > maxNuggetsSeen) {
                maxNuggetsSeen = totalNuggets;
            }
            if (maxNuggetsSeen > gainedNuggets) {
                gainedNuggets = maxNuggetsSeen;
            }

            int currentXp = Microbot.getClientThread().runOnClientThreadOptional(() ->
                    Microbot.getClient().getSkillExperience(Skill.MINING)).orElse(startXp);

            SessionSnapshot snap = new SessionSnapshot(
                    status,
                    miningSpot,
                    currentSackCount(),
                    maxSackSize,
                    startTimeMs,
                    System.currentTimeMillis(),
                    miningSession.getTargetVein() != null ? miningSession.getTargetVein().toString() : null,
                    miningSession.isActivelyMining(),
                    miningSession.getFailedClicks(),
                    recoveryAttempts.get(),
                    !hopperSession.isIdle(),
                    lastHopperDepositTimestampMs,
                    !repairSession.isIdle(),
                    repairSession.getPhaseLabel(),
                    tickBrokenStrutCount,
                    0L,
                    totalValueGained,
                    gainedNuggets,
                    totalNuggets,
                    startXp,
                    currentXp,
                    sessionRunite,
                    sessionAdamantite,
                    sessionMithril,
                    sessionGold,
                    sessionCoal,
                    getCurrentSubStateLabel(),
                    returningToSpot
            );

            snapshotRef.set(snap);
        } catch (Exception e) {
            log.error("[MLM] updateSnapshot crash — overlay will show stale data", e);
        }
    }

    public SessionSnapshot getSnapshot() {
        SessionSnapshot snap = snapshotRef.get();
        return snap != null ? snap : SessionSnapshot.empty();
    }

    private String getCurrentSubStateLabel() {
        switch (status) {
            case MINING:            return miningSession.getPhaseLabel();
            case DEPOSIT_HOPPER:    return hopperSession.getPhaseLabel();
            case EMPTY_SACK:        return sackSession.getPhaseLabel();
            case FIXING_WATERWHEEL: return repairSession.getPhaseLabel();
            case RECOVERY:          return "Recovering (" + recoveryAttempts.get() + ")";
            default:                return "Idle";
        }
    }

    // =========================================================================
    // Logging & utilities
    // =========================================================================

    private void logStatusTransitionIfChanged() {
        if (status == lastLoggedStatus) return;
        log.info("MLM status transition: {} -> {}", lastLoggedStatus, status);
        lastLoggedStatus = status;
    }

    private void resetAllSessions() {
        miningSession.reset();
        hopperSession.reset();
        sackSession.reset();
        repairSession.reset();
    }

    @Override
    public void shutdown() {
        log.info("Starting MLM script shutdown");
        super.shutdown();
        Rs2Antiban.resetAntibanSettings();
        Rs2Walker.setTarget(null);
        resetAllSessions();
        log.info("MLM script shutdown complete");
    }
}