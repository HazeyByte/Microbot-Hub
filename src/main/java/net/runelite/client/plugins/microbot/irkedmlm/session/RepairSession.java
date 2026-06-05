package net.runelite.client.plugins.microbot.irkedmlm.session;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.motherloadmine.IrkedMLMMapConstants;
import net.runelite.client.plugins.microbot.motherloadmine.IrkedMLMConfig;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

/**
 * Tick-progressive RepairSession for Motherlode Mine.
 * Handles fetching a hammer and repairing broken struts on the waterwheel.
 */
@Slf4j
public class RepairSession extends Session {

    public enum RepairSubState {
        IDLE,
        TRANSITIONING_FLOOR,
        EVALUATE,
        NEED_HAMMER,
        FETCH_HAMMER_EVAL,
        FETCH_HAMMER_CLEAR_SPACE,
        FETCH_HAMMER_WALK_CRATE,
        FETCH_HAMMER_SEARCH_CRATE,
        FETCH_HAMMER_VERIFY,
        WALK_TO_STRUT,
        CLICK_STRUT,
        ANIMATING,
        VERIFY,
        CLEANUP,
        DONE,
        FAILED
    }

    private static final WorldPoint SUPPLY_CRATE_POINT =
            new WorldPoint(3752, 5674, 0);

    private static final long WALK_THROTTLE_MS   = 1400L;
    private static final long SETTLE_DELAY_MS    = 600L;

    /**
     * How long to wait for the repair animation to START after clicking the strut.
     */
    private static final long ANIM_START_TIMEOUT_MS  = 10000L;

    /**
     * How long to wait for the repair animation to FINISH once it has started.
     */
    private static final long ANIM_FINISH_TIMEOUT_MS = 8000L;

    private static final int MAX_REPAIR_ATTEMPTS = 4;
    private static final int MAX_CRATE_SEARCHES  = 3;

    private static final int SUPPLY_CRATE_ID = 357; // Authentic ID for MLM Hammer crate
    private static final int STRUT_ID        = ObjectID.MOTHERLODE_WHEEL_STRUT_BROKEN;

    @Getter
    private RepairSubState repairSubState = RepairSubState.IDLE;

    @Getter
    private boolean ownsHammer = false;

    private final Rs2TileObjectCache tileCache;

    private WorldPoint targetStrutPoint;
    private int        targetStrutId = -1;

    /** Last repaired strut (persists across jobs for alternate-waterwheel preference; dist>6 heuristic). */
    private WorldPoint lastRepairedStrutPoint = null;

    private int  repairAttempts;
    private int  crateSearchAttempts;
    private int  brokenBeforeClick = -1;

    private boolean animStartObserved = false;
    private boolean postRepairPaused = false;

    private final IrkedMLMConfig config;

    public RepairSession(Rs2TileObjectCache tileCache, IrkedMLMConfig config) {
        super(config);
        this.tileCache = tileCache;
        this.config = config;
    }

    @Override
    public void reset() {
        super.reset();
        repairSubState      = RepairSubState.IDLE;
        ownsHammer          = false;
        repairAttempts      = 0;
        crateSearchAttempts = 0;
        targetStrutPoint    = null;
        targetStrutId       = -1;
        brokenBeforeClick   = -1;
        animStartObserved   = false;
        postRepairPaused    = false;
        // lastRepairedStrutPoint intentionally persists (for wheel swap); cleared only on new instance.
    }

    @Override
    public void begin() {
        if (!isIdle()) {
            log.warn("[RepairSession] begin() called while not idle");
            return;
        }
        repairAttempts      = 0;
        crateSearchAttempts = 0;
        targetStrutPoint    = null;
        targetStrutId       = -1;
        brokenBeforeClick   = -1;
        animStartObserved   = false;
        // lastRepairedStrutPoint persists for alternation (see selectTargetStrut).
        transitionSub(RepairSubState.IDLE);
        transition(State.ACTIVE);
    }

    private static final long STALL_THRESHOLD_MS = 5000L;

    @Override
    protected void tickInternal() {
        switch (repairSubState) {

            case IDLE:
                if (isUpperFloor()) {
                    transitionSub(RepairSubState.TRANSITIONING_FLOOR);
                } else {
                    transitionSub(RepairSubState.EVALUATE);
                }
                break;

            case TRANSITIONING_FLOOR:
                if (Rs2Player.isMoving() || Rs2Player.isAnimating(5000)) {
                    scheduleNextAdaptive(180L, 600L);
                    break;
                }
                if (ensureFloor(false)) {
                    transitionSub(RepairSubState.EVALUATE);
                }
                break;

            case EVALUATE:
                tickEvaluate();
                break;

            case NEED_HAMMER:
                tickNeedHammer();
                break;

            case FETCH_HAMMER_EVAL:
                tickFetchHammerEval();
                break;

            case FETCH_HAMMER_CLEAR_SPACE:
                tickFetchHammerClearSpace();
                break;

            case FETCH_HAMMER_WALK_CRATE:
                tickFetchHammerWalkCrate();
                break;

            case FETCH_HAMMER_SEARCH_CRATE:
                tickFetchHammerSearchCrate();
                break;

            case FETCH_HAMMER_VERIFY:
                tickFetchHammerVerify();
                break;

            case WALK_TO_STRUT:
                tickWalkToStrut();
                break;

            case CLICK_STRUT:
                tickClickStrut();
                break;

            case ANIMATING:
                tickAnimating();
                break;

            case VERIFY:
                tickVerify();
                break;

            case CLEANUP:
                tickCleanup();
                break;

            default:
                break;
        }
    }

    // -----------------------------------------------------------------------
    // Sub-state handlers
    // -----------------------------------------------------------------------

    private void tickEvaluate() {
        if (countBrokenStruts() == 0) {
            transitionSub(RepairSubState.CLEANUP);
            return;
        }
        if (hasHammer()) {
            ownsHammer = false;
            selectTargetStrut();

            if (isObjectInScene(STRUT_ID)) {
                log.info("[RepairSession] Strut detected in scene — moving to CLICK_STRUT");
                transitionSub(RepairSubState.CLICK_STRUT);
            } else {
                transitionSub(RepairSubState.WALK_TO_STRUT);
            }
        } else {
            transitionSub(RepairSubState.NEED_HAMMER);
        }
    }

    private void tickNeedHammer() {
        if (hasHammer()) {
            ownsHammer = false;
            selectTargetStrut();
            transitionSub(RepairSubState.WALK_TO_STRUT);
            return;
        }
        crateSearchAttempts = 0;
        transitionSub(RepairSubState.FETCH_HAMMER_EVAL);
    }

    private void tickFetchHammerEval() {
        if (crateSearchAttempts < MAX_CRATE_SEARCHES) {
            if (isObjectInScene(SUPPLY_CRATE_ID)) {
                transitionSub(RepairSubState.FETCH_HAMMER_SEARCH_CRATE);
            } else {
                transitionSub(RepairSubState.FETCH_HAMMER_WALK_CRATE);
            }
        } else {
            transitionSub(RepairSubState.FAILED);
            transition(State.FAILED);
        }
    }

    private void tickFetchHammerClearSpace() {
        if (!Rs2Inventory.isFull()) {
            transitionSub(RepairSubState.FETCH_HAMMER_WALK_CRATE);
            return;
        }
        if (Rs2Inventory.drop(ItemID.PAYDIRT)) {
            scheduleNextAdaptive(180L, 600L);
            transitionSub(RepairSubState.FETCH_HAMMER_WALK_CRATE);
        } else {
            transitionSub(RepairSubState.FAILED);
            transition(State.FAILED);
        }
    }

    private void tickFetchHammerWalkCrate() {
        if (isObjectInScene(SUPPLY_CRATE_ID)) {
            log.info("[RepairSession] Crate detected during walk — interrupting.");
            transitionSub(RepairSubState.FETCH_HAMMER_SEARCH_CRATE);
            return;
        }

        if (Rs2Player.isMoving()) {
            scheduleNextAdaptive(180L, 600L);
            return;
        }

        if (mayAct(tickDelayMs(400L, WALK_THROTTLE_MS))) {
            Rs2Walker.walkFastCanvas(SUPPLY_CRATE_POINT, true);
        }

        if (isStalled(STALL_THRESHOLD_MS)) {
            log.warn("[RepairSession] Walk to crate stalled");
            transitionSub(RepairSubState.FAILED);
            transition(State.FAILED);
        }
    }

    private void tickFetchHammerSearchCrate() {
        if (Rs2Inventory.isFull()) {
            transitionSub(RepairSubState.FETCH_HAMMER_CLEAR_SPACE);
            return;
        }

        if (Rs2Player.isInteracting() || Rs2Player.isMoving()) {
            scheduleNextAdaptive(180L, 600L);
            return;
        }

        var crate = findSupplyCrate();
        if (crate == null) {
            log.debug("[RepairSession] Crate lost from view — falling back to walk");
            transitionSub(RepairSubState.FETCH_HAMMER_WALK_CRATE);
            return;
        }

        log.debug("[RepairSession] Searching crate (ID: {})", SUPPLY_CRATE_ID);
        if (crate.click("Search")) {
            applyActionCooldown();
            crateSearchAttempts++;
            scheduleNextAdaptive(400L, 1800L);
            transitionSub(RepairSubState.FETCH_HAMMER_VERIFY);
            return;
        }

        if (isStalled(STALL_THRESHOLD_MS)) {
            transitionSub(RepairSubState.FAILED);
            transition(State.FAILED);
        }
    }

    private void tickFetchHammerVerify() {
        if (hasHammer()) {
            ownsHammer = true;
            selectTargetStrut();
            transitionSub(RepairSubState.WALK_TO_STRUT);
            return;
        }
        if (crateSearchAttempts < MAX_CRATE_SEARCHES) {
            transitionSub(RepairSubState.FETCH_HAMMER_EVAL);
        } else {
            transitionSub(RepairSubState.FAILED);
            transition(State.FAILED);
        }
    }

    private void tickWalkToStrut() {
        if (countBrokenStruts() == 0) {
            transitionSub(RepairSubState.CLEANUP);
            return;
        }

        if (isAnotherPlayerRepairing()) {
            log.info("[RepairSession] Another player is repairing — backing off");
            transitionSub(RepairSubState.CLEANUP);
            return;
        }

        if (!hasHammer()) {
            log.info("[RepairSession] No hammer when about to walk to strut — must fetch from crate first");
            transitionSub(RepairSubState.NEED_HAMMER);
            return;
        }

        if (isObjectInScene(STRUT_ID)) {
            log.info("[RepairSession] Strut detected during walk — interrupting.");
            selectTargetStrut();
            transitionSub(RepairSubState.CLICK_STRUT);
            return;
        }

        if (Rs2Player.isMoving()) {
            scheduleNextAdaptive(180L, 600L);
            return;
        }

        if (targetStrutPoint == null) {
            selectTargetStrut();
            if (targetStrutPoint == null) {
                transitionSub(RepairSubState.CLEANUP);
                return;
            }
        }

        if (mayAct(tickDelayMs(400L, WALK_THROTTLE_MS))) {
            Rs2Walker.walkFastCanvas(targetStrutPoint, true);
        }

        if (isStalled(STALL_THRESHOLD_MS)) {
            log.warn("[RepairSession] Walk to strut stalled");
            transitionSub(RepairSubState.FAILED);
            transition(State.FAILED);
        }
    }

    private void tickClickStrut() {
        if (countBrokenStruts() == 0) {
            transitionSub(RepairSubState.CLEANUP);
            return;
        }

        if (isAnotherPlayerRepairing()) {
            log.info("[RepairSession] Another player started repairing — backing off");
            transitionSub(RepairSubState.CLEANUP);
            return;
        }

        if (!hasHammer()) {
            log.info("[RepairSession] No hammer when about to click repair on strut — must fetch from crate first");
            transitionSub(RepairSubState.NEED_HAMMER);
            return;
        }

        if (Rs2Player.isInteracting() || Rs2Player.isMoving()) {
            scheduleNextAdaptive(180L, 600L);
            return;
        }

        var target = findStrutAt(targetStrutPoint, targetStrutId);
        if (target == null) {
            transitionSub(RepairSubState.WALK_TO_STRUT);
            return;
        }

        log.debug("[RepairSession] Repairing strut (ID: {})", targetStrutId);
        if (target.click("Repair")) {
            applyActionCooldown();
            brokenBeforeClick   = countBrokenStruts();
            animStartObserved   = false;
            transitionSub(RepairSubState.ANIMATING);
            scheduleNextAdaptive(180L, 600L);
            return;
        }

        if (isStalled(STALL_THRESHOLD_MS)) {
            transitionSub(RepairSubState.FAILED);
            transition(State.FAILED);
        }
    }

    private void tickAnimating() {
        long animElapsed = subElapsed();

        if (!animStartObserved) {
            if (Rs2Player.isAnimating()) {
                animStartObserved = true;
                log.debug("[RepairSession] Repair animation started");
                subStateEnteredMs = System.currentTimeMillis();
            } else if (Rs2Player.isInteracting() || Rs2Player.isMoving()) {
                scheduleNextAdaptive(180L, 600L);
            } else if (animElapsed > ANIM_START_TIMEOUT_MS) {
                log.warn("[RepairSession] Repair animation never started after {}ms — proceeding to verify",
                        ANIM_START_TIMEOUT_MS);
                scheduleNextAdaptive(200L, SETTLE_DELAY_MS);
                transitionSub(RepairSubState.VERIFY);
            }
        } else {
            if (!Rs2Player.isAnimating()) {
                log.debug("[RepairSession] Repair animation finished");
                scheduleNextAdaptive(200L, SETTLE_DELAY_MS);
                transitionSub(RepairSubState.VERIFY);
            } else if (animElapsed > ANIM_FINISH_TIMEOUT_MS) {
                log.warn("[RepairSession] Repair animation still running after {}ms — forcing verify",
                        ANIM_FINISH_TIMEOUT_MS);
                scheduleNextAdaptive(200L, SETTLE_DELAY_MS);
                transitionSub(RepairSubState.VERIFY);
            }
        }
    }

    private void tickVerify() {
        int broken = countBrokenStruts();

        if (brokenBeforeClick >= 0 && broken < brokenBeforeClick) {
            if (targetStrutPoint != null) {
                lastRepairedStrutPoint = targetStrutPoint;
            }
            repairAttempts   = 0;
            targetStrutPoint = null;
            targetStrutId    = -1;

            if (broken > 0 && isHumanLikeEnabled() && Rs2Random.between(0, 100) < 70) {
                log.info("[RepairSession] Strut fixed; continuing to next broken strut");
                transitionSub(RepairSubState.EVALUATE);
            } else {
                log.info("[RepairSession] Repair complete; moving to cleanup");
                transitionSub(RepairSubState.CLEANUP);
            }
            return;
        }

        repairAttempts++;
        if (repairAttempts >= MAX_REPAIR_ATTEMPTS) {
            transitionSub(RepairSubState.CLEANUP);
        } else {
            targetStrutPoint = null;
            targetStrutId    = -1;
            transitionSub(RepairSubState.WALK_TO_STRUT);
        }
    }

    private void tickCleanup() {
        if (isHumanLikeEnabled() && Rs2Random.between(0, 100) < 20 && !postRepairPaused) {
            postRepairPaused = true;
            long pauseMs = Rs2Random.between(1000, 2000);
            log.debug("[RepairSession] Post-repair admiration pause: {}ms", pauseMs);
            scheduleNext(pauseMs);
            return;
        }

        if (Rs2Inventory.hasItem("hammer")) {
            log.debug("[RepairSession] Cleanup: dropping hammer");
            Rs2Inventory.drop(ItemID.HAMMER);
            ownsHammer = false;
            scheduleNext(1200L);
            return;
        }
        log.debug("[RepairSession] Cleanup: hammer gone — finishing");
        transitionSub(RepairSubState.DONE);
        transition(State.COMPLETE);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    public int countBrokenStruts() {
        return tileCache.query()
                .where(o -> o.getId() == ObjectID.MOTHERLODE_WHEEL_STRUT_BROKEN)
                .count();
    }

    private void selectTargetStrut() {
        var struts = tileCache.query()
                .where(o -> o.getId() == ObjectID.MOTHERLODE_WHEEL_STRUT_BROKEN)
                .toList();
        if (struts.isEmpty()) return;

        Rs2TileObjectModel chosen;
        if (struts.size() > 1 && lastRepairedStrutPoint != null) {
            java.util.List<Rs2TileObjectModel> otherWheel = new java.util.ArrayList<>();
            for (var s : struts) {
                if (s.getWorldLocation().distanceTo(lastRepairedStrutPoint) > 6) {
                    otherWheel.add(s);
                }
            }
            if (!otherWheel.isEmpty()) {
                java.util.Collections.shuffle(otherWheel, new java.util.Random(System.nanoTime()));
                chosen = otherWheel.get(0);
                targetStrutPoint = chosen.getWorldLocation();
                targetStrutId    = chosen.getId();
                log.info("[RepairSession] Selected strut from OTHER waterwheel (swap) at {} (last was {})",
                        targetStrutPoint, lastRepairedStrutPoint);
                return;
            }
        }

        java.util.Collections.shuffle(struts, new java.util.Random(System.nanoTime()));
        chosen = struts.get(0);
        targetStrutPoint = chosen.getWorldLocation();
        targetStrutId    = chosen.getId();
        log.debug("[RepairSession] Selected random strut at {} (ID: {}) from {} options",
                targetStrutPoint, targetStrutId, struts.size());
    }

    private Rs2TileObjectModel findStrutAt(WorldPoint wp, int id) {
        return tileCache.query()
                .where(o -> o.getId() == id && (wp == null || wp.equals(o.getWorldLocation())))
                .nearestReachable();
    }

    private Rs2TileObjectModel findSupplyCrate() {
        return tileCache.query()
                .where(o -> o.getId() == SUPPLY_CRATE_ID)
                .nearestReachable();
    }

    private boolean hasHammer() {
        return Rs2Equipment.isWearing("hammer") || Rs2Inventory.hasItem("hammer");
    }

    /**
     * Detects if another player is likely repairing or about to repair the strut.
     * Checks for any non-local player within 5 tiles of the waterwheel area.
     */
    private boolean isAnotherPlayerRepairing() {
        // Name is pushed from IrkedMLMScript (no client thread fetch in session)
        WorldPoint strutArea = IrkedMLMMapConstants.WATERWHEEL_AREA;

        int nearbyPlayers = Microbot.getRs2PlayerCache().query()
                .where(p -> {
                    String pName = p.getName();
                    return pName != null
                            && !pName.equalsIgnoreCase(cachedLocalPlayerName)
                            && p.getWorldLocation().distanceTo(strutArea) <= 5;
                })
                .count();

        if (nearbyPlayers > 0) {
            log.debug("[RepairSession] {} other player(s) near waterwheel — deferring repair", nearbyPlayers);
            return true;
        }
        return false;
    }

    public boolean canFastComplete() {
        return countBrokenStruts() == 0;
    }

    private void transitionSub(RepairSubState next) {
        log.debug("[RepairSession] Sub-state: {} → {}", repairSubState, next);
        repairSubState    = next;
        subStateEnteredMs = System.currentTimeMillis();

        switch (next) {
            case TRANSITIONING_FLOOR:    updateStatus("Climbing Ladder"); break;
            case FETCH_HAMMER_WALK_CRATE: updateStatus("Walking to Hammer Crate"); break;
            case FETCH_HAMMER_SEARCH_CRATE: updateStatus("Searching Hammer Crate"); break;
            case WALK_TO_STRUT:          updateStatus("Walking to Waterwheel"); break;
            case CLICK_STRUT:            updateStatus("Repairing Waterwheel"); break;
            case ANIMATING:              updateStatus("Fixing Strut"); break;
            case CLEANUP:                updateStatus("Dropping Hammer"); break;
            case DONE:                   updateStatus("Repair Complete"); break;
            case EVALUATE:               updateStatus("Evaluating Struts"); break;
            case NEED_HAMMER:            updateStatus("Checking for Hammer"); break;
            default:                     updateStatus(formatEnum(next.name())); break;
        }
    }
}
