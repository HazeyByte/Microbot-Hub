package net.runelite.client.plugins.microbot.irkedmlm;

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
import net.runelite.client.plugins.microbot.irkedmlm.enums.MLMMiningSpot;
import net.runelite.client.plugins.microbot.irkedmlm.enums.MLMSackSize;
import net.runelite.client.plugins.microbot.irkedmlm.enums.MLMStatus;
import net.runelite.client.plugins.microbot.irkedmlm.enums.Pickaxe;
import net.runelite.client.plugins.microbot.irkedmlm.session.HopperSession;
import net.runelite.client.plugins.microbot.irkedmlm.session.MiningSession;
import net.runelite.client.plugins.microbot.irkedmlm.session.RepairSession;
import net.runelite.client.plugins.microbot.irkedmlm.session.SackSession;
import net.runelite.client.plugins.microbot.irkedmlm.session.SessionSnapshot;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.antiban.enums.Activity;
import net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Gembag;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

@Slf4j
@Singleton
public class IrkedMLMScript extends Script {

    // --- Sack ---
    private static final int SACK_LARGE_SIZE = 189;
    private static final int SACK_SIZE = 108;
    /** Varbit ID for the amount of ore currently in the sack. */
    public static final int SACK_COUNT_VARBIT = 5558;

    // --- Recovery ---
    private static final int MAX_RECOVERY_ATTEMPTS = 5;

    // --- Special attack ---
    /** OSRS player 300 — special attack energy, scaled 0–1000 (100% = 1000). */
    private static final int VARP_SPEC_ENERGY = 300;
    private static final int SPEC_ENERGY_CRYSTAL = 1000;
    private static final int SPEC_ENERGY_DRAGON_INFERNAL = 500;
    private static final int SPEC_ENERGY_FULL = 1000;
    private static final long SPEC_COOLDOWN_MS = 60_000L;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    @Getter private MLMStatus     status     = MLMStatus.IDLE;
    @Getter private MLMMiningSpot miningSpot = null;

    /** Atomic reference so overlay always sees latest snapshot without stale refs. */
    private final AtomicReference<SessionSnapshot> snapshotRef = new AtomicReference<>(SessionSnapshot.empty());

    private int maxSackSize;

    private int tickBrokenStrutCount = 0;

    /** When the waterwheel was first seen broken (0 = currently intact). Used to stop indefinitely
     *  deferring repair to a player standing near the wheel who turns out to be idle (not repairing). */
    private long brokenStrutsObservedSinceMs = 0L;
    /** After this long with struts still broken, a "nearby" player clearly isn't repairing — we do it. */
    private static final long REPAIR_DEFER_TIMEOUT_MS = 25_000L;

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

    private final IrkedMLMPlugin plugin;
    private final IrkedMLMConfig config;

    private final MiningSession  miningSession;
    private final HopperSession  hopperSession;
    private final SackSession    sackSession;
    private final RepairSession  repairSession;

    /** Per-login behavioural identity (reaction speed, patience, AFK/hesitation/mouse habits, etc.).
     *  Rolled once in {@link #initialise()} and pushed to every session so the whole run behaves like
     *  one consistent, session-unique player instead of independent per-decision coin-flips. */
    private SessionPersonality personality = SessionPersonality.neutral();

    // -------------------------------------------------------------------------
    // Timing / counters
    // -------------------------------------------------------------------------

    private final AtomicLong    globalLastXpTime = new AtomicLong(0L);
    private final AtomicInteger recoveryAttempts = new AtomicInteger(0);

    private MLMStatus lastLoggedStatus = null;
    private int       lastMiningXp     = -1;
    private long      recoveryWaitUntil = 0L;
    private long      statusEnteredMs   = System.currentTimeMillis();

    // Cached values to reduce client thread calls (prevents TimeoutExceptions and breakhandler popups at startup)
    private int lastSnapshotMiningXp = 0;
    private volatile long lastVarbitReadMs = 0;
    private volatile int lastVarbitValue = 0;
    private static final long VARBIT_CACHE_MS = 1500;

    /** Tick counter for the execute loop. Used to force fresh sack reads on the first few ticks
     *  after start so that "start plugin with sack already full" reliably detects via varbit
     *  instead of a potentially stale first client read causing it to go mine. */
    private int scriptTicks = 0;

    /** One-time-per-run gate: becomes true once we've authoritatively read the sack at startup. */
    private volatile boolean startupSackChecked = false;

    /** Guard against concurrent execution of executeTask from multiple scheduled threads
     *  (which can happen if run() is invoked multiple times without proper shutdown,
     *  leading to duplicate clicks, ladder spamming, etc.). */
    private final java.util.concurrent.atomic.AtomicBoolean executing = new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Used to early-exit executeTask during/after shutdown to avoid races with Rs2Antiban reset etc. */
    private volatile boolean running = false;

    // XP read cache (same treatment as varbit/sack/floor to cut client thread pressure)
    private long lastXpReadMs = 0;
    private int lastXpValue = -1;
    private static final long XP_CACHE_MS = 900;

    /** Timestamp of the last hopper/sack begin() (ladder throttle). */
    private long lastLadderInteractionMs = 0L;

    private long lastSpecTime = 0L;
    /** Random variance added to spec cooldown so it's not robotic. */
    private long specCooldownVarianceMs = 0L;
    /** When the spec bar was first seen full this cycle (0 = not full / already consumed). */
    private long specBarFullSinceMs = 0L;
    /** Randomized wait after the bar fills before we actually spec, so it's never instant-on-full. */
    private long specReadinessDelayMs = 0L;
    /** After using spec, wait for animation before MiningSession resumes the vein click. */
    private boolean needPostSpecMiningResume = false;

    // -------------------------------------------------------------------------
    // Session tracking
    // -------------------------------------------------------------------------

    private long startTimeMs                   = 0L;
    @Getter private int startXp                = 0;
    /**
     * Golden nuggets currently owned = inventory + bank (see {@link #updateGainedNuggets}). Counting both
     * containers is robust to Deposit-All banking the nuggets, which is why an inventory-only count read 0.
     */
    @Getter
    private int  gainedNuggets                 = 0;
    private long totalValueGained              = 0L;

    /** Initial pay-dirt count captured before hopper reset, for post-deposit delta calc. */
    private int lastHopperDepositInitial = 0;

    /** Owns the post-deposit sack-count projection (compensates for the laggy sack varbit) and the
     *  last-deposit timestamp. Replaces the four hand-managed fields that used to live here. See
     *  {@link SackState}. */
    private final SackState sackState = new SackState();

    private final java.util.Map<Integer, Integer> lastInventoryCounts = new java.util.HashMap<>();

    private static final java.util.List<Integer> TRACKED_ITEMS = java.util.Arrays.asList(
            ItemID.MOTHERLODE_NUGGET, ItemID.RUNITE_ORE, ItemID.ADAMANTITE_ORE,
            ItemID.MITHRIL_ORE, ItemID.GOLD_ORE, ItemID.COAL,
            ItemID.UNCUT_SAPPHIRE, ItemID.UNCUT_EMERALD, ItemID.UNCUT_RUBY, ItemID.UNCUT_DIAMOND,
            ItemID.PAYDIRT
    );
    private static final int[] ORE_STAT_IDS = {
            ItemID.RUNITE_ORE, ItemID.ADAMANTITE_ORE, ItemID.MITHRIL_ORE, ItemID.GOLD_ORE, ItemID.COAL
    };

    // -------------------------------------------------------------------------
    // Per-ore session counters
    // -------------------------------------------------------------------------

    private int sessionRunite      = 0;
    private int sessionAdamantite  = 0;
    private int sessionMithril     = 0;
    private int sessionGold        = 0;
    private int sessionCoal        = 0;

    // -------------------------------------------------------------------------
    // Mining spot shuffle timer
    // -------------------------------------------------------------------------

    private long lastSpotShuffleMs = 0L;
    private static final long SPOT_SHUFFLE_INTERVAL_MS = 300_000L; // 5 minutes
    private WorldPoint currentAnchor = null;

    // -------------------------------------------------------------------------
    // Gem bag tracking
    // -------------------------------------------------------------------------
    private long lastGemBagCheckMs = 0L;
    private static final long GEMBAG_RECHECK_COOLDOWN_MS = 8000L;

    // -------------------------------------------------------------------------

    @Inject
    public IrkedMLMScript(IrkedMLMPlugin plugin, IrkedMLMConfig config) {
        this.plugin = plugin;
        this.config = config;

        var tileCache = Microbot.getRs2TileObjectCache();
        this.miningSession = new MiningSession(tileCache, config);
        this.hopperSession = new HopperSession(tileCache, config);
        this.sackSession   = new SackSession(tileCache, config);
        this.repairSession = new RepairSession(tileCache, config);
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

    private void humanPause(int minMs, int maxMs) {
        humanPause(minMs, maxMs, false);
    }

    private void humanPause(int minMs, int maxMs, boolean urgent) {
        if (!isHumanLikeEnabled()) {
            return;
        }
        int delay = getHumanizedDelay(minMs, maxMs, urgent);
        debug("[MLM] humanPause: {}ms (min={}, max={}, urgent={})", delay, minMs, maxMs, urgent);
        try {
            Rs2Antiban.actionCooldown();
        } catch (IllegalArgumentException e) {
            debug("[MLM] humanPause antiban cooldown skipped: {}", e.getMessage());
        }
        sleep(delay);
    }

    private int getHumanizedDelay(int minMs, int maxMs, boolean urgent) {
        return HumanBehaviorProfile.pauseDelayMs(isHumanLikeEnabled(), minMs, maxMs, urgent);
    }

    private boolean isHumanLikeEnabled() {
        return config != null && config.enableHumanLikeBehavior();
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    public boolean run() {
        // Prevent duplicate scheduled tasks (which cause concurrent executeTask from multiple threads,
        // resulting in duplicate clicks, repeated ladder climbs, vein spam, etc.).
        if (mainScheduledFuture != null && !mainScheduledFuture.isDone()) {
            log.warn("[MLM] run() called while executor already active — cancelling previous task to avoid concurrency");
            mainScheduledFuture.cancel(true);
        }

        log.info("Starting Motherload Mine script v{} (session-oriented)", IrkedMLMPlugin.version);
        running = true;
        initialise();
        long tickIntervalMs = isHumanLikeEnabled() ? 600L : 250L;
        log.info("[MLM] Main executor started — ticking every {}ms (humanLike={})",
                tickIntervalMs, isHumanLikeEnabled());
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(
                this::executeTask, 0, tickIntervalMs, TimeUnit.MILLISECONDS);
        return true;
    }

    private void initialise() {
        log.debug("Initialising MLM runtime state");
        configureAntibanSettings();

        // Roll one behavioural identity for this login and push it to every session. From here on all
        // "human" odds/timings are a stable function of these traits, so the run has a consistent
        // character and differs from the next login. See SessionPersonality.
        personality = SessionPersonality.roll();
        log.info("MLM {}", personality);
        miningSession.updatePersonality(personality);
        hopperSession.updatePersonality(personality);
        sackSession.updatePersonality(personality);
        repairSession.updatePersonality(personality);

        startTimeMs                  = System.currentTimeMillis();
        // Re-baseline every run(): the script is a @Singleton whose instance survives stop/start,
        // so a stale startXp from a previous run would inflate the overlay's XP/hr (all XP since the
        // first run divided by the current run's shorter runtime).
        startXp = 0;
        if (Microbot.isLoggedIn()) {
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
        miningSpot                   = null;
        lastLoggedStatus             = null;
        lastMiningXp                 = startXp;
        sackState.reset();
        totalValueGained             = 0L;
        lastHopperDepositInitial     = 0;
        lastXpReadMs                 = 0L;
        lastXpValue                  = -1;
        // Clear leftover sack state from a prior run (singleton survives stop/start).
        sackIsFullFlag               = false;
        lastVarbitValue              = 0;
        lastVarbitReadMs             = 0L;
        brokenStrutsObservedSinceMs  = 0L;
        startupSackChecked           = false;
        recoveryAttempts.set(0);
        recoveryWaitUntil            = 0L;
        statusEnteredMs              = System.currentTimeMillis();
        lastLadderInteractionMs      = 0L;
        lastSpotShuffleMs            = System.currentTimeMillis();
        currentAnchor                = null;
        lastSpecTime                 = 0L;
        specCooldownVarianceMs       = Rs2Random.between(-5000, 10000);
        specBarFullSinceMs           = 0L;
        specReadinessDelayMs         = 0L;
        needPostSpecMiningResume     = false;

        if (Microbot.isLoggedIn()) {
            localPlayerName = Microbot.getClientThread().runOnClientThreadOptional(() -> {
                var lp = Microbot.getClient().getLocalPlayer();
                return (lp != null) ? lp.getName() : "";
            }).orElse("");
        } else {
            localPlayerName = "";
        }
        miningSession.updateCachedLocalPlayerName(localPlayerName);
        repairSession.updateCachedLocalPlayerName(localPlayerName);

        lastInventoryCounts.clear();
        for (int itemId : TRACKED_ITEMS) {
            lastInventoryCounts.put(itemId, Rs2Inventory.count(itemId));
        }
        if (Microbot.isLoggedIn()) {
            cacheItemPrices();
            if (config.useGemBag() && Rs2Gembag.isUnknown()) {
                lastGemBagCheckMs = System.currentTimeMillis();
                Rs2Gembag.checkGemBag();
            }
        }

        if (miningSpot == null) {
            selectMiningSpotFromConfig();
        }

        updateSackSize();

        status = MLMStatus.IDLE;
        lastLoggedStatus = MLMStatus.IDLE; // prevent duplicate null->IDLE log spam at startup

        updateSnapshot();

        log.info("MLM Initialised — startXp={}, sackSize={}/{}, spot={}",
                startXp, currentSackCount(), maxSackSize, miningSpot);
    }

    private void configureAntibanSettings() {
        Rs2Antiban.resetAntibanSettings();
        Rs2Antiban.antibanSetupTemplates.applyMiningSetup();

        boolean human = isHumanLikeEnabled();
        Rs2AntibanSettings.usePlayStyle = human;

        Rs2Antiban.setActivity(Activity.GENERAL_MINING);
        Rs2Antiban.setActivityIntensity(human ? ActivityIntensity.MODERATE : ActivityIntensity.HIGH);

        Rs2AntibanSettings.dynamicIntensity = human;
        Rs2AntibanSettings.dynamicActivity = human;

        Rs2AntibanSettings.naturalMouse = human;
        Rs2AntibanSettings.simulateMistakes = human;
        Rs2AntibanSettings.simulateFatigue = human;

        // Off-screen mouse is handled by MiningSession only (once, while actively mining a vein).
        // The global antiban off-screen fires on ANY action cooldown (deposits, walking, sack
        // emptying), which is not what we want — keep it disabled so off-screen == mining.
        Rs2AntibanSettings.moveMouseOffScreen = false;
        Rs2AntibanSettings.moveMouseOffScreenChance = 0.0;

        Rs2AntibanSettings.moveMouseRandomly = human;
        Rs2AntibanSettings.moveMouseRandomlyChance = human ? 0.15 : 0.0;

        Rs2AntibanSettings.actionCooldownChance = human ? 0.15 : 0.0;

        Rs2AntibanSettings.behavioralVariability = human;
        Rs2AntibanSettings.nonLinearIntervals = human;
        Rs2AntibanSettings.takeMicroBreaks = false;
        Rs2AntibanSettings.contextualVariability = human;

        log.info("MLM Antiban configured — humanLike={}, actionCooldownChance={}",
                human, Rs2AntibanSettings.actionCooldownChance);
    }

    // =========================================================================
    // Main loop
    // =========================================================================

    private void executeTask() {
        if (!executing.compareAndSet(false, true)) {
            debug("Execution skipped: already running (concurrent tick avoided to prevent duplicate clicks)");
            return;
        }
        if (!running) {
            executing.set(false);
            return;
        }
        try {
            if (!Microbot.isLoggedIn()) {
                debug("Execution paused: not logged in");
                resetAllSessions();
                updateSnapshot();
                return;
            }

            scriptTicks++;

            // Before doing anything else this run: if we started with a full sack (on ANY floor), detect
            // it and route to EMPTY_SACK instead of wandering off to mine. Blocks until we get a
            // *successful* varbit read so a not-yet-ready varbit (which reads 0) can't be mistaken for
            // an empty sack.
            if (!ensureStartupSackChecked()) {
                return;
            }

            tickBrokenStrutCount = queryBrokenStrutCount();  // early for accurate logging
            if (tickBrokenStrutCount > 0) {
                if (brokenStrutsObservedSinceMs == 0L) {
                    brokenStrutsObservedSinceMs = System.currentTimeMillis();
                }
            } else {
                brokenStrutsObservedSinceMs = 0L;  // wheel intact (we or someone fixed it) — reset timer
            }

            debug("[MLM] executeTask tick | status={} | miningSpot={} | sack={}/{} | paydirt={} | brokenStruts={}",
                    status, miningSpot, currentSackCount(), maxSackSize, payDirtCount(), tickBrokenStrutCount);

            if (!super.run()) {
                debug("Execution paused: script not runnable");
                resetAllSessions();
                return;
            }

            if (localPlayerName.isEmpty()) {
                localPlayerName = Microbot.getClientThread().runOnClientThreadOptional(() -> {
                    var lp = Microbot.getClient().getLocalPlayer();
                    return lp != null ? lp.getName() : "";
                }).orElse("");
                debug("[MLM] Refreshed localPlayerName for anti-crash/repair checks: {}", localPlayerName);
            }
            miningSession.updateCachedLocalPlayerName(localPlayerName);
            repairSession.updateCachedLocalPlayerName(localPlayerName);

            // Handle post-spec mining resumption first
            if (needPostSpecMiningResume) {
                handlePostSpecMining();
                return;
            }

            updateSessionStats();

            // XP read with short cache (same treatment)
            int currentXp;
            long nowXp = System.currentTimeMillis();
            if (nowXp - lastXpReadMs < XP_CACHE_MS && lastXpValue != -1) {
                currentXp = lastXpValue;
            } else {
                currentXp = Microbot.getClientThread().runOnClientThreadOptional(() -> Microbot.getClient().getSkillExperience(Skill.MINING)).orElse(-1);
                lastXpReadMs = nowXp;
                lastXpValue = currentXp;
            }
            if (lastMiningXp != -1 && currentXp > lastMiningXp) {
                globalLastXpTime.set(System.currentTimeMillis());
                miningSession.onMiningXp();
            }
            lastMiningXp = currentXp;
            lastSnapshotMiningXp = currentXp;

            updateSackSize();

            final boolean antibanDispatchPause = isHumanLikeEnabled()
                    && Rs2AntibanSettings.actionCooldownActive;

            if (!hasRequiredTools()) {
                Microbot.showMessage("Missing required tools (Pickaxe). Please ensure you have one.");
                log.warn("Missing required tools (Pickaxe), stopping plugin");
                Microbot.stopPlugin(plugin);
                return;
            }

            if (config.useGemBag()
                    && Rs2Gembag.isUnknown()
                    && (System.currentTimeMillis() - lastGemBagCheckMs > GEMBAG_RECHECK_COOLDOWN_MS)) {
                lastGemBagCheckMs = System.currentTimeMillis();
                Rs2Gembag.checkGemBag();
            }

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
                        setStatus(MLMStatus.RECOVERY);
                        return;
                    }
                    if (config.miningArea() != null && config.miningArea() != miningSpot) {
                        log.info("[MLM] Config area {} != active spot {} — re-syncing (prevents wrong-floor ladder)",
                                config.miningArea(), miningSpot);
                        selectMiningSpotFromConfig();
                        miningSession.reset();
                        setStatus(MLMStatus.MINING);
                        handleMiningStatus();
                        return;
                    }
                    int effectiveSackForMining = getEffectiveSackCount();
                    if (sackState.hasProjection()) {
                        debug("[MLM] Passing projected sack count to MiningSession: {}/{}",
                                effectiveSackForMining, maxSackSize);
                    }
                    // Spec must run while the mining session is active (handleMiningStatus only
                    // runs when the session is idle). Attempt before tick so we are at-vein mid-cycle.
                    if (status == MLMStatus.MINING && canAttemptPickaxeSpec()) {
                        handlePickaxeSpec();
                        if (needPostSpecMiningResume) {
                            return;
                        }
                    }
                    miningSession.tick(miningSpot, effectiveSackForMining, maxSackSize, globalLastXpTime);
                    if (miningSession.isMiningComplete() || miningSession.isFailed()) {
                        boolean failed = miningSession.isFailed();
                        boolean sackFull = miningSession.endedWithSackFull();
                        boolean invFull = miningSession.endedWithInvFull();
                        miningSession.reset();
                        if (failed) {
                            if (shouldRetryMiningAfterSessionFail()) {
                                log.info("[MLM] Mining session failed near spot with inventory room — retrying MINING (not recovery/deposit)");
                                setStatus(MLMStatus.MINING);
                                handleMiningStatus();
                                return;
                            }
                            setStatus(MLMStatus.RECOVERY);
                        } else if (sackFull) {
                            // MiningSession detected sack full — route directly to EMPTY_SACK.
                            // Do NOT re-query currentSackCount() which is unreliable due to varbit lag.
                            // However, if we still have pay-dirt, drop it now (hopper/sack won't accept more
                            // when full) so the EMPTY_SACK handler can proceed to empty instead of flipping
                            // back to DEPOSIT_HOPPER.
                            if (payDirtCount() > 0) {
                                log.info("[MLM] Mining ended with SACK_FULL but pay-dirt remains — dropping pay-dirt before emptying sack");
                                dropAllPayDirt();
                            }
                            log.info("[MLM] MiningSession ended with SACK_FULL — routing to EMPTY_SACK");
                            sackState.clearProjection();
                            setStatus(MLMStatus.EMPTY_SACK);
                        } else if (invFull) {
                            setStatus(MLMStatus.DEPOSIT_HOPPER);
                        } else {
                            setStatus(determineNextStatusAfterMining());
                        }
                    }
                    return;
                }
            }

            if (!sackSession.isIdle()) {
                sackSession.tick(getEffectiveSackCount(), maxSackSize);
                applySackSessionOreSnapshot();
                updateSessionStats();
                if (sackSession.isComplete() || sackSession.isFailed()) {
                    boolean    failed      = sackSession.isFailed();
                    WorldPoint returnPoint = sackSession.getReturnPoint();
                    sackSession.reset();
                    if (failed) {
                        log.warn("[MLM] Sack session failed - moving to recovery");
                        setStatus(MLMStatus.RECOVERY);
                    } else {
                        if (returnPoint != null) {
                            // Sack always returns to lower; upper returnPoints need ladder handling on next cycle.
                            WorldPoint walkTarget = returnPoint;
                            if (returnPoint.getY() >= IrkedMLMMapConstants.UPPER_HUB_MIN_Y) {
                                walkTarget = IrkedMLMMapConstants.LADDER_BOTTOM_SAFE;
                            }
                            Rs2Walker.walkFastCanvas(walkTarget);
                        }
                        setSackIsFull(false);
                        sackState.clearProjection();
                        setStatus(MLMStatus.IDLE);
                    }
                }
                updateSnapshot();
                return;
            }

            if (!hopperSession.isIdle()) {
                HopperSession.HopperSubState beforeSub = hopperSession.getHopperSubState();

                hopperSession.tick();

                HopperSession.HopperSubState afterSub = hopperSession.getHopperSubState();

                if (beforeSub == HopperSession.HopperSubState.TRANSITIONING_FLOOR
                        && afterSub != HopperSession.HopperSubState.TRANSITIONING_FLOOR) {
                    debug("[MLM] HopperSession: ladder complete, now on correct floor for hopper (upper={})",
                            HopperSession.useUpperHopperForDeposit(config, miningSpot));
                    lastVarbitReadMs = 0L;
                    int sackNow = currentSackCount();
                    boolean sackFullNow = sackIsFullFlag || sackNow >= maxSackSize;
                    if (sackFullNow) {
                        log.info("[MLM] Post-ladder: sack full/flag ({} / {}) — abort to EMPTY_SACK",
                                sackNow, maxSackSize);
                        if (payDirtCount() > 0) {
                            dropAllPayDirt();
                        }
                        hopperSession.reset();
                        sackState.clearProjection();
                        setStatus(MLMStatus.EMPTY_SACK);
                        return;
                    }
                    if (payDirtCount() == 0) {
                        log.info("[MLM] Post-ladder: no pay-dirt — back to MINING");
                        hopperSession.reset();
                        setStatus(MLMStatus.MINING);
                        handleMiningStatus();
                        return;
                    }
                    debug("[MLM] Post-ladder: sack has room ({}), proceeding to deposit", sackNow);
                }

                if (hopperSession.isComplete() || hopperSession.isFailed()) {
                    boolean wasComplete = hopperSession.isComplete();
                    int capturedInitial = wasComplete ? hopperSession.getInitialPayDirtCount() : 0;
                    hopperSession.reset();

                    if (wasComplete) {
                        lastHopperDepositInitial = capturedInitial;
                        handlePostDepositAudit();
                        if (status == MLMStatus.MINING) {
                            debug("[MLM] Fast-starting mining session immediately after deposit");
                            handleMiningStatus();
                        }
                    } else {
                        // Hopper failed — run audit to decide: repair, retry, or recover.
                        // Capture initial before reset so audit can compare residual vs initial.
                        lastHopperDepositInitial = hopperSession.getInitialPayDirtCount();
                        handlePostDepositAudit();
                    }
                }
                return;
            }

            if (!repairSession.isIdle()) {
                repairSession.tick();
                if (repairSession.isComplete() || repairSession.isFailed()) {
                    repairSession.reset();
                    setStatus(determineNextStatusAfterRepair());
                }
                return;
            }

            if (antibanDispatchPause) {
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

            // Progress watchdog (>4min in working status without change -> RECOVERY). Never stops the plugin.
            // statusEnteredMs is maintained by setStatus() at the exact moment status changes (including
            // changes made inside the handle* methods called above), so this reflects true dwell time.
            if (status != MLMStatus.IDLE && status != MLMStatus.RECOVERY && statusEnteredMs > 0) {
                long stuckMs = System.currentTimeMillis() - statusEnteredMs;
                if (stuckMs > 240_000L) {  // 4 minutes no status change / no completion — very generous for long walks on south spots
                    log.warn("[MLM] WATCHDOG: no status progress in {} for {}s — forcing RECOVERY (universal resolver for any reason/any spot)", status, stuckMs / 1000);
                    setStatus(MLMStatus.RECOVERY);
                    recoveryAttempts.set(0);
                }
            }
        } catch (Exception e) {
            log.error("[MLM] executeTask crash", e);
        } finally {
            // Always update snapshot once running to keep overlay fresh, but client thread reads inside are now heavily cached
            // to prevent the startup TimeoutExceptions that were triggering breakhandler dialogs and blocking events.
            updateSnapshot();
            executing.set(false);
        }
    }

    // =========================================================================
    // Special Attack
    // =========================================================================

    /**
     * Dragon/infernal/crystal pickaxe special. Requires {@link #canAttemptPickaxeSpec()} (in mining
     * area, {@link MiningSession.MiningSubState#MINING} only). Sets {@link #needPostSpecMiningResume}
     * so we wait out the spec animation before the next Mine click (no duplicate click).
     */
    private void handlePickaxeSpec() {
        long now = System.currentTimeMillis();
        long effectiveCooldown = SPEC_COOLDOWN_MS + specCooldownVarianceMs;

        if (now - lastSpecTime < effectiveCooldown) {
            return;
        }

        if (status != MLMStatus.MINING) {
            debug("[MLM] Spec skipped: not actively mining (status={})", status);
            return;
        }
        // Avoid firing spec on the very first status transition at startup before we've even begun a mining session.
        if (startTimeMs == 0 || System.currentTimeMillis() - startTimeMs < 3000L) {
            debug("[MLM] Spec skipped: too soon after startup");
            return;
        }

        if (!canAttemptPickaxeSpec()) {
            debug("[MLM] Spec skipped: not positioned at a mineable vein");
            return;
        }

        int specEnergy = Microbot.getRs2PlayerStateCache().getVarpValue(VARP_SPEC_ENERGY);
        if (specEnergy < SPEC_ENERGY_FULL) {
            debug("[MLM] Spec skipped: need full spec bar ({}/{})", specEnergy, SPEC_ENERGY_FULL);
            specBarFullSinceMs = 0L; // bar not full — reset so the readiness delay re-rolls when it refills
            return;
        }

        // Spec is NOT instant-on-full: once the bar reaches full, wait a randomized readiness window of
        // continued mining before actually speccing (re-rolled each time the bar refills). Applies in
        // both modes so it never robotically fires the moment the bar tops off.
        if (specBarFullSinceMs == 0L) {
            specBarFullSinceMs = now;
            specReadinessDelayMs = Rs2Random.between(3_000, 25_000);
            debug("[MLM] Spec bar full — deferring spec by {}ms (not instant)", specReadinessDelayMs);
            return;
        }
        if (now - specBarFullSinceMs < specReadinessDelayMs) {
            return;
        }

        boolean specUsed = false;

        // Energy is already confirmed full (>= SPEC_ENERGY_FULL) above, so no per-pickaxe energy
        // threshold check is needed here.
        if (Rs2Equipment.isWearing(Pickaxe.CRYSTAL_PICKAXE.getItemID())) {
            // 15% chance to skip this tick even if conditions met (human hesitation / imperfection).
            // Only when human-like enabled; when off we spec as soon as energy + next-to-vein (optimal).
            if (isHumanLikeEnabled() && personality.roll(personality.specHesitationChance())) {
                debug("[MLM] Spec hesitation: crystal pickaxe, skipping this tick");
                return;
            }
            humanPause(150, 350, false);
            Rs2Combat.setSpecState(true, SPEC_ENERGY_CRYSTAL);
            lastSpecTime = now;
            specCooldownVarianceMs = Rs2Random.between(-5000, 15000); // -5s to +15s
            specUsed = true;
            log.info("[MLM] Crystal pickaxe spec activated (energy={}/1000)", specEnergy);
        } else if (Rs2Equipment.isWearing(Pickaxe.DRAGON_PICKAXE.getItemID(), ItemID.INFERNAL_PICKAXE, ItemID.INFERNAL_PICKAXE_EMPTY)) {
            if (isHumanLikeEnabled() && personality.roll(personality.specHesitationChance())) {
                debug("[MLM] Spec hesitation: dragon/infernal pickaxe, skipping this tick");
                return;
            }
            humanPause(150, 350, false);
            Rs2Combat.setSpecState(true, SPEC_ENERGY_DRAGON_INFERNAL);
            lastSpecTime = now;
            specCooldownVarianceMs = Rs2Random.between(-5000, 15000);
            specUsed = true;
            log.info("[MLM] Dragon/Infernal pickaxe spec activated (energy={}/1000)", specEnergy);
        }
        // (no per-tick "not wearing a spec pickaxe" log — it fired every tick since spec energy is always
        //  full for non-spec pickaxes; pure noise.)

        if (specUsed) {
            specBarFullSinceMs = 0L; // consumed — re-roll the readiness delay next time the bar refills
            needPostSpecMiningResume = true;
            // Brief pause to let spec animation start. Human gated for likeness (when off: shorter consistent to feel attentive).
            if (isHumanLikeEnabled()) {
                sleep(Rs2Random.between(800, 1200));
            } else {
                sleep(Rs2Random.between(120, 220));
            }
        }
    }

    /** Wait out spec animation; MiningSession issues the next Mine click (no duplicate here). */
    private void handlePostSpecMining() {
        debug("[MLM] Post-spec: clearing resume flag (MiningSession owns the next Mine click)");

        if (Rs2Player.isAnimating()) {
            debug("[MLM] Post-spec: waiting for spec animation to finish");
            return;
        }

        needPostSpecMiningResume = false;
    }

    // =========================================================================
    // Hopper floor policy (mirrors HopperSession#desiredFloorIsUpper)
    // =========================================================================

    static WorldPoint hopperWalkTargetFor(IrkedMLMConfig config, MLMMiningSpot spot) {
        return HopperSession.hopperWalkTargetFor(config, spot);
    }

    static WorldPoint recoverySafeWalkPoint(MLMMiningSpot spot) {
        if (spot != null && spot.isDownstairs()) {
            List<WorldPoint> pts = spot.getWorldPoint();
            if (pts != null && !pts.isEmpty()) {
                return pts.get(0);
            }
        }
        return IrkedMLMMapConstants.LADDER_BOTTOM_SAFE;
    }

    private WorldPoint hopperWalkTarget() {
        return hopperWalkTargetFor(config, miningSpot);
    }

    /** Hopper deposit is only warranted when the inventory is full of pay-dirt. */
    private boolean shouldDepositPayDirtAtHopper() {
        return Rs2Inventory.isFull() && payDirtCount() > 0;
    }

    /**
     * Returns true if player is in a mining area (near ore veins).
     * Used to prevent spec activation while walking to hopper/sack.
     */
    private boolean isInMiningArea() {
        var playerLoc = Rs2Player.getWorldLocation();
        if (playerLoc == null || miningSpot == null) return false;

        if (miningSpot.contains(playerLoc)) {
            return true;
        }

        // Fallback to anchor distance (for compatibility / legacy spots)
        List<WorldPoint> anchors = miningSpot.getWorldPoint();
        if (anchors == null || anchors.isEmpty()) return false;
        for (WorldPoint anchor : anchors) {
            if (playerLoc.distanceTo(anchor) <= 10) {
                return true;
            }
        }
        return false;
    }

    /**
     * After a mining-session fail (e.g. web-walk pathfinder-null while already at the spot),
     * retry mining instead of recovery→hopper when we still have inventory room.
     */
    private boolean shouldRetryMiningAfterSessionFail() {
        if (miningSpot == null || Rs2Inventory.isFull()) {
            return false;
        }
        var here = Rs2Player.getWorldLocation();
        if (here == null) {
            return false;
        }
        boolean inArea = miningSpot.contains(here);
        if (!inArea) {
            List<WorldPoint> anchors = miningSpot.getWorldPoint();
            boolean nearAnchor = false;
            if (anchors != null) {
                for (WorldPoint anchor : anchors) {
                    if (anchor != null && here.distanceTo(anchor) <= 12) {
                        nearAnchor = true;
                        break;
                    }
                }
            }
            if (!nearAnchor) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when the player is at a vein and may use pickaxe special (dragon/infernal/crystal).
     * Uses the session target first (wall veins may sit outside a strict mesh tile but still be mined).
     */
    private boolean canAttemptPickaxeSpec() {
        if (!isInMiningArea()) {
            return false;
        }
        if (miningSession.isIdle()) {
            return false;
        }
        var playerLoc = Rs2Player.getWorldLocation();
        return miningSession.canAttemptPickaxeSpecial(miningSpot, playerLoc);
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

        updateGainedNuggets();
    }

    /**
     * Recomputes the golden-nugget total the overlay shows as {@code inventory + bank}, matching the
     * reference MotherloadMine plugin. An inventory-only baseline ("gained this session") reads ~0 because
     * Deposit-All banks the nuggets, so the inventory count never accumulates — counting both containers
     * is the robust reading that always reflects what's actually owned.
     */
    private void updateGainedNuggets() {
        gainedNuggets = Rs2Inventory.count(ItemID.MOTHERLODE_NUGGET)
                + Rs2Bank.count(ItemID.MOTHERLODE_NUGGET);
    }

    private void applySackSessionOreSnapshot() {
        int[] oreCounts = sackSession.consumePendingOreDepositSnapshot();
        for (int i = 0; i < oreCounts.length && i < ORE_STAT_IDS.length; i++) {
            int snapshotCount = oreCounts[i];
            if (snapshotCount <= 0) {
                continue;
            }

            int oreId = ORE_STAT_IDS[i];
            int currentCount = Rs2Inventory.count(oreId);
            int lastSeenCount = lastInventoryCounts.getOrDefault(oreId, 0);

            // If the ore is still visible in inventory, normal inventory-delta accounting
            // will count it below. Only add the snapshot when deposit-all was fast enough
            // that the script never observed the ore inventory increase.
            if (currentCount == 0 && lastSeenCount < snapshotCount) {
                addOreSessionStats(oreId, snapshotCount - lastSeenCount);
                lastInventoryCounts.put(oreId, 0);
            }
        }
    }

    private void addOreSessionStats(int itemId, int added) {
        if (added <= 0) {
            return;
        }
        switch (itemId) {
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
                return;
        }
        int price = cachedItemPrices.getOrDefault(itemId, 0);
        totalValueGained += (long) price * added;
    }

    public void setSackIsFull(boolean full) {
        if (full && !sackIsFullFlag) {
            log.info("[MLM] Sack fullness flag set via external trigger (chat)");
        }
        this.sackIsFullFlag = full;
    }

    public void onSackVarbitChanged(int value) {
        lastVarbitValue = value;
        lastVarbitReadMs = System.currentTimeMillis();
        // Push snapshot immediately so overlay sees the fresh sack count on next render.
        // Safe: this path will hit the just-updated lastVarbit (cache hit on time) with no extra client-thread invoke.
        updateSnapshot();
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
        int initial  = lastHopperDepositInitial > 0 ? lastHopperDepositInitial : hopperSession.getInitialPayDirtCount();
        lastHopperDepositInitial = 0; // consume the snapshot for this audit
        // Consume the sack count captured fresh at the start of this deposit (pre + measured delta gives
        // a reliable post-deposit projection independent of varbit lag). Falls back to a live read.
        int preSack = sackState.consumePreDeposit(currentSackCount());

        log.info("[MLM] Post-deposit audit: residual={} (started with {})", residual, initial);

        if (residual == 0) {
            hopperSession.resetRetryCount();

            SackTracker.DepositProjection projection =
                    SackTracker.projectDeposit(preSack, initial, residual, maxSackSize, sackIsFullFlag);
            int deposited = projection.getDeposited();
            int projectedSack = projection.getProjectedSackCount();
            sackState.applyProjection(deposited, projectedSack, System.currentTimeMillis());
            boolean willSackBeFull = projection.isSackFullAfterDeposit();
            if (willSackBeFull) {
                setSackIsFull(true);
            }

            log.info("[MLM] Projected sack after deposit: {}/{} (current={}, deposited={})",
                    projectedSack, maxSackSize, currentSackCount(), deposited);

            // THE single repair gate. We just deposited pay-dirt into the hopper; broken struts stop
            // the wheel from processing it into the sack. Repair now — but only when the sack has room
            // (processing is pointless if it's full → empty instead) and no one else is fixing it
            // (isWaterwheelClear + deferral). Every other repair trigger was removed so the bot no
            // longer wanders off to repair while mining / idle / recovering.
            if (shouldRepairStrutsBeforeDeposit()
                    && !willSackBeFull && !isSackFull() && !sackIsFullFlag
                    && !(Rs2Inventory.isFull() && payDirtCount() > 0)) {
                log.info("[MLM] Post-deposit: struts broken and clear — repairing so the hopper processes the deposit");
                setStatus(MLMStatus.FIXING_WATERWHEEL);
                return;
            }

            if (Rs2Inventory.isFull() && payDirtCount() > 0) {
                if (willSackBeFull || isSackFull() || sackIsFullFlag) {
                    log.info("[MLM] Sack will be full after deposit but still have pay-dirt — dropping then emptying sack");
                    dropAllPayDirt();
                    setStatus(MLMStatus.EMPTY_SACK);
                } else {
                    setStatus(MLMStatus.DEPOSIT_HOPPER);
                }
            } else if (willSackBeFull || isSackFull() || sackIsFullFlag || hasOreInInventory()) {
                log.info("[MLM] Sack will be full after deposit — going straight to empty sack");
                setStatus(MLMStatus.EMPTY_SACK);
            } else {
                log.debug("[MLM] Post-deposit: resuming mining (repair, if needed, happens on the next deposit trip)");
                setStatus(MLMStatus.MINING);

                if (currentAnchor != null) {
                    WorldPoint here = Rs2Player.getWorldLocation();
                    if (here != null && here.distanceTo(currentAnchor) > 5) {
                        Rs2Walker.walkFastCanvas(currentAnchor);
                    }
                }
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
            sackState.clearProjection();
            setStatus(MLMStatus.EMPTY_SACK);
            return;
        }

        SackTracker.DepositProjection partialProjection =
                SackTracker.projectDeposit(preSack, initial, residual, maxSackSize, sackIsFullFlag);
        int thisBatchDeposited = partialProjection.getDeposited();
        int projectedAfterBatch = partialProjection.getProjectedSackCount();
        if (partialProjection.isSackFullAfterDeposit()) {
            sackState.applyProjection(thisBatchDeposited, projectedAfterBatch, System.currentTimeMillis());
            setSackIsFull(true);
            log.info("[MLM] Audit: deposit batch of {} projects sack to {}/{} (varbit now {}) with {} pay-dirt remaining — drop pay-dirt and empty sack (do not retry deposit)",
                    thisBatchDeposited, projectedAfterBatch, maxSackSize, currentSackCount(), residual);
            if (!hasOreInInventory() && !hasGemsInInventory() && payDirtCount() > 0) {
                dropAllPayDirt();
            }
            setStatus(MLMStatus.EMPTY_SACK);
            return;
        }

        // (Post-deposit repair-first logic already handled above for will-fill cases.)
        // Repair session itself handles fetching hammer (clearing space by dropping pay-dirt if needed),
        // random 1-or-both, dropping hammer after, and return-to-mining (climb if was upper).

        if (residual == initial) {
            if (hopperSession.incrementAndCheckRetryLimit()) {
                log.error("[MLM] Deposit failed {} times — entering recovery", HopperSession.MAX_DEPOSIT_RETRIES);
                hopperSession.resetRetryCount();
                setStatus(MLMStatus.RECOVERY);
                return;
            }
            if (!shouldDepositPayDirtAtHopper()) {
                log.info("[MLM] Audit: deposit failed but inv not full ({} remain) — resuming MINING", residual);
                hopperSession.reset();
                setStatus(MLMStatus.MINING);
                return;
            }
            // Re-enter DEPOSIT_HOPPER; its repair gate will fix broken struts before retrying.
            log.info("[MLM] Audit: deposit failed ({} / {} remain) — retrying ({}/{})",
                    residual, initial, hopperSession.getDepositRetryCount(), HopperSession.MAX_DEPOSIT_RETRIES);
            setStatus(MLMStatus.DEPOSIT_HOPPER);
            return;
        }

        if (hopperSession.incrementAndCheckRetryLimit()) {
            log.error("[MLM] Partial deposit failed {} times — entering recovery", HopperSession.MAX_DEPOSIT_RETRIES);
            hopperSession.resetRetryCount();
            setStatus(MLMStatus.RECOVERY);
            return;
        }
        if (!shouldDepositPayDirtAtHopper()) {
            log.info("[MLM] Audit: partial deposit ({} remain) but inv not full — resuming MINING", residual);
            hopperSession.reset();
            setStatus(MLMStatus.MINING);
            return;
        }
        // Re-enter DEPOSIT_HOPPER; its repair gate will fix broken struts before retrying.
        log.info("[MLM] Audit: partial deposit ({} / {} remain) — retrying ({}/{})",
                residual, initial, hopperSession.getDepositRetryCount(), HopperSession.MAX_DEPOSIT_RETRIES);
        setStatus(MLMStatus.DEPOSIT_HOPPER);
    }

    private void dropAllPayDirt() {
        int count = payDirtCount();
        if (count == 0) return;

        log.info("[MLM] Dropping {} pay-dirt to clear inventory for sack emptying", count);

        int dropped = 0;
        // Attempt cap > max possible paydirt (28 slots) so we never strand items across back-to-back
        // dropAll calls. Drops are fast successive shift-clicks with only a small gap between them —
        // NOT a ~280ms deliberate pause + antiban cooldown per item, which used to block the single
        // executor thread for many seconds (freezing the overlay and every other session) on a full
        // inventory. A human dropping pay-dirt does it quickly, so this is both faster and more human.
        while (payDirtCount() > 0 && dropped < 40) {
            if (!Rs2Inventory.interact(ItemID.PAYDIRT, "Drop")) {
                log.warn("[MLM] Failed to drop pay-dirt at count {}, breaking drop loop", dropped);
                break;
            }
            dropped++;
            if (isHumanLikeEnabled()) {
                sleep(Rs2Random.between(60, 150));
            }
        }

        log.info("[MLM] Pay-dirt drop complete, dropped={}, remaining: {}", dropped, payDirtCount());
    }

    // =========================================================================
    // Status determination
    // =========================================================================

    private MLMStatus determineNextStatusAfterMining() {
        int effectiveSackCount = getEffectiveSackCount();
        if (sackState.hasProjection()) {
            debug("[MLM] Using projected sack count: {}/{} (varbit={}, justDeposited={}) [knownAfter={}]",
                    effectiveSackCount, maxSackSize, currentSackCount(),
                    sackState.payDirtJustDeposited(), sackState.knownAfterDeposit());
        }
        boolean sackFull = maxSackSize > 0 && effectiveSackCount >= maxSackSize;

        // Priority 1: Sack full → must empty. Emptying does NOT need the waterwheel; repair is only
        // ever done on the deposit trip, so no repair routing here.
        if (sackFull || hasOreInInventory()) {
            // If we also have pay-dirt, drop it first so sack emptying can proceed
            if (payDirtCount() > 0) {
                log.info("[MLM] Sack full with pay-dirt in inventory — dropping pay-dirt before emptying sack");
                dropAllPayDirt();
            }
            return MLMStatus.EMPTY_SACK;
        }

        // Priority 2: Inventory full of pay-dirt — deposit at hopper. Repair check happens in handler.
        if (Rs2Inventory.isFull() && payDirtCount() > 0) {
            return MLMStatus.DEPOSIT_HOPPER;
        }

        if (config.dropGems() && hasGemsInInventory())   return MLMStatus.DROP_GEMS;
        return MLMStatus.MINING;
    }

    private MLMStatus determineNextStatusAfterRepair() {
        // After repair (or repair abort e.g. another player near wheel), re-check sack state with
        // highest priority. Sack full (detected via flag or count) always wins over resuming mining.
        // If we have leftover pay-dirt from hammer-fetch drop, deposit ONLY if sack not full.
        boolean sackFull = isSackFull() || sackIsFullFlag;
        if (Rs2Inventory.isFull() && payDirtCount() > 0) {
            if (sackFull) {
                log.info("[MLM] Post-repair: pay-dirt remains but sack is full — dropping pay-dirt then EMPTY_SACK");
                dropAllPayDirt();
                return MLMStatus.EMPTY_SACK;
            }
            return MLMStatus.DEPOSIT_HOPPER;
        }

        if (sackFull || hasOreInInventory()) {
            return MLMStatus.EMPTY_SACK;
        }
        return MLMStatus.MINING;
    }

    // =========================================================================
    // Status dispatch
    // =========================================================================

    private void dispatchByStatus() {
        switch (status) {
            case IDLE:
                updateSackSize();

                // Startup / initial IDLE special case: if we have no record of any deposits yet in this run,
                // force a fresh varbit read right before the full-sack decision. This guarantees that
                // starting the plugin while the sack is already full (no "will be full" chat this session,
                // flag=false, possible stale first read) will see the real current fill level and go to
                // EMPTY_SACK instead of MINING.
                if (!sackState.hasProjection() && sackState.lastDepositTimestamp() == 0) {
                    int fresh = Microbot.getClientThread().runOnClientThreadOptional(() ->
                            Microbot.getClient().getVarbitValue(SACK_COUNT_VARBIT)).orElse(lastVarbitValue);
                    if (fresh > 0) {
                        lastVarbitValue = fresh;
                        lastVarbitReadMs = System.currentTimeMillis();
                        debug("[MLM] IDLE initial fresh sack varbit probe: {}", fresh);
                    }
                    // Also let a high fresh value auto-correct the sack size assumption
                    if (fresh > SACK_SIZE) {
                        maxSackSize = SACK_LARGE_SIZE;
                    }
                    if (maxSackSize > 0 && fresh >= maxSackSize && !sackIsFullFlag) {
                        debug("[MLM] Treating startup fresh varbit {} as full sack, setting flag for downstream checks", fresh);
                        setSackIsFull(true);
                    }
                }

                if (isSackFull() || hasOreInInventory()) {
                    if (payDirtCount() > 0 && !hasOreInInventory() && !hasGemsInInventory()) {
                        log.info("[MLM] IDLE eval: sack full with only pay-dirt in inventory — dropping pay-dirt before emptying sack");
                        dropAllPayDirt();
                    }
                    // Emptying the sack doesn't need the waterwheel; repair only happens on the deposit trip.
                    // Small human "time to go banking" pause instead of instant action from idle.
                    // Urgent because sack is (or just became) full — bias quick when human on.
                    humanPause(300, 800, true);
                    setStatus(MLMStatus.EMPTY_SACK);
                    sackState.clearProjection();
                    log.info("[MLM] IDLE eval -> {} (sack={}/{})", status, currentSackCount(), maxSackSize);
                } else if (Rs2Inventory.isFull() && payDirtCount() > 0) {
                    humanPause(200, 600);
                    setStatus(MLMStatus.DEPOSIT_HOPPER);
                    log.info("[MLM] IDLE eval -> DEPOSIT_HOPPER");
                } else if (config.dropGems() && hasGemsInInventory()) {
                    setStatus(MLMStatus.DROP_GEMS);
                } else {
                    // On a true cold start (plugin just enabled, no prior deposits this run, low tick count)
                    // we want to begin the first mining cycle promptly so it doesn't "feel slow to find
                    // a mining area". Only apply the small random human "gear up" pause on subsequent cycles.
                    boolean coldStart = (scriptTicks < 4 && sackState.lastDepositTimestamp() == 0);
                    // 25% "gear up / orient" pause only when human-like enabled (imperfection / relaxed start).
                    // When disabled or cold: prompt start (attentive).
                    boolean doGearUp = isHumanLikeEnabled() && !coldStart
                            && personality.roll(personality.gearUpPauseChance());
                    if (doGearUp) {
                        humanPause(150, 400);
                    } else if (coldStart) {
                        debug("[MLM] Cold start IDLE -> MINING (skipping gear-up pause for responsive first cycle)");
                    } else {
                        debug("[MLM] IDLE -> MINING (no extra gear-up pause this time)");
                    }
                    if (coldStart) {
                        log.info("[MLM] Cold start: proceeding to first mining cycle (spot pre-selected at init)");
                    }
                    setStatus(MLMStatus.MINING);
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
        // GUARD: never start mining while holding a full inventory of pay-dirt.
        // This catches edge cases where status was set to MINING but pay-dirt
        // remains (e.g. after repair dropped some for hammer, or recovery).
        // If sack is also full, drop the pay-dirt and go empty sack instead of trying to deposit
        // (hopper will reject when sack at capacity).
        if (Rs2Inventory.isFull() && payDirtCount() > 0) {
            if (isSackFull() || sackIsFullFlag) {
                log.info("[MLM] Mining blocked: full pay-dirt but sack full — dropping pay-dirt then emptying sack");
                dropAllPayDirt();
                setStatus(MLMStatus.EMPTY_SACK);
            } else {
                log.info("[MLM] Mining blocked: full inventory of pay-dirt — depositing first");
                setStatus(MLMStatus.DEPOSIT_HOPPER);
            }
            return;
        }

        // CRITICAL: if sack is full (flag or count), do not start mining at all — go empty first.
        // (Emptying doesn't need the waterwheel; repair is only ever done on the deposit trip.)
        if (isSackFull() || sackIsFullFlag) {
            log.info("[MLM] Sack full at mining status — routing to EMPTY_SACK instead of mining");
            setStatus(MLMStatus.EMPTY_SACK);
            return;
        }

        // Check spec BEFORE starting mining session
        handlePickaxeSpec();

        // If we just used spec, don't start a new mining session yet
        if (needPostSpecMiningResume) {
            debug("[MLM] Mining status: waiting for post-spec resume");
            return;
        }

        debug("[MLM] handleMiningStatus: proceeding to mine | spot={} | anchor={} | brokenStruts={}",
                miningSpot, currentAnchor, tickBrokenStrutCount);

        // Guard against NPE if Rs2Antiban activity was reset (e.g. shutdown race or external reset).
        // This line re-syncs intensity from the Activity; safe to skip if not configured.
        try {
            Activity act = Rs2Antiban.getActivity();
            if (act != null) {
                Rs2Antiban.setActivityIntensity(act.getActivityIntensity());
            }
        } catch (Exception e) {
            debug("[MLM] Antiban intensity sync skipped during mining status: {}", e.getMessage());
        }

        if (config.useAntiCrash() && miningSpot != null && isPlayerAtMiningSpot()) {
            debug("[MLM] Anti-crash: player detected at mining spot, re-selecting");
            selectMiningSpotFromConfig();
            // Human "oh, someone else is here — I'll move" reaction time.
            humanPause(350, 950);
        } else if (config.useAntiCrash() && miningSpot != null) {
            debug("[MLM] Anti-crash check passed: no crowding at anchor {}", currentAnchor);
        }

        if (miningSpot == null || shouldRefreshMiningSpot()) {
            if (miningSpot != null) {
                debug("[MLM] Refreshing mining spot (config change or shuffle jitter)");
            }
            selectMiningSpotFromConfig();
        }

        if (miningSpot == null) {
            log.warn("[MLM] Cannot start mining — no valid spot assigned");
            selectMiningSpotFromConfig();
            if (miningSpot == null) {
                log.error("[MLM] Spot selection failed — entering recovery");
                setStatus(MLMStatus.RECOVERY);
                return;
            }
        }

        if (miningSession.isIdle()) {
            log.info("[MLM] Beginning MiningSession for spot {} (anchor {})", miningSpot, currentAnchor);
            miningSession.begin();
        }
    }

    private boolean shouldRefreshMiningSpot() {
        if (config.miningArea() != miningSpot) {
            log.info("[MLM] Mining area changed in config — forcing spot refresh");
            return true;
        }
        // Human "getting bored of this exact spot" – interval has natural jitter (only when human enabled).
        // When disabled: exact 5min (predictable, attentive, no artificial "bored" shuffle).
        long jitter = isHumanLikeEnabled() ? Rs2Random.between(-45000, 75000) : 0L; // +/- 45-75s around 5min
        long elapsed = System.currentTimeMillis() - lastSpotShuffleMs;
        if (elapsed > (SPOT_SHUFFLE_INTERVAL_MS + jitter)) {
            log.info("[MLM] Spot shuffle due to time + jitter (elapsed={}ms, jitter={})", elapsed, jitter);
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

        if (miningSpot != selected) {
            miningSession.invalidateUpperFloorCache();
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
        debug("[MLM] Spot selection complete — anchors available: {}", miningSpot != null ? miningSpot.getWorldPoint() : "none");
    }

    private void handleEmptySackStatus() {
        // GUARD: never empty sack while holding pay-dirt unless the sack itself is full.
        // If sack is full we *cannot* deposit pay-dirt (hopper rejects), so drop it instead
        // and proceed to empty. Otherwise deposit the pay-dirt first (deposit box won't take pay-dirt).
        if (payDirtCount() > 0) {
            if (isSackFull() || sackIsFullFlag) {
                log.info("[MLM] Pay-dirt ({}) during EMPTY_SACK but sack full — cannot deposit, dropping pay-dirt to proceed with empty",
                        payDirtCount());
                dropAllPayDirt();
            } else {
                log.info("[MLM] Pay-dirt ({}) during EMPTY_SACK — depositing at hopper first before going down",
                        payDirtCount());
                setStatus(MLMStatus.DEPOSIT_HOPPER);
                return;
            }
        }

        if (hasOreInInventory() || getEffectiveSackCount() > 0 || sackIsFullFlag) {
            // Small human pause to "decide it's time to go empty the sack" instead of instant transition.
            humanPause(200, 650);

            if (sackSession.isIdle() && canInteractWithLadder()) {
                lastLadderInteractionMs = System.currentTimeMillis();
                if (miningSpot == null) {
                    selectMiningSpotFromConfig();
                }
                // We are committing to emptying the sack now. The flag's effect is bypassed
                // in currentSackCount() while emptying, so the live (decreasing) sack count is visible
                // to the sackSession (to prevent "stayed full at 189" infinite loop) and to the
                // overlay. The flag remains true in case the session fails/interrupts.
                sackSession.begin(miningSpot);
            } else if (sackSession.isIdle()) {
                // Cooldown active (or session busy); still move toward the sack so we don't idle
                // at a distant mining spot while the ladder throttle elapses.
                var anySack = Microbot.getRs2TileObjectCache().query()
                        .where(o -> o.getId() == ObjectID.MOTHERLODE_SACK
                                || o.getId() == ObjectID.MOTHERLODE_SACK_GRAPHIC)
                        .nearest();
                if (anySack != null) {
                    WorldPoint loc = anySack.getWorldLocation();
                    int d = Rs2Player.getWorldLocation() != null ? Rs2Player.getWorldLocation().distanceTo(loc) : 0;
                    if (d > 10) {
                        if (d > 15) Rs2Walker.walkTo(loc); else Rs2Walker.walkFastCanvas(loc);
                    }
                }
            }
        } else {
            setSackIsFull(false);
            sackState.clearProjection();
            setStatus(MLMStatus.IDLE);
        }
    }

    private void handleDepositHopperStatus() {
        // GUARD 1: Do we still need to deposit?
        if (!shouldDepositPayDirtAtHopper()) {
            log.info("[MLM] Aborting hopper deposit — inventory not full (pay-dirt={})", payDirtCount());
            hopperSession.reset();
            setStatus(MLMStatus.MINING);
            return;
        }

        // GUARD 2: Can we even deposit? (sack not full). Emptying the sack does NOT need the
        // waterwheel (struts only gate hopper→sack processing), so a full sack just routes to
        // EMPTY_SACK regardless of strut state.
        if ((isSackFull() || sackIsFullFlag) && payDirtCount() > 0) {
            log.info("[MLM] Sack full — hopper cannot accept pay-dirt. Dropping {} pay-dirt to proceed to EMPTY_SACK.",
                    payDirtCount());
            dropAllPayDirt();
            setStatus(MLMStatus.EMPTY_SACK);
            return;
        }

        // (Repair is handled AFTER the deposit — see handlePostDepositAudit. Repairing before we've
        // emptied the pay-dirt would leave a full inventory that bounces us straight back here.)

        // Extra guard: if we've been retrying deposits and the hopper session
        // has exceeded its retry limit, something is wrong. Check if sack is full
        // as a last resort before entering infinite retry.
        if (hopperSession.getDepositRetryCount() >= HopperSession.MAX_DEPOSIT_RETRIES - 1) {
            log.warn("[MLM] Deposit retries nearly exhausted — checking if sack is actually full");
            if (currentSackCount() >= maxSackSize - 5) { // Within 5 of full
                log.info("[MLM] Sack near/full capacity detected on retry — routing to EMPTY_SACK");
                if (payDirtCount() > 0) {
                    dropAllPayDirt();
                }
                setStatus(MLMStatus.EMPTY_SACK);
                hopperSession.reset();
                return;
            }
        }

        if (hopperSession.isIdle() && canInteractWithLadder()) {
            lastLadderInteractionMs = System.currentTimeMillis();

            // Extra small human "deciding to go deposit" pause + the existing random checks.
            // The 5% pre-deposit linger + variance is human likeness only.
            humanPause(150, 450);

            if (isHumanLikeEnabled() && Rs2Random.between(0, 100) < 5) {
                debug("[MLM] Pre-deposit inventory check");
                sleep(Rs2Random.between(200, 500));
            }

            if (isHumanLikeEnabled()) {
                long variance = Rs2Random.between(-200, 400);
                if (variance > 0) {
                    sleep((int) variance);
                }
            }

            // Capture fresh sack value at the exact start of this deposit attempt.
            // This pre (combined with the measured accepted delta from inv change during the action)
            // gives a reliable "post for this deposit" for projection/known/ will-full detection,
            // independent of varbit lag at audit time and regardless of arbitrary starting sack value
            // (critical near capacity where hopper may accept only a small fraction of the offered inventory).
            lastVarbitReadMs = 0L;
            sackState.capturePreDeposit(currentSackCount());
            hopperSession.begin(miningSpot);
        } else if (hopperSession.isIdle()) {
            // Cooldown active; move toward the (correct/desired floor) hopper target while waiting
            // so we don't stand idle far from the facility. Target chosen from config, not current Y
            // (prevents nudging toward lower when we actually want to use upstairs hopper).
            WorldPoint hopperTarget = hopperWalkTarget();
            var p = Rs2Player.getWorldLocation();
            int d = p != null ? p.distanceTo(hopperTarget) : 0;
            if (d > 10) {
                if (d > 15) Rs2Walker.walkTo(hopperTarget); else Rs2Walker.walkFastCanvas(hopperTarget);
            }
        }
    }

    /**
     * Wall-clock throttle between hopper/sack begin() calls (prevents ladder spam on the
     * 600ms executor). Jittered per check; shorter when human-like is off.
     */
    private long ladderInteractionCooldownMs() {
        return HumanBehaviorProfile.ladderInteractionCooldownMs(isHumanLikeEnabled());
    }

    private boolean canInteractWithLadder() {
        if (lastLadderInteractionMs == 0L) {
            return true;
        }
        long elapsed = System.currentTimeMillis() - lastLadderInteractionMs;
        long required = ladderInteractionCooldownMs();
        boolean ready = elapsed >= required;
        if (!ready && (status == MLMStatus.EMPTY_SACK || status == MLMStatus.DEPOSIT_HOPPER)) {
            debug("[MLM] Ladder cooldown active for {} — {}ms left", status, required - elapsed);
        }
        return ready;
    }

    private void handleRepairStatus() {
        // 1-strut skip only when human-like enabled AND sack is not full (no urgency).
        // When the sack is full, even 1 broken strut blocks the hopper and must be fixed.
        boolean skipOneStrut = !isSackFull() && !sackIsFullFlag
                && isHumanLikeEnabled() && personality.roll(personality.oneStrutSkipChance());
        if (tickBrokenStrutCount == 1 && skipOneStrut) {
            debug("[MLM] Skipping repair with 1 broken strut (90% chance) — only {} strut(s) broken", tickBrokenStrutCount);
            repairSession.reset();
            setStatus(determineNextStatusAfterRepair());
            return;
        }

        if (repairSession.isIdle()) {
            // Quick "notice the broken wheel and go" reaction — repair only happens right after a deposit
            // now, so it's an in-flow action, not something to dither over. Short with light randomness.
            humanPause(150, 550, true);
            debug("[MLM] Repair notice/orientation pause done");
        }

        log.debug("[MLM] handleRepairStatus: brokenStruts={}, clear={}, sessionIdle={}",
                tickBrokenStrutCount, isWaterwheelClear(), repairSession.isIdle());

        if (repairSession.canFastComplete()) {
            repairSession.reset();
            setStatus(determineNextStatusAfterRepair());
        } else if (repairSession.isIdle()) {
            // Deposit pay-dirt before going down to repair, unless sack is full (hopper rejects).
            if (payDirtCount() > 0 && !isSackFull() && !sackIsFullFlag) {
                log.info("[MLM] Pay-dirt ({}) before repair — depositing at hopper first", payDirtCount());
                setStatus(MLMStatus.DEPOSIT_HOPPER);
                return;
            }
            if (miningSpot == null) {
                selectMiningSpotFromConfig();
            }
            // RepairSession will handle: if no hammer, fetch from supply crate first (may drop pay-dirt
            // to make space), search crate, verify, then walk to strut(s), repair (1 or both randomly),
            // then cleanup drops hammer (if we fetched it), then we return here to decide next (deposit
            // any remaining pay from hammer fetch, or empty sack, or back to MINING + climb if upper).
            // If the deferral timeout expired, a nearby player isn't actually repairing — tell the
            // session to ignore bystanders so it doesn't immediately back off to cleanup and loop.
            boolean reclaimFromBystander = repairDeferralExpired();
            log.info("[MLM] Starting repair session (post-deposit) — will fetch hammer from crate if needed (reclaim={})",
                    reclaimFromBystander);
            repairSession.begin(reclaimFromBystander);
        }
    }

    private void handleDropGemsStatus() {
        dropGems();
        setStatus(MLMStatus.IDLE);
    }

    private void handleRecoveryStatus() {
        if (System.currentTimeMillis() < recoveryWaitUntil) return;

        // Always ensure we have a spot selected — recovery must be able to drive us back to *any* configured
        // location (upper west tested; lower east/west, east upper, south spots, mid etc. all go through here).
        if (miningSpot == null) {
            selectMiningSpotFromConfig();
        }

        // Recovery: only route to hopper when pay-dirt must be cleared (full inv or sack full).
        // A few pieces from an interrupted mining walk should not trigger a deposit run.
        if (payDirtCount() > 0) {
            if (isSackFull() || sackIsFullFlag) {
                log.info("[MLM] Recovery: pay-dirt but sack full — dropping pay-dirt then emptying sack");
                dropAllPayDirt();
                sackSession.reset();
                setStatus(MLMStatus.EMPTY_SACK);
                return;
            }
            if (Rs2Inventory.isFull()) {
                log.info("[MLM] Recovery: inventory full of pay-dirt — prioritizing DEPOSIT_HOPPER");
                hopperSession.reset();
                setStatus(MLMStatus.DEPOSIT_HOPPER);
                return;
            }
            debug("[MLM] Recovery: {} pay-dirt but inventory not full — continuing recovery (will resume mining)",
                    payDirtCount());
        }

        if (isSackFull() || hasOreInInventory()) {
            log.info("[MLM] Recovery: ores detected in inventory/sack — prioritizing EMPTY_SACK");
            sackSession.reset();
            setStatus(MLMStatus.EMPTY_SACK);
            return;
        }

        int attempts = recoveryAttempts.incrementAndGet();
        log.info("[MLM] RECOVERY attempt {}/{} | lastStatus={} | sack={}/{} | paydirt={} | spot={}",
                attempts, MAX_RECOVERY_ATTEMPTS, lastLoggedStatus, currentSackCount(), maxSackSize, payDirtCount(), miningSpot);

        // UNIVERSAL RESOLVER: never hard-stop the plugin. On too many recoveries (any reason, any location,
        // bad mesh on untested east-upper/south-lower, ladder fail, no veins, path stall, etc.) we nuke
        // transient state and force a clean cycle from a known safe point. This lets it "resolve anything".
        if (attempts > MAX_RECOVERY_ATTEMPTS) {
            log.warn("[MLM] Exceeded {} recoveries — UNIVERSAL HARD RESET to resolve any stuck state (any reason). Re-selecting spot, clearing projections/flags/blacklists, walking to safe, forcing MINING. Will not stop.", MAX_RECOVERY_ATTEMPTS);
            Microbot.showMessage("MLM: Hard-resetting to recover from repeated stuck/recovery (works for all spots including untested).");

            resetAllSessions();
            miningSpot = null;
            currentAnchor = null;
            sackState.reset();
            sackIsFullFlag = false;
            lastVarbitReadMs = 0L;
            lastHopperDepositInitial = 0;
            // Note: crate blacklists live on the plugin; we don't clear here (small list, per-run intent).
            // A full plugin restart or future "reset crates" would clear them.
            recoveryAttempts.set(0);

            selectMiningSpotFromConfig();
            if (miningSpot == null) {
                // last resort default
                miningSpot = MLMMiningSpot.WEST_LOWER;
                currentAnchor = miningSpot.getWorldPoint().get(0);
            }

            Rs2Walker.setTarget(null);

            WorldPoint safe = recoverySafeWalkPoint(miningSpot);
            log.info("[MLM] Hard recovery: walking to safe {} for spot {}", safe, miningSpot);
            Rs2Walker.walkTo(safe, 6);
            recoveryWaitUntil = System.currentTimeMillis() + 8000L;
            int freshSack = currentSackCount();
            if ((maxSackSize > 0 && freshSack >= maxSackSize) || hasOreInInventory()) {
                log.info("[MLM] Hard recovery found sack/ores still need clearing ({}/{}) — routing to EMPTY_SACK, not MINING",
                        freshSack, maxSackSize);
                if (maxSackSize > 0 && freshSack >= maxSackSize) {
                    setSackIsFull(true);
                }
                setStatus(MLMStatus.EMPTY_SACK);
                return;
            }
            setStatus(MLMStatus.MINING);
            return;
        }

        if (attempts == 1 && shouldDepositPayDirtAtHopper()
                && sackState.lastDepositTimestamp() > 0
                && System.currentTimeMillis() - sackState.lastDepositTimestamp() < 15000L) {
            debug("[MLM] Recovery: attempting hopper re-click before full recovery (inv full)");
            hopperSession.reset();
            setStatus(MLMStatus.DEPOSIT_HOPPER);
            return;
        }

        resetAllSessions();
        Rs2Walker.setTarget(null);

        if (attempts >= 2) {
            log.info("[MLM] Recovery attempt {} — walking to safe area (resolver for any location)", attempts);
            Rs2Walker.walkTo(recoverySafeWalkPoint(miningSpot), 3);
            recoveryWaitUntil = System.currentTimeMillis() + 5000L;
            return;
        }

        if (Rs2Player.isAnimating() || Rs2Player.isMoving() || Rs2Player.isInteracting()) {
            recoveryWaitUntil = System.currentTimeMillis() + 1200L;
            return;
        }

        recoveryWaitUntil = System.currentTimeMillis() + 600L;
        sackState.clearProjection();
        setStatus(MLMStatus.MINING);
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

    /**
     * One-time startup gate. After login, authoritatively read the sack varbit before any mining/dispatch.
     * If it's full, set the sack-full flag so the state machine routes straight to EMPTY_SACK regardless
     * of floor. We wait (retry next tick) until we get a *successful* read — a not-yet-ready varbit reads
     * as 0 via the usual {@code orElse} path and would otherwise look like an empty sack and send us mining.
     *
     * @return true once the check is complete and normal execution may proceed; false while still waiting
     *         for a valid read (caller should return and retry next tick).
     */
    private boolean ensureStartupSackChecked() {
        if (startupSackChecked) {
            return true;
        }
        java.util.Optional<Integer> read = Microbot.getClientThread().runOnClientThreadOptional(() ->
                Microbot.getClient().getVarbitValue(SACK_COUNT_VARBIT));
        if (!read.isPresent()) {
            debug("[MLM] Startup sack check: varbit not ready yet — waiting before any action");
            return false;
        }
        int sack = read.get();
        lastVarbitValue  = sack;
        lastVarbitReadMs = System.currentTimeMillis();
        // Auto-correct the capacity assumption from a live value that only an upgraded sack can reach.
        if (sack > SACK_SIZE) {
            maxSackSize = SACK_LARGE_SIZE;
        }
        if (maxSackSize > 0 && sack >= maxSackSize) {
            log.info("[MLM] Startup: sack is full ({}/{}) — routing to EMPTY_SACK before anything else", sack, maxSackSize);
            setSackIsFull(true);
        } else {
            debug("[MLM] Startup sack check: {}/{} — not full, proceeding normally", sack, maxSackSize);
        }
        startupSackChecked = true;
        return true;
    }

    private int currentSackCount() {
        if (!Microbot.isLoggedIn()) {
            return lastVarbitValue; // safe fallback, no client thread when not ready
        }
        if (sackIsFullFlag && status != MLMStatus.EMPTY_SACK && sackSession.isIdle()) {
            // Chat flag is authoritative for "full" — varbit can lag or report pre-cap value.
            // Force count to max so logs, projections, and isSackFull all treat it as full until emptied.
            // EXCEPTION: when we are *actively emptying* (EMPTY_SACK status or sackSession running),
            // we must use the live varbit so the sackSession can observe the count decreasing and
            // the overlay can show accurate live sack progress. The flag's job (prevent deposit/mine)
            // has already been satisfied by routing to EMPTY_SACK.
            debug("[MLM] currentSackCount: flag set, returning max {} (varbit may be stale)", maxSackSize);
            return maxSackSize;
        }
        long now = System.currentTimeMillis();
        int varbit;
        // Force fresh reads:
        // - after a recent deposit (for accurate projection + so getEffectiveSackCount can detect catch-up;
        //   note we also capture a preSack at the *start* of the deposit for the projection math itself)
        // - on the very first read (lastRead==0)
        // - for the first few script ticks after start (ensures "start with sack already full"
        //   detects correctly even if the absolute first client read was momentarily stale/low)
        // - during active sack emptying (so live varbit is seen as we withdraw loads; fixes the
        //   "sack stayed full at 189 while in EMPTY_SACK causing infinite loop + overlay not updating")
        boolean forceRead = sackState.withinProjectionWindow(now)
                || lastVarbitReadMs == 0
                || scriptTicks < 4
                || (status == MLMStatus.EMPTY_SACK || !sackSession.isIdle());

        if (!forceRead && (now - lastVarbitReadMs < VARBIT_CACHE_MS)) {
            varbit = lastVarbitValue;
        } else {
            varbit = Microbot.getClientThread().runOnClientThreadOptional(() ->
                    Microbot.getClient().getVarbitValue(SACK_COUNT_VARBIT)).orElse(lastVarbitValue);
            lastVarbitValue = varbit;
            lastVarbitReadMs = now;
        }

        debug("[MLM] Varbit 5558 read: {} (cached={})", varbit, !forceRead && (now - lastVarbitReadMs < VARBIT_CACHE_MS));

        // Fallback: if varbit reads 0 but we recently deposited, use the projected count.
        // Extended to 30s because varbit updates can be delayed, especially after
        // the final deposit that fills the sack to capacity.
        // Note: the main projection/known now uses preSack (at deposit start) + delta, and
        // getEffectiveSackCount self-corrects on catch-up using sackValueKnownAfterLastDeposit.
        if (varbit == 0 && sackState.withinProjectionWindow(now)) {
            int projectedTotal = sackState.projectedFallbackTotal();
            debug("[MLM] Varbit 0 after deposit — using projected sack total {} (batch={}, pre={})",
                    projectedTotal, sackState.payDirtJustDeposited(), sackState.preDepositSackCount());
            return projectedTotal;
        }

        return varbit;
    }

    /**
     * Returns the best known sack count, including a short-term "floor" from a recent deposit
     * (varbit lags).

     * During lag after a deposit we return the known post-deposit value (so decisions don't
     * under-estimate fullness). As soon as the live varbit base reaches the known value, we
     * detect catch-up, drop the special state, and switch to the live base. This prevents
     * the double-count that caused premature EMPTY_SACK (real 186 + stale projection 27 = 213
     * >= cap while there was still room, as seen in logs).

     * The known value (and lastPreDepositSackCount) is computed from pre captured fresh at
     * deposit start + the actual accepted delta (inv drop during action; small near cap since
     * excess stays in inv). This is reliable for any starting value.
     * The known value is only kept for a limited window (SACK_PROJECTION_WINDOW_MS); after that
     * we fall back to base. Window aligned to 30s for consistency with forceRead logic in currentSackCount.
     */
    private int getEffectiveSackCount() {
        return effectiveSackCount(true);
    }

    /**
     * Read-only projection value for the overlay snapshot. Passes {@code mutate=false} because
     * updateSnapshot() runs on the client thread (varbit/chat events) as well as the executor, so it
     * must not clear projection state out from under an in-progress executor tick. Only the executor's
     * own {@link #getEffectiveSackCount()} calls perform the catch-up clearing.
     */
    private int getEffectiveSackCountForDisplay() {
        return effectiveSackCount(false);
    }

    /**
     * Best known sack count, with a short-term "floor" from a recent deposit (varbit lags). When
     * {@code mutate} is true and the live varbit has caught up (or the window expired), the projection
     * fields are cleared so we stop over-estimating; when false the fields are left untouched.
     */
    private int effectiveSackCount(boolean mutate) {
        return sackState.effective(currentSackCount(), System.currentTimeMillis(), mutate);
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
        if (!hasGemsInInventory()) return;
        log.info("[MLM] Dropping gems to save space");
        humanPause(150, 450);
        int[] gems = {ItemID.UNCUT_SAPPHIRE, ItemID.UNCUT_EMERALD, ItemID.UNCUT_RUBY, ItemID.UNCUT_DIAMOND};
        for (int gem : gems) {
            // Attempt-cap + break on failure so a rejected click (open interface, etc.) can't spin
            // the executor thread forever. Mirrors dropAllPayDirt().
            int attempts = 0;
            while (Rs2Inventory.hasItem(gem) && attempts < 28) {
                if (!Rs2Inventory.interact(gem, "Drop")) {
                    log.warn("[MLM] Failed to drop gem {}, breaking drop loop", gem);
                    break;
                }
                attempts++;
                // Quick successive drops, not a per-item pause+cooldown that blocks the executor.
                if (isHumanLikeEnabled()) {
                    sleep(Rs2Random.between(60, 150));
                }
            }
        }
    }

    private int queryBrokenStrutCount() {
        return Microbot.getRs2TileObjectCache().query()
                .withId(ObjectID.MOTHERLODE_WHEEL_STRUT_BROKEN)
                .toList()
                .size();
    }

    private boolean isWaterwheelClear() {
        // Root-cause guard for the "idle bystander" deadlock: every repair-gating decision routes
        // through here. A player standing near the wheel might be repairing, so we normally defer.
        // But if the struts stay broken past the deferral timeout, that player clearly isn't fixing
        // them — reclaim the repair ourselves regardless of proximity so we don't get stuck retrying
        // deposits at a hopper that can never process (or looping in recovery).
        if (repairDeferralExpired()) {
            debug("[MLM] Waterwheel broken > {}ms with a nearby player not repairing — reclaiming repair",
                    REPAIR_DEFER_TIMEOUT_MS);
            return true;
        }
        WorldPoint waterwheelArea = IrkedMLMMapConstants.WATERWHEEL_AREA;
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

    /** True once struts have been broken longer than {@link #REPAIR_DEFER_TIMEOUT_MS}. */
    private boolean repairDeferralExpired() {
        return brokenStrutsObservedSinceMs > 0L
                && System.currentTimeMillis() - brokenStrutsObservedSinceMs > REPAIR_DEFER_TIMEOUT_MS;
    }

    /**
     * True when we should repair struts before attempting to deposit.
     * Broken waterwheels block the hopper; if no other player is fixing them, we must.
     */
    private boolean shouldRepairStrutsBeforeDeposit() {
        return config.repairStruts() && tickBrokenStrutCount > 0 && isWaterwheelClear();
    }



    // =========================================================================
    // Snapshot & overlay
    // =========================================================================

    private void updateSnapshot() {
        try {
            if (!Microbot.isLoggedIn()) {
                // Avoid client thread calls at startup or when not ready; prevents TimeoutExceptions
                // that were triggering breakhandler dialogs and spamming errors.
                snapshotRef.set(SessionSnapshot.empty());
                return;
            }

            boolean returningToSpot = !sackSession.isIdle()
                    && sackSession.getReturnPoint() != null;

            int currentXp = lastSnapshotMiningXp > 0 ? lastSnapshotMiningXp : startXp;

            SessionSnapshot snap = new SessionSnapshot(
                    status,
                    miningSpot,
                    // Use effective (projection-aware) value for the *displayed* sack in the overlay.
                    // This makes the "Sack X/Y + progress bar" update immediately after a deposit
                    // (via the known post-deposit value) instead of waiting for the lagged raw varbit.
                    // During emptying the projection state is cleared, so it shows live decreasing count.
                    // The event listener (onSackVarbitChanged) keeps the underlying lastVarbit fresh.
                    // Read-only variant: updateSnapshot() runs on the client thread too (varbit/chat
                    // events), so it must not clear projection state from under the executor tick.
                    getEffectiveSackCountForDisplay(),
                    maxSackSize,
                    startTimeMs,
                    System.currentTimeMillis(),
                    miningSession.getTargetVein() != null ? miningSession.getTargetVein().toString() : null,
                    miningSession.isActivelyMining(),
                    miningSession.getFailedClicks(),
                    recoveryAttempts.get(),
                    !hopperSession.isIdle(),
                    sackState.lastDepositTimestamp(),
                    !repairSession.isIdle(),
                    repairSession.getPhaseLabel(),
                    tickBrokenStrutCount,
                    0L,
                    totalValueGained,
                    gainedNuggets,
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

    /** Active spot for area overlay, else configured mining area. */
    public MLMMiningSpot getAreaOverlaySpot() {
        return miningSpot != null ? miningSpot : config.miningArea();
    }

    public java.util.Set<WorldPoint> getRememberedRockfallTiles() {
        return miningSession.getRememberedRockfallsView();
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

    /**
     * Single choke point for every status change. Logs the transition and — critically —
     * resets statusEnteredMs at the exact moment status changes, so the watchdog's dwell-time
     * measurement is always accurate no matter which handle* method (or early-return branch)
     * triggers the change. Direct {@code status = ...} assignment must never be used elsewhere.
     */
    private void setStatus(MLMStatus newStatus) {
        if (newStatus == status) return;
        log.info("MLM status transition: {} -> {} | sack={}/{} | paydirt={} | oresInInv={} | brokenStruts={}",
                status, newStatus,
                currentSackCount(), maxSackSize,
                payDirtCount(),
                hasOreInInventory(),
                tickBrokenStrutCount);
        status = newStatus;
        lastLoggedStatus = newStatus;
        statusEnteredMs = System.currentTimeMillis();
    }

    private void resetAllSessions() {
        miningSession.reset();
        hopperSession.reset();
        sackSession.reset();
        repairSession.reset();
        lastHopperDepositInitial = 0;
        // Only the in-flight pre-deposit capture is abandoned here; a completed deposit's projection
        // must survive a session reset because the varbit may still be lagging.
        sackState.clearPreDeposit();
    }

    @Override
    public void shutdown() {
        log.info("Starting MLM script shutdown");
        running = false;
        super.shutdown();
        Rs2Antiban.resetAntibanSettings();
        Rs2Walker.setTarget(null);
        resetAllSessions();
        log.info("MLM script shutdown complete");
    }
}
