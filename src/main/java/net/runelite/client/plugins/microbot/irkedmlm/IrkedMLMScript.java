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
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.irkedmlm.enums.DepositMethod;
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
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.settings.Rs2Settings;
import net.runelite.client.plugins.microbot.util.world.Rs2WorldUtil;
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

    /** Number of waterwheels currently running (MOTHERLODE_WHEEL_FIXED in scene). Pay-dirt only
     *  freezes when this reaches 0 (both wheels broken); while ≥1 wheel turns, water still flows. */
    private int tickRunningWheelCount = 0;

    /** When the water first froze (both wheels broken; 0 = at least one wheel running). Used to stop
     *  indefinitely deferring repair to a player standing near the wheel who turns out to be idle. */
    private long brokenStrutsObservedSinceMs = 0L;

    /**
     * Sack capacity proven by the hopper refusing a deposit, or 0 while unknown. The configured size
     * is only a claim: pick "Upgraded" on an account that still has the 108 sack and the varbit caps
     * at 108 while maxSackSize says 189, so isSackFull() is never true and the deposit loop retries
     * forever. A refusal measures the real ceiling, so we believe that over the config.
     */
    private int measuredSackCapacity = 0;

    /** Pay-dirt dropped to make room for emptying the sack, so it can be reclaimed afterwards. */
    private final DroppedPayDirt droppedPayDirt = new DroppedPayDirt();

    /** Shared "is somebody else already on the wheel, and how long do we leave them to it" policy. */
    private final MlmRepairEtiquette repairEtiquette = new MlmRepairEtiquette();
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
    private long      recoveryWaitUntil = 0L;
    private long      statusEnteredMs   = System.currentTimeMillis();

    // Cached values to reduce client thread calls (prevents TimeoutExceptions and breakhandler popups at startup)
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

    /** Cross-session persisted totals + intended loadout. Loaded (as this session's immutable baseline)
     *  in {@link #initialise()}, recomputed from that baseline + session deltas in {@link #saveStats()}. */
    private static final String STATS_KEY = "stats.mlm";
    private MlmStats stats = new MlmStats();
    private int sessionSacksEmptied = 0;
    /** Golden nuggets gained this session: the running sum of every rise in the carried count. */
    @Getter
    /** Last observed carried nugget count; -1 until the first reading establishes the baseline. */

    /** Initial pay-dirt count captured before hopper reset, for post-deposit delta calc. */
    private int lastHopperDepositInitial = 0;

    /** Deposit-trip retry count. Owned by the script rather than HopperSession because each retry is a
     *  reset()+begin() pair and begin() zeroes the session's own counter, so a session-level count could
     *  never reach the RECOVERY backstop. Incremented by the audit on a rejected/partial deposit; reset
     *  by setStatus() on any transition out of DEPOSIT_HOPPER. */
    private int hopperDepositRetries = 0;

    /** Owns the post-deposit sack-count projection (compensates for the laggy sack varbit) and the
     *  last-deposit timestamp. See {@link SackState}. */
    private final SackState sackState = new SackState();

    /**
     * Session counting — XP, nuggets, per-ore totals and their GE value. Owns its own baselines and
     * price cache; see {@link MlmSessionStats} for the counting rules.
     */
    private final MlmSessionStats sessionStats = new MlmSessionStats(
            Rs2Inventory::itemQuantity,
            itemId -> Microbot.getClientThread()
                    .runOnClientThreadOptional(() -> Microbot.getItemManager().getItemPrice(itemId))
                    .orElse(0));

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
    /** One-shot per run: have we confirmed a gem bag is in the inventory (or the feature is off)? */
    private boolean gemBagEnsured = false;
    private boolean preflightDone = false;
    private boolean preflightBankVisitDone = false;
    private DepositMethod effectiveDepositMethod = DepositMethod.ITEMS;

    // -------------------------------------------------------------------------

    @Inject
    public IrkedMLMScript(IrkedMLMPlugin plugin, IrkedMLMConfig config) {
        this.plugin = plugin;
        this.config = config;

        var tileCache = Microbot.getRs2TileObjectCache();
        this.miningSession = new MiningSession(tileCache, config);
        this.hopperSession = new HopperSession(tileCache, config);
        this.sackSession   = new SackSession(tileCache, config);
        this.repairSession = new RepairSession(tileCache, config, repairEtiquette);
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

        log.info("Starting Motherlode Mine script v{} (session-oriented)", IrkedMLMPlugin.version);
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
        sackSession.setGemBagSuppliers(
                () -> { Rs2ItemModel b = firstGemBagItem(); return b != null && b.getSlot() == GEM_BAG_SLOT; },
                this::isGemBagInSlotLocked);
        sackSession.setDepositMethodSupplier(() -> effectiveDepositMethod);

        gemBagEnsured                = false; // re-verify each run (singleton survives stop/start)
        preflightDone                = false; // one-shot startup gate re-runs each fresh start
        preflightBankVisitDone       = false; // Phase A (single bank session) not yet done this run
        effectiveDepositMethod       = config.depositMethod();
        startTimeMs                  = System.currentTimeMillis();
        // Re-baseline every run(): the script is a @Singleton whose instance survives stop/start, so a
        // stale baseline would inflate this run's rates. The XP baseline is taken from the first real
        // reading in the tick loop rather than here, so it works when started at the login screen too.
        sessionStats.reset();
        sessionSacksEmptied          = 0;
        try {
            MlmStats loaded = (Microbot.getConfigManager() != null)
                    ? Microbot.getConfigManager().getConfiguration(IrkedMLMConfig.configGroup, STATS_KEY, MlmStats.class)
                    : null;
            stats = (loaded != null) ? loaded : new MlmStats();
        } catch (Exception e) {
            stats = new MlmStats();
        }
        log.info("[MLM] Loaded persisted stats: ores={} nuggets={} xp={} sacks={}",
                stats.oresMined, stats.nuggets, stats.xpGained, stats.sacksEmptied);
        miningSpot                   = null;
        lastLoggedStatus             = null;
        sackState.reset();
        lastHopperDepositInitial     = 0;
        hopperDepositRetries         = 0;
        // Clear leftover sack state from a prior run (singleton survives stop/start).
        sackIsFullFlag               = false;
        lastVarbitValue              = 0;
        lastVarbitReadMs             = 0L;
        brokenStrutsObservedSinceMs  = 0L;
        measuredSackCapacity         = 0;
        droppedPayDirt.clear();
        repairEtiquette.reset();
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

        sessionStats.rebaselineInventory();
        if (Microbot.isLoggedIn()) {
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
                sessionStats.getStartXp(), currentSackCount(), maxSackSize, miningSpot);
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
            tickRunningWheelCount = queryRunningWheelCount();
            // Pay-dirt only freezes when BOTH wheels stop (no running wheel). While one wheel turns,
            // water still flows and the hopper keeps processing — so we only "need repair", and only
            // start the deferral timer, when no wheel is running.
            if (tickBrokenStrutCount > 0 && tickRunningWheelCount == 0) {
                if (brokenStrutsObservedSinceMs == 0L) {
                    brokenStrutsObservedSinceMs = System.currentTimeMillis();
                }
            } else {
                brokenStrutsObservedSinceMs = 0L;  // a wheel is running (we/someone fixed one) — reset timer
            }

            debug("[MLM] executeTask tick | status={} | miningSpot={} | sack={}/{} | paydirt={} | brokenStruts={} | runningWheels={}",
                    status, miningSpot, currentSackCount(), maxSackSize, payDirtCount(), tickBrokenStrutCount, tickRunningWheelCount);

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

            sessionStats.update();

            // Mining XP, throttled to one client-thread read per XP_CACHE_MS.
            long nowXp = System.currentTimeMillis();
            if (sessionStats.needsXpRead(nowXp)) {
                sessionStats.cacheXp(Microbot.getClientThread()
                        .runOnClientThreadOptional(() -> Microbot.getClient().getSkillExperience(Skill.MINING))
                        .orElse(-1), nowXp);
            }
            if (sessionStats.recordXp(sessionStats.cachedXp())) {
                globalLastXpTime.set(System.currentTimeMillis());
                miningSession.onMiningXp();
            }

            updateSackSize();

            final boolean antibanDispatchPause = isHumanLikeEnabled()
                    && Rs2AntibanSettings.actionCooldownActive;

            if (!hasRequiredTools()) {
                Microbot.showMessage("Missing required tools (Pickaxe). Please ensure you have one.");
                log.warn("Missing required tools (Pickaxe), stopping plugin");
                Microbot.stopPlugin(plugin);
                return;
            }

            // One-shot startup preflight: establish the gem bag, the off-hand Imcando hammer, and the
            // locked gem-bag slot, and resolve the deposit method that is actually safe. It must run to
            // completion once and never re-enter — re-checking the slot lock on the per-tick path walks
            // the player to the bank mid-run and freezes the deposit loop.
            if (!preflightDone) {
                if (!runPreflight()) return; // still settling (withdraw/wield/lock) or stopped
                preflightDone = true;
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
                sessionStats.applyDepositSnapshot(sackSession.consumePendingOreDepositSnapshot());
                sessionStats.update();
                if (sackSession.isComplete() || sackSession.isFailed()) {
                    boolean    failed      = sackSession.isFailed();
                    WorldPoint returnPoint = sackSession.getReturnPoint();
                    sackSession.reset();
                    if (failed && payDirtCount() > 0) {
                        // SackSession aborts when pay-dirt is in the inventory, and says so: it wants a
                        // hopper trip, not recovery. Honour that instead of treating it as a fault.
                        log.info("[MLM] Sack session aborted holding {} pay-dirt — depositing at the hopper first",
                                payDirtCount());
                        setStatus(MLMStatus.DEPOSIT_HOPPER);
                    } else if (failed) {
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
                        sessionSacksEmptied++;
                        saveStats();
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
                    // Capture the initial pay-dirt count BEFORE reset() zeroes it — for BOTH paths. The
                    // audit compares residual vs initial to route (rejected/partial/repair/recover); a
                    // failed deposit that read 0 here made residual!=initial always true, skipping the
                    // frozen-wheel + repair gates and dropping into an infinite blind-retry loop.
                    lastHopperDepositInitial = hopperSession.getInitialPayDirtCount();
                    hopperSession.reset();

                    handlePostDepositAudit();
                    if (wasComplete && status == MLMStatus.MINING) {
                        debug("[MLM] Fast-starting mining session immediately after deposit");
                        handleMiningStatus();
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
            // PRIORITY 1.5 — reclaim pay-dirt dropped for the sack trip
            // -------------------------------------------------------------------------
            // The sack trip is finished (every session above is idle) and the slots it needed are free
            // again, so go back for the pile rather than leaving several minutes of mining on the floor.
            // Deliberately after the session block: collecting mid-trip would refill the very slots the
            // sack emptying needs.
            if (droppedPayDirt.isPending(System.currentTimeMillis())
                    && status != MLMStatus.EMPTY_SACK
                    && status != MLMStatus.RECOVERY
                    && sackSession.isIdle() && hopperSession.isIdle() && repairSession.isIdle()) {
                if (collectDroppedPayDirt()) {
                    updateSnapshot();
                    return;
                }
            }

            // -------------------------------------------------------------------------
            // PRIORITY 2 — dispatch
            // -------------------------------------------------------------------------
            dispatchByStatus();

            // Progress watchdog (>4min in working status without change -> RECOVERY). Never stops the plugin.
            // statusEnteredMs is maintained by setStatus() at the exact moment status changes (including
            // changes made inside the handle* methods called above), so this reflects true dwell time.
            if (status != MLMStatus.IDLE && status != MLMStatus.RECOVERY
                    && status != MLMStatus.WAITING_FOR_REPAIR && statusEnteredMs > 0) {
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
        return HopperSession.hopperWalkTargetFor(config, miningSpot);
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
            hopperDepositRetries = 0;

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

            // THE single repair gate. We just deposited pay-dirt into the hopper; the hopper only
            // stalls when BOTH wheels are broken (no wheel turning) — one running wheel still washes
            // pay-dirt into the sack (see shouldRepairStrutsBeforeDeposit). Repair only when the sack has room
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

            // Always drop: SackSession refuses to run while pay-dirt is held (it needs the slots), so
            // carrying it in "because there are ores too" ended the trip in RECOVERY. The pile is
            // reclaimed after the sack is emptied, so dropping costs nothing.
            dropAllPayDirt();
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
            dropAllPayDirt();
            setStatus(MLMStatus.EMPTY_SACK);
            return;
        }

        // (Post-deposit repair-first logic already handled above for will-fill cases.)
        // Repair session itself handles fetching hammer (clearing space by dropping pay-dirt if needed),
        // random 1-or-both, dropping hammer after, and return-to-mining (climb if was upper).

        if (residual == initial) {
            // Deposit rejected with nothing accepted — the hopper is jammed. It only stalls when the water
            // freezes (both wheels broken), so it's backed up and won't accept pay-dirt until a wheel turns.
            // Retrying the click does nothing; the wheel has to be dealt with first.
            if (shouldRepairStrutsBeforeDeposit() && !isSackFull() && !sackIsFullFlag) {
                // Repairs on + wheel frozen + no one else on it: go fix it so the hopper drains and accepts.
                log.info("[MLM] Deposit rejected while wheel frozen — repairing so the hopper drains and accepts pay-dirt");
                setStatus(MLMStatus.FIXING_WATERWHEEL);
                return;
            }
            if (shouldWaitForOthersToRepair()) {
                // Repairs off but user opted to wait for other players to fix the wheel.
                log.info("[MLM] Deposit rejected while wheel frozen (repairs off) — waiting for another player to repair");
                hopperSession.reset();
                setStatus(MLMStatus.WAITING_FOR_REPAIR);
                return;
            }
            hopperDepositRetries++;

            // The hopper refuses everything for exactly two reasons: the water is frozen (both gates
            // above) or the sack is full. A wheel is turning, so it is the sack — regardless of what
            // the varbit or the configured sack size say. One retry first, in case the click simply
            // missed; a second identical refusal is proof.
            //
            // This is what was spamming the hopper: with the sack genuinely full but maxSackSize
            // overstated, isSackFull() stayed false, so the "sack full → empty it" branch never ran and
            // this path retried to the recovery cap, recovered (which resets the counter), and came
            // straight back for another burst.
            if (tickRunningWheelCount > 0 && hopperDepositRetries > 1) {
                int measured = currentSackCount();
                if (measured > 0 && measured < maxSackSize) {
                    log.warn("[MLM] Hopper refused all {} pay-dirt with a wheel running at sack {}/{} — "
                                    + "real capacity is {}, correcting (configured sack size was wrong)",
                            initial, measured, maxSackSize, measured);
                    measuredSackCapacity = measured;
                    maxSackSize = measured;
                } else {
                    log.info("[MLM] Hopper refused all {} pay-dirt with a wheel running — sack is full", initial);
                }
                setSackIsFull(true);
                sackState.clearProjection();
                dropAllPayDirt();
                setStatus(MLMStatus.EMPTY_SACK);
                return;
            }

            if (hopperDepositRetries >= HopperSession.MAX_DEPOSIT_RETRIES) {
                log.error("[MLM] Deposit rejected {} times (wheel not frozen / repairs off) — entering recovery", HopperSession.MAX_DEPOSIT_RETRIES);
                setStatus(MLMStatus.RECOVERY);
                return;
            }
            if (!shouldDepositPayDirtAtHopper()) {
                log.info("[MLM] Audit: deposit failed but inv not full ({} remain) — resuming MINING", residual);
                hopperSession.reset();
                setStatus(MLMStatus.MINING);
                return;
            }
            log.info("[MLM] Audit: deposit rejected ({} / {} remain) — retrying ({}/{})",
                    residual, initial, hopperDepositRetries, HopperSession.MAX_DEPOSIT_RETRIES);
            setStatus(MLMStatus.DEPOSIT_HOPPER);
            return;
        }

        // residual < initial here: pay-dirt WAS accepted, so this is normal multi-click depositing, not a
        // stall. Progress clears the no-progress retry budget (the residual==initial branch above owns
        // stall escalation); pay-dirt is finite and decreasing, so this can't loop forever.
        hopperDepositRetries = 0;
        if (!shouldDepositPayDirtAtHopper()) {
            log.info("[MLM] Audit: partial deposit ({} remain) but inv not full — resuming MINING", residual);
            hopperSession.reset();
            setStatus(MLMStatus.MINING);
            return;
        }
        // Re-enter DEPOSIT_HOPPER to deposit the rest.
        log.info("[MLM] Audit: partial deposit ({} / {} remain, progress made) — depositing the rest",
                residual, initial);
        setStatus(MLMStatus.DEPOSIT_HOPPER);
    }

    private void dropAllPayDirt() {
        int count = payDirtCount();
        if (count == 0) return;

        log.info("[MLM] Dropping {} pay-dirt to clear inventory for sack emptying", count);

        int dropped = 0;
        // Attempt cap > max possible pay-dirt (28 slots) so we never strand items across back-to-back
        // dropAll calls. Fast successive shift-clicks with only a small gap — deliberately NOT a pause
        // plus antiban cooldown per item, which would block the single executor thread for seconds on a
        // full inventory and freeze the overlay and every other session. A human drops pay-dirt quickly,
        // so this is both faster and more human.
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

        if (dropped > 0) {
            // Remember the pile so it can be picked back up once the sack is clear — that is several
            // minutes of mining lying on the floor, and a player would never just walk off from it.
            droppedPayDirt.note(Rs2Player.getWorldLocation(),
                    net.runelite.client.plugins.microbot.irkedmlm.session.Session.playerOnUpperFloor(),
                    dropped, System.currentTimeMillis());
        }
    }

    /**
     * Collects pay-dirt we dropped to free slots for emptying the sack.
     *
     * @return {@code true} while still collecting (the caller should not move on yet)
     */
    private boolean collectDroppedPayDirt() {
        long now = System.currentTimeMillis();

        if (!droppedPayDirt.isPending(now)) {
            droppedPayDirt.clear();
            return false;
        }

        // Floor first, and it must be the height-based test: both MLM levels are the same plane, so a
        // pile dropped upstairs sits only ~12 world tiles from the downstairs deposit box. Every
        // WorldPoint distance check reads that as "right there", and Rs2GroundItem happily finds the
        // pile in the loaded scene — so the bot stood on the lower floor clicking pay-dirt it could
        // only reach by ladder, forever. If we are on the wrong level, do nothing and keep the claim:
        // the run climbs back up to its mining spot on its own, and collection resumes there.
        boolean upstairsNow = net.runelite.client.plugins.microbot.irkedmlm.session.Session.playerOnUpperFloor();
        if (upstairsNow != droppedPayDirt.isOnUpperFloor()) {
            debug("[MLM] Dropped pay-dirt is on the {} floor and we are on the {} — waiting until we are back there",
                    droppedPayDirt.isOnUpperFloor() ? "upper" : "lower", upstairsNow ? "upper" : "lower");
            return false;
        }

        WorldPoint here = Rs2Player.getWorldLocation();
        WorldPoint pile = droppedPayDirt.getWhere();
        boolean nearPile = here != null && pile != null && here.distanceTo(pile) <= 12;

        if (!droppedPayDirt.shouldCollect(now, Rs2Inventory.emptySlotCount(), nearPile)) {
            if (!nearPile) {
                debug("[MLM] Dropped pay-dirt is out of range now — writing it off");
            }
            droppedPayDirt.clear();
            return false;
        }

        droppedPayDirt.beginCollecting(now);

        if (!Rs2GroundItem.exists(ItemID.PAYDIRT, 12)) {
            log.info("[MLM] Collected the dropped pay-dirt — depositing it before returning to mine");
            droppedPayDirt.clear();
            routeReclaimedPayDirt();
            return false;
        }
        if (Rs2Inventory.emptySlotCount() <= 0) {
            log.info("[MLM] Inventory full while collecting dropped pay-dirt — depositing what we have");
            droppedPayDirt.clear();
            routeReclaimedPayDirt();
            return false;
        }

        if (Rs2GroundItem.loot(ItemID.PAYDIRT, 12)) {
            humanPause(120, 340, true);
        } else {
            debug("[MLM] Pay-dirt pickup click did not register — retrying");
            sleep(Rs2Random.between(250, 600));
        }
        return true;
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

        // Gems before the hopper trip: this check used to sit *below* the deposit branch, which
        // matches on any full inventory and so made it unreachable. Never carry gems to the hopper.
        if (config.dropGems() && !config.useGemBag() && hasGemsInInventory()) {
            return MLMStatus.DROP_GEMS;
        }

        // Inventory full of pay-dirt — deposit at hopper. Repair check happens in handler.
        if (Rs2Inventory.isFull() && payDirtCount() > 0) {
            return MLMStatus.DEPOSIT_HOPPER;
        }

        // Full, but not of pay-dirt: junk, nuggets and anything else the user is carrying have taken
        // every slot. Returning MINING here span forever — MiningSession immediately reports INV_FULL,
        // the session completes, and we land right back here. Emptying the sack is the only move that
        // frees space, and it is harmless when the sack is already empty.
        if (Rs2Inventory.isFull()) {
            log.warn("[MLM] Inventory full with no pay-dirt to deposit — going to empty the sack to free space");
            return MLMStatus.EMPTY_SACK;
        }

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
                    if (payDirtCount() > 0) {
                        log.info("[MLM] IDLE eval: sack full with pay-dirt in inventory — dropping it before emptying sack");
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
            case WAITING_FOR_REPAIR: handleWaitingForRepair();   break;
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
        if (hopperDepositRetries >= HopperSession.MAX_DEPOSIT_RETRIES - 1) {
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

        boolean clear = isWaterwheelClear();
        log.debug("[MLM] handleRepairStatus: brokenStruts={}, clear={}, sessionIdle={}",
                tickBrokenStrutCount, clear, repairSession.isIdle());

        if (repairSession.canFastComplete()) {
            repairSession.reset();
            setStatus(determineNextStatusAfterRepair());
        } else if (!clear && repairSession.isIdle()) {
            // Someone is stood at the wheel. This check used to be computed for the log line above and
            // then thrown away, so the bot barged in and repaired alongside them every time. Park and
            // let them finish; MlmRepairEtiquette's patience window reclaims the job if they turn out
            // to be idle.
            log.info("[MLM] Another player is at the waterwheel ({}s) — waiting rather than repairing over them",
                    repairEtiquette.yieldedSeconds());
            setStatus(MLMStatus.WAITING_FOR_REPAIR);
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
            // We only get here when the wheel is clear, so the session may ignore anyone who wanders
            // up *after* our patience already ran out — otherwise it would back off again on arrival.
            log.info("[MLM] Starting repair session (post-deposit) — will fetch hammer from crate if needed");
            repairSession.begin(repairEtiquette.yieldExpired());
        }
    }

    private void handleDropGemsStatus() {
        dropGems();
        // Straight back to mining rather than via IDLE: dropping gems is a two-second housekeeping
        // task, not a reason to re-evaluate the whole run.
        setStatus(payDirtCount() > 0 && Rs2Inventory.isFull() ? MLMStatus.DEPOSIT_HOPPER : MLMStatus.MINING);
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

            Rs2Walker.setTarget(null, "mlm_route_reset");

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
        Rs2Walker.setTarget(null, "mlm_route_reset");

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
            maxSackSize = SACK_LARGE_SIZE; // config understated it — the varbit is proof
        }
        if (measuredSackCapacity > 0) {
            if (live > measuredSackCapacity) {
                // Holding more than we thought fits: the sack was upgraded mid-run, so the old
                // measurement is stale. Drop it and go back to trusting the varbit/config.
                log.info("[MLM] Sack now holds {} > measured capacity {} — clearing the measurement",
                        live, measuredSackCapacity);
                measuredSackCapacity = 0;
            } else if (measuredSackCapacity < maxSackSize) {
                maxSackSize = measuredSackCapacity;
            }
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
        return Rs2Inventory.itemQuantity(ItemID.PAYDIRT);
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

    /**
     * After reclaiming dropped pay-dirt, send it to the hopper rather than carrying it back to the
     * veins. We are stood at the facility with the sack freshly emptied, so this is both the cheapest
     * and the most natural moment to deposit — walking back to mine with a part-load, then walking
     * here again, is neither.
     */
    private void routeReclaimedPayDirt() {
        if (payDirtCount() <= 0) {
            return;
        }
        if (isSackFull() || sackIsFullFlag) {
            debug("[MLM] Reclaimed pay-dirt but the sack is still full — leaving routing to the audit");
            return;
        }
        log.info("[MLM] Reclaimed {} pay-dirt — depositing at the hopper before mining", payDirtCount());
        setStatus(MLMStatus.DEPOSIT_HOPPER);
    }

    private int queryBrokenStrutCount() {
        return Microbot.getRs2TileObjectCache().query()
                .withId(ObjectID.MOTHERLODE_WHEEL_STRUT_BROKEN)
                .toList()
                .size();
    }

    /** Number of running (turning) waterwheels in scene. Water flows — and the hopper processes
     *  pay-dirt — as long as this is ≥1; it only freezes when both wheels are broken. */
    private int queryRunningWheelCount() {
        return Microbot.getRs2TileObjectCache().query()
                .withId(ObjectID.MOTHERLODE_WHEEL_FIXED)
                .toList()
                .size();
    }

    private boolean isWaterwheelClear() {
        return !repairEtiquette.shouldYield(config.waitForOthersToRepair(), localPlayerName);
    }

    /**
     * True once struts have been broken longer than {@link #REPAIR_DEFER_TIMEOUT_MS}. Used only as a
     * backstop for deposit thrash; the bystander decision now uses {@link MlmRepairEtiquette}'s own
     * clock, which starts when we begin yielding rather than when the strut broke.
     */
    private boolean repairDeferralExpired() {
        return brokenStrutsObservedSinceMs > 0L
                && System.currentTimeMillis() - brokenStrutsObservedSinceMs > REPAIR_DEFER_TIMEOUT_MS;
    }

    /**
     * True when we should repair struts before attempting to deposit.
     * Broken waterwheels block the hopper; if no other player is fixing them, we must.
     */
    private boolean shouldRepairStrutsBeforeDeposit() {
        return config.repairStruts()
                && waterwheelNeedsRepair(tickRunningWheelCount, tickBrokenStrutCount)
                && isWaterwheelClear();
    }

    /**
     * Pure repair decision: the hopper only stalls when the water freezes, which the OSRS mechanic
     * ties to <b>both</b> wheels being broken. So a repair is needed only when no wheel is running
     * (and there is a broken strut to actually click). One running wheel keeps pay-dirt flowing —
     * repairing then is wasted effort and jumps in on another player already fixing the broken wheel.
     */
    static boolean waterwheelNeedsRepair(int runningWheels, int brokenStruts) {
        return runningWheels == 0 && brokenStruts > 0;
    }

    /**
     * True when the wheel is frozen, we won't repair it ourselves ('Repair Struts' off), and the user
     * opted to wait for other players (optionally hopping). This is the signal to park in
     * {@link MLMStatus#WAITING_FOR_REPAIR} instead of thrashing failed deposits into RECOVERY.
     */
    private boolean shouldWaitForOthersToRepair() {
        return config.waitForOthersToRepair()
                && !config.repairStruts()
                && waterwheelNeedsRepair(tickRunningWheelCount, tickBrokenStrutCount);
    }

    /**
     * Idle while the wheel is frozen and we're relying on other players to fix it (repairs off). Polls
     * each tick: the moment a wheel is running again (someone repaired it — repairs are world-wide) we
     * resume (depositing first if we're still holding pay-dirt). If 'Hop World When Frozen' is on and the
     * freeze outlasts the grace period, hop to the most-populated world (progress is saved across hops)
     * and re-evaluate on arrival.
     */
    private void handleWaitingForRepair() {
        if (tickRunningWheelCount > 0 || tickBrokenStrutCount == 0) {
            log.info("[MLM] Wheel running again — resuming from wait");
            setStatus(payDirtCount() > 0 ? MLMStatus.DEPOSIT_HOPPER : MLMStatus.MINING);
            return;
        }

        // If we are only waiting because someone was stood at the wheel, take the job back as soon as
        // the deferral times out — otherwise a bystander who never repairs parks us here forever.
        if (config.repairStruts() && isWaterwheelClear()) {
            log.info("[MLM] Bystander isn't repairing — taking the waterwheel job back");
            setStatus(MLMStatus.FIXING_WATERWHEEL);
            return;
        }

        long waitedMs = System.currentTimeMillis() - statusEnteredMs;

        if (config.hopWhenFrozen()
                && waitedMs >= Math.max(0L, config.frozenHopGraceSeconds()) * 1000L) {
            int target = Rs2WorldUtil.getMostPopulatedAccessibleWorld();
            if (target > 0 && target != Rs2Player.getWorld()) {
                log.info("[MLM] Frozen {}s with no repair — hopping to busy world {}", waitedMs / 1000, target);
                Microbot.hopToWorld(target);
                sleepUntil(() -> Rs2Player.getWorld() == target, 8000);
                // Re-evaluate on the new world: reset the grace clock; next tick's wheel counts decide.
                setStatus(MLMStatus.WAITING_FOR_REPAIR);
            } else {
                log.warn("[MLM] Hop requested but no better world found — continuing to wait");
                sleep(2000);
            }
            return;
        }

        debug("[MLM] Waiting for another player to repair the wheel ({}s elapsed)", waitedMs / 1000);
        sleep(Rs2Random.between(2000, 4000));
    }

    /** Whether the off-hand Imcando hammer is wanted this run (repair + Imcando both on). */
    private boolean wantImcando() { return config.repairStruts() && config.useImcandoHammer(); }

    /** Gem bag enabled means the bag is always kept locked in slot 1, whatever the deposit method. */
    private boolean wantSlotLock() {
        return config.useGemBag();
    }

    /** Withdraw a gem bag from the already-open bank; safe-degrades to no-bag handling if none exists. */
    private void withdrawGemBag() {
        for (int id : Rs2Gembag.getGemBagItemIds()) {
            if (Rs2Bank.hasBankItem(id, 1)) {
                Rs2Bank.withdrawItem(id);
                sleepUntil(Rs2Gembag::hasGemBag, 3000);
                if (Rs2Gembag.hasGemBag()) { log.info("[MLM] Withdrew gem bag from the bank chest"); return; }
            }
        }
        log.warn("[MLM] Use Gem Bag is ON but no gem bag in inventory or bank — running without gem-bag handling this session");
    }

    /** Ensure the (already-present) gem bag is open so mined gems auto-store. Returns true when open. */
    private boolean ensureGemBagOpen() {
        if (Rs2Gembag.isGemBagOpen()) return true;
        Rs2Inventory.interact(net.runelite.api.gameval.ItemID.GEM_BAG, "Open");
        sleepUntil(Rs2Gembag::isGemBagOpen, 3000);
        return Rs2Gembag.isGemBagOpen();
    }

    /** Protected inventory slot index for the gem bag (slot "1" in the UI = index 0). */
    private static final int GEM_BAG_SLOT = 0;

    private Rs2ItemModel firstGemBagItem() {
        for (int id : Rs2Gembag.getGemBagItemIds()) {
            Rs2ItemModel m = Rs2Inventory.get(id);
            if (m != null) return m;
        }
        return null;
    }

    /**
     * True when the gem bag is in slot 0 AND slot 0 is locked, verified via the BANK_LOCKED_SLOTS varp
     * (works at the deposit box where the bank widget is absent).
     */
    private boolean isGemBagInSlotLocked() {
        Rs2ItemModel bag = firstGemBagItem();
        if (bag == null || bag.getSlot() != GEM_BAG_SLOT) return false;
        // The lock BIT is inert unless inventory-slot-locking is enabled, and a stale bit on its own is
        // enough for Deposit-All to bank the bag. Require both.
        if (!Rs2Settings.isBankSlotLockingEnabled()) return false;
        int mask = Microbot.getVarbitPlayerValue(VarPlayerID.BANK_LOCKED_SLOTS);
        return (mask & (1 << GEM_BAG_SLOT)) != 0;
    }

    private boolean imcandoResolvedToCrate = false;

    /** Open the MLM bank chest for the one preflight bank session. Returns true once open. */
    private boolean reachBankChest() {
        return Rs2Bank.isOpen() || Rs2Bank.walkToBankAndUseBank(BankLocation.MOTHERLOAD);
    }

    /** Withdraw an Imcando hammer from the already-open bank (off-hand preferred); wield happens later, post-close. */
    private void withdrawImcando() {
        final int OFF = ItemID.IMCANDO_HAMMER_OFFHAND, MAIN = ItemID.IMCANDO_HAMMER;
        if (Rs2Equipment.isWearing(OFF) || Rs2Equipment.isWearing(MAIN)
                || Rs2Inventory.hasItem(OFF) || Rs2Inventory.hasItem(MAIN)) return;
        int want = Rs2Bank.hasBankItem(OFF, 1) ? OFF : (Rs2Bank.hasBankItem(MAIN, 1) ? MAIN : -1);
        if (want == -1) {
            log.warn("[MLM] Use Imcando Hammer is ON but no Imcando hammer in inventory, equipment, or bank — will fall back to a regular hammer from the supply crate");
            return; // ensureImcandoWielded resolves the crate fallback
        }
        Rs2Bank.withdrawOne(want);
        int w = want;
        sleepUntil(() -> Rs2Inventory.hasItem(w), 2500);
    }

    /**
     * Wield the off-hand Imcando (deposit-safe shield slot). Bank must be CLOSED — you can't "Wield" a
     * bank-interface item, which is why this is a separate post-bank phase. Crate fallback if none.
     */
    private boolean ensureImcandoWielded() {
        if (!wantImcando()) { imcandoResolvedToCrate = false; return true; }
        final int OFF = ItemID.IMCANDO_HAMMER_OFFHAND, MAIN = ItemID.IMCANDO_HAMMER;
        boolean offEq = Rs2Equipment.isWearing(OFF), mainEq = Rs2Equipment.isWearing(MAIN);
        boolean offInv = Rs2Inventory.hasItem(OFF), mainInv = Rs2Inventory.hasItem(MAIN);
        MlmPreflightLogic.HammerStep step = MlmPreflightLogic.nextHammerStep(offEq, mainEq, offInv, mainInv, false);
        log.info("[MLM] Imcando wield: offEquip={} mainEquip={} offInv={} mainInv={} -> {}", offEq, mainEq, offInv, mainInv, step);
        switch (step) {
            case DONE:
                imcandoResolvedToCrate = false;
                return true;
            case WIELD_OFFHAND:
                Rs2Inventory.interact(OFF, "Wield");
                return sleepUntil(() -> Rs2Equipment.isWearing(OFF), 2500);
            case SWAP_THEN_WIELD:
                // Main-hand Imcando in inv → "Swap" converts it to the off-hand variant, then wield next tick.
                Rs2Inventory.interact(MAIN, "Swap");
                sleepUntil(() -> Rs2Inventory.hasItem(OFF), 2500);
                return false;
            case WITHDRAW:      // bank already closed in this phase — nothing to withdraw from
            case FALLBACK_CRATE:
            default:
                log.warn("[MLM] No Imcando hammer available — falling back to a regular hammer from the supply crate");
                imcandoResolvedToCrate = true;
                return true;
        }
    }

    /**
     * One-shot startup preflight. Establishes every invariant before mining and NEVER runs on the
     * per-tick path afterwards (that on-path slot-lock walk was the Deposit-All freeze). Safe-degrades
     * where a safe path exists; stops only when unworkable. Returns true when fully settled.
     */
    private boolean runPreflight() {
        // ---- Phase B (bank closed): settle inventory-side invariants and finish. ----
        if (preflightBankVisitDone) {
            if (!ensureImcandoWielded()) return false;                              // wield off-hand from inv
            if (config.useGemBag() && Rs2Gembag.hasGemBag() && !ensureGemBagOpen()) return false; // open the bag
            gemBagEnsured = true;
            resolveEffectiveDeposit();
            logResolvedState();
            return true;
        }

        // ---- Phase A (one bank session): withdraws + slot lock, one open, one close. Needs are read
        // from LIVE game state, so anything already present is skipped — no pointless bank trip. ----
        boolean needBag    = config.useGemBag() && !Rs2Gembag.hasGemBag();
        boolean needHammer = wantImcando()
                && !Rs2Equipment.isWearing(ItemID.IMCANDO_HAMMER_OFFHAND)
                && !Rs2Equipment.isWearing(ItemID.IMCANDO_HAMMER)   // equipped either variant survives Deposit-All
                && !Rs2Inventory.hasItem(ItemID.IMCANDO_HAMMER_OFFHAND)
                && !Rs2Inventory.hasItem(ItemID.IMCANDO_HAMMER);
        boolean needLock   = wantSlotLock() && !isGemBagInSlotLocked();

        if (needBag || needHammer || needLock) {
            if (!reachBankChest()) return false;                    // walk/open across as many ticks as needed
            if (needBag)    withdrawGemBag();                       // withdraws share the one open bank...
            if (needHammer) withdrawImcando();
            if (wantSlotLock() && !isGemBagInSlotLocked()) lockGemBagSlot();  // ...and so does the slot lock
            if (Rs2Bank.isOpen()) Rs2Bank.closeBank();              // the ONE close
        }
        preflightBankVisitDone = true;
        return false; // next tick: Phase B, with the bank now closed (required to wield)
    }

    /** Resolve the effective (possibly degraded ALL→ITEMS) deposit method from the settled state. */
    private void resolveEffectiveDeposit() {
        boolean haveBag = config.useGemBag() && Rs2Gembag.hasGemBag();
        boolean lockEstablished = !wantSlotLock() || isGemBagInSlotLocked();
        effectiveDepositMethod = MlmPreflightLogic.resolveEffectiveDepositMethod(
                config.depositMethod(), haveBag, lockEstablished);
        if (config.depositMethod() == DepositMethod.ALL && effectiveDepositMethod == DepositMethod.ITEMS) {
            log.warn("[MLM] Preflight: Deposit-All unsafe for the gem bag (slot 1 not locked / not requested) — using Deposit-Items this run");
        }
    }

    /**
     * Move the gem bag to slot 0 and lock slot 0 (and enable inventory-slot-locking if off). Bank-phase
     * helper: assumes the bank is open and never opens/closes it — the caller owns the single session.
     * Locking needs the bank interface (the deposit box can't). Safe-degrades (leaves the lock off, which
     * resolveEffectiveDeposit reads as ITEMS) rather than failing when slot locking is unavailable.
     */
    private void lockGemBagSlot() {
        if (!Rs2Settings.isBankSlotLockingEnabled() && !Rs2Settings.enableBankSlotLocking()) {
            log.warn("[MLM] Bank slot locking unavailable — can't protect the gem bag; will use Deposit-Items");
            return;
        }
        Rs2ItemModel bag = firstGemBagItem();
        if (bag == null) return;
        if (bag.getSlot() != GEM_BAG_SLOT) {
            Rs2Inventory.moveItemToSlot(bag, GEM_BAG_SLOT);
            sleepUntil(() -> {
                Rs2ItemModel b = firstGemBagItem();
                return b != null && b.getSlot() == GEM_BAG_SLOT;
            }, 2000);
        }
        if (!Rs2Bank.isLockedSlot(GEM_BAG_SLOT)) {
            Rs2Bank.lockAllBySlot(GEM_BAG_SLOT);
            sleepUntil(() -> Rs2Bank.isLockedSlot(GEM_BAG_SLOT), 2000);
        }
        Rs2ItemModel after = firstGemBagItem();
        boolean varpLocked = isGemBagInSlotLocked();
        log.info("[MLM] Slot-lock attempt: bagSlot={} widgetLockedSlot0={} varpLocked={} -> {}",
                after != null ? after.getSlot() : -1, Rs2Bank.isLockedSlot(GEM_BAG_SLOT), varpLocked,
                varpLocked ? "LOCKED" : "NOT LOCKED (will degrade to Deposit-Items)");
    }

    /** One-shot INFO block: full config + resolved preflight state, so live reports are one glance. */
    private void logResolvedState() {
        int off = ItemID.IMCANDO_HAMMER_OFFHAND;
        int main = ItemID.IMCANDO_HAMMER;
        log.info("[MLM] ===== Preflight resolved (v{}) =====", IrkedMLMPlugin.version);
        log.info("[MLM] config: depositMethod={} useGemBag={} repairStruts={} useImcando={} debug={}",
                config.depositMethod(), config.useGemBag(),
                config.repairStruts(), config.useImcandoHammer(), config.debugMode());
        log.info("[MLM] state: imcando(offEquipped={} mainEquipped={} crateFallback={}) gemBag(present={} slot0Locked={}) effectiveDeposit={} freeSlots={}",
                Rs2Equipment.isWearing(off), Rs2Equipment.isWearing(main), imcandoResolvedToCrate,
                Rs2Gembag.hasGemBag(), isGemBagInSlotLocked(), effectiveDepositMethod,
                Rs2Inventory.emptySlotCount());
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

            int currentXp = sessionStats.displayXp();

            SessionSnapshot snap = new SessionSnapshot(
                    status,
                    miningSpot,
                    // Projection-aware value so the overlay's sack row updates straight after a deposit
                    // instead of waiting for the lagged varbit. Read-only variant: updateSnapshot() also
                    // runs on the client thread (varbit/chat events) and must not clear projection state
                    // from under an in-progress executor tick.
                    getEffectiveSackCountForDisplay(),
                    maxSackSize,
                    startTimeMs,
                    sessionStats.getTotalValueGained(),
                    sessionStats.getGainedNuggets(),
                    sessionStats.getStartXp(),
                    currentXp,
                    sessionStats.getRunite(),
                    sessionStats.getAdamantite(),
                    sessionStats.getMithril(),
                    sessionStats.getGold(),
                    sessionStats.getCoal(),
                    getCurrentSubStateLabel(),
                    statusEnteredMs,
                    globalLastXpTime.get()
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
            case WAITING_FOR_REPAIR: return config.hopWhenFrozen() ? "Waiting/Hopping (frozen)" : "Waiting for repair";
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
        // Leaving the deposit loop (to mining/empty/repair/wait/recovery) clears the trip-retry count.
        // Staying in DEPOSIT_HOPPER can't reach here (the same-status guard above returns early), so the
        // count accumulates across retries exactly as the RECOVERY backstop expects.
        if (newStatus != MLMStatus.DEPOSIT_HOPPER) {
            hopperDepositRetries = 0;
        }
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

    /** Persist lifetime totals as baseline + session-delta (idempotent — repeated saves don't double-count). */
    private void saveStats() {
        if (Microbot.getConfigManager() == null) return;
        MlmStats out = new MlmStats();
        out.oresMined        = stats.oresMined + sessionStats.totalOres();
        out.xpGained         = stats.xpGained + sessionStats.xpGained();
        out.runtimeMs        = stats.runtimeMs + Math.max(0L, System.currentTimeMillis() - startTimeMs);
        out.sacksEmptied     = stats.sacksEmptied + sessionSacksEmptied;
        out.nuggets          = stats.nuggets + sessionStats.getGainedNuggets(); // session gain, same baseline+delta as the rest

        out.lastSavedEpochMs = System.currentTimeMillis();
        out.loadoutUseImcando = config.useImcandoHammer();
        try {
            Microbot.getConfigManager().setConfiguration(IrkedMLMConfig.configGroup, STATS_KEY, out);
        } catch (Exception e) {
            log.warn("[MLM] Failed to persist stats", e);
        }
    }

    @Override
    public void shutdown() {
        log.info("Starting MLM script shutdown");
        running = false;
        saveStats();
        super.shutdown();
        Rs2Antiban.resetAntibanSettings();
        Rs2Walker.setTarget(null, "mlm_route_reset");
        resetAllSessions();
        log.info("MLM script shutdown complete");
    }
}
