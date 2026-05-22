/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  javax.inject.Inject
 *  javax.inject.Singleton
 *  net.runelite.api.coords.WorldPoint
 *  net.runelite.client.plugins.microbot.Microbot
 *  net.runelite.client.plugins.microbot.Script
 *  net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache
 *  net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectQueryable
 *  net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel
 *  net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban
 *  net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings
 *  net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity
 *  net.runelite.client.plugins.microbot.util.combat.Rs2Combat
 *  net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment
 *  net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
 *  net.runelite.client.plugins.microbot.util.math.Rs2Random
 *  net.runelite.client.plugins.microbot.util.player.Rs2Player
 *  net.runelite.client.plugins.microbot.util.walker.Rs2Walker
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 */
package net.runelite.client.plugins.microbot.irkedminer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectQueryable;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.irkedminer.IrkedMinerConfig;
import net.runelite.client.plugins.microbot.irkedminer.IrkedMinerState;
import net.runelite.client.plugins.microbot.irkedminer.data.Ores;
import net.runelite.client.plugins.microbot.irkedminer.handlers.InventoryHandler;
import net.runelite.client.plugins.microbot.irkedminer.handlers.RockSelector;
import net.runelite.client.plugins.microbot.irkedminer.handlers.WorldHopManager;
import net.runelite.client.plugins.microbot.irkedminer.priority.BatchTierOption;
import net.runelite.client.plugins.microbot.irkedminer.priority.PriorityMiningManager;
import net.runelite.client.plugins.microbot.irkedminer.priority.PrioritySelection;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class IrkedMinerScript
extends Script {
    private static final Logger log = LoggerFactory.getLogger(IrkedMinerScript.class);
    @Inject
    private IrkedMinerConfig config;
    @Inject
    private Rs2TileObjectCache tileObjectCache;
    public static final String version = "1.1.2";
    private IrkedMinerState state = IrkedMinerState.IDLE;
    private long stateStartTime = System.currentTimeMillis();
    private Ores selectedOre;
    private String selectedOreName;
    private String selectedOreKey;
    private WorldPoint initialPlayerLocation;
    private WorldPoint targetLocation;
    private WorldPoint lastMiningLocation;
    private int maxDistanceFromStart = 20;
    private int oresMined = 0;
    private long scriptStartTime;
    private int oreRetryCount = 0;
    private static final int STATE_TIMEOUT_MS = 30000;
    private static final long MAIN_LOOP_INTERVAL_MS = 100L;
    private static final long FIND_TARGET_ACTION_INTERVAL_MS = 100L;
    private static final long BANK_ACTION_INTERVAL_MS = 300L;
    private static final long DROP_ACTION_INTERVAL_MS = 220L;
    private static final long HOP_ACTION_INTERVAL_MS = 800L;
    private static final long RECOVER_ACTION_INTERVAL_MS = 500L;
    private static final int MAX_ORE_RETRIES = 2;
    private static final long WAITING_ROCK_CHECK_INTERVAL_MS = 100L;
    private static final long WAITING_PROGRESS_LOG_INTERVAL_MS = 3000L;
    private static final long MINING_SCAN_INTERVAL_MS = 80L;
    private static final long MINING_RETRY_BACKOFF_MS = 140L;
    private static final long MINE_INTERACTING_GRACE_AFTER_CLICK_MS = 2500L;
    private static final int FIND_TARGET_CLICK_FAIL_HOP_THRESHOLD = 3;
    private static final int RUNITE_WAIT_MIN_SECONDS = 2;
    private static final int RUNITE_WAIT_MAX_SECONDS = 4;
    private static final int OCCUPIED_CHECKS_BEFORE_HOP = 3;
    private static final int OCCUPIED_MIN_DURATION_SECONDS = 3;
    private static final long SAME_ROCK_CLICK_GUARD_MS = 250L;
    private static final int FIND_TARGET_NO_ROCK_RUNITE_MIN_MS = 800;
    private static final int FIND_TARGET_NO_ROCK_RUNITE_MAX_MS = 1200;
    private static final int FIND_TARGET_NO_ROCK_HIGH_MIN_MS = 1000;
    private static final int FIND_TARGET_NO_ROCK_HIGH_MAX_MS = 1600;
    private static final int FIND_TARGET_NO_ROCK_DEFAULT_MIN_MS = 1400;
    private static final int FIND_TARGET_NO_ROCK_DEFAULT_MAX_MS = 2000;
    private static final String[] SPEC_PICKAXES = new String[]{"dragon pickaxe", "crystal pickaxe", "infernal pickaxe"};
    private long waitingForOreSinceMs = 0L;
    private long lastWaitingRockCheckMs = 0L;
    private long lastWaitingProgressLogMs = 0L;
    private int waitingTargetDurationMs = 0;
    private int occupiedConsecutiveChecks = 0;
    private long occupiedSinceMs = 0L;
    private long lastMiningScanMs = 0L;
    private long nextMiningClickAllowedAtMs = 0L;
    private WorldPoint lastClickedRockLocation = null;
    private long lastClickedRockAtMs = 0L;
    private long lastFindTargetActionAtMs = 0L;
    private long lastBankActionAtMs = 0L;
    private long lastDropActionAtMs = 0L;
    private long lastHopActionAtMs = 0L;
    private long lastRecoverActionAtMs = 0L;
    private long recoverReadyAtMs = 0L;
    private int recoverLoopCount = 0;
    private FailureReason failureReason = FailureReason.NONE;
    private int lastTrackedOreInventoryCount = -1;
    private long findTargetNoRockSinceMs = 0L;
    private int findTargetNoRockTimeoutMs = 0;
    private int findTargetClickFailStreak = 0;
    private RockSelector rockSelector;
    private InventoryHandler inventoryHandler;
    private WorldHopManager worldHopManager;
    private PriorityMiningManager priorityMiningManager;

    public boolean start(IrkedMinerConfig config) {
        this.config = config;
        this.scriptStartTime = System.currentTimeMillis();
        this.stateStartTime = System.currentTimeMillis();
        this.state = IrkedMinerState.BOOTSTRAP;
        this.initialPlayerLocation = null;
        this.targetLocation = null;
        this.lastMiningLocation = null;
        this.oresMined = 0;
        this.oreRetryCount = 0;
        this.waitingForOreSinceMs = 0L;
        this.lastWaitingRockCheckMs = 0L;
        this.lastWaitingProgressLogMs = 0L;
        this.waitingTargetDurationMs = 0;
        this.occupiedConsecutiveChecks = 0;
        this.occupiedSinceMs = 0L;
        this.lastMiningScanMs = 0L;
        this.nextMiningClickAllowedAtMs = 0L;
        this.lastFindTargetActionAtMs = 0L;
        this.lastBankActionAtMs = 0L;
        this.lastDropActionAtMs = 0L;
        this.lastHopActionAtMs = 0L;
        this.lastRecoverActionAtMs = 0L;
        this.recoverReadyAtMs = 0L;
        this.recoverLoopCount = 0;
        this.failureReason = FailureReason.NONE;
        this.lastClickedRockLocation = null;
        this.lastClickedRockAtMs = 0L;
        this.lastTrackedOreInventoryCount = -1;
        this.findTargetNoRockSinceMs = 0L;
        this.findTargetNoRockTimeoutMs = 0;
        this.findTargetClickFailStreak = 0;
        Rs2AntibanSettings.actionCooldownActive = false;
        this.rockSelector = new RockSelector(config, this.tileObjectCache, () -> this.targetLocation, () -> this.maxDistanceFromStart, () -> this.selectedOreKey, () -> this.selectedOreName);
        this.inventoryHandler = new InventoryHandler(config, () -> this.selectedOre, () -> this.selectedOreKey, this::changeState, this::walkBackIfNeeded);
        this.inventoryHandler.reset();
        this.worldHopManager = new WorldHopManager(config, this::changeState, () -> this.selectedOreName, () -> this.selectedOreKey, this::refreshTileObjectCache, () -> {
            this.oreRetryCount = 0;
        });
        this.worldHopManager.reset();
        this.priorityMiningManager = new PriorityMiningManager(config);
        this.initializeAntiBan();
        try {
            if (!this.selectMiningLocation()) {
                return false;
            }
            this.priorityMiningManager.reset(this.selectedOre);
            this.refreshTileObjectCache();
            if (this.selectedOreName == null || this.selectedOreName.isBlank()) {
                Microbot.log((String)"No target ore configured. Please set Target Ore in the plugin config.");
                return false;
            }
            this.lastTrackedOreInventoryCount = this.getCurrentTrackedOreCount();
            if (config.enableDebugLogging()) {
                log.debug("Tile object cache initialized: {}", (Object)(this.tileObjectCache != null ? 1 : 0));
                List testObjects = ((Rs2TileObjectQueryable)this.tileObjectCache.query().within(20)).toList();
                log.debug("Found {} objects in tile cache within 20 tiles", (Object)testObjects.size());
            }
            this.mainScheduledFuture = this.scheduledExecutorService.scheduleWithFixedDelay(() -> {
                try {
                    if (!super.run()) {
                        return;
                    }
                    StateContext context = this.observeStateContext();
                    this.updateOreCounterFromInventory();
                    IrkedMinerState nextState = this.decideNextState(context);
                    if (nextState != this.state) {
                        this.changeState(nextState);
                    }
                    this.actCurrentState(context);
                }
                catch (Exception ex) {
                    log.error("Error in main loop", (Throwable)ex);
                    this.changeState(IrkedMinerState.RECOVER);
                }
            }, 0L, 100L, TimeUnit.MILLISECONDS);
            return true;
        }
        catch (Exception ex) {
            log.error("Failed to start irkedMiner", (Throwable)ex);
            return false;
        }
    }

    private void handlePickaxeSpec() {
        if (Rs2Equipment.isWearing((String[])SPEC_PICKAXES)) {
            Rs2Combat.setSpecState((boolean)true, (int)1000);
            if (this.config.enableDebugLogging()) {
                log.debug("Activated pickaxe special attack");
            }
        }
    }

    private boolean selectMiningLocation() {
        Ores initialOre;
        int validatedDistance;
        this.maxDistanceFromStart = validatedDistance = Math.max(1, Math.min(50, this.config.distanceToStray()));
        if (this.config.enableDebugLogging() && validatedDistance != this.config.distanceToStray()) {
            log.debug("Configuration values clamped - distance: {}->{}", (Object)this.config.distanceToStray(), (Object)validatedDistance);
        }
        if ((initialOre = this.resolveInitialOreForStart()) == null) {
            Microbot.log((String)"Please set a Target Ore in the plugin config.");
            return false;
        }
        this.setActiveOre(initialOre, true);
        WorldPoint currentLocation = Rs2Player.getWorldLocation();
        if (currentLocation != null) {
            this.targetLocation = currentLocation;
        }
        if (this.config.enableDebugLogging()) {
            log.debug("Init mining target: ore='{}' key='{}' targetLocation={} maxDistance={}", new Object[]{this.selectedOreName, this.selectedOreKey, this.targetLocation, this.maxDistanceFromStart});
        }
        return true;
    }

    private Ores resolveInitialOreForStart() {
        BatchTierOption[] tiers;
        Ores configuredTarget = this.config.targetOre();
        if (configuredTarget == null) {
            return null;
        }
        if (!this.config.enablePriorityMining()) {
            return configuredTarget;
        }
        for (BatchTierOption tier : tiers = new BatchTierOption[]{this.config.batchTier1(), this.config.batchTier2(), this.config.batchTier3(), this.config.batchTier4()}) {
            if (tier == null || tier.getOre() == null) continue;
            return tier.getOre();
        }
        return configuredTarget;
    }

    private void setActiveOre(Ores ore, boolean logSelectionChange) {
        if (ore == null) {
            return;
        }
        Ores previousOre = this.selectedOre;
        this.selectedOre = ore;
        this.selectedOreName = ore.getName();
        this.selectedOreKey = ore.getKey();
        this.configureAntibanForOre();
        if (logSelectionChange && previousOre != ore) {
            log.info("Active ore set to {}", (Object)this.selectedOreName);
        } else if (logSelectionChange) {
            log.info("Mining configured ore: {}", (Object)this.selectedOreName);
        }
    }

    private Ores resolveDefaultOre() {
        Ores configured;
        Ores ores = configured = this.config != null ? this.config.targetOre() : null;
        if (configured != null) {
            return configured;
        }
        return this.selectedOre != null ? this.selectedOre : Ores.IRON;
    }

    private StateContext observeStateContext() {
        boolean inventoryFull;
        long now = System.currentTimeMillis();
        boolean loggedIn = Microbot.isLoggedIn();
        boolean bl = inventoryFull = loggedIn && Rs2Inventory.isFull();
        if (this.priorityMiningManager != null) {
            Ores defaultOre;
            this.priorityMiningManager.refreshConfigState();
            if (!this.priorityMiningManager.isPriorityOperational() && (defaultOre = this.resolveDefaultOre()) != null && this.selectedOre != defaultOre) {
                this.setActiveOre(defaultOre, false);
            }
        }
        Rs2TileObjectModel nearestRock = null;
        if (loggedIn && (this.state == IrkedMinerState.FIND_TARGET || this.state == IrkedMinerState.WAIT_RESPAWN || this.state == IrkedMinerState.IDLE || this.state == IrkedMinerState.RECOVER)) {
            nearestRock = this.state == IrkedMinerState.FIND_TARGET ? this.rockSelector.findNearestRockFastNoOccupancy() : this.rockSelector.findNearestRockFast();
        }
        return new StateContext(now, loggedIn, inventoryFull, nearestRock);
    }

    private IrkedMinerState decideNextState(StateContext ctx) {
        if (this.state == IrkedMinerState.STOP) {
            return IrkedMinerState.STOP;
        }
        if (!ctx.loggedIn) {
            if (this.state == IrkedMinerState.HOP) {
                return IrkedMinerState.HOP;
            }
            return IrkedMinerState.IDLE;
        }
        if (ctx.now - this.stateStartTime > 30000L && this.state != IrkedMinerState.MINE && this.state != IrkedMinerState.WAIT_RESPAWN && this.state != IrkedMinerState.FIND_TARGET && this.state != IrkedMinerState.HOP && this.state != IrkedMinerState.RECOVER) {
            this.failureReason = FailureReason.STATE_TIMEOUT;
            return IrkedMinerState.RECOVER;
        }
        if (ctx.inventoryFull && this.state != IrkedMinerState.BANK && this.state != IrkedMinerState.DROP && this.state != IrkedMinerState.INVENTORY_RESOLVE && this.state != IrkedMinerState.RECOVER) {
            if (this.priorityMiningManager != null) {
                this.priorityMiningManager.onInventoryFullTransition();
            }
            this.rememberLastMiningLocation();
            return IrkedMinerState.INVENTORY_RESOLVE;
        }
        switch (this.state) {
            case BOOTSTRAP: 
            case IDLE: 
            case FIND_TARGET: {
                return IrkedMinerState.FIND_TARGET;
            }
            case INVENTORY_RESOLVE: {
                return this.config.useBank() || this.config.useDepositBox() ? IrkedMinerState.BANK : IrkedMinerState.DROP;
            }
        }
        return this.state;
    }

    private void actCurrentState(StateContext ctx) {
        if (this.config.enableDebugLogging()) {
            log.debug("Act state: {} failureReason={}", (Object)this.state, (Object)this.failureReason);
        }
        try {
            switch (this.state) {
                case BOOTSTRAP: 
                case INVENTORY_RESOLVE: {
                    break;
                }
                case IDLE: {
                    Microbot.status = "Idle";
                    break;
                }
                case FIND_TARGET: {
                    this.actFindTarget(ctx);
                    break;
                }
                case MINE: {
                    this.executeMining();
                    break;
                }
                case WAIT_RESPAWN: {
                    this.executeWaitingForOre();
                    break;
                }
                case BANK: {
                    if (ctx.now - this.lastBankActionAtMs < 300L) break;
                    this.inventoryHandler.executeBanking();
                    this.lastBankActionAtMs = ctx.now;
                    break;
                }
                case DROP: {
                    if (ctx.now - this.lastDropActionAtMs < 220L) break;
                    this.inventoryHandler.executeDropping();
                    this.lastDropActionAtMs = ctx.now;
                    break;
                }
                case HOP: {
                    if (ctx.now - this.lastHopActionAtMs < 800L) break;
                    this.worldHopManager.executeWorldHopping();
                    this.lastHopActionAtMs = ctx.now;
                    break;
                }
                case RECOVER: {
                    if (ctx.now - this.lastRecoverActionAtMs < 500L) break;
                    this.executeRecovery();
                    this.lastRecoverActionAtMs = ctx.now;
                    ++this.recoverLoopCount;
                    if (this.recoverLoopCount <= 8) break;
                    log.error("Recovery loop exceeded budget, stopping script");
                    this.changeState(IrkedMinerState.STOP);
                    break;
                }
                case STOP: {
                    this.shutdown();
                }
            }
        }
        catch (Exception ex) {
            log.error("Error acting in state {}: {}", new Object[]{this.state, ex.getMessage(), ex});
            this.failureReason = FailureReason.STATE_TIMEOUT;
            this.changeState(IrkedMinerState.RECOVER);
        }
    }

    private void actFindTarget(StateContext ctx) {
        boolean worldHopEnabled;
        Rs2TileObjectModel rock;
        Ores priorityOre;
        PrioritySelection prioritySelection;
        if (ctx.now - this.lastFindTargetActionAtMs < 100L) {
            return;
        }
        this.lastFindTargetActionAtMs = ctx.now;
        if (this.findTargetNoRockSinceMs == 0L) {
            this.findTargetNoRockSinceMs = ctx.now;
            this.findTargetNoRockTimeoutMs = this.resolveFindTargetNoRockTimeoutMs(this.selectedOreKey);
        }
        Microbot.status = "Finding target rock...";
        PrioritySelection prioritySelection2 = prioritySelection = this.priorityMiningManager != null ? this.priorityMiningManager.resolveForFindTarget(this.rockSelector) : PrioritySelection.disabled();
        if (prioritySelection.isPriorityDriving() && (priorityOre = prioritySelection.getSelectedOre()) != null && priorityOre != this.selectedOre) {
            this.setActiveOre(priorityOre, false);
            this.findTargetNoRockSinceMs = ctx.now;
            this.findTargetNoRockTimeoutMs = this.resolveFindTargetNoRockTimeoutMs(this.selectedOreKey);
        }
        if (prioritySelection.isPriorityDriving()) {
            rock = prioritySelection.getTargetRock();
        } else {
            Rs2TileObjectModel rs2TileObjectModel = rock = ctx.nearestRock != null ? ctx.nearestRock : this.rockSelector.findNearestRockFastNoOccupancy();
        }
        if (rock != null) {
            this.findTargetNoRockSinceMs = 0L;
            if (this.mineRock(rock)) {
                this.findTargetClickFailStreak = 0;
                this.changeState(IrkedMinerState.MINE);
            } else {
                boolean worldHopEnabled2;
                ++this.findTargetClickFailStreak;
                boolean bl = worldHopEnabled2 = this.config.enableWorldHopping() && this.worldHopManager != null && !this.worldHopManager.isWorldHopOnCooldown();
                if (this.findTargetClickFailStreak >= 3) {
                    this.findTargetClickFailStreak = 0;
                    if (this.shouldPreferFastHopForOre(this.selectedOreKey) && worldHopEnabled2) {
                        log.info("Repeated target click failures for {}, forcing world hop", (Object)this.selectedOreName);
                        this.changeState(IrkedMinerState.HOP);
                    } else {
                        this.changeState(IrkedMinerState.WAIT_RESPAWN);
                    }
                }
            }
            return;
        }
        this.findTargetClickFailStreak = 0;
        long elapsedNoRockMs = ctx.now - this.findTargetNoRockSinceMs;
        if (elapsedNoRockMs < (long)this.findTargetNoRockTimeoutMs) {
            return;
        }
        boolean bl = worldHopEnabled = this.config.enableWorldHopping() && this.worldHopManager != null && !this.worldHopManager.isWorldHopOnCooldown();
        if (this.shouldPreferFastHopForOre(this.selectedOreKey) && worldHopEnabled) {
            this.changeState(IrkedMinerState.HOP);
        } else {
            this.changeState(IrkedMinerState.WAIT_RESPAWN);
        }
    }

    private void changeState(IrkedMinerState newState) {
        if (this.state == IrkedMinerState.STOP && newState != IrkedMinerState.STOP) {
            return;
        }
        if (newState != this.state) {
            IrkedMinerState previousState = this.state;
            log.info("State change: {} -> {}", (Object)previousState, (Object)newState);
            this.state = newState;
            this.stateStartTime = System.currentTimeMillis();
            if (previousState == IrkedMinerState.HOP && this.worldHopManager != null) {
                this.worldHopManager.cancelPendingHop();
            }
            if (this.priorityMiningManager != null && previousState == IrkedMinerState.BANK && (newState == IrkedMinerState.MINE || newState == IrkedMinerState.WAIT_RESPAWN || newState == IrkedMinerState.FIND_TARGET)) {
                this.priorityMiningManager.onBankingSuccess();
            }
            if (newState != IrkedMinerState.RECOVER) {
                this.recoverLoopCount = 0;
                this.recoverReadyAtMs = 0L;
            }
            if (newState == IrkedMinerState.FIND_TARGET) {
                this.findTargetNoRockSinceMs = 0L;
                this.findTargetNoRockTimeoutMs = this.resolveFindTargetNoRockTimeoutMs(this.selectedOreKey);
                this.findTargetClickFailStreak = 0;
            } else if (previousState == IrkedMinerState.FIND_TARGET) {
                this.findTargetNoRockSinceMs = 0L;
                this.findTargetClickFailStreak = 0;
            }
            if (newState == IrkedMinerState.WAIT_RESPAWN) {
                this.oreRetryCount = 0;
                this.resetWaitingStateTracking();
                this.waitingTargetDurationMs = this.resolveWaitingDurationMs(this.selectedOreKey);
            } else if (previousState == IrkedMinerState.WAIT_RESPAWN) {
                this.resetWaitingStateTracking();
                this.waitingTargetDurationMs = 0;
            }
            if (newState == IrkedMinerState.HOP) {
                this.failureReason = FailureReason.NONE;
            }
            if (this.inventoryHandler != null) {
                this.inventoryHandler.resetForState(newState);
            }
            if (this.config != null && this.config.enableDebugLogging()) {
                log.debug("State context: ore='{}' key='{}' targetLocation={} maxDistance={} inventoryFull={} moving={} animating={}", new Object[]{this.selectedOreName, this.selectedOreKey, this.targetLocation, this.maxDistanceFromStart, Rs2Inventory.isFull(), Rs2Player.isMoving(), Rs2Player.isAnimating()});
            }
        }
    }

    public String getFormattedRuntime() {
        if (this.scriptStartTime <= 0L) {
            return "0s";
        }
        return this.formatRuntime(System.currentTimeMillis() - this.scriptStartTime);
    }

    public int getWorldHops() {
        return this.worldHopManager != null ? this.worldHopManager.getWorldHops() : 0;
    }

    public String getSelectedOreDisplayName() {
        return this.selectedOreName != null && !this.selectedOreName.isBlank() ? this.selectedOreName : "Unknown";
    }

    public String getCurrentActionLabel() {
        IrkedMinerState currentState = this.state;
        if (currentState == null) {
            return "Idle";
        }
        switch (currentState) {
            case MINE: {
                return "Mining";
            }
            case BANK: {
                return "Banking";
            }
            case DROP: {
                return "Dropping";
            }
            case HOP: {
                return "Hopping";
            }
            case WAIT_RESPAWN: {
                return "Waiting";
            }
            case FIND_TARGET: {
                return "Finding";
            }
            case RECOVER: {
                return "Recovering";
            }
        }
        return "Idle";
    }

    public WorldPoint getReturnPoint() {
        return this.lastMiningLocation == null ? this.initialPlayerLocation : this.lastMiningLocation;
    }

    private boolean walkBackIfNeeded() {
        if (!this.config.useBank() && !this.config.useDepositBox()) {
            return true;
        }
        WorldPoint targetPoint = this.getReturnPoint();
        if (targetPoint == null) {
            log.warn("Walk-back skipped: no mining return point available");
            return false;
        }
        WorldPoint currentLocation = Rs2Player.getWorldLocation();
        if (currentLocation == null) {
            log.warn("Walk-back skipped: player location unavailable");
            return false;
        }
        int distance = currentLocation.distanceTo(targetPoint);
        if (distance <= 4) {
            return true;
        }
        if (distance <= 20) {
            log.debug("Using walkFastCanvas for nearby return (distance: {})", (Object)distance);
            boolean success = Rs2Walker.walkFastCanvas((WorldPoint)targetPoint);
            if (!success) {
                log.debug("walkFastCanvas failed, falling back to web walker.");
                Rs2Walker.walkTo((WorldPoint)targetPoint);
            }
        } else {
            log.debug("Using web walker for distant return (distance: {})", (Object)distance);
            Rs2Walker.walkTo((WorldPoint)targetPoint);
        }
        boolean arrived = IrkedMinerScript.sleepUntil(() -> {
            WorldPoint location = Rs2Player.getWorldLocation();
            return location != null && location.distanceTo(targetPoint) <= 4;
        }, (int)5000);
        if (!arrived) {
            log.warn("Walk-back timed out before reaching return point {}", (Object)targetPoint);
        }
        return arrived;
    }

    private void rememberLastMiningLocation() {
        WorldPoint current = Rs2Player.getWorldLocation();
        if (current != null) {
            this.lastMiningLocation = current;
        }
    }

    private String formatRuntime(long milliseconds) {
        long seconds = milliseconds / 1000L;
        long minutes = seconds / 60L;
        long hours = minutes / 60L;
        if (hours > 0L) {
            return String.format("%dh %dm %ds", hours, minutes % 60L, seconds % 60L);
        }
        if (minutes > 0L) {
            return String.format("%dm %ds", minutes, seconds % 60L);
        }
        return String.format("%ds", seconds);
    }

    private void executeMining() {
        boolean recentlyClickedRock;
        WorldPoint currentLocation;
        if (this.initialPlayerLocation == null) {
            this.initialPlayerLocation = Rs2Player.getWorldLocation();
        }
        if (this.targetLocation == null && (currentLocation = Rs2Player.getWorldLocation()) != null) {
            this.targetLocation = currentLocation;
        }
        if (this.lastMiningLocation == null) {
            this.lastMiningLocation = Rs2Player.getWorldLocation();
        }
        if (Rs2Inventory.isFull()) {
            if (this.inventoryHandler.tryFillBagsIfPossible()) {
                return;
            }
            this.rememberLastMiningLocation();
            log.debug("Inventory full, transitioning to INVENTORY_RESOLVE");
            this.changeState(IrkedMinerState.INVENTORY_RESOLVE);
            return;
        }
        if (this.targetLocation != null && Rs2Player.distanceTo((WorldPoint)this.targetLocation) > this.maxDistanceFromStart) {
            this.navigateToLocation(this.targetLocation);
            return;
        }
        long now = System.currentTimeMillis();
        boolean animating = Rs2Player.isAnimating();
        boolean interacting = Microbot.getClient().getLocalPlayer() != null && Microbot.getClient().getLocalPlayer().isInteracting();
        boolean bl = recentlyClickedRock = now - this.lastClickedRockAtMs <= 2500L;
        if (animating || interacting && recentlyClickedRock) {
            Microbot.status = "Mining...";
            this.stateStartTime = now;
            return;
        }
        if (now < this.nextMiningClickAllowedAtMs) {
            return;
        }
        if (now - this.lastMiningScanMs < 80L) {
            return;
        }
        this.lastMiningScanMs = now;
        Rs2TileObjectModel rock = this.rockSelector.findNearestRockFastNoOccupancy();
        if (rock == null) {
            this.changeState(IrkedMinerState.FIND_TARGET);
            return;
        }
        if (this.targetLocation != null && rock.getWorldLocation().distanceTo(this.targetLocation) > this.maxDistanceFromStart) {
            if (this.config.enableDebugLogging()) {
                log.debug("Ignoring rock outside distance limit: {} at {}", (Object)rock.getName(), (Object)rock.getWorldLocation());
            }
            return;
        }
        if (this.shouldSkipImmediateReClickOnSameRock(rock, now)) {
            return;
        }
        if (!this.mineRock(rock)) {
            this.nextMiningClickAllowedAtMs = System.currentTimeMillis() + 140L;
        }
    }

    private boolean mineRock(Rs2TileObjectModel rock) {
        if (rock == null) {
            return false;
        }
        this.handlePickaxeSpec();
        if (this.config.enableDebugLogging()) {
            log.debug("Attempting to mine rock: {} (ID: {}) at {}", new Object[]{rock.getName(), rock.getId(), rock.getWorldLocation()});
        }
        if (rock.click("Mine")) {
            long now = System.currentTimeMillis();
            this.lastClickedRockLocation = rock.getWorldLocation();
            this.lastClickedRockAtMs = now;
            this.nextMiningClickAllowedAtMs = now + 140L;
            return true;
        }
        log.debug("Failed to click rock");
        this.nextMiningClickAllowedAtMs = System.currentTimeMillis() + 140L;
        return false;
    }

    private boolean shouldSkipImmediateReClickOnSameRock(Rs2TileObjectModel rock, long now) {
        if (rock == null || this.lastClickedRockLocation == null) {
            return false;
        }
        if (!rock.getWorldLocation().equals((Object)this.lastClickedRockLocation)) {
            return false;
        }
        return now - this.lastClickedRockAtMs < 250L;
    }

    private int resolveFindTargetNoRockTimeoutMs(String oreKey) {
        if (oreKey == null || oreKey.isBlank()) {
            return Rs2Random.between((int)1400, (int)2000);
        }
        String key = oreKey.toLowerCase(Locale.ROOT);
        if ("runite".equals(key)) {
            return Rs2Random.between((int)800, (int)1200);
        }
        if ("adamantite".equals(key) || "mithril".equals(key)) {
            return Rs2Random.between((int)1000, (int)1600);
        }
        return Rs2Random.between((int)1400, (int)2000);
    }

    private boolean shouldPreferFastHopForOre(String oreKey) {
        if (oreKey == null || oreKey.isBlank()) {
            return false;
        }
        String key = oreKey.toLowerCase(Locale.ROOT);
        return "runite".equals(key) || "adamantite".equals(key);
    }

    private void updateOreCounterFromInventory() {
        int currentTrackedCount = this.getCurrentTrackedOreCount();
        if (this.lastTrackedOreInventoryCount < 0) {
            this.lastTrackedOreInventoryCount = currentTrackedCount;
            return;
        }
        if (currentTrackedCount > this.lastTrackedOreInventoryCount) {
            int delta = currentTrackedCount - this.lastTrackedOreInventoryCount;
            this.oresMined += delta;
            if (this.worldHopManager != null) {
                this.worldHopManager.onMiningProgress();
            }
            if (this.config != null && this.config.enableDebugLogging()) {
                log.debug("Ore counter +{} ({} -> {})", new Object[]{delta, this.lastTrackedOreInventoryCount, currentTrackedCount});
            }
        }
        this.lastTrackedOreInventoryCount = currentTrackedCount;
    }

    private int getCurrentTrackedOreCount() {
        Set<String> trackedKeys = this.getTrackedOreKeys();
        if (trackedKeys.isEmpty()) {
            return 0;
        }
        return Rs2Inventory.all().stream().filter(item -> item != null && item.getName() != null).mapToInt(item -> {
            String name = item.getName().toLowerCase(Locale.ROOT);
            boolean match = false;
            for (String oreKey : trackedKeys) {
                if (oreKey == null || oreKey.isBlank()) continue;
                switch (oreKey) {
                    case "coal": {
                        match = name.equals("coal");
                        break;
                    }
                    case "clay": {
                        match = name.contains("clay");
                        break;
                    }
                    case "gem": {
                        match = name.startsWith("uncut ");
                        break;
                    }
                    default: {
                        match = name.contains(oreKey);
                    }
                }
                if (!match) continue;
                break;
            }
            return match ? Math.max(1, item.getQuantity()) : 0;
        }).sum();
    }

    private Set<String> getTrackedOreKeys() {
        String key;
        LinkedHashSet<String> keys = new LinkedHashSet<String>();
        if (this.selectedOreKey != null && !this.selectedOreKey.isBlank()) {
            keys.add(this.selectedOreKey.toLowerCase(Locale.ROOT));
        }
        if (this.config != null && this.config.enablePriorityMining()) {
            BatchTierOption[] tiers;
            for (BatchTierOption tier : tiers = new BatchTierOption[]{this.config.batchTier1(), this.config.batchTier2(), this.config.batchTier3(), this.config.batchTier4()}) {
                String key2;
                if (tier == null || tier.getOre() == null || (key2 = tier.getOre().getKey()) == null || key2.isBlank()) continue;
                keys.add(key2.toLowerCase(Locale.ROOT));
            }
        } else if (this.config != null && this.config.targetOre() != null && (key = this.config.targetOre().getKey()) != null && !key.isBlank()) {
            keys.add(key.toLowerCase(Locale.ROOT));
        }
        return keys;
    }

    private void refreshTileObjectCache() {
        block3: {
            try {
                long refreshedCount = Microbot.getRs2TileObjectCache().getStream().count();
                if (this.config.enableDebugLogging()) {
                    log.debug("Tile object cache has {} objects", (Object)refreshedCount);
                }
            }
            catch (Exception e) {
                if (!this.config.enableDebugLogging()) break block3;
                log.debug("Failed to check cache: {}", (Object)e.getMessage());
            }
        }
    }

    private void executeWaitingForOre() {
        long elapsedMs;
        int waitTime;
        boolean fastRuniteHopReady;
        Microbot.status = "Waiting for rocks to respawn...";
        String selectedOreDisplay = this.selectedOreName != null ? this.selectedOreName : "Unknown";
        String oreKey = this.selectedOreKey != null ? this.selectedOreKey : "";
        long now = System.currentTimeMillis();
        boolean bl = fastRuniteHopReady = "runite".equalsIgnoreCase(oreKey) && this.config.enableWorldHopping() && this.worldHopManager != null && !this.worldHopManager.isWorldHopOnCooldown();
        if (fastRuniteHopReady) {
            this.resetOccupiedTracking();
            log.info("Runite mode: skipping respawn wait and hopping immediately");
            this.changeState(IrkedMinerState.HOP);
            return;
        }
        int n = waitTime = this.waitingTargetDurationMs > 0 ? this.waitingTargetDurationMs : this.resolveWaitingDurationMs(oreKey);
        if (this.waitingForOreSinceMs == 0L) {
            this.waitingForOreSinceMs = now;
            this.waitingTargetDurationMs = waitTime;
            log.info("Waiting up to {} seconds for {} to respawn...", (Object)(waitTime / 1000), (Object)selectedOreDisplay);
        }
        if (now - this.lastWaitingRockCheckMs >= 100L) {
            this.lastWaitingRockCheckMs = now;
            RockSelector.RockAvailability availability = this.rockSelector.getRockAvailabilitySnapshot();
            if (this.handleFastHopForOccupiedRocks(availability, now, selectedOreDisplay)) {
                return;
            }
            Rs2TileObjectModel availableRock = this.rockSelector.findNearestRockFastNoOccupancy();
            if (availableRock == null) {
                availableRock = this.rockSelector.findNearestRock();
            }
            if (availableRock != null) {
                this.resetOccupiedTracking();
                log.info("Rocks respawned, returning to mining");
                this.oreRetryCount = 0;
                this.changeState(IrkedMinerState.MINE);
                return;
            }
        }
        if ((elapsedMs = now - this.waitingForOreSinceMs) < (long)waitTime) {
            if (this.config.enableDebugLogging() && now - this.lastWaitingProgressLogMs >= 3000L) {
                long remainingMs = (long)waitTime - elapsedMs;
                log.debug("Still waiting for rocks: {}s remaining", (Object)Math.max(0L, remainingMs / 1000L));
                this.lastWaitingProgressLogMs = now;
            }
            return;
        }
        log.info("No rocks found after {} seconds, checking world hop conditions", (Object)(waitTime / 1000));
        this.waitingForOreSinceMs = now;
        this.waitingTargetDurationMs = this.resolveWaitingDurationMs(oreKey);
        if (oreKey.equalsIgnoreCase("runite")) {
            this.resetOccupiedTracking();
            if (this.config.enableWorldHopping()) {
                log.info("Runite ore depleted, initiating world hop");
                this.changeState(IrkedMinerState.HOP);
            } else {
                log.info("Runite ore depleted but world hopping disabled, returning to mining");
                this.changeState(IrkedMinerState.MINE);
            }
            return;
        }
        ++this.oreRetryCount;
        if (this.oreRetryCount >= 2) {
            this.resetOccupiedTracking();
            if (this.config.enableWorldHopping()) {
                log.info("{} ore depleted after {} attempts, initiating world hop", (Object)selectedOreDisplay, (Object)this.oreRetryCount);
                this.changeState(IrkedMinerState.HOP);
            } else {
                log.info("{} ore depleted but world hopping disabled, returning to mining", (Object)selectedOreDisplay);
                this.changeState(IrkedMinerState.MINE);
            }
        } else {
            log.info("No rocks found, will try again (attempt {}/{})", (Object)this.oreRetryCount, (Object)2);
        }
    }

    private boolean detectFailure() {
        if (Microbot.getClient() == null) {
            log.warn("Client API is null");
            return true;
        }
        try {
            if (Rs2Player.isMoving()) {
                return false;
            }
        }
        catch (Exception ex) {
            log.debug("Error checking player movement: {}", (Object)ex.getMessage());
        }
        if (System.currentTimeMillis() - this.stateStartTime > 30000L) {
            log.warn("State timeout detected in failure check");
            return true;
        }
        return false;
    }

    private void executeRecovery() {
        long now = System.currentTimeMillis();
        if (this.recoverReadyAtMs == 0L) {
            this.recoverReadyAtMs = now + 1200L;
            log.warn("Entering recovery state - resetting cached data");
        }
        Microbot.status = "Recovering...";
        if (this.detectFailure()) {
            log.warn("Recovery triggered by failure detection");
        }
        this.targetLocation = Rs2Player.getWorldLocation();
        this.oreRetryCount = 0;
        if (this.inventoryHandler != null) {
            this.inventoryHandler.reset();
        }
        if (this.config.useBank()) {
            Rs2Antiban.takeMicroBreakByChance();
        }
        if (now < this.recoverReadyAtMs) {
            return;
        }
        this.recoverReadyAtMs = 0L;
        if (!Microbot.isLoggedIn()) {
            log.info("Not logged in, going to IDLE");
            this.changeState(IrkedMinerState.IDLE);
            return;
        }
        if (Rs2Inventory.isFull()) {
            log.info("Inventory full after recovery, routing to INVENTORY_RESOLVE");
            this.changeState(IrkedMinerState.INVENTORY_RESOLVE);
            return;
        }
        Rs2TileObjectModel rock = this.rockSelector.findNearestRock();
        if (rock != null) {
            log.info("Found rocks after recovery, returning to MINING");
            this.changeState(IrkedMinerState.MINE);
        } else {
            boolean fastRuniteHopReady;
            boolean bl = fastRuniteHopReady = "runite".equalsIgnoreCase(this.selectedOreKey) && this.config.enableWorldHopping() && this.worldHopManager != null && !this.worldHopManager.isWorldHopOnCooldown();
            if (fastRuniteHopReady) {
                log.info("No rocks found after recovery for runite, hopping immediately");
                this.changeState(IrkedMinerState.HOP);
            } else {
                log.info("No rocks found after recovery, going to WAITING_FOR_ORE");
                this.changeState(IrkedMinerState.WAIT_RESPAWN);
            }
        }
    }

    private void navigateToLocation(WorldPoint target) {
        if (target == null) {
            return;
        }
        int distance = Rs2Player.getWorldLocation().distanceTo(target);
        if (distance <= 20) {
            log.debug("Using walkFastCanvas for navigation (distance: {})", (Object)distance);
            boolean success = Rs2Walker.walkFastCanvas((WorldPoint)target);
            if (!success) {
                log.debug("walkFastCanvas failed, falling back to web walker");
                Rs2Walker.walkTo((WorldPoint)target);
            }
        } else {
            log.debug("Using web walker for navigation (distance: {})", (Object)distance);
            Rs2Walker.walkTo((WorldPoint)target);
        }
        Microbot.status = "Walking to " + (this.targetLocation != null ? "mining area" : "target");
        IrkedMinerScript.sleepUntil(() -> Rs2Player.getWorldLocation().distanceTo(target) <= 4, (int)5000);
    }

    private int resolveWaitingDurationMs(String oreKey) {
        if (oreKey != null && oreKey.equalsIgnoreCase("runite")) {
            return Rs2Random.between((int)2, (int)4) * 1000;
        }
        return this.rockSelector.getOreRespawnTime(oreKey);
    }

    private boolean handleFastHopForOccupiedRocks(RockSelector.RockAvailability availability, long now, String oreName) {
        if (!this.config.enableWorldHopping()) {
            this.resetOccupiedTracking();
            return false;
        }
        if (availability == null || !availability.allRocksOccupied()) {
            this.resetOccupiedTracking();
            return false;
        }
        if (this.occupiedSinceMs == 0L) {
            this.occupiedSinceMs = now;
        }
        ++this.occupiedConsecutiveChecks;
        int checksBeforeHop = 3;
        int minDurationMs = 3000;
        long occupiedDurationMs = now - this.occupiedSinceMs;
        if (this.config.enableDebugLogging()) {
            log.debug("All {} rocks occupied: total={} available={} streak={}/{} duration={}s/{}s", new Object[]{oreName, availability.getTotalRocks(), availability.getAvailableRocks(), this.occupiedConsecutiveChecks, checksBeforeHop, occupiedDurationMs / 1000L, minDurationMs / 1000});
        }
        if (this.occupiedConsecutiveChecks >= checksBeforeHop && occupiedDurationMs >= (long)minDurationMs) {
            log.info("All {} rocks occupied by nearby players for {} checks ({}s), hopping worlds", new Object[]{oreName, this.occupiedConsecutiveChecks, occupiedDurationMs / 1000L});
            this.resetOccupiedTracking();
            this.changeState(IrkedMinerState.HOP);
            return true;
        }
        return false;
    }

    private void resetOccupiedTracking() {
        this.occupiedConsecutiveChecks = 0;
        this.occupiedSinceMs = 0L;
    }

    private void resetWaitingStateTracking() {
        this.waitingForOreSinceMs = 0L;
        this.lastWaitingRockCheckMs = 0L;
        this.lastWaitingProgressLogMs = 0L;
        this.resetOccupiedTracking();
    }

    private void initializeAntiBan() {
        try {
            Rs2Antiban.resetAntibanSettings();
            Rs2Antiban.antibanSetupTemplates.applyMiningSetup();
            Rs2Antiban.setActivityIntensity((ActivityIntensity)ActivityIntensity.HIGH);
            Rs2AntibanSettings.actionCooldownChance = 0.01;
            Rs2AntibanSettings.microBreakChance = 0.0;
            Rs2AntibanSettings.microBreakDurationLow = 0;
            Rs2AntibanSettings.microBreakDurationHigh = 1;
            Rs2AntibanSettings.moveMouseRandomlyChance = 0.005;
            Rs2AntibanSettings.moveMouseOffScreen = false;
            log.info("Applied Microbot mining anti-ban setup");
        }
        catch (Exception e) {
            log.warn("Failed to apply anti-ban setup: {}", (Object)e.getMessage());
        }
    }

    private void configureAntibanForOre() {
    }

    public void shutdown() {
        log.info("Shutting down Irked Miner - Final Stats:");
        log.info("Total ores mined: {}", (Object)this.oresMined);
        log.info("Total world hops: {}", (Object)this.getWorldHops());
        log.info("Total runtime: {}", (Object)this.getFormattedRuntime());
        if (this.mainScheduledFuture != null && !this.mainScheduledFuture.isCancelled()) {
            this.mainScheduledFuture.cancel(true);
        }
        this.changeState(IrkedMinerState.STOP);
        Rs2Antiban.resetAntibanSettings();
    }

    public IrkedMinerState getState() {
        return this.state;
    }

    public int getOresMined() {
        return this.oresMined;
    }

    private static final class StateContext {
        private final long now;
        private final boolean loggedIn;
        private final boolean inventoryFull;
        private final Rs2TileObjectModel nearestRock;

        private StateContext(long now, boolean loggedIn, boolean inventoryFull, Rs2TileObjectModel nearestRock) {
            this.now = now;
            this.loggedIn = loggedIn;
            this.inventoryFull = inventoryFull;
            this.nearestRock = nearestRock;
        }
    }

    private static enum FailureReason {
        NONE,
        STATE_TIMEOUT,
        BANK_FAILED,
        DROP_FAILED,
        HOP_FAILED,
        TARGET_NOT_FOUND,
        PATHING_FAILED;

    }
}

