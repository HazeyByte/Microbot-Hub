package net.runelite.client.plugins.microbot.brutusmaximus;

import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Hitsplat;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileitem.models.Rs2TileItemModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import javax.inject.Singleton;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Singleton
public class BrutusMaximusScript extends Script {
    private static final String BRUTUS_NPC_NAME = "Brutus";
    private static final String ENTRY_GATE_ACTION = "Release";
    private static final String EXIT_GATE_ACTION = "Leave";
    private static final String[] ENTRY_GATE_ACTIONS = {"Release", "Open", "Enter", "Pass"};
    private static final String[] EXIT_GATE_ACTIONS = {"Leave", "Exit", "Open", "Pass"};
    private static final String COWBELL_TELEPORT_ACTION = "Teleport";
    private static final String COWBELL_RING_ACTION = "Ring";

    private static final int COWBELL_ITEM_ID = 33104;
    private static final int SNORT_HAZARD_OBJECT_ID = 3588;
    private static final WorldPoint BRUTUS_SPAWN_TILE = new WorldPoint(3262, 3288, 0);
    private static final WorldPoint POST_KILL_RETURN_TILE = new WorldPoint(3262, 3290, 0);

    // User requested ids.
    private static final int BRUTUS_SNORT_ANIMATION = 13785;
    private static final int BRUTUS_GROWL_ANIMATION = 13788;
    // Keep compatibility for clients still emitting old growl id.
    private static final int BRUTUS_GROWL_ANIMATION_LEGACY = 13778;

    private static final long LOOP_DELAY_MS = 15;
    private static final long ACTION_CD_MS = 80;
    private static final long ATTACK_CD_MS = 120;
    private static final long EAT_CD_MS = 450;
    private static final long EAT_EMERGENCY_CD_MS = 120;
    private static final long SPEC_CD_MS = 4500;
    private static final long SPEC_BLOCK_AFTER_DODGE_MS = 1800;
    private static final int SPEC_MIN_HP_PERCENT = 65;
    
    // Critical fixes for movement and dodge spam
    private static final long DODGE_COOLDOWN_MS = 800;
    private static final long MOVE_COOLDOWN_MS = 600;
    private static final long ANIM_DEBOUNCE_MS = 100;

    private static final long TELEPORT_CD_MS = 700;
    private static final long GATE_CD_MS = 90;
    private static final long STATE_RECOVERY_TIMEOUT_MS = 25000;
    private static final int STARTUP_BANK_NEAR_DISTANCE = 12;
    private static final int GATE_QUERY_RADIUS = 60;
    private static final int ENTRY_STAGING_RADIUS = 14;

    private static final int INTERACT_DISTANCE = 2;
    private static final int GROWL_SIDE_STEP = 2;
    private static final int SNORT_TOTAL_STEPS = 3;
    private static final long SNORT_STEP_DELAY_MS = 80;
    private static final long SNORT_RETRY_DELAY_MS = 5;
    private static final long SNORT_TIMEOUT_MS = 3200;
    private static final int DODGE_MOVE_START_TIMEOUT_MS = 90;
    private static final long EXIT_COWBELL_CONFIRM_TIMEOUT_MS = 1800;
    private static final int EXIT_COWBELL_MAX_ATTEMPTS = 2;
    private static final long EXIT_DEBUG_THROTTLE_MS = 800;
    private static final long POST_DODGE_REATTACK_MS = 220;
    private static final long SPEC_BLOCK_DURING_MECHANIC_MS = 1800;
    private static final long MISSED_DODGE_LOG_WINDOW_MS = 2200;

    private static final WorldPoint[] ENTRY_STAGING_TILES = {
        new WorldPoint(3262, 3294, 0),
        new WorldPoint(3263, 3294, 0)
    };
    private static final WorldPoint[] EXIT_STAGING_TILES = {
        new WorldPoint(3253, 3267, 0),
        new WorldPoint(3253, 3266, 0)
    };
    private static final int[] ENTRY_GATE_IDS = {60760, 60763};
    private static final int[] EXIT_GATE_IDS = {60764, 60765};

    @Inject
    private BrutusMaximusConfig config;

    private final Client client;

    private volatile BrutusMaximusState state = BrutusMaximusState.IDLE;
    private volatile boolean stopping = false;

    private long startedAt = 0;
    private long stateEnteredAt = 0;
    private long lastActionAt = 0;
    private long lastSpecAt = 0;
    private long lastTeleportAt = 0;
    private long lastRingAt = 0;
    private long lastLootAt = 0;
    
    // Critical fixes for movement and dodge spam
    private long lastMoveAttemptAt = 0;
    private long lastDodgeAttemptAt = 0;
    private long lastDodgeAt = 0;

    private volatile int lockedBrutusIndex = -1;
    private volatile int lastPolledAnim = -1;
    private volatile int lastObservedAnim = -1;
    private volatile int lastProcessedAnim = -1;
    private volatile long lastAnimChangeTime = 0;
    private volatile boolean growlQueued = false;
    private final AtomicInteger snortRemaining = new AtomicInteger(0);
    private final AtomicInteger snortStep = new AtomicInteger(0);
    private volatile long snortStartedAt = 0;
    private volatile long nextSnortAt = 0;
    private volatile long reattackAfter = 0;
    private final AtomicLong mechanicLockUntil = new AtomicLong(0);
    private final AtomicBoolean growlPreferLeft = new AtomicBoolean(true);
    private volatile boolean pendingPostKillReturn = false;
    private volatile long lastGrowlQueuedAt = 0;
    private volatile long lastSnortQueuedAt = 0;
    private volatile boolean exitCowbellPending = false;
    private final AtomicInteger exitCowbellAttempts = new AtomicInteger(0);
    private volatile long exitCowbellAttemptedAt = 0;
    private volatile long lastExitDebugAt = 0;

    private long totalKills = 0;
    private int startCombatXp = -1;
    private boolean xpStartCaptured = false;
    private boolean startupTopUpChecked = false;
    private boolean runCheckedOnStartup = false;

    @Inject
    public BrutusMaximusScript(Client client) {
        this.client = client;
    }

    public boolean run(BrutusMaximusConfig config) {
        this.config = config;
        reset();
        setState(BrutusMaximusState.BOOTSTRAP, "startup");
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (stopping || !Microbot.isLoggedIn() || !super.run()) return;
                onLoop();
            } catch (Exception ex) {
                if (!isExpectedShutdownException(ex)) {
                    Microbot.logStackTrace(getClass().getSimpleName(), ex);
                }
            }
        }, 0, LOOP_DELAY_MS, TimeUnit.MILLISECONDS);
        return true;
    }

    @Override
    public void shutdown() {
        stopping = true;
        super.shutdown();
        setState(BrutusMaximusState.IDLE, "shutdown");
    }

    public void onGameTick(GameTick ignored) {
        // Reserved for future tick-local logic.
    }

    public void onNpcSpawned(NpcSpawned event) {
        if (event == null) return;
        NPC npc = event.getNpc();
        if (isBrutus(npc)) {
            lockBrutus(npc.getIndex());
        }
    }

    public void onNpcDespawned(NpcDespawned event) {
        if (event == null) return;
        NPC npc = event.getNpc();
        if (isNotBrutus(npc)) return;
        if (state == BrutusMaximusState.COMBAT && isInside()) {
            totalKills++;
            if (config.enableDebugLogging()) log.info("Brutus kill recorded. Total kills: {}", totalKills);
            ringCowbellAfterKill();
        }
        if (npc.getIndex() == lockedBrutusIndex) {
            lockedBrutusIndex = -1;
        }
        clearMechanic("Brutus despawn");
    }

    public void onAnimationChanged(AnimationChanged event) {
        if (event == null || !(event.getActor() instanceof NPC)) return;
        NPC npc = (NPC) event.getActor();
        if (isNotBrutus(npc)) return;
        int animation = npc.getAnimation();
        int previousObserved = lastObservedAnim;
        lastObservedAnim = animation;
        lockBrutus(npc.getIndex());
        
        // CRITICAL: Debounce animation changes
        long now = System.currentTimeMillis();
        if (now - lastAnimChangeTime < ANIM_DEBOUNCE_MS) {
            return; // Ignore rapid changes
        }
        
        if (animation != lastProcessedAnim && animation != -1) {
            lastProcessedAnim = animation;
            lastAnimChangeTime = now;
            
            if (config.enableDebugLogging() && animation != previousObserved) {
                log.info("Brutus animation observed: {}", animation);
            }
            queueFromAnimation(animation);
        }
    }

    public void onHitsplatApplied(HitsplatApplied event) {
        if (event == null || config == null || !config.enableDebugLogging()) return;
        if (state != BrutusMaximusState.COMBAT || !isInside()) return;
        if (client == null || client.getLocalPlayer() == null) return;
        if (event.getActor() != client.getLocalPlayer()) return;
        Hitsplat hitsplat = event.getHitsplat();
        if (hitsplat == null) return;

        int amount = hitsplat.getAmount();
        if (amount <= 0) return;

        long now = System.currentTimeMillis();
        boolean inGrowlWindow = growlQueued
            || (lastGrowlQueuedAt > 0 && now - lastGrowlQueuedAt <= MISSED_DODGE_LOG_WINDOW_MS);
        boolean inSnortWindow = snortRemaining.get() > 0
            || (lastSnortQueuedAt > 0 && now - lastSnortQueuedAt <= MISSED_DODGE_LOG_WINDOW_MS);
        if (!inGrowlWindow && !inSnortWindow) return;

        String mechanic = inGrowlWindow && inSnortWindow ? "GROWL/SNORT" : (inGrowlWindow ? "GROWL" : "SNORT");
        long sinceQueue = inGrowlWindow
            ? (lastGrowlQueuedAt > 0 ? now - lastGrowlQueuedAt : -1)
            : (lastSnortQueuedAt > 0 ? now - lastSnortQueuedAt : -1);
        long sinceLastDodge = lastDodgeAt > 0 ? now - lastDodgeAt : -1;
        WorldPoint player = Rs2Player.getWorldLocation();

        log.warn(
            "Possible missed dodge: took {} damage during {} window (sinceQueue={}ms, sinceLastDodge={}ms, growlQueued={}, snortRemaining={}, player={}, lastAnim={})",
            amount,
            mechanic,
            sinceQueue,
            sinceLastDodge,
            growlQueued,
            snortRemaining.get(),
            player,
            lastObservedAnim
        );
    }

    private void onLoop() {
        maybeEnableRunOnStartup();
        if (!xpStartCaptured) {
            startCombatXp = combatXpSnapshot();
            xpStartCaptured = true;
        }
        if (isLongStateStall()) {
            if (config != null && config.enableDebugLogging()) {
                log.info("State timeout recovery: {} -> BOOTSTRAP", state);
            }
            setState(BrutusMaximusState.BOOTSTRAP, "state timeout recovery");
            return;
        }
        switch (state) {
            case BOOTSTRAP:
                bootstrap();
                return;
            case RESUPPLY:
                resupply();
                return;
            case TRAVEL_ENTER:
                travelEnter();
                return;
            case COMBAT:
                combat();
                return;
            case EXIT_FIGHT:
                exitFight();
                return;
            case STOP:
                shutdown();
                return;
            case IDLE:
            default:
                setState(BrutusMaximusState.BOOTSTRAP, "idle bootstrap");
        }
    }

    private void bootstrap() {
        if (isInside()) {
            setState(lowFood() ? BrutusMaximusState.EXIT_FIGHT : BrutusMaximusState.COMBAT, "inside bootstrap");
            return;
        }
        boolean needsResupply = lowFood();
        if (!startupTopUpChecked) {
            startupTopUpChecked = true;
            needsResupply = needsResupply || shouldTopUpAtStartup();
        }
        setState(needsResupply ? BrutusMaximusState.RESUPPLY : BrutusMaximusState.TRAVEL_ENTER, "outside bootstrap");
    }

    private void resupply() {
        if (isInside()) {
            setState(BrutusMaximusState.EXIT_FIGHT, "inside while resupply");
            return;
        }

        int foodId = config.foodSelection().getId();
        int desiredFood = effectiveDesiredFoodCount();
        boolean readyForTrip = Rs2Inventory.count(foodId) >= desiredFood
            && (!config.useCowbellTeleport() || Rs2Inventory.hasItem(COWBELL_ITEM_ID));

        if (readyForTrip && Rs2Player.getHealthPercentage() < 95) {
            if (Rs2Bank.isOpen()) {
                Rs2Bank.closeBank();
                markAction();
                return;
            }
            if (eatIfNeeded(95, false, true)) return;
        }

        if (!Rs2Bank.isOpen()) {
            if (isActionOnCooldown(ACTION_CD_MS)) return;
            boolean opened = Rs2Bank.openBank();
            if (!opened) {
                Rs2Bank.walkToBankAndUseBank();
            }
            markAction();
            return;
        }

        if (!Rs2Bank.hasBankItem(foodId, 1)) {
            stop("Missing configured food in bank");
            return;
        }
        if (config.useCowbellTeleport() && !Rs2Inventory.hasItem(COWBELL_ITEM_ID) && !Rs2Bank.hasBankItem(COWBELL_ITEM_ID, 1)) {
            stop("Missing Cowbell amulet in bank");
            return;
        }

        if (isActionOnCooldown(ACTION_CD_MS)) return;

        if (config.useCowbellTeleport()) {
            Rs2Bank.depositAllExcept(COWBELL_ITEM_ID, foodId);
        } else {
            Rs2Bank.depositAllExcept(foodId);
        }

        int missing = Math.max(0, desiredFood - Rs2Inventory.count(foodId));
        if (missing > 0) {
            if (!(Rs2Bank.withdrawX(foodId, missing) || Rs2Bank.withdrawDeficit(foodId, desiredFood))) {
                stop("Failed to withdraw food");
                return;
            }
        }

        if (config.useCowbellTeleport() && !Rs2Inventory.hasItem(COWBELL_ITEM_ID)) {
            if (!(Rs2Bank.withdrawX(COWBELL_ITEM_ID, 1) || Rs2Bank.withdrawDeficit(COWBELL_ITEM_ID, 1))) {
                stop("Failed to withdraw cowbell");
                return;
            }
        }

        if (Rs2Inventory.count(foodId) >= desiredFood && (!config.useCowbellTeleport() || Rs2Inventory.hasItem(COWBELL_ITEM_ID))) {
            if (Rs2Player.getHealthPercentage() < 95) {
                Rs2Bank.closeBank();
                markAction();
                return;
            }
            Rs2Bank.closeBank();
            setState(BrutusMaximusState.TRAVEL_ENTER, "resupply complete");
        }
        markAction();
    }

    private void travelEnter() {
        if (isInside()) {
            setState(BrutusMaximusState.COMBAT, "entered instance");
            return;
        }
        if (lowFood()) {
            setState(BrutusMaximusState.RESUPPLY, "low food while traveling");
            return;
        }

        if (clickDialogueYesOrContinue()) {
            markAction();
            return;
        }

        if (clickGateUsingApi(false)) {
            // Wait for instance entry to complete
            sleepUntil(this::isInside, 3000);
            return;
        }

        Rs2TileObjectModel gate = findEntryGate();
        if (gate != null) {
            WorldPoint gateTile = gate.getWorldLocation();
            // Click first so the client can auto-path + interact in one action.
            if (clickGate(gate, ENTRY_GATE_ACTION)) {
                // Wait for instance entry to complete
                sleepUntil(this::isInside, 3000);
                return;
            }
            if (moveIfFar(gateTile)) return;
            return;
        }

        if (config.useCowbellTeleport() && !isNearEntryStagingTile()) {
            if (!Rs2Inventory.hasItem(COWBELL_ITEM_ID)) {
                setState(BrutusMaximusState.RESUPPLY, "missing cowbell during travel");
                return;
            }
            if (System.currentTimeMillis() - lastTeleportAt >= TELEPORT_CD_MS) {
                if (Rs2Inventory.interact(COWBELL_ITEM_ID, COWBELL_TELEPORT_ACTION)
                    || Rs2Inventory.interact(COWBELL_ITEM_ID, COWBELL_RING_ACTION)) {
                    lastTeleportAt = System.currentTimeMillis();
                    markAction();
                    return;
                }
            }
        }

        WorldPoint staging = nearestReferenceTile(ENTRY_STAGING_TILES);
        if (staging != null) {
            moveFastNonBlocking(staging);
            markAction();
        }
    }

    private void combat() {
        if (!isInside()) {
            clearMechanic("outside in combat state");
            setState(BrutusMaximusState.TRAVEL_ENTER, "outside while combat");
            return;
        }
        if (lowFood() && !pendingPostKillReturn) {
            setState(BrutusMaximusState.EXIT_FIGHT, "low food in combat");
            return;
        }

        Rs2NpcModel brutus = brutus();
        if (brutus == null) {
            if (handlePostKillReturn()) return;
            maybeEat();
            if (config.enableLooting()) loot();
            return;
        }

        if (config.enableMechanicDodging() && handleMechanics(brutus)) return;
        if (eatIfNeeded(Math.max(8, config.eatAtPercent() - 25), true, true)) return;
        if (eatIfNeeded(Math.max(1, config.eatAtPercent()), false, false)) return;
        if (useSpecIfReady(brutus)) return;

        if (reattackAfter > System.currentTimeMillis()) return;
        attack(brutus);
    }

    private void exitFight() {
        if (!isInside()) {
            clearMechanic("outside after exit");
            resetExitCowbellState();
            setState(BrutusMaximusState.RESUPPLY, "left arena");
            return;
        }

        // EXIT_FIGHT priority: do not run combat mechanics; leave immediately.
        if (clickDialogueYesOrContinue()) {
            debugExit("Handled dialogue while exiting.");
            markAction();
            return;
        }

        if (tryExitViaCowbell()) {
            return;
        }

        debugExit("Cowbell exit unavailable/failed, falling back to gate exit.");

        if (clickGateUsingApi(true)) {
            debugExit("Clicked exit gate using queryable API.");
            // Wait for instance exit to complete
            sleepUntil(() -> !isInside(), 3000);
            return;
        }

        Rs2TileObjectModel gate = findExitGate();
        if (gate != null) {
            WorldPoint gateTile = gate.getWorldLocation();
            // Click first so the client can auto-path + interact in one action.
            if (clickGate(gate, EXIT_GATE_ACTION)) {
                debugExit("Clicked nearest exit gate object fallback.");
                // Wait for instance exit to complete
                sleepUntil(() -> !isInside(), 3000);
                return;
            }
            if (moveIfFar(gateTile)) {
                debugExit("Moving toward exit gate tile {}", gateTile);
                return;
            }
        }

        if (clickAnyExitGate()) {
            debugExit("Clicked one of exit gates from sorted fallback.");
            // Wait for instance exit to complete
            sleepUntil(() -> !isInside(), 3000);
            return;
        }

        // Safety only after gate actions fail; keep threshold low to prioritize fast exit.
        if (eatIfNeeded(12, true, true)) return;

        WorldPoint near = nearestReferenceTile(EXIT_STAGING_TILES);
        if (near != null) {
            moveFastNonBlocking(near);
            debugExit("No gate click yet; stepping toward exit staging tile {}", near);
            markAction();
        }
    }

    private boolean tryExitViaCowbell() {
        if (config == null || !config.useCowbellTeleport()) {
            return false;
        }

        long now = System.currentTimeMillis();
        if (exitCowbellPending) {
            if (!isInside()) {
                debugExit("Cowbell teleport confirmed, now outside.");
                resetExitCowbellState();
                clearMechanic("outside after cowbell exit");
                setState(BrutusMaximusState.RESUPPLY, "cowbell exit success");
                return true;
            }

            if (now - exitCowbellAttemptedAt <= EXIT_COWBELL_CONFIRM_TIMEOUT_MS) {
                debugExit("Waiting for cowbell exit confirmation...");
                return true;
            }

            exitCowbellPending = false;
            int attempts = exitCowbellAttempts.get();
            debugExit("Cowbell exit confirmation timed out (attempt {}/{}).", attempts, EXIT_COWBELL_MAX_ATTEMPTS);
            return attempts < EXIT_COWBELL_MAX_ATTEMPTS;
        }

        if (!Rs2Inventory.hasItem(COWBELL_ITEM_ID)) {
            debugExit("Cannot use cowbell exit: amulet missing.");
            return false;
        }

        if (exitCowbellAttempts.get() >= EXIT_COWBELL_MAX_ATTEMPTS) {
            debugExit("Cowbell exit exhausted after {} attempts; using gate fallback.", EXIT_COWBELL_MAX_ATTEMPTS);
            return false;
        }

        long sinceLastTeleport = now - lastTeleportAt;
        if (sinceLastTeleport < TELEPORT_CD_MS) {
            debugExit("Cowbell exit on cooldown ({}ms remaining).", TELEPORT_CD_MS - sinceLastTeleport);
            return true;
        }

        boolean clicked = Rs2Inventory.interact(COWBELL_ITEM_ID, COWBELL_TELEPORT_ACTION)
            || Rs2Inventory.interact(COWBELL_ITEM_ID, COWBELL_RING_ACTION);

        if (!clicked) {
            int attempts = exitCowbellAttempts.incrementAndGet();
            debugExit("Cowbell exit interaction failed (attempt {}/{}).", attempts, EXIT_COWBELL_MAX_ATTEMPTS);
            return attempts < EXIT_COWBELL_MAX_ATTEMPTS;
        }

        int attempts = exitCowbellAttempts.incrementAndGet();
        exitCowbellPending = true;
        exitCowbellAttemptedAt = now;
        lastTeleportAt = now;
        debugExit("Cowbell exit attempt {}/{} queued.", attempts, EXIT_COWBELL_MAX_ATTEMPTS);
        markAction();
        return true;
    }

    private boolean handleMechanics(Rs2NpcModel brutus) {
        int current = brutus.getAnimation();
        if (current == -1) {
            current = lastObservedAnim;
        }
        queueFromAnimation(current);

        if (growlQueued) {
            if (doGrowlDodge(brutus)) {
                growlQueued = false;
                reattackAfter = System.currentTimeMillis() + POST_DODGE_REATTACK_MS;
                return true;
            }
            return true;
        }

        if (snortRemaining.get() > 0) {
            long now = System.currentTimeMillis();
            if (now - snortStartedAt > SNORT_TIMEOUT_MS) {
                clearMechanic("snort sequence timeout");
                return true;
            }
            if (now >= nextSnortAt) {
                if (doSnortDodge(brutus)) {
                    snortRemaining.decrementAndGet();
                    snortStep.incrementAndGet();
                    nextSnortAt = now + SNORT_STEP_DELAY_MS;
                    
                    // Only schedule reattack after complete 3-snort sequence
                    if (snortRemaining.get() <= 0) {
                        reattackAfter = now + POST_DODGE_REATTACK_MS;
                        clearMechanic("snort sequence complete");
                        if (config.enableDebugLogging()) {
                            log.info("SNORT sequence complete - scheduling attack after delay");
                        }
                    }
                } else {
                    nextSnortAt = now + SNORT_RETRY_DELAY_MS;
                }
            }
            return true;
        }

        return false;
    }

    private void queueFromAnimation(int animation) {
        if (animation == lastPolledAnim) return;
        lastPolledAnim = animation;

        boolean snort = animation == BRUTUS_SNORT_ANIMATION;
        if (snort) {
            if (snortRemaining.get() > 0) return;
            snortRemaining.set(SNORT_TOTAL_STEPS);
            snortStep.set(0);
            snortStartedAt = System.currentTimeMillis();
            lastSnortQueuedAt = snortStartedAt;
            nextSnortAt = snortStartedAt;
            lockMechanicsUntil(snortStartedAt + SPEC_BLOCK_DURING_MECHANIC_MS);
            growlQueued = false;
            if (config.enableDebugLogging()) log.info("SNORT sequence started: {} dodges", SNORT_TOTAL_STEPS);
            return;
        }

        boolean growl = isGrowlAnimation(animation);
        if (growl) {
            if (growlQueued || snortRemaining.get() > 0) return;
            growlQueued = true;
            lastGrowlQueuedAt = System.currentTimeMillis();
            lockMechanicsUntil(System.currentTimeMillis() + SPEC_BLOCK_DURING_MECHANIC_MS);
            snortRemaining.set(0);
            snortStep.set(0);
            snortStartedAt = 0;
            nextSnortAt = 0;
            if (config.enableDebugLogging()) log.info("GROWL detected: queue immediate dodge");
        }
    }

    private boolean doGrowlDodge(Rs2NpcModel brutus) {
        WorldPoint player = Rs2Player.getWorldLocation();
        if (player == null) return false;
        WorldPoint boss = brutus != null ? brutus.getWorldLocation() : null;
        if (boss == null) return false;

        // Growl handling for this encounter is strict left/right lane movement only.
        // This prevents backwards (into-boss) movement caused by dynamic axis flips.
        List<WorldPoint> candidates = new ArrayList<>(2);
        WorldPoint left = player.dx(-GROWL_SIDE_STEP);
        WorldPoint right = player.dx(GROWL_SIDE_STEP);

        if (growlPreferLeft.get()) {
            candidates.add(left);
            candidates.add(right);
        } else {
            candidates.add(right);
            candidates.add(left);
        }

        for (WorldPoint d : candidates) {
            if (d == null) continue;
            if (d.getY() != player.getY()) continue;
            if (d.equals(BRUTUS_SPAWN_TILE)) continue;
            if (Math.abs(d.getX() - BRUTUS_SPAWN_TILE.getX()) <= 1
                && Math.abs(d.getY() - BRUTUS_SPAWN_TILE.getY()) <= 1) continue;
            if (d.equals(boss) || d.distanceTo(boss) < 2) continue;
            if (validDodge(d, player) && dodgeMove(d)) {
                growlPreferLeft.set(!growlPreferLeft.get());
                lastDodgeAt = System.currentTimeMillis();
                lockMechanicsUntil(System.currentTimeMillis() + SPEC_BLOCK_DURING_MECHANIC_MS);
                if (config.enableDebugLogging()) log.info("Dodging GROWL to {}", d);
                markAction();
                return true;
            }
        }
        return false;
    }

    private boolean doSnortDodge(Rs2NpcModel brutus) {
        WorldPoint player = Rs2Player.getWorldLocation();
        if (player == null) return false;
        WorldPoint boss = brutus != null && brutus.getWorldLocation() != null ? brutus.getWorldLocation() : BRUTUS_SPAWN_TILE;
        DodgeAxis axis = dodgeAxisFromPlayer(boss, player);
        List<WorldPoint> hazards = snortHazardTiles(player, boss);

        List<WorldPoint> candidates = new ArrayList<>(12);
        for (WorldPoint tile : spawnSnortTilesBy2()) {
            if (tile == null) continue;
            if (tile.equals(BRUTUS_SPAWN_TILE)) continue;
            if (!validDodge(tile, player)) continue;
            if (isHazardTile(tile, hazards)) continue;
            candidates.add(tile);
        }

        if (candidates.isEmpty()) {
            List<WorldPoint> ring = ringTiles(boss, axis);
            for (WorldPoint tile : ring) {
                if (tile == null) continue;
                if (tile.equals(BRUTUS_SPAWN_TILE)) continue;
                if (!validDodge(tile, player)) continue;
                if (isHazardTile(tile, hazards)) continue;
                candidates.add(tile);
            }
        }

        if (candidates.isEmpty()) {
            for (WorldPoint fallback : playerSnortFallbackTiles(player, boss)) {
                if (fallback == null || fallback.equals(BRUTUS_SPAWN_TILE)) continue;
                if (!validDodge(fallback, player)) continue;
                if (isHazardTile(fallback, hazards)) continue;
                candidates.add(fallback);
            }
        }

        candidates.sort((a, b) -> {
            int aDanger = nearestHazardDistance(a, hazards);
            int bDanger = nearestHazardDistance(b, hazards);
            if (aDanger != bDanger) {
                return Integer.compare(bDanger, aDanger);
            }
            int aBoss = a.distanceTo(boss);
            int bBoss = b.distanceTo(boss);
            return Integer.compare(bBoss, aBoss);
        });

        if (candidates.isEmpty()) {
            WorldPoint left = growlSideStep(player, axis, true);
            WorldPoint right = growlSideStep(player, axis, false);
            if (validDodge(left, player) && !isHazardTile(left, hazards)) {
                candidates.add(left);
            }
            if (validDodge(right, player) && !isHazardTile(right, hazards)) {
                candidates.add(right);
            }
        }

        for (WorldPoint d : candidates) {
            if (validDodge(d, player) && dodgeMove(d)) {
                lastDodgeAt = System.currentTimeMillis();
                lockMechanicsUntil(System.currentTimeMillis() + SPEC_BLOCK_DURING_MECHANIC_MS);
                if (config.enableDebugLogging()) {
                    log.info("Dodging SNORT step {}/{} to {}", snortStep.get() + 1, SNORT_TOTAL_STEPS, d);
                }
                markAction();
                return true;
            }
        }

        if (config.enableDebugLogging()) {
            log.info("SNORT dodge unavailable: no valid safe tile (hazards={})", hazards.size());
        }
        return false;
    }

    private boolean handlePostKillReturn() {
        if (!pendingPostKillReturn) return false;
        WorldPoint player = Rs2Player.getWorldLocation();
        if (player == null) return false;
        if (player.getPlane() != POST_KILL_RETURN_TILE.getPlane()) {
            pendingPostKillReturn = false;
            return false;
        }
        if (player.equals(POST_KILL_RETURN_TILE)) {
            pendingPostKillReturn = false;
            return false;
        }
        if (isActionOnCooldown(ACTION_CD_MS)) return true;
        moveToPostKillReturnTile();
        markAction();
        return true;
    }

    private List<WorldPoint> spawnSnortTilesBy2() {
        List<WorldPoint> tiles = new ArrayList<>(8);
        WorldPoint c = BRUTUS_SPAWN_TILE;
        tiles.add(c.dx(2));
        tiles.add(c.dx(-2));
        tiles.add(c.dy(2));
        tiles.add(c.dy(-2));
        tiles.add(c.dx(2).dy(2));
        tiles.add(c.dx(2).dy(-2));
        tiles.add(c.dx(-2).dy(2));
        tiles.add(c.dx(-2).dy(-2));
        return tiles;
    }

    private void attack(Rs2NpcModel brutus) {
        if (brutus == null || brutus.isDead()) return;
        if (isActionBlocked(false) || isAttackOnCooldown()) return;

        if (engaged(brutus)) return;

        boolean clicked = brutus.click("Attack") || brutus.click("attack");

        if (clicked) {
            markAction();
            lockBrutus(brutus.getIndex());
        }

    }

    private boolean useSpecIfReady(Rs2NpcModel brutus) {
        if (!config.useSpecialAttack() || brutus == null || brutus.isDead()) return false;
        if (isActionBlocked(false)) return false;
        if (System.currentTimeMillis() < mechanicLockUntil.get()) return false;
        if (growlQueued || snortRemaining.get() > 0 || !engaged(brutus)) return false;
        if (Rs2Player.getHealthPercentage() < SPEC_MIN_HP_PERCENT) return false;

        long now = System.currentTimeMillis();
        if (now - lastSpecAt < SPEC_CD_MS) return false;
        if (reattackAfter > 0 && now - reattackAfter < SPEC_BLOCK_AFTER_DODGE_MS) return false;

        int requiredPct = Math.max(0, Math.min(100, config.specialAttackEnergyPercent()));
        int requiredEnergy = requiredPct * 10;
        if (Rs2Combat.getSpecEnergy() < requiredEnergy) return false;

        Rs2Combat.setSpecState(true, requiredEnergy);
        lastSpecAt = now;
        markAction();
        return true;
    }

    private void loot() {
        if (!config.enableLooting() || System.currentTimeMillis() - lastLootAt < 1500) return;

        String[] wanted = parseLoot(config.lootItems());
        Rs2TileItemModel byName = null;
        if (wanted.length > 0) {
            byName = Microbot.getRs2TileItemCache().query()
                .within(10)
                .where(this::lootable)
                .where(item -> {
                    String name = item.getName();
                    if (name == null) return false;
                    String n = name.toLowerCase(Locale.ROOT);
                    for (String w : wanted) if (n.contains(w)) return true;
                    return false;
                })
                .nearestReachable();
        }

        if (byName != null && byName.click("Take")) {
            lastLootAt = System.currentTimeMillis();
            markAction();
            return;
        }

        if (config.lootValueThreshold() <= 0) return;

        Rs2TileItemModel byValue = Microbot.getRs2TileItemCache().query()
            .within(10)
            .where(this::lootable)
            .where(item -> item.getTotalValue() >= config.lootValueThreshold())
            .nearestReachable();

        if (byValue != null && byValue.click("Take")) {
            lastLootAt = System.currentTimeMillis();
            markAction();
        }
    }

    private boolean clickGate(Rs2TileObjectModel gate, String action) {
        if (gate == null || action == null || action.isBlank()) return false;
        
        boolean clicked = clickGateAction(gate, action);
        if (!clicked) {
            String[] fallbacks = ENTRY_GATE_ACTION.equalsIgnoreCase(action) ? ENTRY_GATE_ACTIONS : EXIT_GATE_ACTIONS;
            for (String fallback : fallbacks) {
                if (clickGateAction(gate, fallback)) {
                    clicked = true;
                    break;
                }
            }
        }
        if (clicked) markAction();
        return clicked;
    }

    private boolean clickGateAction(Rs2TileObjectModel gate, String action) {
        return gate.click(action)
            || gate.click(action.toLowerCase(Locale.ROOT))
            || gate.click(action.toUpperCase(Locale.ROOT));
    }

    private boolean clickGateUsingApi(boolean exiting) {
        int[] ids = exiting ? EXIT_GATE_IDS : ENTRY_GATE_IDS;
        String[] actions = exiting ? EXIT_GATE_ACTIONS : ENTRY_GATE_ACTIONS;

        for (String action : actions) {
            for (int id : ids) {
                if (interactGateById(id, action)) {
                    markAction();
                    return true;
                }
            }
        }
        return false;
    }

    private boolean interactGateById(int id, String action) {
        return Microbot.getRs2TileObjectCache().query()
            .fromWorldView()
            .withId(id)
            .within(GATE_QUERY_RADIUS)
            .interact(action)
            || Microbot.getRs2TileObjectCache().query()
            .withId(id)
            .within(GATE_QUERY_RADIUS)
            .interact(action);
    }

    private boolean clickAnyExitGate() {
        List<Rs2TileObjectModel> gates = findExitGates();
        if (gates == null || gates.isEmpty()) return false;

        WorldPoint player = Rs2Player.getWorldLocation();
        List<Rs2TileObjectModel> sorted = new ArrayList<>(gates);
        if (player != null) {
            sorted.sort(Comparator.comparingInt(g -> {
                WorldPoint t = g == null ? null : g.getWorldLocation();
                return t == null ? Integer.MAX_VALUE : t.distanceTo(player);
            }));
        }

        for (Rs2TileObjectModel gate : sorted) if (clickGate(gate, EXIT_GATE_ACTION)) return true;
        return false;
    }

    private Rs2TileObjectModel findEntryGate() {
        WorldPoint player = Rs2Player.getWorldLocation();
        if (player == null) return null;
        List<Rs2TileObjectModel> gates = queryGatesByIds(ENTRY_GATE_IDS);
        return gates.stream()
            .filter(Objects::nonNull)
            .min(Comparator.comparingInt(g -> {
                WorldPoint tile = g.getWorldLocation();
                return tile.distanceTo(player);
            }))
            .orElse(null);
    }

    private Rs2TileObjectModel findExitGate() {
        WorldPoint player = Rs2Player.getWorldLocation();
        if (player == null) return null;
        List<Rs2TileObjectModel> gates = queryGatesByIds(EXIT_GATE_IDS);
        return gates.stream()
            .filter(Objects::nonNull)
            .min(Comparator.comparingInt(g -> {
                WorldPoint tile = g.getWorldLocation();
                return tile.distanceTo(player);
            }))
            .orElse(null);
    }

    private List<Rs2TileObjectModel> findExitGates() {
        return queryGatesByIds(EXIT_GATE_IDS);
    }

    private boolean isNearEntryStagingTile() {
        WorldPoint player = Rs2Player.getWorldLocation();
        if (player == null) return false;
        return Arrays.stream(ENTRY_STAGING_TILES)
            .filter(Objects::nonNull)
            .anyMatch(tile -> tile.distanceTo(player) <= ENTRY_STAGING_RADIUS);
    }

    private List<Rs2TileObjectModel> queryGatesByIds(int... ids) {
        if (ids == null || ids.length == 0) return new ArrayList<>();
        List<Rs2TileObjectModel> gates = Microbot.getRs2TileObjectCache().query()
            .fromWorldView()
            .withIds(ids)
            .within(GATE_QUERY_RADIUS)
            .toList();
        if (!gates.isEmpty()) {
            return gates;
        }
        return Microbot.getRs2TileObjectCache().query()
            .withIds(ids)
            .within(GATE_QUERY_RADIUS)
            .toList();
    }


    private WorldPoint nearestReferenceTile(WorldPoint[] references) {
        if (references == null || references.length == 0) return null;
        WorldPoint player = Rs2Player.getWorldLocation();
        if (player == null) return references[0];
        return Arrays.stream(references)
            .filter(Objects::nonNull)
            .min(Comparator.comparingInt(tile -> tile.distanceTo(player)))
            .orElse(references[0]);
    }

    private boolean clickDialogueYesOrContinue() {
        if (Rs2Dialogue.hasSelectAnOption()) {
            return Rs2Dialogue.clickOption("yes", false)
                || Rs2Dialogue.clickOption("leave", false)
                || Rs2Dialogue.clickOption("exit", false)
                || Rs2Dialogue.clickOption("continue", false);
        }
        if (Rs2Dialogue.hasContinue()) {
            Rs2Dialogue.clickContinue();
            return true;
        }
        if (Rs2Dialogue.isInDialogue()) {
            Rs2Dialogue.clickContinue();
            return true;
        }
        return false;
    }

    private Rs2NpcModel brutus() {
        List<Rs2NpcModel> list = Microbot.getRs2NpcCache().query()
            .fromWorldView()
            .withName(BRUTUS_NPC_NAME)
            .where(npc -> !npc.isDead())
            .toList();

        if (list.isEmpty()) {
            list = Microbot.getRs2NpcCache().query()
                .withName(BRUTUS_NPC_NAME)
                .where(npc -> !npc.isDead())
                .toList();
        }

        if (list.isEmpty()) return null;

        if (lockedBrutusIndex >= 0) {
            for (Rs2NpcModel n : list) {
                if (n != null && n.getIndex() == lockedBrutusIndex) return n;
            }
        }

        WorldPoint player = Rs2Player.getWorldLocation();
        if (player == null) return list.get(0);

        Rs2NpcModel nearest = list.stream()
            .filter(n -> n != null && n.getWorldLocation() != null)
            .min(Comparator.comparingInt(n -> {
                WorldPoint t = n.getWorldLocation();
                return player.distanceTo(t) * 10 + BRUTUS_SPAWN_TILE.distanceTo(t);
            }))
            .orElse(list.get(0));

        lockBrutus(nearest.getIndex());
        return nearest;
    }

    private boolean moveIfFar(WorldPoint destination) {
        WorldPoint player = Rs2Player.getWorldLocation();
        if (destination == null || player == null) return false;
        if (player.distanceTo(destination) <= INTERACT_DISTANCE) return false;
        if (isActionOnCooldown(GATE_CD_MS)) return false;
        moveFastNonBlocking(destination);
        markAction();
        return true;
    }

    private void moveToPostKillReturnTile() {
        WorldPoint destination = POST_KILL_RETURN_TILE;
        WorldPoint player = Rs2Player.getWorldLocation();
        if (player != null && player.equals(destination)) return;

        if (Rs2Walker.walkFastCanvas(destination, true)) return;

        LocalPoint local = null;
        if (client != null && client.getTopLevelWorldView() != null) {
            local = LocalPoint.fromWorld(client.getTopLevelWorldView(), destination);
        }
        if (local != null) {
            Rs2Walker.walkFastLocal(local);
            return;
        }

        Rs2Walker.walkTo(destination, INTERACT_DISTANCE);
    }

    private void moveFastNonBlocking(WorldPoint destination) {
        if (destination == null) return;
        WorldPoint player = Rs2Player.getWorldLocation();
        if (player != null && player.equals(destination)) return;
        
        // CRITICAL: Check if already moving
        if (Rs2Player.isMoving()) {
            return;
        }
        
        // CRITICAL: Add movement cooldown
        if (System.currentTimeMillis() - lastMoveAttemptAt < MOVE_COOLDOWN_MS) {
            return;
        }
        
        lastMoveAttemptAt = System.currentTimeMillis();

        if (Rs2Walker.walkFastCanvas(destination, true)) return;

        if (client != null && client.getTopLevelWorldView() != null) {
            LocalPoint local = LocalPoint.fromWorld(client.getTopLevelWorldView(), destination);
            if (local != null) {
                Rs2Walker.walkFastLocal(local);
            }
        }
    }

    private boolean dodgeMove(WorldPoint destination) {
        WorldPoint player = Rs2Player.getWorldLocation();
        if (player != null && player.equals(destination)) return true;
        
        // CRITICAL: Add dodge cooldown
        if (System.currentTimeMillis() - lastDodgeAttemptAt < DODGE_COOLDOWN_MS) {
            return false;
        }
        
        // CRITICAL: Validate movement state
        if (Rs2Player.isMoving()) {
            return false;
        }
        
        lastDodgeAttemptAt = System.currentTimeMillis();

        if (Rs2Walker.walkFastCanvas(destination, true) && waitForMovementStart()) {
            markDodgeAction();
            return true;
        }

        LocalPoint local = null;
        if (client != null && client.getTopLevelWorldView() != null) {
            local = LocalPoint.fromWorld(client.getTopLevelWorldView(), destination);
        }
        if (local != null) {
            Rs2Walker.walkFastLocal(local);
            if (waitForMovementStart()) {
                return true;
            }
        }

        if (config != null && config.enableDebugLogging()) {
            log.info("Dodge move failed to start: from={} to={} moving={}", player, destination, Rs2Player.isMoving());
        }
        return false;
    }

    private boolean validDodge(WorldPoint destination, WorldPoint player) {
        if (destination == null || player == null) return false;
        if (destination.equals(player) || destination.getPlane() != player.getPlane()) return false;
        int dist = player.distanceTo(destination);
        if (dist != 2) return false;
        return Rs2Tile.isTileReachable(destination);
    }
    
    private void markDodgeAction() {
        lastDodgeAt = System.currentTimeMillis();
        markAction();
    }
    
    private List<WorldPoint> snortHazardTiles(WorldPoint player, WorldPoint boss) {
        int radius = 14;
        if (player != null && boss != null) {
            radius = Math.max(10, Math.min(24, player.distanceTo(boss) + 8));
        }

        List<Rs2TileObjectModel> hazards = Microbot.getRs2TileObjectCache().query()
            .fromWorldView()
            .withIds(SNORT_HAZARD_OBJECT_ID)
            .within(radius)
            .toList();

        if (hazards.isEmpty()) {
            hazards = Microbot.getRs2TileObjectCache().query()
                .withIds(SNORT_HAZARD_OBJECT_ID)
                .within(radius)
                .toList();
        }

        List<WorldPoint> tiles = new ArrayList<>(hazards.size());
        for (Rs2TileObjectModel hazard : hazards) {
            if (hazard == null) continue;
            WorldPoint tile = hazard.getWorldLocation();
            if (!tiles.contains(tile)) {
                tiles.add(tile);
            }
        }
        return tiles;
    }

    private boolean isHazardTile(WorldPoint tile, List<WorldPoint> hazards) {
        if (tile == null || hazards == null || hazards.isEmpty()) return false;
        for (WorldPoint hazard : hazards) {
            if (tile.equals(hazard)) return true;
        }
        return false;
    }

    private int nearestHazardDistance(WorldPoint tile, List<WorldPoint> hazards) {
        if (tile == null || hazards == null || hazards.isEmpty()) return Integer.MAX_VALUE;
        int best = Integer.MAX_VALUE;
        for (WorldPoint hazard : hazards) {
            if (hazard == null) continue;
            int dist = tile.distanceTo(hazard);
            if (dist < best) best = dist;
        }
        return best;
    }

    private List<WorldPoint> playerSnortFallbackTiles(WorldPoint player, WorldPoint boss) {
        List<WorldPoint> tiles = new ArrayList<>(4);
        if (player == null) return tiles;
        tiles.add(player.dx(2));
        tiles.add(player.dx(-2));
        tiles.add(player.dy(2));
        tiles.add(player.dy(-2));

        if (boss == null) return tiles;
        tiles.sort((a, b) -> Integer.compare(b.distanceTo(boss), a.distanceTo(boss)));
        return tiles;
    }

    private DodgeAxis dodgeAxisFromPlayer(WorldPoint boss, WorldPoint player) {
        if (boss == null || player == null) return DodgeAxis.NORTH;
        int dx = player.getX() - boss.getX();
        int dy = player.getY() - boss.getY();
        if (Math.abs(dx) >= Math.abs(dy)) {
            return dx >= 0 ? DodgeAxis.EAST : DodgeAxis.WEST;
        }
        return dy >= 0 ? DodgeAxis.NORTH : DodgeAxis.SOUTH;
    }

    private List<WorldPoint> frontRowTiles(WorldPoint boss, DodgeAxis axis) {
        List<WorldPoint> row = new ArrayList<>(3);
        if (boss == null || axis == null) return row;
        switch (axis) {
            case NORTH:
                row.add(boss.dx(-1).dy(1));
                row.add(boss.dy(1));
                row.add(boss.dx(1).dy(1));
                break;
            case SOUTH:
                row.add(boss.dx(1).dy(-1));
                row.add(boss.dy(-1));
                row.add(boss.dx(-1).dy(-1));
                break;
            case EAST:
                row.add(boss.dx(1).dy(1));
                row.add(boss.dx(1));
                row.add(boss.dx(1).dy(-1));
                break;
            case WEST:
                row.add(boss.dx(-1).dy(-1));
                row.add(boss.dx(-1));
                row.add(boss.dx(-1).dy(1));
                break;
            default:
                break;
        }
        return row;
    }

    private List<WorldPoint> backRowTiles(WorldPoint boss, DodgeAxis axis) {
        return frontRowTiles(boss, opposite(axis));
    }

    private List<WorldPoint> ringTiles(WorldPoint boss, DodgeAxis axis) {
        List<WorldPoint> tiles = new ArrayList<>(8);
        List<WorldPoint> front = frontRowTiles(boss, axis);
        List<WorldPoint> back = backRowTiles(boss, axis);
        WorldPoint left = sideTile(boss, axis, true);
        WorldPoint right = sideTile(boss, axis, false);

        if (!front.isEmpty()) {
            if (front.size() > 1) tiles.add(front.get(1));
            tiles.add(front.get(0));
            if (front.size() > 2) tiles.add(front.get(2));
        }
        tiles.add(left);
        tiles.add(right);
        if (!back.isEmpty()) {
            if (back.size() > 1) tiles.add(back.get(1));
            tiles.add(back.get(0));
            if (back.size() > 2) tiles.add(back.get(2));
        }
        return tiles;
    }

    private WorldPoint sideTile(WorldPoint boss, DodgeAxis axis, boolean left) {
        if (boss == null || axis == null) return null;
        switch (axis) {
            case NORTH:
                return left ? boss.dx(-1) : boss.dx(1);
            case SOUTH:
                return left ? boss.dx(1) : boss.dx(-1);
            case EAST:
                return left ? boss.dy(1) : boss.dy(-1);
            case WEST:
                return left ? boss.dy(-1) : boss.dy(1);
            default:
                return null;
        }
    }

    private WorldPoint growlSideStep(WorldPoint player, DodgeAxis axis, boolean left) {
        if (player == null || axis == null) return null;
        switch (axis) {
            case NORTH:
                return left ? player.dx(-GROWL_SIDE_STEP) : player.dx(GROWL_SIDE_STEP);
            case SOUTH:
                return left ? player.dx(GROWL_SIDE_STEP) : player.dx(-GROWL_SIDE_STEP);
            case EAST:
                return left ? player.dy(GROWL_SIDE_STEP) : player.dy(-GROWL_SIDE_STEP);
            case WEST:
                return left ? player.dy(-GROWL_SIDE_STEP) : player.dy(GROWL_SIDE_STEP);
            default:
                return null;
        }
    }

    private DodgeAxis opposite(DodgeAxis axis) {
        if (axis == null) return DodgeAxis.NORTH;
        switch (axis) {
            case NORTH:
                return DodgeAxis.SOUTH;
            case SOUTH:
                return DodgeAxis.NORTH;
            case EAST:
                return DodgeAxis.WEST;
            case WEST:
                return DodgeAxis.EAST;
            default:
                throw new IllegalStateException("Unhandled dodge axis: " + axis);
        }
    }

    private enum DodgeAxis {
        NORTH,
        SOUTH,
        EAST,
        WEST
    }

    private boolean shouldTopUpAtStartup() {
        if (config == null || isInside()) return false;

        boolean nearBank = Rs2Bank.isOpen() || Rs2Bank.isNearBank(STARTUP_BANK_NEAR_DISTANCE);
        if (!nearBank) return false;

        int foodId = config.foodSelection().getId();
        if (Rs2Inventory.count(foodId) < effectiveDesiredFoodCount()) return true;
        return config.useCowbellTeleport() && !Rs2Inventory.hasItem(COWBELL_ITEM_ID);
    }

    private boolean lowFood() {
        return Rs2Inventory.count(config.foodSelection().getId()) < bankThreshold();
    }

    private int desiredFoodCount() {
        return Math.max(1, Math.min(28, config.foodAmount()));
    }

    private int effectiveDesiredFoodCount() {
        int desired = desiredFoodCount();
        if (config != null && config.useCowbellTeleport()) {
            // Keep one slot for the cowbell amulet when teleport mode is enabled.
            desired = Math.min(desired, 27);
        }
        return desired;
    }

    private int bankThreshold() {
        int configured = Math.max(1, Math.min(28, config.bankWhenFoodBelow()));
        return Math.min(configured, effectiveDesiredFoodCount());
    }

    private void maybeEat() {
        if (config == null) return;
        if (Rs2Player.getHealthPercentage() > Math.max(1, config.eatAtPercent())) return;
        int foodId = config.foodSelection().getId();
        if (!Rs2Inventory.hasItem(foodId) || isEatOnCooldown(false)) return;
        if (Rs2Inventory.interact(foodId, "Eat") || Rs2Inventory.interact(foodId, "eat")) {
            markAction();
        }
    }

    private boolean eatIfNeeded(int hpPct, boolean emergency, boolean override) {
        if (Rs2Player.getHealthPercentage() > hpPct) return false;
        int foodId = config.foodSelection().getId();
        if (!Rs2Inventory.hasItem(foodId) || isEatOnCooldown(emergency) || isActionBlocked(override)) return false;

        int before = Rs2Inventory.count(foodId);
        boolean clicked = Rs2Inventory.interact(foodId, "Eat") || Rs2Inventory.interact(foodId, "eat");
        if (!clicked) return false;

        int after = Rs2Inventory.count(foodId);
        if (after < before || Rs2Player.getHealthPercentage() > hpPct) {
            markAction();
            return true;
        }
        return false;
    }

    private void ringCowbellAfterKill() {
        pendingPostKillReturn = false;
        if (!config.ringCowbellAfterKill()) return;
        long now = System.currentTimeMillis();
        if (now - lastRingAt < 600) return;
        if (!Rs2Inventory.hasItem(COWBELL_ITEM_ID)) return;
        if (Rs2Inventory.interact(COWBELL_ITEM_ID, COWBELL_RING_ACTION)) {
            lastRingAt = now;
            pendingPostKillReturn = true;
            markAction();
            if (config.enableDebugLogging()) log.info("Rang Cowbell amulet after kill.");
        }
    }

    private boolean engaged(Rs2NpcModel brutus) {
        if (interactingWithBrutus()) return true;
        return brutus != null && brutus.isInteractingWithPlayer();
    }

    private boolean interactingWithBrutus() {
        Actor actor = Rs2Player.getInteracting();
        if (!(actor instanceof NPC)) return false;
        String name = actor.getName();
        return name != null && name.equalsIgnoreCase(BRUTUS_NPC_NAME);
    }

    private boolean isInside() {
        return Rs2Player.IsInInstance();
    }

    private boolean isBrutus(NPC npc) {
        if (npc == null) return false;
        String name = npc.getName();
        return name != null && name.equalsIgnoreCase(BRUTUS_NPC_NAME);
    }

    private boolean isNotBrutus(NPC npc) {
        return !isBrutus(npc);
    }

    private boolean isGrowlAnimation(int animation) {
        return animation == BRUTUS_GROWL_ANIMATION || animation == BRUTUS_GROWL_ANIMATION_LEGACY;
    }

    private void clearMechanic(String reason) {
        if ((growlQueued || snortRemaining.get() > 0) && config != null && config.enableDebugLogging()) {
            log.info("Clearing mechanic state: {}", reason);
        }
        growlQueued = false;
        snortRemaining.set(0);
        snortStep.set(0);
        snortStartedAt = 0;
        nextSnortAt = 0;
    }

    private void lockBrutus(int index) {
        if (index < 0) return;
        lockedBrutusIndex = index;
    }

    private void maybeEnableRunOnStartup() {
        if (runCheckedOnStartup) return;
        if (Rs2Player.getWorldLocation() == null) return;
        if (!Rs2Player.isRunEnabled()) {
            Rs2Player.toggleRunEnergy(true);
        }
        runCheckedOnStartup = true;
    }

    private void reset() {
        stopping = false;
        startedAt = System.currentTimeMillis();
        stateEnteredAt = startedAt;

        lastActionAt = 0;
        lastSpecAt = 0;
        lastTeleportAt = 0;
        lastRingAt = 0;
        lastLootAt = 0;

        lockedBrutusIndex = -1;
        lastPolledAnim = -1;
        lastObservedAnim = -1;
        growlQueued = false;
        snortRemaining.set(0);
        snortStep.set(0);
        snortStartedAt = 0;
        nextSnortAt = 0;
        reattackAfter = 0;
        mechanicLockUntil.set(0);
        growlPreferLeft.set(true);
        pendingPostKillReturn = false;
        lastGrowlQueuedAt = 0;
        lastSnortQueuedAt = 0;
        lastDodgeAt = 0;
        resetExitCowbellState();

        totalKills = 0;
        startCombatXp = -1;
        xpStartCaptured = false;
        startupTopUpChecked = false;
        runCheckedOnStartup = false;
    }

    private void setState(BrutusMaximusState next, String reason) {
        if (next == null || next == state) return;
        if (state == BrutusMaximusState.EXIT_FIGHT && next != BrutusMaximusState.EXIT_FIGHT) {
            resetExitCowbellState();
        } else if (state != BrutusMaximusState.EXIT_FIGHT && next == BrutusMaximusState.EXIT_FIGHT) {
            resetExitCowbellState();
        }
        state = next;
        stateEnteredAt = System.currentTimeMillis();
        if (config != null && config.enableDebugLogging()) log.info("State change: {} ({})", next, reason);
    }

    private void stop(String reason) {
        log.info("BrutusMaximus stop: {}", reason);
        setState(BrutusMaximusState.STOP, reason);
    }

    private boolean isLongStateStall() {
        if (state != BrutusMaximusState.RESUPPLY
            && state != BrutusMaximusState.TRAVEL_ENTER
            && state != BrutusMaximusState.EXIT_FIGHT) {
            return false;
        }
        return System.currentTimeMillis() - stateEnteredAt > STATE_RECOVERY_TIMEOUT_MS;
    }

    private boolean isActionBlocked(boolean override) {
        return !override && isActionOnCooldown(ACTION_CD_MS);
    }

    private boolean isActionOnCooldown(long ms) {
        return System.currentTimeMillis() - lastActionAt < ms;
    }

    private boolean isAttackOnCooldown() {
        return isActionOnCooldown(ATTACK_CD_MS);
    }

    private boolean isEatOnCooldown(boolean emergency) {
        long cooldown = emergency ? EAT_EMERGENCY_CD_MS : EAT_CD_MS;
        return isActionOnCooldown(cooldown);
    }

    private void markAction() {
        lastActionAt = System.currentTimeMillis();
    }

    private boolean lootable(Rs2TileItemModel item) {
        return item != null && item.isLootAble();
    }

    private String[] parseLoot(String value) {
        if (value == null || value.trim().isEmpty()) return new String[0];
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(s -> s.toLowerCase(Locale.ROOT))
            .toArray(String[]::new);
    }

    private int combatXpSnapshot() {
        if (client == null) return 0;
        return client.getSkillExperience(Skill.ATTACK)
            + client.getSkillExperience(Skill.STRENGTH)
            + client.getSkillExperience(Skill.DEFENCE)
            + client.getSkillExperience(Skill.HITPOINTS)
            + client.getSkillExperience(Skill.RANGED)
            + client.getSkillExperience(Skill.MAGIC);
    }

    private boolean isExpectedShutdownException(Exception ex) {
        if (ex == null || !stopping) return false;
        String m = ex.getMessage();
        if (m == null) return false;
        String n = m.toLowerCase(Locale.ROOT);
        return n.contains("rejected execution")
            || n.contains("rejected" + "execution")
            || n.contains("schedule")
            || n.contains("shutdown")
            || n.contains("terminated")
            || n.contains("interrupted waiting for client thread");
    }

    private void lockMechanicsUntil(long untilMillis) {
        mechanicLockUntil.accumulateAndGet(untilMillis, Math::max);
    }

    private void resetExitCowbellState() {
        exitCowbellPending = false;
        exitCowbellAttempts.set(0);
        exitCowbellAttemptedAt = 0;
        lastExitDebugAt = 0;
    }

    private void debugExit(String message, Object... args) {
        if (config == null || !config.enableDebugLogging()) return;
        long now = System.currentTimeMillis();
        if (now - lastExitDebugAt < EXIT_DEBUG_THROTTLE_MS) return;
        lastExitDebugAt = now;
        log.info(message, args);
    }

    private boolean waitForMovementStart() {
        WorldPoint before = Rs2Player.getWorldLocation();
        return sleepUntil(() -> {
            WorldPoint now = Rs2Player.getWorldLocation();
            return Rs2Player.isMoving() || (before != null && now != null && !before.equals(now));
        }, DODGE_MOVE_START_TIMEOUT_MS);
    }

    public String getStateName() {
        return state.name();
    }

    public String getRuntimeText() {
        if (startedAt <= 0) return "00:00:00";
        long elapsed = System.currentTimeMillis() - startedAt;
        long seconds = elapsed / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes % 60, seconds % 60);
    }

    public String getTotalKillsText() {
        return fmt(totalKills);
    }

    public String getTotalXpGainedText() {
        if (!xpStartCaptured || startCombatXp < 0) return "0";
        return fmt(Math.max(0, (long) combatXpSnapshot() - startCombatXp));
    }

    public String getXpPerHourText() {
        if (startedAt <= 0 || !xpStartCaptured || startCombatXp < 0) return "0";
        long elapsed = Math.max(1, System.currentTimeMillis() - startedAt);
        long gained = Math.max(0, (long) combatXpSnapshot() - startCombatXp);
        return fmt((gained * 3_600_000L) / elapsed);
    }

    public String getMechanicStatusText() {
        if (snortRemaining.get() > 0) return "Snort " + snortRemaining.get() + " left";
        if (growlQueued) return "Growl";
        return "Idle";
    }

    private String fmt(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

}
