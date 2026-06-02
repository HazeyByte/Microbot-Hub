package net.runelite.client.plugins.microbot.motherloadmine.session;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.motherloadmine.MotherloadMineConfig;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

/**
 * Tick-progressive HopperSession for Motherlode Mine.
 * Handles walking to and depositing pay-dirt into the hopper.
 *
 * <pre>
 * IDLE → TRANSITIONING_FLOOR → WALKING → DEPOSITING → VERIFYING → COMPLETE
 * </pre>
 */
@Slf4j
public class HopperSession extends Session {

    public enum HopperSubState {
        IDLE,
        TRANSITIONING_FLOOR,
        WALKING,
        DEPOSITING,
        VERIFYING,
        COMPLETE,
        FAILED
    }

    private static final long WALK_THROTTLE_MS   = 600L;
    private static final long CLICK_THROTTLE_MS  = 600L;
    public static final int MAX_DEPOSIT_RETRIES = 3;

    /** Object ID of the MLM hopper on both floors. */
    private static final int HOPPER_ID = 26674;

    private static final WorldPoint LOWER_HOPPER_POINT = new WorldPoint(3748, 5672, 0);
    private static final WorldPoint UPPER_HOPPER_POINT = new WorldPoint(3755, 5677, 0);

    @Getter
    private HopperSubState hopperSubState = HopperSubState.IDLE;

    @Getter
    private int initialPayDirtCount = 0;

    @Getter
    private int depositRetryCount = 0;

    private final Rs2TileObjectCache tileCache;
    private final MotherloadMineConfig config;

    public HopperSession(Rs2TileObjectCache tileCache, MotherloadMineConfig config) {
        this.tileCache = tileCache;
        this.config    = config;
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void reset() {
        super.reset();
        log.debug("[HopperSession] Resetting session");
        hopperSubState       = HopperSubState.IDLE;
        initialPayDirtCount  = 0;
        depositRetryCount    = 0;
    }

    public void resetRetryCount() {
        depositRetryCount = 0;
    }

    public boolean incrementAndCheckRetryLimit() {
        return ++depositRetryCount >= MAX_DEPOSIT_RETRIES;
    }

    @Override
    public void begin() {
        if (!isIdle()) {
            log.warn("[HopperSession] begin() called while not idle");
            return;
        }
        initialPayDirtCount = Rs2Inventory.count(ItemID.PAYDIRT);
        log.info("[HopperSession] Beginning session with {} pay-dirt (retry={})",
                initialPayDirtCount, depositRetryCount);
        transitionSub(HopperSubState.IDLE);
        transition(State.ACTIVE);
    }

    private static final long STALL_THRESHOLD_MS = 4000L;
    private static final long DEPOSIT_STALL_MS   = 8000L;

    @Override
    protected void tickInternal() {
        switch (hopperSubState) {

            case IDLE: {
                if (shouldGoDown()) {
                    transitionSub(HopperSubState.TRANSITIONING_FLOOR);
                    break;
                }

                // CLICK-FIRST: If hopper is already in view/reachable, skip walking.
                if (isObjectInScene(HOPPER_ID)) {
                    log.info("[HopperSession] Hopper detected in scene — moving to DEPOSITING");
                    transitionSub(HopperSubState.DEPOSITING);
                } else {
                    transitionSub(HopperSubState.WALKING);
                }
                break;
            }

            case TRANSITIONING_FLOOR: {
                if (ensureFloor(false)) { // Always go down if we are on the wrong floor for the hopper
                    transitionSub(HopperSubState.IDLE);
                }
                break;
            }

            // ----------------------------------------------------------------
            case WALKING: {
                if (shouldGoDown()) {
                    transitionSub(HopperSubState.TRANSITIONING_FLOOR);
                    break;
                }

                // ADAPTIVE DETECTION: Interrupt walk the moment the hopper enters the scene.
                if (isObjectInScene(HOPPER_ID)) {
                    log.info("[HopperSession] Hopper detected during walk — interrupting.");
                    transitionSub(HopperSubState.DEPOSITING);
                    break;
                }

                if (Rs2Player.isMoving()) {
                    scheduleNext(600);
                    break;
                }

                // Dynamic fallback: if we've been walking on the upper floor for 4s
                // and the hopper still hasn't appeared, the upper hopper is probably
                // not unlocked or inaccessible. Climb down to the lower floor.
                if (isUpperFloor() && subElapsed() > 4_000L && !isObjectInScene(HOPPER_ID)) {
                    log.warn("[HopperSession] Upper hopper not found after 4s — climbing down to lower floor");
                    transitionSub(HopperSubState.TRANSITIONING_FLOOR);
                    break;
                }

                WorldPoint target = isUpperFloor() ? UPPER_HOPPER_POINT : LOWER_HOPPER_POINT;
                if (mayAct(WALK_THROTTLE_MS)) {
                    Rs2Walker.walkFastCanvas(target);
                    scheduleNext(WALK_THROTTLE_MS);
                }

                if (isStalled(STALL_THRESHOLD_MS)) {
                    log.warn("[HopperSession] Walk to hopper stalled");
                    transitionSub(HopperSubState.FAILED);
                    transition(State.FAILED);
                }
                break;
            }

            // ----------------------------------------------------------------
            case DEPOSITING: {
                if (Rs2Player.isInteracting() || Rs2Player.isAnimating() || Rs2Player.isMoving()) {
                    scheduleNext(600);
                    break;
                }

                var hopper = tileCache.query()
                        .withId(HOPPER_ID)
                        .nearestReachable();

                if (hopper == null) {
                    // ADAPTIVE: If lost from view, fallback to walking.
                    log.debug("[HopperSession] Hopper lost from view — falling back to WALKING");
                    transitionSub(HopperSubState.WALKING);
                    break;
                }

                if (hopper.click("Deposit")) {
                    log.info("[HopperSession] Deposit interaction sent");
                    applyActionCooldown();
                    scheduleNext(CLICK_THROTTLE_MS);
                    transitionSub(HopperSubState.VERIFYING);
                } else {
                    scheduleNext(CLICK_THROTTLE_MS);
                }

                if (isStalled(DEPOSIT_STALL_MS)) {
                    log.debug("[HopperSession] Deposit stalled — retrying interaction");
                    subStateEnteredMs = System.currentTimeMillis();
                }
                break;
            }

            // ----------------------------------------------------------------
            case VERIFYING: {
                // Wait for deposit animation to finish before checking inventory
                if (Rs2Player.isAnimating() || Rs2Player.isInteracting()) {
                    scheduleNext(600L);
                    break;
                }
                int currentCount = Rs2Inventory.count(ItemID.PAYDIRT);
                if (currentCount < initialPayDirtCount) {
                    log.info("[HopperSession] Deposit confirmed ({} → {} pay-dirt)",
                            initialPayDirtCount, currentCount);
                    transitionSub(HopperSubState.COMPLETE);
                    transition(State.COMPLETE);
                } else if (isStalled(DEPOSIT_STALL_MS)) {
                    log.warn("[HopperSession] Verify stalled — retrying deposit (retry={})", depositRetryCount);
                    initialPayDirtCount = currentCount; // Reset baseline for next attempt
                    transitionSub(HopperSubState.DEPOSITING);
                }
                break;
            }

            // ----------------------------------------------------------------
            case COMPLETE:
                transition(State.COMPLETE);
                break;

            case FAILED:
                transition(State.FAILED);
                break;

            default:
                break;
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private boolean shouldGoDown() {
        return isUpperFloor() && !config.upstairsHopperUnlocked();
    }

    private void transitionSub(HopperSubState next) {
        log.debug("[HopperSession] Sub-state: {} → {}", hopperSubState, next);
        hopperSubState    = next;
        subStateEnteredMs = System.currentTimeMillis();

        switch (next) {
            case TRANSITIONING_FLOOR: updateStatus("Climbing Ladder"); break;
            case WALKING:             updateStatus("Walking to Hopper"); break;
            case DEPOSITING:          updateStatus("Depositing Pay-dirt"); break;
            case VERIFYING:           updateStatus("Verifying Deposit"); break;
            case COMPLETE:            updateStatus("Deposit Complete"); break;
            default:                  updateStatus(formatEnum(next.name())); break;
        }
    }

}