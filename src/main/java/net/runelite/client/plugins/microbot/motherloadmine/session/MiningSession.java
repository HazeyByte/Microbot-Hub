package net.runelite.client.plugins.microbot.motherloadmine.session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.motherloadmine.MotherloadMineConfig;
import net.runelite.client.plugins.microbot.motherloadmine.enums.MLMMiningSpot;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

/**
 * Tick-progressive MiningSession for Motherload Mine.
 * Handles floor transitions, walking to spots, and the mining loop.
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
     * All active ore-vein object IDs in Motherload Mine.
     * We use IDs rather than withName("Ore vein") because the internal name
     * can differ from the menu target, causing cache misses.
     * IDs confirmed from live menu entries: 26661 to 26665.
     */
    private static final int[] ORE_VEIN_IDS = {26661, 26662, 26663, 26664, 26665};

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

    /** Session-long memory of rockfall tiles. Even if another player mines a rockfall,
     *  we continue to treat those tiles as blocked for the remainder of the session. */
    private final Set<WorldPoint> rememberedRockfalls = new HashSet<>();

    /** Veins that recently failed interaction (depleted, blocked, or click-failed).
     *  Key = vein WorldPoint, Value = timestamp of failure. */
    private final Map<WorldPoint, Long> recentFailures = new HashMap<>();

    /** How long to blacklist a vein after it fails interaction. */
    private static final long FAILURE_BLACKLIST_MS = 10_000L;

    /** Cached local player name to avoid background-thread client access. */
    private String localPlayerName = "";

    private final Rs2TileObjectCache   tileCache;
    private final MotherloadMineConfig config;

    public MiningSession(Rs2TileObjectCache tileCache, MotherloadMineConfig config) {
        this.tileCache = tileCache;
        this.config    = config;
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
        rememberedRockfalls.clear();
        localPlayerName   = "";
    }

    @Override
    public void begin() {
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
            log.warn("[MiningSession] tick() called with null spot — failing session");
            transitionSub(MiningSubState.FAILED);
            transition(State.FAILED);
            return;
        }

        try {
            tickMiningInternal(spot, sackCount, maxSack, globalLastXpTime, System.currentTimeMillis());
        } catch (Exception e) {
            log.error("[MiningSession] Tick crash", e);
            transitionSub(MiningSubState.FAILED);
            transition(State.FAILED);
        }
    }

    @Override
    protected void tickInternal() {
        // MiningSession uses its own tick() overload — no-op here.
    }

    // -----------------------------------------------------------------------
    // Core tick logic
    // -----------------------------------------------------------------------

    private void tickMiningInternal(MLMMiningSpot spot, int sackCount, int maxSack,
                                    AtomicLong globalLastXpTime, long now) {

        updateRememberedRockfalls();
        cleanupRecentFailures();

        if (localPlayerName.isEmpty()) {
            localPlayerName = Microbot.getClientThread().runOnClientThreadOptional(() -> {
                var lp = Microbot.getClient().getLocalPlayer();
                return lp != null ? lp.getName() : "";
            }).orElse("");
        }

        // Resolve the most recent XP timestamp from both local and global sources.
        long lastXpResolved = Math.max(lastXpTimestamp, globalLastXpTime.get());

        switch (subState) {

            // -----------------------------------------------------------------
            case IDLE:
                if (Rs2Inventory.isFull()) {
                    transitionSub(MiningSubState.INV_FULL);
                    break;
                }
                if (isWrongFloor(spot)) {
                    transitionSub(MiningSubState.TRANSITIONING_FLOOR);
                    break;
                }
                transitionSub(MiningSubState.WALKING);
                break;

            // -----------------------------------------------------------------
            case TRANSITIONING_FLOOR:
                if (ensureFloor(spot.isUpstairs())) {
                    transitionSub(MiningSubState.WALKING);
                }
                if (isStalled(20_000L)) {
                    log.warn("[MiningSession] Floor transition stalled — failing");
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
                if (isNearSpot(spot)) {
                    transitionSub(MiningSubState.SELECTED);
                    break;
                }

                // Fast-path: if veins are already visible and close, skip walking.
                WorldPoint anchor = getFirstAnchor(spot);
                WorldPoint playerLoc = Rs2Player.getWorldLocation();
                var visibleVeins = tileCache.query()
                        .where(o -> isOreVein(o.getId())
                                && isActiveVein(o)
                                && spot.containsInArea(o.getWorldLocation()))
                        .toList();
                Rs2TileObjectModel bestVisible = null;
                long bestScore = Long.MAX_VALUE;
                for (Rs2TileObjectModel v : visibleVeins) {
                    if (v.getWorldLocation().distanceTo(playerLoc) > 10) continue;

                    // NEW: ignore veins that are in the area but blocked by a remembered rockfall
                    if (isPathBlockedByRockfall(playerLoc, v.getWorldLocation())) {
                        log.debug("[MiningSession] Visible vein at {} blocked by rockfall — skipping",
                                v.getWorldLocation());
                        continue;
                    }

                    long score = (anchor != null) ? anchor.distanceTo(v.getWorldLocation()) * 2L : 0L;
                    score += playerLoc.distanceTo(v.getWorldLocation()) * 2L;

                    if (score < bestScore) {
                        bestScore = score;
                        bestVisible = v;
                    }
                }
                if (bestVisible != null) {
                    log.info("[MiningSession] Vein visible at {} (score={}) — skipping walk",
                            bestVisible.getWorldLocation(), bestScore);
                    targetVein = bestVisible.getWorldLocation();
                    transitionSub(MiningSubState.CLICKED);
                    break;
                }

                // Walking cooldown & logic
                if (!Rs2Player.isMoving() && (now - lastWalkCommandMs >= 1200L)) {
                    var nearestVein = tileCache.query()
                            .where(o -> isOreVein(o.getId())
                                    && isActiveVein(o)
                                    && spot.containsInArea(o.getWorldLocation()))
                            .nearest();

                    // NEW: if the nearest vein is behind a rockfall, don't walk toward it.
                    if (nearestVein != null && isPathBlockedByRockfall(playerLoc, nearestVein.getWorldLocation())) {
                        log.debug("[MiningSession] Nearest vein at {} blocked by rockfall — using anchor instead",
                                nearestVein.getWorldLocation());
                        nearestVein = null;
                    }

                    WorldPoint walkTarget;
                    if (nearestVein != null) {
                        walkTarget = nearestVein.getWorldLocation();
                    } else {
                        walkTarget = anchor;
                    }

                    if (walkTarget != null) {
                        int distance = Rs2Player.getWorldLocation().distanceTo(walkTarget);
                        if (distance > 15) {
                            log.debug("[MiningSession] Target {} tiles away — using webwalker", distance);
                            Rs2Walker.walkTo(walkTarget);
                        } else {
                            Rs2Walker.walkFastCanvas(walkTarget);
                        }
                    } else {
                        log.warn("[MiningSession] Spot {} has no walk target and no visible veins", spot);
                        transitionSub(MiningSubState.FAILED);
                        transition(State.FAILED);
                        break;
                    }
                    lastWalkCommandMs = now;
                    scheduleNext(600L);
                }
                if (isStalled(15_000L)) {
                    log.warn("[MiningSession] Walking stalled — failing");
                    transitionSub(MiningSubState.FAILED);
                    transition(State.FAILED);
                }
                break;

            // -----------------------------------------------------------------
            case CLEARING_OBSTACLE: {
                Rs2TileObjectModel rockfall = findNearbyRockfall();
                if (rockfall == null) {
                    log.info("[MiningSession] Obstacle cleared — resuming walk");
                    transitionSub(MiningSubState.WALKING);
                    break;
                }
                if (Rs2Player.isAnimating()) {
                    scheduleNext(600L);
                    break;
                }
                if (rockfall.click("Mine")) {
                    applyActionCooldown();
                    scheduleNext(1200L);
                }
                break;
            }

            // -----------------------------------------------------------------
            case SELECTED:
                if (Rs2Inventory.isFull()) {
                    transitionSub(MiningSubState.INV_FULL);
                    break;
                }
                targetVein = selectVein(spot);
                if (targetVein != null) {
                    log.info("[MiningSession] Vein selected at {}", targetVein);
                    transitionSub(MiningSubState.CLICKED);
                } else {
                    log.info("[MiningSession] No veins found in area");
                    transitionSub(MiningSubState.REPOSITIONING);
                }
                if (isStalled(10_000L)) {
                    log.warn("[MiningSession] Vein selection stalled — failing");
                    transitionSub(MiningSubState.FAILED);
                    transition(State.FAILED);
                }
                break;

            // -----------------------------------------------------------------
            case REPOSITIONING:
                log.debug("[MiningSession] No veins found — repositioning");
                WorldPoint repositionTarget = getFirstAnchor(spot);
                if (repositionTarget != null) {
                    int distance = Rs2Player.getWorldLocation().distanceTo(repositionTarget);
                    if (distance > 15) {
                        Rs2Walker.walkTo(repositionTarget);
                    } else {
                        Rs2Walker.walkFastCanvas(repositionTarget);
                    }
                    scheduleNext(2000L);
                    transitionSub(MiningSubState.WALKING);
                } else {
                    log.warn("[MiningSession] Spot {} has no reposition target", spot);
                    transitionSub(MiningSubState.FAILED);
                    transition(State.FAILED);
                }
                break;

            // -----------------------------------------------------------------
            case CLICKED: {
                // Spam-click prevention: if already interacting/animating, wait
                if (Rs2Player.isInteracting() || Rs2Player.isAnimating()) {
                    scheduleNext(400L);
                    break;
                }

                Rs2TileObjectModel vein = findVeinAt(targetVein);
                if (vein == null) {
                    log.debug("[MiningSession] Vein at {} lost before click — clearing and reselecting", targetVein);
                    markFailed(targetVein);
                    targetVein = null;
                    failedClicks = 0;
                    transitionSub(MiningSubState.SELECTED);
                    break;
                }

                // Reactive camera: Turn to vein if not on screen
                if (!Rs2Camera.isTileOnScreen(vein.getLocalLocation())) {
                    log.debug("[MiningSession] Vein off-screen — turning camera");
                    Rs2Camera.turnTo(vein);
                    scheduleNext(400L);
                    break;
                }

                if (vein.click("Mine")) {
                    applyActionCooldown();
                    failedClicks     = 0;
                    lastXpTimestamp  = now;
                    sessionLockUntil = now + 5000L;
                    // If player is still moving toward the vein, wait in ARRIVING
                    // instead of jumping straight to CONFIRMED. This prevents
                    // the 8s animation timeout from firing while we're still walking.
                    if (Rs2Player.isMoving()) {
                        log.debug("[MiningSession] Click sent while walking — waiting to arrive");
                        transitionSub(MiningSubState.ARRIVING);
                        scheduleNext(800L);
                    } else {
                        transitionSub(MiningSubState.CONFIRMED);
                        scheduleNext(1200L);
                    }
                } else {
                    failedClicks++;
                    log.debug("[MiningSession] Click failed ({}/3)", failedClicks);
                    if (failedClicks > 3) {
                        log.warn("[MiningSession] Too many failed clicks on vein at {} — picking a new one", targetVein);
                        markFailed(targetVein);
                        targetVein = null;
                        failedClicks = 0;
                        transitionSub(MiningSubState.SELECTED);
                    } else {
                        scheduleNext(800L);
                    }
                }
                break;
            }

            // -----------------------------------------------------------------
            case ARRIVING: {
                // Wait for player to stop moving, then check if mining started
                if (Rs2Player.isMoving()) {
                    scheduleNext(600L);
                    break;
                }
                // Player stopped moving — check animation
                if (Rs2Player.isAnimating()) {
                    transitionSub(MiningSubState.MINING);
                    break;
                }
                // NEW: a rockfall can spawn while we're walking. If one now blocks the
                // path, bail out immediately instead of timing out in CONFIRMED.
                if (targetVein != null && isPathBlockedByRockfall(Rs2Player.getWorldLocation(), targetVein)) {
                    log.debug("[MiningSession] Rockfall blocked path after arriving — reselecting");
                    markFailed(targetVein);
                    transitionSub(MiningSubState.SELECTED);
                    break;
                }
                // Not animating yet — give it a moment before declaring CONFIRMED
                if (subElapsed() > 2000L) {
                    log.debug("[MiningSession] Arrived but no animation yet — moving to CONFIRMED");
                    transitionSub(MiningSubState.CONFIRMED);
                    scheduleNext(600L);
                }
                break;
            }

            // -----------------------------------------------------------------
            case CONFIRMED:
                // If player started moving again (e.g., click sent from too far),
                // wait — don't count movement toward the animation timeout.
                if (Rs2Player.isMoving()) {
                    scheduleNext(600L);
                    break;
                }
                if (Rs2Player.isAnimating()) {
                    transitionSub(MiningSubState.MINING);
                    break;
                }
                // NEW: after 3 seconds of no animation, check if the vein is depleted or
                // a rockfall has spawned. If so, bail out early instead of waiting 12s.
                if (targetVein != null && subElapsed() > 3_000L) {
                    if (findVeinAt(targetVein) == null) {
                        log.debug("[MiningSession] Target vein depleted during CONFIRMED — reselecting");
                        transitionSub(MiningSubState.SELECTED);
                        break;
                    }
                    if (isPathBlockedByRockfall(Rs2Player.getWorldLocation(), targetVein)) {
                        log.debug("[MiningSession] Path blocked by rockfall during CONFIRMED — reselecting");
                        markFailed(targetVein);
                        transitionSub(MiningSubState.SELECTED);
                        break;
                    }
                }
                if (subElapsed() > 12_000L) {
                    log.debug("[MiningSession] Animation timeout (12s) — re-selecting vein");
                    transitionSub(MiningSubState.SELECTED);
                }
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

                // If player is moving, they haven't started mining yet — don't count
                // this time toward stuck detection. Transition back to ARRIVING.
                if (Rs2Player.isMoving()) {
                    log.debug("[MiningSession] Player moving during MINING — returning to ARRIVING");
                    transitionSub(MiningSubState.ARRIVING);
                    break;
                }

                // Check if player is too far from the vein (>2 tiles)
                if (targetVein != null && Rs2Player.getWorldLocation().distanceTo(targetVein) > 2) {
                    log.debug("[MiningSession] Player too far from vein — walking closer");
                    transitionSub(MiningSubState.WALKING);
                    break;
                }

                boolean isMining = Rs2Player.isAnimating();

                // If player is still animating, don't check vein depletion yet.
                // The animation may outlast the vein object in the cache, and the
                // player will still receive the pay-dirt. Re-check next tick.
                if (isMining) {
                    break;
                }

                // Player has stopped animating — check if the vein is still active
                // or if we got stuck (no pay-dirt received for 10s).
                boolean veinPresent = isVeinPresent(targetVein);

                long lastActivityResolved = Math.max(
                        Math.max(lastXpResolved, globalLastXpTime.get()),
                        subStateEnteredMs);

                // Stuck detection: 10s of no activity while vein is still there
                boolean isStuck = (now - lastActivityResolved > 10_000L)
                        && (subElapsed() > 3_000L);

                if (!veinPresent || isStuck) {
                    if (isStuck && veinPresent) {
                        log.debug("[MiningSession] Stuck on vein (no activity 10s) — reselecting");
                        markFailed(targetVein);
                    }
                    transitionSub(MiningSubState.DEPLETED);
                    break;
                }

                break;
            }

            // -----------------------------------------------------------------
            case DEPLETED:
                targetVein = null;
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
        recentFailures.entrySet().removeIf(e -> now - e.getValue() > FAILURE_BLACKLIST_MS);
    }

    private boolean isRecentlyFailed(WorldPoint wp) {
        if (wp == null) return false;
        Long ts = recentFailures.get(wp);
        if (ts == null) return false;
        if (System.currentTimeMillis() - ts > FAILURE_BLACKLIST_MS) {
            recentFailures.remove(wp);
            return false;
        }
        return true;
    }

    private void markFailed(WorldPoint wp) {
        if (wp != null) recentFailures.put(wp, System.currentTimeMillis());
    }

    // -----------------------------------------------------------------------
    // Depleted vein helper
    // -----------------------------------------------------------------------

    /**
     * Returns true if the object is an active (non-depleted) vein.
     * Depleted veins share the same object IDs as active ones, so we must check
     * the resolved name to avoid selecting them.
     */
    private boolean isActiveVein(Rs2TileObjectModel o) {
        if (o == null) return false;
        String name = o.getName();
        return name == null || !name.toLowerCase().contains("depleted");
    }

    /**
     * Scans the current scene for rockfalls and adds all occupied tiles to the
     * session-long memory. Called once per tick so the remembered set stays current.
     */
    private void updateRememberedRockfalls() {
        List<Rs2TileObjectModel> current = tileCache.query()
                .where(o -> isRockfall(o.getId()))
                .toList();

        for (Rs2TileObjectModel rock : current) {
            WorldPoint base = rock.getWorldLocation();
            // Rockfalls are 2×2 objects in MLM. The cache stores the SW origin,
            // so we mark all 4 occupied tiles as impassable.
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
     * Returns true if {@code id} is one of the active ore-vein object IDs.
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
     * Selects the best ore vein in the mining spot area.
     * <p>
     * Primary path: uses {@code nearestReachable()} as a line-of-sight / pathability
     * gate. This prevents targeting veins behind walls or in unreachable niches.
     * <p>
     * Fallback: exhaustive search filtered for active (non-depleted) veins, then
     * sorted by anchor proximity. Minimal randomization (10% chance for 2nd-closest
     * only when within 1 tile) replaces the old heavy weighted random.
     * <p>
     * Recently-failed veins (click-failed, rockfall-blocked, or stuck) are blacklisted
     * for 10 seconds to prevent the "mine same tile over and over" loop.
     */
    private WorldPoint selectVein(MLMMiningSpot spot) {
        WorldPoint playerLoc = Rs2Player.getWorldLocation();
        WorldPoint anchor = getFirstAnchor(spot);

        // 1. Primary: nearest reachable, non-depleted, non-failed vein in area.
        // nearestReachable() acts as the line-of-sight / pathability check.
        var primary = tileCache.query()
                .where(o -> isOreVein(o.getId())
                        && isActiveVein(o)
                        && spot.containsInArea(o.getWorldLocation())
                        && !isPathBlockedByRockfall(playerLoc, o.getWorldLocation())
                        && !isRecentlyFailed(o.getWorldLocation()))
                .nearestReachable();

        if (primary != null) {
            WorldPoint p = primary.getWorldLocation();
            // Only accept if it stays within the designated area (prevents edge drift)
            if (anchor == null || p.distanceTo(anchor) <= 12) {
                log.info("[MiningSession] Selected reachable vein at {} (dist={}, pool=reachable)",
                        p, playerLoc.distanceTo(p));
                return p;
            }
        }

        // 2. Fallback: exhaustive search with same hygiene filters
        var query = tileCache.query()
                .where(o -> isOreVein(o.getId())
                        && isActiveVein(o)
                        && spot.containsInArea(o.getWorldLocation()))
                .where(o -> Rs2Tile.areSurroundingTilesWalkable(o.getWorldLocation(), 1, 1));

        List<Rs2TileObjectModel> allVeins = query.toList();
        if (allVeins.isEmpty()) {
            log.info("[MiningSession] No active veins found in spot {}", spot);
            return null;
        }

        // Filter: rockfall block, recent failure, anchor distance
        List<Rs2TileObjectModel> candidates = new ArrayList<>();
        for (Rs2TileObjectModel vein : allVeins) {
            WorldPoint vp = vein.getWorldLocation();
            if (isPathBlockedByRockfall(playerLoc, vp)) continue;
            if (isRecentlyFailed(vp)) continue;
            if (anchor != null && vp.distanceTo(anchor) > 12) continue;
            candidates.add(vein);
        }

        if (candidates.isEmpty()) {
            log.warn("[MiningSession] All {} veins blocked, failed, or too far from anchor", allVeins.size());
            return null;
        }

        // Sort by distance to anchor (primary), then player distance (secondary)
        final WorldPoint finalAnchor = anchor;
        final WorldPoint finalPlayer = playerLoc;
        candidates.sort((a, b) -> {
            int cmp = 0;
            if (finalAnchor != null) {
                cmp = Integer.compare(
                        finalAnchor.distanceTo(a.getWorldLocation()),
                        finalAnchor.distanceTo(b.getWorldLocation()));
            }
            if (cmp == 0) {
                cmp = Integer.compare(
                        finalPlayer.distanceTo(a.getWorldLocation()),
                        finalPlayer.distanceTo(b.getWorldLocation()));
            }
            return cmp;
        });

        // Minimal randomization: only if top 2 are within 1 tile distance,
        // 10% chance to pick the 2nd closest for natural-looking variance.
        Rs2TileObjectModel chosen = candidates.get(0);
        if (candidates.size() > 1) {
            int d0 = finalPlayer.distanceTo(chosen.getWorldLocation());
            int d1 = finalPlayer.distanceTo(candidates.get(1).getWorldLocation());
            if (d1 <= d0 + 1 && java.util.concurrent.ThreadLocalRandom.current().nextDouble() < 0.10) {
                chosen = candidates.get(1);
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
                                    && !pName.equalsIgnoreCase(localPlayerName)
                                    && p.getWorldLocation().distanceTo(finalChosen.getWorldLocation()) <= 2;
                        })
                        .count();
                if (playersNearby > 0) {
                    log.debug("[MiningSession] Chosen vein at {} crowded — trying next candidate",
                            finalChosen.getWorldLocation());
                    // Pick next candidate if available, otherwise keep it
                    if (candidates.size() > 1) {
                        chosen = candidates.get(1);
                    }
                }
            }
        }

        WorldPoint chosenPoint = chosen.getWorldLocation();
        log.info("[MiningSession] Selected vein at {} (dist={}, rank={}, pool={})",
                chosenPoint,
                finalPlayer.distanceTo(chosenPoint),
                candidates.indexOf(chosen) + 1,
                candidates.size());
        return chosenPoint;
    }

    /**
     * Returns true if the straight-line path between {@code from} and {@code to}
     * passes through a tile occupied by a remembered rockfall.
     */
    private boolean isPathBlockedByRockfall(WorldPoint from, WorldPoint to) {
        if (from == null || to == null) return true;
        if (rememberedRockfalls.isEmpty()) return false;

        int dx = Math.abs(to.getX() - from.getX());
        int dz = Math.abs(to.getY() - from.getY());
        int sx = from.getX() < to.getX() ? 1 : -1;
        int sz = from.getY() < to.getY() ? 1 : -1;
        int err = dx - dz;

        int x = from.getX();
        int z = from.getY();

        while (true) {
            WorldPoint step = new WorldPoint(x, z, from.getPlane());
            if (rememberedRockfalls.contains(step)) {
                return true;
            }
            if (x == to.getX() && z == to.getY()) break;
            int e2 = 2 * err;
            if (e2 > -dz) { err -= dz; x += sx; }
            if (e2 < dx)  { err += dx; z += sz; }
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
                .where(o -> isOreVein(o.getId()) && o.getWorldLocation().distanceTo(wp) <= 2)
                .nearest();
        if (vein == null) return null;

        // Depleted veins share the same object ID but their name changes.
        // Clicking one produces a menu entry that does nothing and leaves the
        // player stuck in CONFIRMED for 12 seconds.
        String name = vein.getName();
        if (name != null && name.toLowerCase().contains("depleted")) {
            log.debug("[MiningSession] Vein at {} is depleted — treating as absent", wp);
            return null;
        }
        return vein;
    }

    /**
     * Returns {@code true} if any active (non-depleted) ore vein is present at
     * the given WorldPoint. MLM respawns veins with different IDs on the same
     * tile, so we check for ANY ore vein rather than the specific ID.
     */
    private boolean isVeinPresent(WorldPoint wp) {
        if (wp == null) return false;
        Rs2TileObjectModel vein = tileCache.query()
                .where(o -> isOreVein(o.getId()) && o.getWorldLocation().equals(wp))
                .nearest();
        if (vein == null) return false;
        String name = vein.getName();
        return name == null || !name.toLowerCase().contains("depleted");
    }

    /** Returns {@code true} if the player is inside the mining spot area. */
    private boolean isNearSpot(MLMMiningSpot spot) {
        if (spot == null) return false;
        return spot.containsInArea(Rs2Player.getWorldLocation());
    }

    /** Returns {@code true} if the player is on the wrong floor for the selected spot. */
    private boolean isWrongFloor(MLMMiningSpot spot) {
        return isUpperFloor() != spot.isUpstairs();
    }
}
