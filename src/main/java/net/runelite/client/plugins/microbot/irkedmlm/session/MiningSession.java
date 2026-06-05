package net.runelite.client.plugins.microbot.motherloadmine.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.motherloadmine.IrkedMLMMapConstants;
import net.runelite.client.plugins.microbot.motherloadmine.IrkedMLMConfig;
import net.runelite.client.plugins.microbot.motherloadmine.enums.MLMMiningSpot;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import net.runelite.api.gameval.ObjectID;

/**
 * Behaviour engine for Motherlode Mine mining: vein selection, rockfall memory, path checks,
 * blacklists, scoring, floor transitions, and return-to-area via spot anchors only.
 * <p>
 * Uses {@link net.runelite.client.plugins.microbot.motherloadmine.enums.MLMMiningSpot} for
 * {@code contains()} and anchors — never for decisions beyond pure geometry.
 */
@Slf4j
public class MiningSession extends Session {

    public enum MiningSubState {
        IDLE,
        TRANSITIONING_FLOOR,
        WALKING,
        CLEARING_OBSTACLE,
        SELECTED,
        REPOSITIONING,
        CLICKED,
        ARRIVING,
        CONFIRMED,
        MINING,
        DEPLETED,
        INV_FULL,
        SACK_FULL,
        FAILED
    }

    /**
     * All active (non-depleted) ore-vein object IDs in Motherload Mine.
     * We use IDs rather than withName("Ore vein") because the internal name
     * can differ from the menu target, causing cache misses.
     *
     * Per gamevals (ObjectID.java): active veins have distinct IDs from the
     * MOTHERLODE_DEPLETED_* variants. We must NOT include depleted IDs here.
     * Name checks (isActiveVein) are kept as a defensive fallback.
     */
    private static final int[] ORE_VEIN_IDS = {
            ObjectID.MOTHERLODE_ORE_SINGLE,
            ObjectID.MOTHERLODE_ORE_LEFT,
            ObjectID.MOTHERLODE_ORE_MIDDLE,
            ObjectID.MOTHERLODE_ORE_RIGHT
    };

    /** Object IDs for rockfalls (collapsed tunnels) in Motherload Mine. */
    private static final int[] ROCKFALL_IDS = {26679, 26680};

    @Getter
    private MiningSubState subState = MiningSubState.IDLE;

    @Getter
    private WorldPoint targetVein;

    @Getter
    private int failedClicks = 0;

    private long lastXpTimestamp = 0L;

    /** Timestamp after which the current session/target is no longer "soft locked". */
    private long sessionLockUntil = 0L;
    /** Timestamp of the last issued walk command. */
    private long lastWalkCommandMs = 0L;
    private long lastMineClickMs = 0L;

    /** Session-long memory of rockfall tiles. Even if another player mines a rockfall,
     *  we continue to treat those tiles as blocked for the remainder of the session. */
    private final Set<WorldPoint> rememberedRockfalls = new HashSet<>();

    /** Veins that recently failed interaction (depleted, blocked, or click-failed).
     *  Key = vein WorldPoint, Value = timestamp of failure. */
    private final Map<WorldPoint, Long> recentFailures = new HashMap<>();

    /** How long to blacklist a vein after it fails interaction. */
    private static final long FAILURE_BLACKLIST_MS = 10_000L;
    private static final long FAILURE_BLACKLIST_UPPER_MS = 60_000L;

    /** Beyond this distance, lower-mine travel uses web-walk (canvas is unreliable across tunnels). */
    private static final int LOWER_WEB_WALK_MIN_DISTANCE = 14;
    private static final int UPPER_WEB_WALK_MIN_DISTANCE = 20;
    /** Max tiles to consider a vein for scoring (upper chamber + ladder landing). */
    private static final int UPPER_VEIN_MAX_SELECTION_DISTANCE = 12;
    /** Upper chambers: pool veins for scoring (selection prefers dist {@code <=2} click range). */
    private static final int UPPER_CHAMBER_VEIN_MAX_DISTANCE = 8;
    /** OSRS click range for Mine on ore veins. Increased to 3 to allow direct clicks from slightly further away. */
    private static final int VEIN_CLICK_MAX_DISTANCE = 8;
    private static final int LOWER_VEIN_MAX_SELECTION_DISTANCE = 14;

    /** Score penalties (lower score = more desirable). Blocked veins stay in the pool. */
    private static final int SCORE_FAILED_PENALTY     = 50;
    private static final int SCORE_OUT_OF_RANGE       = 500;
    /** Prefer veins that are part of a nearby cluster (around-the-corner groups). */
    private static final int SCORE_CLUSTER_BONUS_PER_NEIGHBOR = 3;
    private static final int SCORE_CLUSTER_BONUS_CAP = 9;

    /** No mining activity after click — mark vein failed (human "that one didn't work"). */
    private static final long CONFIRMED_FAIL_MS_FAST   = 8_000L;
    private static final long CONFIRMED_FAIL_MS_HUMAN  = 8_000L;

    private final Rs2TileObjectCache   tileCache;

    public MiningSession(Rs2TileObjectCache tileCache, IrkedMLMConfig config) {
        super(config);
        this.tileCache = tileCache;
    }

    @Override
    public void reset() {
        super.reset();
        subState          = MiningSubState.IDLE;
        targetVein        = null;
        failedClicks      = 0;
        lastXpTimestamp   = 0L;
        sessionLockUntil  = 0L;
        lastWalkCommandMs = 0L;
        lastMineClickMs     = 0L;
        rememberedRockfalls.clear();
        recentFailures.clear(); // full clear on reset (recovery/hard resolve) so stale per-vein blacklists don't prevent resolving
    }

    @Override
    public void begin() {
        invalidateUpperFloorCache();
        if (!isIdle()) return;
        super.begin();
        transitionSub(MiningSubState.IDLE);
    }

    public void onMiningXp() {
        lastXpTimestamp = System.currentTimeMillis();
    }

    public boolean isActivelyMining() {
        return state == State.ACTIVE && (
                subState == MiningSubState.CLICKED
                        || subState == MiningSubState.CONFIRMED
                        || subState == MiningSubState.MINING);
    }

    public boolean isMiningComplete() {
        return subState == MiningSubState.INV_FULL
                || subState == MiningSubState.SACK_FULL;
    }

    /**
     * Returns true if the session ended because the sack was detected as full.
     * Used by the orchestrator to route directly to EMPTY_SACK without
     * re-querying the unreliable varbit.
     */
    public boolean endedWithSackFull() {
        return subState == MiningSubState.SACK_FULL;
    }

    /**
     * Returns true if the session ended because inventory was full of pay-dirt.
     */
    public boolean endedWithInvFull() {
        return subState == MiningSubState.INV_FULL;
    }

    /** Returns true if the session is currently in a "soft lock" window. */
    public boolean isLocked() {
        return System.currentTimeMillis() < sessionLockUntil;
    }

    /** Returns true if the session has committed to a target and is actively processing it. */
    public boolean isCommitted() {
        return subState == MiningSubState.CLICKED
                || subState == MiningSubState.CONFIRMED
                || subState == MiningSubState.MINING;
    }

    // -----------------------------------------------------------------------
    // Tick entry-point
    // -----------------------------------------------------------------------

    /**
     * Main tick method called by the orchestrator.
     */
    public void tick(MLMMiningSpot spot, int sackCount, int maxSack, AtomicLong globalLastXpTime) {
        if (!isActive()) return;
        if (System.currentTimeMillis() < nextActionMs) return;
        if (spot == null) {
            log.warn("[MiningSession] tick() called with null spot - failing session");
            transitionSub(MiningSubState.FAILED);
            transition(State.FAILED);
            return;
        }

        try {
            tickMiningInternal(spot, sackCount, maxSack, globalLastXpTime, System.currentTimeMillis());
        } catch (Exception e) {
            if (isClientThreadInvokeInterrupted(e)) {
                log.debug("[MiningSession] Tick deferred - client thread busy/interrupted, retry next tick");
                scheduleNextAdaptive(180L, 600L);
                return;
            }
            log.error("[MiningSession] Tick crash", e);
            transitionSub(MiningSubState.FAILED);
            transition(State.FAILED);
        }
    }

    @Override
    protected void tickInternal() {
        // MiningSession uses its own tick() overload - no-op here.
    }

    // -----------------------------------------------------------------------
    // Core tick logic
    // -----------------------------------------------------------------------

    private void tickMiningInternal(MLMMiningSpot spot, int sackCount, int maxSack,
                                    AtomicLong globalLastXpTime, long now) {

        updateRememberedRockfalls();
        cleanupRecentFailures();

        // Early exit if the orchestrator already knows the sack is full (or we were started while full).
        // Prevents walking to veins, selecting, and clicking "Mine" on upper/lower when we should be emptying.
        // The main script passes effective (current + any just-deposited projection).
        if (maxSack > 0 && sackCount >= maxSack) {
            log.debug("[MiningSession] Sack full ({}/{}) on tick entry - immediately completing as SACK_FULL", sackCount, maxSack);
            transitionSub(MiningSubState.SACK_FULL);
            transition(State.COMPLETE);
            return;
        }

        // localPlayerName is now pushed from IrkedMLMScript via updateCachedLocalPlayerName()
        // (no per-session client thread fetch here - reduces startup pressure + TimeoutExceptions)

        // Resolve the most recent XP timestamp from both local and global sources.
        long lastXpResolved = Math.max(lastXpTimestamp, globalLastXpTime.get());

        switch (subState) {

            // -----------------------------------------------------------------
            case IDLE:
                if (Rs2Inventory.isFull()) {
                    transitionSub(MiningSubState.INV_FULL);
                    break;
                }
                if (maxSack > 0 && sackCount >= maxSack) {
                    transitionSub(MiningSubState.SACK_FULL);
                    break;
                }
                if (isWrongFloor(spot)) {
                    transitionSub(MiningSubState.TRANSITIONING_FLOOR);
                    break;
                }
                transitionSub(miningEntrySubState(spot));
                break;

            // -----------------------------------------------------------------
            case TRANSITIONING_FLOOR:
                // While climbing (or recently started anim), don't re-attempt. Uses event-driven lastAnimationTime.
                // Combined with scheduleNext after click, covers gaps where raw isAnimating() might be false.
                if (Rs2Player.isMoving() || Rs2Player.isAnimating(5000)) {
                    scheduleNextAdaptive(180L, 600L);
                    break;
                }
                invalidateUpperFloorCache();
                if (ensureFloor(spot.isUpstairs())) {
                    invalidateUpperFloorCache();
                    log.info("[MiningSession] Ladder transition done for {} (upper={})", spot, isUpperFloor());
                    transitionSub(miningEntrySubState(spot));
                }
                if (isStalled(20_000L)) {
                    log.warn("[MiningSession] Floor transition stalled - failing");
                    transitionSub(MiningSubState.FAILED);
                    transition(State.FAILED);
                }
                break;

            // -----------------------------------------------------------------
            case WALKING:
                if (isWrongFloor(spot)) {
                    transitionSub(MiningSubState.TRANSITIONING_FLOOR);
                    break;
                }
                if (shouldSkipSpotWalk(spot)) {
                    transitionSub(MiningSubState.SELECTED);
                    break;
                }
                if (isNearSpot(spot)) {
                    transitionSub(MiningSubState.SELECTED);
                    break;
                }

                WorldPoint playerLoc = Rs2Player.getWorldLocation();
                RankedVein bestRanked = selectBestRankedVein(spot, playerLoc);
                Rs2TileObjectModel bestScored = bestRanked != null ? bestRanked.vein : null;
                int veinScore = -1;
                if (bestScored != null && playerLoc != null && !usesUpperStandMiningRules(spot)) {
                    veinScore = scoreVein(bestScored, spot, playerLoc);
                    WorldPoint vp = bestScored.getWorldLocation();
                    if (isReadyToClick(vp, playerLoc, spot)) {
                        log.info("[MiningSession] Score pick adjacent at {} (score={}) - skip walk",
                                vp, veinScore);
                        targetVein = vp;
                        transitionSub(MiningSubState.CLICKED);
                        scheduleNextAdaptive(350L, 700L);
                        break;
                    }
                }

                long walkCmdInterval = tickDelayMs(400L, 1200L);
                if (!Rs2Player.isMoving() && (now - lastWalkCommandMs >= walkCmdInterval)) {
                    WorldPoint walkTarget;
                    if (bestScored != null && !usesUpperStandMiningRules(spot)) {
                        walkTarget = walkTargetForVein(bestScored.getWorldLocation());
                        log.debug("[MiningSession] Walking toward score pick {} (score={})",
                                bestScored.getWorldLocation(), veinScore);
                    } else {
                        walkTarget = getFirstAnchor(spot);
                    }

                    if (walkTarget != null) {
                        walkTowardMiningTarget(walkTarget, spot, playerLoc);
                    } else {
                        log.warn("[MiningSession] Spot {} has no walk target and no visible veins", spot);
                        transitionSub(MiningSubState.FAILED);
                        transition(State.FAILED);
                        break;
                    }
                    lastWalkCommandMs = now;
                    scheduleNextAdaptive(180L, 600L);
                }
                if (isStalled(15_000L)) {
                    if (isNearSpot(spot)) {
                        log.info("[MiningSession] Walk timer elapsed but player is at spot {} - selecting vein",
                                spot);
                        transitionSub(MiningSubState.SELECTED);
                    } else {
                        log.warn("[MiningSession] Walking stalled - failing");
                        transitionSub(MiningSubState.FAILED);
                        transition(State.FAILED);
                    }
                }
                break;

            // -----------------------------------------------------------------
            case CLEARING_OBSTACLE: {
                Rs2TileObjectModel rockfall = findNearbyRockfall();
                if (rockfall == null) {
                    log.info("[MiningSession] Obstacle cleared - resuming mining");
                    transitionSub(miningEntrySubState(spot));
                    break;
                }
                if (Rs2Player.isAnimating()) {
                    scheduleNextAdaptive(180L, 600L);
                    break;
                }
                if (rockfall.click("Mine")) {
                    applyActionCooldown();
                    scheduleNextAdaptive(350L, 1200L);
                }
                break;
            }

            // -----------------------------------------------------------------
            case SELECTED:
                if (Rs2Inventory.isFull()) {
                    transitionSub(MiningSubState.INV_FULL);
                    break;
                }
                WorldPoint selectPlayer = Rs2Player.getWorldLocation();
                targetVein = selectVein(spot);
                if (targetVein != null) {
                    log.info("[MiningSession] Vein selected at {}", targetVein);
                    transitionSub(MiningSubState.CLICKED);
                    scheduleNextAdaptive(350L, 700L);
                } else if (usesUpperStandMiningRules(spot) && selectPlayer != null
                        && nudgeTowardReachableVein(spot, selectPlayer)) {
                    scheduleNextAdaptive(180L, 600L);
                } else {
                    log.info("[MiningSession] No veins found in area");
                    WorldPoint here = Rs2Player.getWorldLocation();
                    if (usesUpperStandMiningRules(spot) && here != null && spot.contains(here)) {
                        scheduleNextAdaptive(350L, 700L);
                    } else {
                        transitionSub(MiningSubState.REPOSITIONING);
                    }
                }
                if (isStalled(10_000L)) {
                    log.warn("[MiningSession] Vein selection stalled - failing");
                    transitionSub(MiningSubState.FAILED);
                    transition(State.FAILED);
                }
                break;

            // -----------------------------------------------------------------
            case REPOSITIONING:
                log.debug("[MiningSession] No veins found - repositioning");
                if (usesUpperStandMiningRules(spot)) {
                    WorldPoint repoPlayer = Rs2Player.getWorldLocation();
                    if (repoPlayer != null && !spot.contains(repoPlayer)) {
                        WorldPoint chamberAnchor = getFirstAnchor(spot);
                        if (chamberAnchor != null && repoPlayer.distanceTo(chamberAnchor) > 3) {
                            log.info("[MiningSession] Upper reposition: walking toward {} section ({})",
                                    spot, chamberAnchor);
                            walkTowardMiningTarget(chamberAnchor, spot, repoPlayer);
                            scheduleNextAdaptive(180L, 600L);
                            transitionSub(MiningSubState.WALKING);
                            break;
                        }
                    }
                    transitionSub(MiningSubState.SELECTED);
                    scheduleNextAdaptive(350L, 700L);
                    break;
                }
                if (shouldSkipSpotWalk(spot)) {
                    WorldPoint repoPlayer = Rs2Player.getWorldLocation();
                    RankedVein repoRanked = selectBestRankedVein(spot, repoPlayer);
                    Rs2TileObjectModel repoVein = repoRanked != null ? repoRanked.vein : null;
                    if (repoVein != null && repoPlayer != null
                            && repoPlayer.distanceTo(repoVein.getWorldLocation()) > 2) {
                        WorldPoint stand = walkTargetForVein(repoVein.getWorldLocation());
                        if (stand != null) {
                            log.info("[MiningSession] Upper reposition: walking toward scored vein at {} (score={})",
                                    stand, scoreVein(repoVein, spot, repoPlayer));
                            Rs2Walker.walkFastCanvas(stand);
                            scheduleNextAdaptive(180L, 600L);
                        }
                    } else if (repoVein == null) {
                        WorldPoint chamberAnchor = getFirstAnchor(spot);
                        if (chamberAnchor != null && repoPlayer != null
                                && repoPlayer.distanceTo(chamberAnchor) > 3) {
                            log.info("[MiningSession] Upper reposition: walking toward chamber anchor {}", chamberAnchor);
                            Rs2Walker.walkFastCanvas(chamberAnchor);
                            scheduleNextAdaptive(180L, 600L);
                        }
                    }
                    transitionSub(MiningSubState.SELECTED);
                    break;
                }
                WorldPoint repositionTarget = getFirstAnchor(spot);
                if (repositionTarget != null) {
                    walkTowardMiningTarget(repositionTarget, spot, Rs2Player.getWorldLocation());
                    scheduleNextAdaptive(500L, 2000L);
                    transitionSub(MiningSubState.WALKING);
                } else {
                    log.warn("[MiningSession] Spot {} has no reposition target", spot);
                    transitionSub(MiningSubState.FAILED);
                    transition(State.FAILED);
                }
                break;

            // -----------------------------------------------------------------
            case CLICKED: {
                long minClickGap = tickDelayMs(900L, 1400L);
                if (now - lastMineClickMs < minClickGap) {
                    scheduleNext(minClickGap - (now - lastMineClickMs));
                    break;
                }
                // Wait for prior interact/anim to finish before another Mine click
                if (Rs2Player.isInteracting() || Rs2Player.isAnimating(1200)) {
                    scheduleNextAdaptive(350L, 700L);
                    break;
                }

                Rs2TileObjectModel vein = findVeinAt(targetVein);
                if (vein == null) {
                    log.debug("[MiningSession] Vein at {} lost before click - clearing and reselecting", targetVein);
                    markFailed(targetVein, spot);
                    targetVein = null;
                    failedClicks = 0;
                    transitionSub(MiningSubState.SELECTED);
                    break;
                }

                WorldPoint playerLocClick = Rs2Player.getWorldLocation();
                if (!isVeinPresent(targetVein)) {
                    log.debug("[MiningSession] Vein at {} depleted before click - reselecting", targetVein);
                    markFailed(targetVein, spot);
                    targetVein = null;
                    failedClicks = 0;
                    transitionSub(MiningSubState.SELECTED);
                    break;
                }
                if (isVeinBarred(spot, playerLocClick, targetVein)) {
                    log.debug("[MiningSession] Vein at {} barred (rockfall tile/path) - reselecting", targetVein);
                    markFailed(targetVein, spot);
                    targetVein = null;
                    failedClicks = 0;
                    transitionSub(MiningSubState.SELECTED);
                    break;
                }
                int distToVein = playerLocClick != null ? playerLocClick.distanceTo(targetVein) : Integer.MAX_VALUE;
                if (distToVein > VEIN_CLICK_MAX_DISTANCE) {
                    if (usesUpperStandMiningRules(spot)) {
                        log.debug("[MiningSession] Upper chamber vein at {} too far to click (dist={}) — reselecting",
                                targetVein, distToVein);
                        markFailed(targetVein, spot);
                        targetVein = null;
                        failedClicks = 0;
                        transitionSub(MiningSubState.SELECTED);
                        break;
                    }
                    if (!Rs2Camera.isTileOnScreen(vein.getLocalLocation())) {
                        Rs2Camera.turnTo(vein);
                        scheduleNextAdaptive(120L, 400L);
                        break;
                    }
                    if (!preferWalkBeforeMineClick(spot, distToVein)) {
                        if (attemptMineClick(vein, now, "direct")) {
                            break;
                        }
                        log.debug("[MiningSession] Direct Mine failed at dist {} - will walk or retry", distToVein);
                    }
                    WorldPoint stand = walkTargetForVein(targetVein);
                    if (stand != null) {
                        log.debug("[MiningSession] Walking to stand tile {} (dist={})", stand, distToVein);
                        Rs2Walker.walkFastCanvas(stand);
                    }
                    scheduleNextAdaptive(180L, 600L);
                    break;
                }

                if (!Rs2Camera.isTileOnScreen(vein.getLocalLocation())) {
                    log.debug("[MiningSession] Vein off-screen - turning camera");
                    Rs2Camera.turnTo(vein);
                    scheduleNextAdaptive(120L, 400L);
                    break;
                }

                if (attemptMineClick(vein, now, "adjacent")) {
                    break;
                } else {
                    failedClicks++;
                    int maxClickFails = spot.isUpstairs() ? 1 : 3;
                    log.debug("[MiningSession] Click failed ({}/{})", failedClicks, maxClickFails);
                    if (failedClicks > maxClickFails) {
                        log.warn("[MiningSession] Too many failed clicks on vein at {} - picking a new one", targetVein);
                        markFailed(targetVein, spot);
                        targetVein = null;
                        failedClicks = 0;
                        transitionSub(MiningSubState.SELECTED);
                    } else {
                        scheduleNextAdaptive(250L, 800L);
                    }
                }
                break;
            }

            // -----------------------------------------------------------------
            case ARRIVING: {
                // Wait for player to stop moving, then check if mining started
                if (Rs2Player.isMoving()) {
                    scheduleNextAdaptive(180L, 600L);
                    break;
                }
                // Player stopped moving - check animation
                if (Rs2Player.isAnimating()) {
                    transitionSub(MiningSubState.MINING);
                    break;
                }
                // NEW: a rockfall can spawn while we're walking. If one now blocks the
                // path, bail out immediately instead of timing out in CONFIRMED.
                if (targetVein != null && isVeinBarred(spot, Rs2Player.getWorldLocation(), targetVein)) {
                    log.debug("[MiningSession] Barred path after arriving - reselecting");
                    markFailed(targetVein, spot);
                    transitionSub(MiningSubState.SELECTED);
                    break;
                }
                // Not animating yet - give it a moment before declaring CONFIRMED
                if (subElapsed() > tickDelayMs(700L, 2000L)) {
                    log.debug("[MiningSession] Arrived but no animation yet - moving to CONFIRMED");
                    transitionSub(MiningSubState.CONFIRMED);
                    scheduleNextAdaptive(180L, 600L);
                }
                break;
            }

            // -----------------------------------------------------------------
            case CONFIRMED:
                if (Rs2Player.isMoving()) {
                    scheduleNextAdaptive(300L, 600L);
                    break;
                }
                if (Rs2Player.isInteracting() || Rs2Player.isAnimating(1200)) {
                    transitionSub(MiningSubState.MINING);
                    scheduleNextAdaptive(400L, 800L);
                    break;
                }
                if (targetVein != null && !isVeinPresent(targetVein)) {
                    log.debug("[MiningSession] Target vein depleted during CONFIRMED - next vein");
                    transitionSub(MiningSubState.DEPLETED);
                    break;
                }
                long xpAfterClick = Math.max(lastXpResolved, globalLastXpTime.get());
                if (xpAfterClick > lastMineClickMs) {
                    transitionSub(MiningSubState.MINING);
                    scheduleNextAdaptive(400L, 800L);
                    break;
                }
                if (targetVein != null && subElapsed() > 3_000L) {
                    if (isVeinBarred(spot, Rs2Player.getWorldLocation(), targetVein)) {
                        log.debug("[MiningSession] Path barred during CONFIRMED - reselecting");
                        markFailed(targetVein, spot);
                        transitionSub(MiningSubState.DEPLETED);
                        break;
                    }
                }
                if (subElapsed() > tickDelayMs(CONFIRMED_FAIL_MS_FAST, CONFIRMED_FAIL_MS_HUMAN)) {
                    log.debug("[MiningSession] No mining started within {}ms - marking vein failed and reselecting",
                            tickDelayMs(CONFIRMED_FAIL_MS_FAST, CONFIRMED_FAIL_MS_HUMAN));
                    markFailed(targetVein, spot);
                    transitionSub(MiningSubState.DEPLETED);
                    break;
                }
                scheduleNextAdaptive(400L, 800L);
                break;

            // -----------------------------------------------------------------
            case MINING: {
                if (Rs2Inventory.isFull()) {
                    transitionSub(MiningSubState.INV_FULL);
                    break;
                }
                if (maxSack > 0 && sackCount >= maxSack) {
                    transitionSub(MiningSubState.SACK_FULL);
                    break;
                }

                // If player is moving, they haven't started mining yet - don't count
                // this time toward stuck detection. Transition back to ARRIVING.
                if (Rs2Player.isMoving()) {
                    log.debug("[MiningSession] Player moving during MINING - returning to ARRIVING");
                    transitionSub(MiningSubState.ARRIVING);
                    break;
                }

                // Check if player is too far from the vein (>2 tiles)
                if (targetVein != null && Rs2Player.getWorldLocation().distanceTo(targetVein) > 2) {
                    log.debug("[MiningSession] Player too far from vein - reselecting");
                    transitionSub(shouldSkipSpotWalk(spot) ? MiningSubState.SELECTED : MiningSubState.WALKING);
                    break;
                }

                if (Rs2Player.isInteracting() || Rs2Player.isAnimating(1200)) {
                    scheduleNextAdaptive(400L, 800L);
                    break;
                }

                boolean veinPresent = isVeinPresent(targetVein);
                long lastActivityResolved = Math.max(
                        Math.max(lastXpResolved, globalLastXpTime.get()),
                        subStateEnteredMs);

                // Pay-dirt XP can arrive slightly after the mining anim ends; don't re-click yet.
                long xpGraceMs = tickDelayMs(1200L, 2000L);
                if (lastActivityResolved <= lastMineClickMs && subElapsed() < xpGraceMs) {
                    scheduleNextAdaptive(400L, 800L);
                    break;
                }

                boolean isStuck = (now - lastActivityResolved > 10_000L)
                        && (subElapsed() > 3_000L);

                if (!veinPresent || isStuck) {
                    if (isStuck && veinPresent) {
                        log.debug("[MiningSession] Stuck on vein (no activity 10s) - reselecting");
                        markFailed(targetVein, spot);
                    } else {
                        log.debug("[MiningSession] Vein depleted or inactive at {} - next vein", targetVein);
                    }
                    transitionSub(MiningSubState.DEPLETED);
                    scheduleNextAdaptive(450L, 900L);
                    break;
                }

                scheduleNextAdaptive(500L, 1000L);
                break;
            }

            // -----------------------------------------------------------------
            case DEPLETED:
                if (targetVein != null) {
                    markFailed(targetVein, spot);
                }
                targetVein = null;
                scheduleNextAdaptive(500L, 1000L);
                transitionSub(MiningSubState.SELECTED);
                break;

            // -----------------------------------------------------------------
            case INV_FULL:
            case SACK_FULL:
                transition(State.COMPLETE);
                break;

            default:
                break;
        }
    }

    // -----------------------------------------------------------------------
    // Sub-state transition
    // -----------------------------------------------------------------------

    private void transitionSub(MiningSubState next) {
        if (subState == next && next != MiningSubState.IDLE) return;
        log.debug("[MiningSession] Sub-state: {} → {}", subState, next);
        subState          = next;
        subStateEnteredMs = System.currentTimeMillis();
        scheduleSubStateEntryDelay();

        switch (next) {
            case TRANSITIONING_FLOOR: updateStatus("Climbing Ladder");          break;
            case WALKING:             updateStatus("Walking to Veins");          break;
            case CLEARING_OBSTACLE:   updateStatus("Clearing Rockfall");         break;
            case SELECTED:            updateStatus("Selecting Vein");            break;
            case REPOSITIONING:       updateStatus("Repositioning");             break;
            case CLICKED:             updateStatus("Clicking Vein");             break;
            case ARRIVING:            updateStatus("Walking to Vein");           break;
            case CONFIRMED:           updateStatus("Waiting for Mining Start");  break;
            case MINING:              updateStatus("Mining Vein");               break;
            case DEPLETED:            updateStatus("Vein Depleted");             break;
            case INV_FULL:            updateStatus("Inventory Full");            break;
            case SACK_FULL:           updateStatus("Sack Full");                 break;
            default:                  updateStatus(formatEnum(next.name()));     break;
        }
    }

    // -----------------------------------------------------------------------
    // Rockfall memory
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // Failure blacklist helpers
    // -----------------------------------------------------------------------

    private void cleanupRecentFailures() {
        long now = System.currentTimeMillis();
        recentFailures.entrySet().removeIf(e -> now - e.getValue() > FAILURE_BLACKLIST_UPPER_MS);
    }

    private long failureBlacklistMs(MLMMiningSpot spot) {
        return spot != null && spot.isUpstairs() ? FAILURE_BLACKLIST_UPPER_MS : FAILURE_BLACKLIST_MS;
    }

    private boolean isRecentlyFailed(WorldPoint wp, MLMMiningSpot spot) {
        if (wp == null) return false;
        Long ts = recentFailures.get(wp);
        if (ts == null) return false;
        if (System.currentTimeMillis() - ts > failureBlacklistMs(spot)) {
            recentFailures.remove(wp);
            return false;
        }
        return true;
    }

    private void markFailed(WorldPoint wp, MLMMiningSpot spot) {
        if (wp != null) {
            recentFailures.put(wp, System.currentTimeMillis());
        }
    }

    private static boolean isClientThreadInvokeInterrupted(Throwable t) {
        while (t != null) {
            if (t instanceof InterruptedException) {
                return true;
            }
            if (t instanceof RuntimeException && t.getMessage() != null
                    && t.getMessage().contains("Interrupted waiting for client thread")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private boolean isVeinCandidate(Rs2TileObjectModel vein, MLMMiningSpot spot, WorldPoint playerLoc) {
        if (vein == null || spot == null || playerLoc == null) return false;
        if (scoreVein(vein, spot, playerLoc) >= SCORE_OUT_OF_RANGE) return false;
        return isReadyToClick(vein.getWorldLocation(), playerLoc, spot);
    }

    private boolean isReadyToClick(WorldPoint veinLoc, WorldPoint playerLoc, MLMMiningSpot spot) {
        if (veinLoc == null || playerLoc == null || spot == null) return false;
        if (!isVeinInSelectableZone(spot, veinLoc)) {
            return false;
        }
        if (usesUpperStandMiningRules(spot)) {
            if (playerLoc.distanceTo(veinLoc) > VEIN_CLICK_MAX_DISTANCE
                    || isVeinBarred(spot, playerLoc, veinLoc)) {
                return false;
            }
            // If player is adjacent (within click range), allow a direct click even if the vein
            // isn't reachable from the preferred stand tiles. This lets a player standing next
            // to a vein simply mine it instead of forcing a walk to a stand tile.
            if (playerLoc.distanceTo(veinLoc) <= VEIN_CLICK_MAX_DISTANCE) {
                return true;
            }
            return isReachableFromPreferredStand(spot, veinLoc);
        }
        if (playerLoc.distanceTo(veinLoc) > VEIN_CLICK_MAX_DISTANCE) {
            return false;
        }
        return !isVeinBarred(spot, playerLoc, veinLoc);
    }

    /** Upper chambers: vein must be clickable from at least one preferred stand (dist + no rockfall path). */
    private boolean isReachableFromPreferredStand(MLMMiningSpot spot, WorldPoint veinLoc) {
        for (WorldPoint stand : spot.getPreferredOperatingTiles()) {
            if (stand != null
                    && stand.distanceTo(veinLoc) <= VEIN_CLICK_MAX_DISTANCE
                    && !isVeinBarred(spot, stand, veinLoc)) {
                return true;
            }
        }
        return false;
    }

    /**
     * When human-like: ~35% walk to stand first; otherwise try Mine on the vein (client paths if needed).
     */
    private boolean preferWalkBeforeMineClick(MLMMiningSpot spot, int distToVein) {
        if (spot != null && usesUpperStandMiningRules(spot)) {
            return false;
        }
        if (distToVein <= 2) {
            return false;
        }
        int maxDirectDist = spot != null && spot.isUpstairs()
                ? UPPER_VEIN_MAX_SELECTION_DISTANCE
                : LOWER_VEIN_MAX_SELECTION_DISTANCE;
        if (distToVein > maxDirectDist) {
            return true;
        }
        if (!isHumanLikeEnabled()) {
            return false;
        }
        return Rs2Random.between(0, 100) < 35;
    }

    /**
     * Single Mine click path — updates timestamps and sub-state. Returns true if click was sent.
     */
    private boolean attemptMineClick(Rs2TileObjectModel vein, long now, String mode) {
        if (vein == null || !vein.click("Mine")) {
            return false;
        }
        lastMineClickMs  = now;
        applyActionCooldown();
        failedClicks     = 0;
        lastXpTimestamp  = now;
        sessionLockUntil = now + 5000L;
        log.debug("[MiningSession] Mine click sent ({})", mode);
        if (Rs2Player.isMoving()) {
            transitionSub(MiningSubState.ARRIVING);
            scheduleNextAdaptive(250L, 800L);
        } else {
            transitionSub(MiningSubState.CONFIRMED);
            scheduleNextAdaptive(350L, 1200L);
        }
        return true;
    }

    private WorldPoint walkTargetForVein(WorldPoint veinLoc) {
        if (veinLoc == null) return null;
        WorldPoint stand = Rs2Tile.getNearestWalkableTile(veinLoc);
        return stand != null ? stand : veinLoc;
    }

    // -----------------------------------------------------------------------
    // Depleted vein helper
    // -----------------------------------------------------------------------

    /**
     * Active ore veins only - uses ObjectIDs (never calls {@code getName()} on the executor thread;
     * that blocks the client thread and can throw "Interrupted waiting for client thread").
     * Depleted variants use separate MOTHERLODE_DEPLETED_* IDs and are excluded via {@link #isOreVein}.
     */
    private boolean isActiveVein(Rs2TileObjectModel o) {
        return o != null && isOreVein(o.getId());
    }

    /**
     * Scans the current scene for rockfalls and adds all occupied tiles to the
     * session-long memory. Called once per tick so the remembered set stays current.
     */
    /**
     * Accumulates rockfall footprint tiles for the session. Tiles are never removed when rockfalls
     * despawn (another player mining them), avoiding re-selection of veins behind a respawning wall.
     */
    private void updateRememberedRockfalls() {
        List<Rs2TileObjectModel> current = tileCache.query()
                .where(o -> isRockfall(o.getId()))
                .toList();

        for (Rs2TileObjectModel rock : current) {
            WorldPoint base = rock.getWorldLocation();
            for (int dx = 0; dx < 2; dx++) {
                for (int dy = 0; dy < 2; dy++) {
                    rememberedRockfalls.add(new WorldPoint(
                            base.getX() + dx,
                            base.getY() + dy,
                            base.getPlane()));
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Vein selection
    // -----------------------------------------------------------------------

    /**
     * Returns true if {@code id} is one of the active (non-depleted) ore-vein object IDs.
     */
    private boolean isOreVein(int id) {
        for (int veinId : ORE_VEIN_IDS) {
            if (id == veinId) return true;
        }
        return false;
    }

    /** Returns true if {@code id} is one of the rockfall IDs. */
    private boolean isRockfall(int id) {
        for (int rockId : ROCKFALL_IDS) {
            if (id == rockId) return true;
        }
        return false;
    }

    /**
     * Helper to safely get the first anchor point from a spot.
     */
    private WorldPoint getFirstAnchor(MLMMiningSpot spot) {
        if (spot == null) return null;
        List<WorldPoint> points = spot.getWorldPoint();
        if (points == null || points.isEmpty()) return null;
        return points.get(0);
    }

    /**
     * Skip walking to anchors when already inside the spot {@link MLMMiningSpot#contains(WorldPoint)}.
     */
    private boolean shouldSkipSpotWalk(MLMMiningSpot spot) {
        if (spot == null || !spot.isUpstairs() || !isUpperFloor()) {
            return false;
        }
        WorldPoint here = Rs2Player.getWorldLocation();
        return here != null && spot.contains(here);
    }

    private MiningSubState miningEntrySubState(MLMMiningSpot spot) {
        if (spot != null && spot.isUpstairs() && isUpperFloor()) {
            WorldPoint here = Rs2Player.getWorldLocation();
            if (here == null || !spot.contains(here)) {
                return MiningSubState.WALKING;
            }
            return MiningSubState.SELECTED;
        }
        return MiningSubState.WALKING;
    }

    /** Session policy: upper spots with preferred stand hints use stand-and-click mining. */
    private boolean usesUpperStandMiningRules(MLMMiningSpot spot) {
        return spot != null && spot.isUpstairs() && !spot.getPreferredOperatingTiles().isEmpty();
    }

    /** Vein plus precomputed effective score (lower = more desirable). */
    private static final class RankedVein {
        private final Rs2TileObjectModel vein;
        private final int effectiveScore;

        private RankedVein(Rs2TileObjectModel vein, int effectiveScore) {
            this.vein = vein;
            this.effectiveScore = effectiveScore;
        }
    }

    /**
     * Lower base score = more desirable. Barred/out-of-range veins return {@link #SCORE_OUT_OF_RANGE};
     * recently-failed veins stay in the pool with {@link #SCORE_FAILED_PENALTY}.
     */
    private int scoreVein(Rs2TileObjectModel vein, MLMMiningSpot spot, WorldPoint playerLoc) {
        if (vein == null || spot == null || playerLoc == null) {
            return SCORE_OUT_OF_RANGE;
        }
        WorldPoint vp = vein.getWorldLocation();
        if (!isVeinInSelectableZone(spot, vp) || !isActiveVein(vein)) {
            return SCORE_OUT_OF_RANGE;
        }
        int maxDist = usesUpperStandMiningRules(spot)
                ? UPPER_CHAMBER_VEIN_MAX_DISTANCE
                : (spot.isUpstairs() ? UPPER_VEIN_MAX_SELECTION_DISTANCE : LOWER_VEIN_MAX_SELECTION_DISTANCE);
        int dist;
        if (usesUpperStandMiningRules(spot)) {
            // For upper chambers prefer veins that are close to preferred operating tiles (stand positions)
            // rather than raw player distance. This biases selection toward veins that are easier to operate
            // from the configured stand hints and avoids picking a farther vein that happens to be nearer
            // to the player but less convenient from operating positions.
            Set<WorldPoint> stands = spot.getPreferredOperatingTiles();
            int minStandDist = Integer.MAX_VALUE;
            if (stands != null && !stands.isEmpty()) {
                for (WorldPoint s : stands) {
                    if (s == null) continue;
                    minStandDist = Math.min(minStandDist, s.distanceTo(vp));
                }
            }
            if (minStandDist == Integer.MAX_VALUE) {
                dist = playerLoc.distanceTo(vp);
            } else {
                dist = minStandDist;
            }
        } else {
            dist = playerLoc.distanceTo(vp);
        }
        if (dist > maxDist) {
            return SCORE_OUT_OF_RANGE;
        }
        if (!spot.isUpstairs()) {
            WorldPoint anchor = getFirstAnchor(spot);
            if (anchor != null && vp.distanceTo(anchor) > 12) {
                return SCORE_OUT_OF_RANGE;
            }
        }
        if (isVeinBarred(spot, playerLoc, vp)) {
            return SCORE_OUT_OF_RANGE;
        }
        int score = dist;
        if (isRecentlyFailed(vp, spot)) {
            score += SCORE_FAILED_PENALTY;
        }
        return score;
    }

    /**
     * True when the vein or any direct path to it crosses a permanent blocked tile (e.g. rockfall lane)
     * or session-remembered rockfall cells.
     */
    private boolean isVeinBarred(MLMMiningSpot spot, WorldPoint playerLoc, WorldPoint veinLoc) {
        if (spot == null || playerLoc == null || veinLoc == null) {
            return true;
        }
        if (isPermanentRockfallBarrier(spot, veinLoc)) {
            return true;
        }
        return pathCrossesBarriers(spot, playerLoc, veinLoc);
    }

    private static Set<WorldPoint> permanentRockfallBarriersFor(MLMMiningSpot spot) {
        return IrkedMLMMapConstants.permanentRockfallBarriersFor(spot);
    }

    private static boolean isPermanentRockfallBarrier(MLMMiningSpot spot, WorldPoint point) {
        return point != null && permanentRockfallBarriersFor(spot).contains(point);
    }

    /**
     * True when the player is within click range of an active ore vein in the configured spot.
     */
    public boolean isPlayerNextToMineableVein(MLMMiningSpot spot, WorldPoint playerLoc) {
        if (playerLoc == null || spot == null) {
            return false;
        }
        return tileCache.query()
                .where(o -> isOreVein(o.getId()) && isActiveVein(o))
                .toList()
                .stream()
                .anyMatch(o -> {
                    WorldPoint veinLoc = o.getWorldLocation();
                    if (playerLoc.distanceTo(veinLoc) > VEIN_CLICK_MAX_DISTANCE) {
                        return false;
                    }
                    return playerLoc.distanceTo(veinLoc) <= VEIN_CLICK_MAX_DISTANCE
                            && isVeinInSelectableZone(spot, veinLoc)
                            && !isVeinBarred(spot, playerLoc, veinLoc);
                });
    }

    /**
     * Whether pickaxe special may fire: actively mining and adjacent to session target or any mineable vein.
     */
    public boolean canAttemptPickaxeSpecial(MLMMiningSpot spot, WorldPoint playerLoc) {
        if (playerLoc == null || spot == null || subState != MiningSubState.MINING) {
            return false;
        }
        if (targetVein != null && playerLoc.distanceTo(targetVein) <= VEIN_CLICK_MAX_DISTANCE) {
            return true;
        }
        return isPlayerNextToMineableVein(spot, playerLoc);
    }

    private int clusterNeighborBonus(WorldPoint vp, List<Rs2TileObjectModel> allVeins) {
        int neighbors = 0;
        for (Rs2TileObjectModel other : allVeins) {
            if (other == null || !isActiveVein(other)) continue;
            WorldPoint op = other.getWorldLocation();
            if (op.equals(vp)) continue;
            if (vp.distanceTo(op) <= 3) {
                neighbors++;
            }
        }
        int bonus = Math.min(neighbors * SCORE_CLUSTER_BONUS_PER_NEIGHBOR, SCORE_CLUSTER_BONUS_CAP);
        return bonus;
    }

    /**
     * Effective sort key: {@link #scoreVein} minus cluster bonus. Values {@code >= SCORE_OUT_OF_RANGE} are not rankable.
     */
    private int effectiveVeinScore(Rs2TileObjectModel vein, MLMMiningSpot spot, WorldPoint playerLoc,
                                   List<Rs2TileObjectModel> allVeins) {
        int score = scoreVein(vein, spot, playerLoc);
        if (score >= SCORE_OUT_OF_RANGE) {
            return score;
        }
        if (usesUpperStandMiningRules(spot)) {
            return score;
        }
        return score - clusterNeighborBonus(vein.getWorldLocation(), allVeins);
    }

    /**
     * Queries active veins in the spot and returns them sorted by effective score (ascending).
     */
    private List<RankedVein> rankVeinsByScore(MLMMiningSpot spot, WorldPoint playerLoc,
                                              List<Rs2TileObjectModel> allVeins) {
        if (spot == null || playerLoc == null || allVeins == null || allVeins.isEmpty()) {
            return Collections.emptyList();
        }
        List<RankedVein> ranked = new ArrayList<>();
        for (Rs2TileObjectModel vein : allVeins) {
            int effective = effectiveVeinScore(vein, spot, playerLoc, allVeins);
            if (effective < SCORE_OUT_OF_RANGE) {
                ranked.add(new RankedVein(vein, effective));
            }
        }
        sortRankedVeinsByScore(ranked);
        return ranked;
    }

    private static void sortRankedVeinsByScore(List<RankedVein> ranked) {
        for (int i = 0; i < ranked.size(); i++) {
            int bestIdx = i;
            int bestScore = ranked.get(i).effectiveScore;
            for (int j = i + 1; j < ranked.size(); j++) {
                int score = ranked.get(j).effectiveScore;
                if (score < bestScore) {
                    bestScore = score;
                    bestIdx = j;
                }
            }
            if (bestIdx != i) {
                RankedVein swap = ranked.get(i);
                ranked.set(i, ranked.get(bestIdx));
                ranked.set(bestIdx, swap);
            }
        }
    }

    /** Top scored vein for walk/reposition paths, or null when none are in range. */
    private RankedVein selectBestRankedVein(MLMMiningSpot spot, WorldPoint playerLoc) {
        List<RankedVein> ranked = rankVeinsByScore(spot, playerLoc, queryVeinsInSpot(spot));
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    private List<Rs2TileObjectModel> queryVeinsInSpot(MLMMiningSpot spot) {
        boolean upperChamber = usesUpperStandMiningRules(spot);
        var query = tileCache.query()
                .where(o -> isOreVein(o.getId())
                        && isActiveVein(o)
                        && isVeinInSelectableZone(spot, o.getWorldLocation()));
        if (!upperChamber) {
            query = query.where(o -> Rs2Tile.areSurroundingTilesWalkable(o.getWorldLocation(), 1, 1));
        }
        return query.toList();
    }

    /** Veins must be inside spot {@link WorldArea} union (safe/selectable zones). */
    private boolean isVeinInSelectableZone(MLMMiningSpot spot, WorldPoint veinLoc) {
        if (spot == null || veinLoc == null) {
            return false;
        }
        return spot.contains(veinLoc);
    }

    /** Session rockfall tiles for map overlay (read-only). */
    public Set<WorldPoint> getRememberedRockfallsView() {
        return Collections.unmodifiableSet(rememberedRockfalls);
    }

    /**
     * Selects the best ore vein in the mining spot by effective score ({@link #rankVeinsByScore}).
     * Human-like mode may take 2nd/3rd ranked picks; anti-crash may skip a crowded top pick on lower floor.
     */
    private WorldPoint selectVein(MLMMiningSpot spot) {
        WorldPoint playerLoc = Rs2Player.getWorldLocation();
        if (playerLoc == null) {
            return null;
        }

        List<Rs2TileObjectModel> allVeins = queryVeinsInSpot(spot);
        if (allVeins.isEmpty()) {
            log.info("[MiningSession] No active veins found in spot {}", spot);
            return null;
        }

        List<RankedVein> ranked = rankVeinsByScore(spot, playerLoc, allVeins);
        if (ranked.isEmpty()) {
            log.warn("[MiningSession] All {} veins filtered for spot {} (player={}, inSection={})",
                    allVeins.size(), spot, playerLoc, spot.contains(playerLoc));
            return null;
        }

        List<RankedVein> pickPool = ranked;
        if (usesUpperStandMiningRules(spot)) {
            List<RankedVein> clickReady = new ArrayList<>();
            for (RankedVein r : ranked) {
                WorldPoint vp = r.vein.getWorldLocation();
                if (isReadyToClick(vp, playerLoc, spot)) {
                    clickReady.add(r);
                }
            }
            if (clickReady.isEmpty()) {
                log.info("[MiningSession] No click-ready veins in {} (best dist={}, pool={})",
                        spot,
                        playerLoc.distanceTo(ranked.get(0).vein.getWorldLocation()),
                        ranked.size());
                return null;
            }
            pickPool = clickReady;
        }

        final WorldPoint finalPlayer = playerLoc;
        int num = pickPool.size();
        Rs2TileObjectModel chosen = pickPool.get(0).vein;
        if (isHumanLikeEnabled() && num > 1) {
            java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
            double r = rng.nextDouble();
            if (r < 0.15 && num >= 3) {
                chosen = pickPool.get(2).vein;
            } else if (r < 0.35) {
                chosen = pickPool.get(1).vein;
            } else if (r < 0.42) {
                int d0 = finalPlayer.distanceTo(chosen.getWorldLocation());
                int d1 = finalPlayer.distanceTo(pickPool.get(1).vein.getWorldLocation());
                if (d1 <= d0 + 2) {
                    chosen = pickPool.get(1).vein;
                }
            }
        }

        final Rs2TileObjectModel finalChosen = chosen;

        // Anti-crash: zero out crowded veins on lower floor
        if (config.useAntiCrash() && !spot.isUpstairs()) {
            var playerCache = Microbot.getRs2PlayerCache();
            if (playerCache != null) {
                int playersNearby = playerCache.query()
                        .where(p -> {
                            String pName = p.getName();
                            return pName != null
                                    && !pName.equalsIgnoreCase(cachedLocalPlayerName)
                                    && p.getWorldLocation().distanceTo(finalChosen.getWorldLocation()) <= 2;
                        })
                        .count();
                if (playersNearby > 0) {
                    log.debug("[MiningSession] Chosen vein at {} crowded - trying next candidate",
                            finalChosen.getWorldLocation());
                    // Pick next candidate if available, otherwise keep it
                    if (num > 1) {
                        chosen = pickPool.get(1).vein;
                    }
                }
            }
        }

        WorldPoint chosenPoint = chosen.getWorldLocation();
        int chosenScore = effectiveVeinScore(chosen, spot, finalPlayer, allVeins);
        boolean ready = isReadyToClick(chosenPoint, finalPlayer, spot);
        log.info("[MiningSession] Score-selected vein at {} (dist={}, score={}, readyToClick={}, pool={})",
                chosenPoint,
                finalPlayer.distanceTo(chosenPoint),
                chosenScore,
                ready,
                num);
        return chosenPoint;
    }

    /**
     * Steps to another operating tile in the chamber (not a vein tile) to get within click range of wall veins.
     */
    private boolean nudgeTowardReachableVein(MLMMiningSpot spot, WorldPoint player) {
        if (spot == null || player == null || !usesUpperStandMiningRules(spot)) {
            return false;
        }
        List<RankedVein> ranked = rankVeinsByScore(spot, player, queryVeinsInSpot(spot));
        if (ranked.isEmpty()) {
            return false;
        }
        WorldPoint vein = null;
        for (RankedVein r : ranked) {
            WorldPoint vp = r.vein.getWorldLocation();
            if (isReachableFromPreferredStand(spot, vp)) {
                vein = vp;
                break;
            }
        }
        if (vein == null || isReadyToClick(vein, player, spot)) {
            return false;
        }
        WorldPoint stand = nearestOperatingTileTowardVein(spot, player, vein);
        if (stand == null || player.equals(stand) || player.distanceTo(stand) > 4) {
            return false;
        }
        log.info("[MiningSession] Stepping to operating tile {} (vein {} dist={})",
                stand, vein, player.distanceTo(vein));
        Rs2Walker.walkFastCanvas(stand);
        return true;
    }

    private WorldPoint nearestOperatingTileTowardVein(MLMMiningSpot spot, WorldPoint player, WorldPoint vein) {
        Set<WorldPoint> stands = spot.getPreferredOperatingTiles();
        if (stands.isEmpty()) {
            return null;
        }
        WorldPoint best = null;
        int bestStep = Integer.MAX_VALUE;
        int playerVeinDist = player.distanceTo(vein);
        for (WorldPoint tile : stands) {
            if (tile == null || tile.equals(player)) {
                continue;
            }
            int step = player.distanceTo(tile);
            if (step > 4) {
                continue;
            }
            if (tile.distanceTo(vein) < playerVeinDist && step < bestStep) {
                bestStep = step;
                best = tile;
            }
        }
        return best;
    }

    private boolean pathCrossesBarriers(MLMMiningSpot spot, WorldPoint from, WorldPoint to) {
        if (from == null || to == null) {
            return true;
        }
        for (WorldPoint blocked : permanentRockfallBarriersFor(spot)) {
            if (pathCrossesTile(from, to, blocked)) {
                return true;
            }
        }
        if (spot != null && spot.isUpstairs()) {
            for (WorldPoint rock : IrkedMLMMapConstants.SHARED_UPPER_ROCKFALL_TILES) {
                if (pathCrossesTile(from, to, rock)) {
                    return true;
                }
            }
            for (WorldPoint approach : IrkedMLMMapConstants.SHARED_UPPER_ROCKFALL_APPROACH_TILES) {
                if (pathCrossesTile(from, to, approach)) {
                    return true;
                }
            }
        }
        return pathCrossesRockfallMemory(from, to);
    }

    private boolean pathCrossesRockfallMemory(WorldPoint from, WorldPoint to) {
        if (from == null || to == null || rememberedRockfalls.isEmpty()) {
            return false;
        }
        for (WorldPoint rock : rememberedRockfalls) {
            if (pathCrossesTile(from, to, rock)) {
                return true;
            }
        }
        return false;
    }

    /** Bresenham: does the line from {@code from} to {@code to} pass through {@code probe}? */
    private boolean pathCrossesTile(WorldPoint from, WorldPoint to, WorldPoint probe) {
        if (from == null || to == null || probe == null) {
            return false;
        }
        int dx = Math.abs(to.getX() - from.getX());
        int dz = Math.abs(to.getY() - from.getY());
        int sx = from.getX() < to.getX() ? 1 : -1;
        int sz = from.getY() < to.getY() ? 1 : -1;
        int err = dx - dz;
        int x = from.getX();
        int z = from.getY();

        while (true) {
            if (probe.getX() == x && probe.getY() == z && probe.getPlane() == from.getPlane()) {
                return true;
            }
            if (x == to.getX() && z == to.getY()) {
                break;
            }
            int e2 = 2 * err;
            if (e2 > -dz) {
                err -= dz;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                z += sz;
            }
        }
        return false;
    }

    /** Finds a nearby rockfall object. */
    private Rs2TileObjectModel findNearbyRockfall() {
        return tileCache.query()
                .where(o -> isRockfall(o.getId()) && o.getWorldLocation().distanceTo(Rs2Player.getWorldLocation()) <= 3)
                .nearest();
    }

    /**
     * Finds the vein object nearest to {@code wp} (within 2 tiles) by ore-vein ID.
     * Returns null if the vein is depleted.
     */
    private Rs2TileObjectModel findVeinAt(WorldPoint wp) {
        if (wp == null) return null;
        Rs2TileObjectModel vein = tileCache.query()
                .where(o -> isOreVein(o.getId())
                        && isActiveVein(o)
                        && o.getWorldLocation().equals(wp))
                .nearest();
        return vein != null && isActiveVein(vein) ? vein : null;
    }

    /**
     * Returns {@code true} if any active (non-depleted) ore vein is present at
     * the given WorldPoint. MLM respawns veins (sometimes with different IDs
     * on the same tile), so we check for any of our active ore-vein IDs.
     */
    private boolean isVeinPresent(WorldPoint wp) {
        if (wp == null) return false;
        Rs2TileObjectModel vein = tileCache.query()
                .where(o -> isOreVein(o.getId()) && o.getWorldLocation().equals(wp))
                .nearest();
        return vein != null && isActiveVein(vein);
    }

    /** Returns {@code true} if the player is inside the mining spot area or near its anchors (correct floor). */
    private boolean isNearSpot(MLMMiningSpot spot) {
        if (spot == null) return false;
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null) return false;
        if (isPlayerInSpotArea(spot, here)) {
            return true;
        }
        if (spot.isUpstairs() && !isUpperFloor()) {
            return false;
        }
        if (spot.isDownstairs() && isUpperFloor()) {
            return false;
        }
        int anchorRadius = spot.isUpstairs() ? 12 : 10;
        return isNearAnchor(spot, here, anchorRadius);
    }

    /** Area check plus MLM floor height (upper/lower share x,y on plane 0). */
    private boolean isPlayerInSpotArea(MLMMiningSpot spot, WorldPoint here) {
        if (spot == null || here == null) {
            return false;
        }
        if (!spot.contains(here)) {
            return false;
        }
        if (spot.isUpstairs()) {
            return isUpperFloor();
        }
        if (spot.isDownstairs()) {
            return !isUpperFloor();
        }
        return true;
    }

    private boolean isNearAnchor(MLMMiningSpot spot, WorldPoint here, int maxDist) {
        if (spot == null || here == null) return false;
        List<WorldPoint> anchors = spot.getWorldPoint();
        if (anchors == null) return false;
        for (WorldPoint anchor : anchors) {
            if (anchor != null && here.distanceTo(anchor) <= maxDist) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walk toward a mining target: canvas for short hops, web-walk when far (including lower tunnels).
     * Clears stale web-walk targets when already at the goal (avoids pathfinder-still-null stall).
     */
    private void walkTowardMiningTarget(WorldPoint target, MLMMiningSpot spot, WorldPoint playerLoc) {
        if (target == null || playerLoc == null) return;
        int distance = playerLoc.distanceTo(target);
        if (distance <= 2) {
            Rs2Walker.setTarget(null);
            return;
        }
        Rs2Walker.setTarget(null);

        int webWalkThreshold = (spot != null && spot.isDownstairs())
                ? LOWER_WEB_WALK_MIN_DISTANCE
                : UPPER_WEB_WALK_MIN_DISTANCE;

        if (distance > webWalkThreshold) {
            log.debug("[MiningSession] Web-walk to {} ({} tiles, threshold={})",
                    target, distance, webWalkThreshold);
            Rs2Walker.walkTo(target);
        } else {
            log.debug("[MiningSession] walkFastCanvas to {} ({} tiles)", target, distance);
            Rs2Walker.walkFastCanvas(target);
        }
    }

    /**
     * Returns true if the player must use the ladder before mining this spot.
     * Proximity to an upper-spot anchor on the lower floor does not count (same x,y, different height).
     */
    private boolean isWrongFloor(MLMMiningSpot spot) {
        if (spot == null) return false;
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null) return false;

        boolean onUpper = isUpperFloor();

        if (spot.isUpstairs()) {
            if (!onUpper) {
                log.info("[MiningSession] Spot {} is upstairs but player is on lower floor - climbing up first", spot);
                return true;
            }
            // On upper floor: correct floor even if not yet in the chamber (walking handles distance).
            return false;
        }

        if (spot.isDownstairs()) {
            if (onUpper) {
                log.info("[MiningSession] Spot {} is downstairs but player is on upper floor - climbing down first", spot);
                return true;
            }
            // On lower floor: correct floor; walking handles distance into tunnels/area.
            return false;
        }

        return !onUpper;
    }
}
