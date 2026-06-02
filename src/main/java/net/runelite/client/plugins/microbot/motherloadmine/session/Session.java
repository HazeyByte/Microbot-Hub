package net.runelite.client.plugins.microbot.motherloadmine.session;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
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
            new net.runelite.api.coords.WorldPoint(3755, 5673, 0);
    protected static final net.runelite.api.coords.WorldPoint LADDER_TOP_WALK =
            new net.runelite.api.coords.WorldPoint(3755, 5675, 0);

    // ------------------------------------------------------------------
    // State predicates
    // ------------------------------------------------------------------

    public boolean isIdle()     { return state == State.IDLE;     }
    public boolean isActive()   { return state == State.ACTIVE;   }
    public boolean isComplete() { return state == State.COMPLETE; }
    public boolean isFailed()   { return state == State.FAILED;   }

    // ------------------------------------------------------------------
    // Floor helpers
    // ------------------------------------------------------------------

    /** Returns true if the player is on the upper floor of MLM. */
    protected boolean isUpperFloor() {
        return net.runelite.client.plugins.microbot.Microbot.getClientThread().runOnClientThreadOptional(() -> {
            net.runelite.api.Client client = net.runelite.client.plugins.microbot.Microbot.getClient();
            if (client == null || client.getLocalPlayer() == null) return false;
            int height = net.runelite.api.Perspective.getTileHeight(
                    client,
                    client.getLocalPlayer().getLocalLocation(),
                    client.getLocalPlayer().getWorldLocation().getPlane());
            return height < UPPER_FLOOR_HEIGHT_THRESHOLD;
        }).orElse(false);
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

    /**
     * Climbs the bottom ladder to reach the upper floor.
     * Uses {@code walkFastCanvas} (no web-walk) so it only issues a canvas
     * click — no pathfinder involved.
     */
    /**
     * Milliseconds since last ladder click. Prevents spam-clicking the ladder
     * when the climb animation hasn't finished yet.
     * STATIC: shared across all session instances so climbing in one session
     * blocks ladder clicks in another session until the animation completes.
     */
    private static long lastLadderClickMs = 0L;
    private static final long LADDER_CLICK_COOLDOWN_MS = 3500L;

    protected void climbUp() {
        // Spam-click prevention: if we recently clicked a ladder, wait
        long now = System.currentTimeMillis();
        if (now - lastLadderClickMs < LADDER_CLICK_COOLDOWN_MS) {
            scheduleNext(600L);
            return;
        }

        // If already moving or animating (climb in progress), wait
        if (Rs2Player.isMoving() || Rs2Player.isAnimating()) {
            scheduleNext(600L);
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
        if (ladder.click("Climb-up")) {
            lastLadderClickMs = now;
            applyActionCooldown();
            scheduleNext(2000L);  // Block ticks while climb animation plays
        }
    }

    /**
     * Climbs the top ladder to reach the lower floor.
     * Uses {@code walkFastCanvas} (no web-walk) so it only issues a canvas
     * click — no pathfinder involved.
     */
    protected void climbDown() {
        // Spam-click prevention: if we recently clicked a ladder, wait
        long now = System.currentTimeMillis();
        if (now - lastLadderClickMs < LADDER_CLICK_COOLDOWN_MS) {
            scheduleNext(600L);
            return;
        }

        // If already moving or animating (climb in progress), wait
        if (Rs2Player.isMoving() || Rs2Player.isAnimating()) {
            scheduleNext(600L);
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
        if (ladder.click("Climb-down")) {
            lastLadderClickMs = now;
            applyActionCooldown();
            scheduleNext(2000L);  // Block ticks while climb animation plays
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