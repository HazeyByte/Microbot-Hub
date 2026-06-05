package net.runelite.client.plugins.microbot.irkedmlm.session;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.motherloadmine.IrkedMLMConfig;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Abstract base class for all tick-progressive Motherload Mine sessions.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>Subclasses MUST call {@link #transition(State)} to change outer state.</li>
 *   <li>Subclasses MUST implement {@link #tickInternal()} (may be a no-op if the
 *       session defines its own parameterised tick).</li>
 *   <li>No {@code Thread.sleep}, no {@code while}-loops, no {@code sleepUntil}.</li>
 *   <li>Use {@link #scheduleNext(long)} to throttle repeated actions.</li>
 *   <li>Use {@link #subElapsed()} to detect phase time-outs.</li>
 * </ul>
 */
@Slf4j
public abstract class Session {

    // ------------------------------------------------------------------
    // Outer lifecycle state
    // ------------------------------------------------------------------

    public enum State {
        IDLE,
        ACTIVE,
        COMPLETE,
        FAILED
    }

    @Getter
    protected State state = State.IDLE;

    // ------------------------------------------------------------------
    // Timing helpers (ms)
    // ------------------------------------------------------------------

    /** Absolute ms at which the current {@link #state} was entered. */
    protected long stateEnteredMs = 0L;

    /**
     * Absolute ms at which the current <em>sub</em>-state was entered.
     * Sub-classes write this directly when they transition sub-states.
     */
    protected long subStateEnteredMs = 0L;

    /** The earliest ms at which the next action may fire. */
    protected long nextActionMs = 0L;

    // ------------------------------------------------------------------
    // Overlay label
    // ------------------------------------------------------------------

    /** Human-readable current phase label used by the overlay. */
    @Getter
    private String phaseLabel = "idle";

    // ------------------------------------------------------------------
    // Floor & Navigation constants (MLM-specific)
    // ------------------------------------------------------------------

    protected static final int UPPER_FLOOR_HEIGHT_THRESHOLD = -490;
    protected static final net.runelite.api.coords.WorldPoint LADDER_BOTTOM_WALK =
            net.runelite.client.plugins.microbot.motherloadmine.IrkedMLMMapConstants.LADDER_BOTTOM_WALK;
    protected static final net.runelite.api.coords.WorldPoint LADDER_TOP_WALK =
            net.runelite.client.plugins.microbot.motherloadmine.IrkedMLMMapConstants.LADDER_TOP_WALK;

    // ------------------------------------------------------------------
    // Floor detection cache (rate-limited client thread access)
    // Same pattern as script's varbit/XP caches to avoid TimeoutException
    // spam and breakhandler modals when client thread is busy (startup, events).
    // ------------------------------------------------------------------
    private long lastUpperFloorCheckMs = 0L;
    private boolean lastUpperFloorResult = false;
    private static final long UPPER_FLOOR_CACHE_MS = 1200L; // ~2 ticks, floor doesn't flip often

    // ------------------------------------------------------------------
    // Player name cache (pushed from script; sessions never do their own client thread fetch)
    // ------------------------------------------------------------------
    /**
     * Local player name kept up-to-date by the orchestrator script.
     * Sessions (Mining/Repair) use this for anti-crash and "another player repairing" checks
     * instead of each doing their own runOnClientThreadOptional + getLocalPlayer.
     */
    protected String cachedLocalPlayerName = "";

    /**
     * Injected config reference (for human-like behavior gating).
     * Subclasses that need per-decision human checks (vein selection, 1-strut skip, admire, etc.)
     * call isHumanLikeEnabled(). Base uses it for ladder hesitation.
     */
    protected final IrkedMLMConfig config;

    public Session(IrkedMLMConfig config) {
        this.config = config;
    }

    // ------------------------------------------------------------------
    // State predicates
    // ------------------------------------------------------------------

    public boolean isIdle()     { return state == State.IDLE;     }
    public boolean isActive()   { return state == State.ACTIVE;   }
    public boolean isComplete() { return state == State.COMPLETE; }
    public boolean isFailed()   { return state == State.FAILED;   }

    /**
     * Returns whether MLM-specific human-like behavior is enabled (our pauses, variation, imperfection).
     * Used to gate random waits / glances / 1-strut skip / admire / jitter etc.
     * When false we use small consistent delays + optimal choices.
     */
    protected boolean isHumanLikeEnabled() {
        return config != null && config.enableHumanLikeBehavior();
    }

    /** Milliseconds to wait before the next action (fast vs human-like). */
    protected long tickDelayMs(long fastMs, long humanMs) {
        return isHumanLikeEnabled() ? humanMs : fastMs;
    }

    protected void scheduleNextAdaptive(long fastMs, long humanMs) {
        scheduleNext(tickDelayMs(fastMs, humanMs));
    }

    /** Minimum pause after sub-state changes so fast mode does not mass-click. */
    protected void scheduleSubStateEntryDelay() {
        scheduleNextAdaptive(200L, 400L);
    }

    /** @deprecated use {@link #scheduleSubStateEntryDelay()} */
    protected void clearActionGateOnFastMode() {
        scheduleSubStateEntryDelay();
    }

    // ------------------------------------------------------------------
    // Floor helpers
    // ------------------------------------------------------------------

    /** Returns true if the player is on the upper floor of MLM. */
    protected boolean isUpperFloor() {
        if (!net.runelite.client.plugins.microbot.Microbot.isLoggedIn()) {
            // Early exit avoids queuing client thread work during login / blocking events.
            // This is the root cause of the TimeoutException + breakhandler spam at startup.
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastUpperFloorCheckMs < UPPER_FLOOR_CACHE_MS) {
            return lastUpperFloorResult;
        }
        boolean result = net.runelite.client.plugins.microbot.Microbot.getClientThread().runOnClientThreadOptional(() -> {
            net.runelite.api.Client client = net.runelite.client.plugins.microbot.Microbot.getClient();
            if (client == null || client.getLocalPlayer() == null) return false;
            int height = net.runelite.api.Perspective.getTileHeight(
                    client,
                    client.getLocalPlayer().getLocalLocation(),
                    client.getLocalPlayer().getWorldLocation().getPlane());
            return height < UPPER_FLOOR_HEIGHT_THRESHOLD;
        }).orElse(false);
        lastUpperFloorCheckMs = now;
        if (result != lastUpperFloorResult) {
            log.debug("[Session] Floor changed: upper={}", result);
        }
        lastUpperFloorResult = result;
        return result;
    }

    public void invalidateUpperFloorCache() {
        lastUpperFloorCheckMs = 0L;
    }

    /**
     * Navigates to the target floor.
     *
     * @param targetIsUp {@code true} = upper floor, {@code false} = lower floor.
     * @return {@code true} if already on the target floor; {@code false} if a
     *         transition was initiated (caller should wait another tick).
     */
    protected boolean ensureFloor(boolean targetIsUp) {
        boolean currentIsUp = isUpperFloor();
        if (currentIsUp == targetIsUp) return true;
        if (targetIsUp) climbUp();
        else            climbDown();
        return false;
    }

    /**
     * Returns {@code true} if an object with the given ID is present and
     * reachable in the current scene.
     */
    protected boolean isObjectInScene(int objectId) {
        return net.runelite.client.plugins.microbot.Microbot.getRs2TileObjectCache()
                .query()
                .withId(objectId)
                .nearestReachable() != null;
    }

    public void updateCachedLocalPlayerName(String name) {
        this.cachedLocalPlayerName = (name != null) ? name : "";
    }

    protected void climbUp() {
        if (Rs2Player.isMoving() || Rs2Player.isAnimating(1200)) {
            scheduleNext(600L);
            return;
        }

        invalidateUpperFloorCache();
        if (isUpperFloor()) {
            log.debug("[Session] Already on upper floor - skip climb-up");
            return;
        }

        var ladder = net.runelite.client.plugins.microbot.Microbot.getRs2TileObjectCache()
                .query()
                .withId(net.runelite.api.gameval.ObjectID.MOTHERLODE_LADDER_BOTTOM)
                .nearestReachable();

        if (ladder == null) {
            net.runelite.client.plugins.microbot.util.walker.Rs2Walker
                    .walkFastCanvas(LADDER_BOTTOM_WALK);
            scheduleNext(1200L);
            return;
        }

        log.info("[Session] Climbing up ladder");

        int hesitation;
        if (isHumanLikeEnabled()) {
            hesitation = Rs2Random.between(250, 650);
            if (Rs2Random.between(0, 100) < 35) {
                hesitation += Rs2Random.between(400, 900); // sometimes "double check"
            }
        } else {
            hesitation = Rs2Random.between(35, 90);
        }
        scheduleNext(hesitation);

        if (ladder.click("Climb-up")) {
            applyActionCooldown();
            // Give time for anim to start + floor height to update in client state.
            // Rely on isAnimating in subsequent ticks + state machine, not our click timestamp.
            scheduleNext(isHumanLikeEnabled() ? 4000L : 900L);
        }
    }

    /**
     * Climbs the top ladder to reach the lower floor.
     * Uses {@code walkFastCanvas} (no web-walk) so it only issues a canvas
     * click — no pathfinder involved.
     */
    protected void climbDown() {
        if (Rs2Player.isMoving() || Rs2Player.isAnimating(1200)) {
            scheduleNext(600L);
            return;
        }

        invalidateUpperFloorCache();
        if (!isUpperFloor()) {
            log.debug("[Session] Already on lower floor - skip climb-down");
            return;
        }

        var ladder = net.runelite.client.plugins.microbot.Microbot.getRs2TileObjectCache()
                .query()
                .withId(net.runelite.api.gameval.ObjectID.MOTHERLODE_LADDER_TOP)
                .nearestReachable();

        if (ladder == null) {
            net.runelite.client.plugins.microbot.util.walker.Rs2Walker
                    .walkFastCanvas(LADDER_TOP_WALK);
            scheduleNext(1200L);
            return;
        }

        log.info("[Session] Climbing down ladder");

        int hesitation;
        if (isHumanLikeEnabled()) {
            hesitation = Rs2Random.between(250, 650);
            if (Rs2Random.between(0, 100) < 35) {
                hesitation += Rs2Random.between(400, 900); // sometimes "double check"
            }
        } else {
            hesitation = Rs2Random.between(35, 90);
        }
        scheduleNext(hesitation);

        if (ladder.click("Climb-down")) {
            applyActionCooldown();
            scheduleNext(isHumanLikeEnabled() ? 4000L : 900L);
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Resets the session back to {@link State#IDLE}.
     * Sub-classes MUST call {@code super.reset()} first.
     */
    public void reset() {
        state             = State.IDLE;
        stateEnteredMs    = 0L;
        subStateEnteredMs = 0L;
        nextActionMs      = 0L;
        phaseLabel        = "idle";
        // NOTE: lastLadderClickMs is intentionally NOT reset here.
        // It is static and shared across all sessions to prevent
        // spam-clicking the ladder when switching between sessions.
    }

    /**
     * Begins the session ({@link State#IDLE} → {@link State#ACTIVE}).
     */
    public void begin() {
        if (!isIdle()) {
            log.warn("[{}] begin() called while not idle (state={})",
                    getClass().getSimpleName(), state);
            return;
        }
        transition(State.ACTIVE);
    }

    // ------------------------------------------------------------------
    // Internal tick
    // ------------------------------------------------------------------

    /**
     * Called by {@link #tick()} on the executor thread.
     * Sessions that define their own parameterised tick must still
     * implement this (may be a no-op body).
     */
    protected abstract void tickInternal();

    /**
     * No-arg tick entry-point used by sessions without extra parameters.
     */
    public void tick() {
        if (!isActive()) return;
        if (System.currentTimeMillis() < nextActionMs) return;
        try {
            tickInternal();
        } catch (Exception e) {
            log.error("[{}] Tick crash", getClass().getSimpleName(), e);
            transition(State.FAILED);
        }
    }

    // ------------------------------------------------------------------
    // Timing helpers
    // ------------------------------------------------------------------

    /** Prevents any action from firing for at least {@code delayMs}. */
    protected void scheduleNext(long delayMs) {
        nextActionMs = System.currentTimeMillis() + delayMs;
    }


    /**
     * Milliseconds elapsed since the current sub-state was entered.
     * Sub-classes must keep {@link #subStateEnteredMs} up-to-date.
     */
    protected long subElapsed() {
        return System.currentTimeMillis() - subStateEnteredMs;
    }

    /**
     * Returns {@code true} when the sub-state has been active for longer
     * than {@code thresholdMs} without progressing — useful for stall detection.
     */
    protected boolean isStalled(long thresholdMs) {
        return subElapsed() > thresholdMs;
    }

    /**
     * Returns {@code true} if at least {@code throttleMs} milliseconds have
     * elapsed since {@code subStateEnteredMs}. Used to gate walk/click actions
     * so they don't fire every 600 ms tick.
     */
    protected boolean mayAct(long throttleMs) {
        return subElapsed() >= throttleMs || System.currentTimeMillis() >= nextActionMs;
    }

    // ------------------------------------------------------------------
    // Antiban helpers
    // ------------------------------------------------------------------

    /**
     * Applies a post-action cooldown via Rs2Antiban.
     * <p>
     * Wrapped in try/catch: {@code PlayStyle.getRandomTickInterval} throws
     * {@code IllegalArgumentException} when {@code minTicks >= maxTicks}, which
     * can happen if {@code usePlayStyle} is true but the play-style was not
     * initialised with valid bounds.  Rather than crashing the session we log a
     * warning and skip the cooldown for that tick only.
     */
    protected void applyActionCooldown() {
        if (!isHumanLikeEnabled()) {
            return;
        }
        try {
            Rs2Antiban.actionCooldown();
        } catch (IllegalArgumentException e) {
            log.warn("[{}] applyActionCooldown skipped — invalid antiban bounds: {}",
                    getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * Sets {@link net.runelite.client.plugins.microbot.Microbot#status} to the
     * given label so the RuneLite overlay picks it up.
     */
    protected void updateStatus(String label) {
        phaseLabel = label;
        net.runelite.client.plugins.microbot.Microbot.status = label;
    }

    /**
     * Converts a SCREAMING_SNAKE_CASE enum name to Title Case for display.
     * e.g. {@code "WALK_TO_SACK"} → {@code "Walk To Sack"}.
     */
    protected String formatEnum(String name) {
        if (name == null || name.isEmpty()) return name;
        String[] parts = name.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) sb.append(part.substring(1).toLowerCase());
                sb.append(' ');
            }
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------
    // State transition
    // ------------------------------------------------------------------

    /** Transitions to {@code next}, recording the entry timestamp. */
    protected void transition(State next) {
        log.debug("[{}] {} -> {}", getClass().getSimpleName(), state, next);
        state          = next;
        stateEnteredMs = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------
    // Overlay support
    // ------------------------------------------------------------------


}
