package net.runelite.client.plugins.microbot.irkedmlm;

/**
 * Owns the post-deposit sack-count projection state that papers over MLM's laggy sack varbit.
 *
 * <h3>The problem it solves</h3>
 * After pay-dirt is deposited into the hopper, the game's sack varbit ({@code 5558}) updates a few
 * ticks late. Decisions made in that window (is the sack full? should we keep mining?) must not
 * under-estimate fullness, so we carry a short-lived projection of the true sack count and hand it
 * out until the live varbit "catches up".
 *
 * <h3>The four fields and their lifecycle</h3>
 * <ul>
 *   <li>{@code lastPreDepositSackCount} — sack count captured fresh at the <em>start</em> of a deposit
 *       ({@link #capturePreDeposit}); consumed once when the deposit completes
 *       ({@link #consumePreDeposit}). Cleared on session reset because an abandoned deposit's pre is
 *       stale — but a <em>completed</em> deposit's projection below must survive a session reset.</li>
 *   <li>{@code payDirtJustDeposited} / {@code sackValueKnownAfterLastDeposit} / {@code lastDepositTimestampMs}
 *       — the completed-deposit projection ({@link #applyProjection}). Held for
 *       {@link #PROJECTION_WINDOW_MS} or until the varbit catches up, whichever comes first.</li>
 * </ul>
 *
 * <h3>Threading</h3>
 * Mutating reads ({@link #effective}(…, mutate=true)) run only on the executor thread. The overlay
 * snapshot path passes {@code mutate=false} so it never clears projection state out from under an
 * in-progress executor tick. This matches the pre-extraction contract exactly; the fields are plain
 * (non-volatile) as before.
 */
final class SackState {

    static final long PROJECTION_WINDOW_MS = 30_000L;

    private int  payDirtJustDeposited;
    private int  sackValueKnownAfterLastDeposit;
    private int  lastPreDepositSackCount;
    private long lastDepositTimestampMs;

    /** Full clear — initialise() and hard recovery. */
    void reset() {
        payDirtJustDeposited           = 0;
        sackValueKnownAfterLastDeposit = 0;
        lastPreDepositSackCount        = 0;
        lastDepositTimestampMs         = 0L;
    }

    /** Clears the completed-deposit projection but leaves the timestamp (recovery reads it). */
    void clearProjection() {
        payDirtJustDeposited           = 0;
        sackValueKnownAfterLastDeposit = 0;
        lastPreDepositSackCount        = 0;
    }

    /** Clears only the in-flight pre-deposit capture (session reset abandons the current deposit). */
    void clearPreDeposit() {
        lastPreDepositSackCount = 0;
    }

    void capturePreDeposit(int preSack) {
        lastPreDepositSackCount = preSack;
    }

    /** Returns the captured pre (or {@code fallback} if none) and consumes it. */
    int consumePreDeposit(int fallback) {
        int v = lastPreDepositSackCount > 0 ? lastPreDepositSackCount : fallback;
        lastPreDepositSackCount = 0;
        return v;
    }

    /** Stores the result of a completed deposit as the active projection. */
    void applyProjection(int deposited, int projectedSack, long nowMs) {
        payDirtJustDeposited           = deposited;
        sackValueKnownAfterLastDeposit = projectedSack;
        lastDepositTimestampMs         = nowMs;
    }

    boolean hasProjection() {
        return payDirtJustDeposited > 0;
    }

    /** True while a recent deposit's projection is still within its validity window. */
    boolean withinProjectionWindow(long now) {
        return payDirtJustDeposited > 0
                && lastDepositTimestampMs > 0
                && now - lastDepositTimestampMs < PROJECTION_WINDOW_MS;
    }

    /** Best-guess total when the live varbit momentarily reads 0 after a deposit. */
    int projectedFallbackTotal() {
        return sackValueKnownAfterLastDeposit > 0
                ? sackValueKnownAfterLastDeposit
                : lastPreDepositSackCount + payDirtJustDeposited;
    }

    /**
     * Projection-aware sack count. During post-deposit lag returns the known-higher value as a floor;
     * once the live {@code base} catches up (or the window expires) drops the projection and returns
     * {@code base}. Only clears when {@code mutate} is true (executor path).
     */
    int effective(int base, long now, boolean mutate) {
        if (payDirtJustDeposited > 0 && lastDepositTimestampMs > 0) {
            long since = now - lastDepositTimestampMs;
            if (since < PROJECTION_WINDOW_MS) {
                if (base >= sackValueKnownAfterLastDeposit) {
                    if (mutate) {
                        clearProjection();
                    }
                    return base;
                }
                return SackTracker.effectiveSackCount(
                        base,
                        sackValueKnownAfterLastDeposit,
                        payDirtJustDeposited,
                        lastDepositTimestampMs,
                        now,
                        PROJECTION_WINDOW_MS);
            } else if (mutate) {
                clearProjection();
            }
        }
        return base;
    }

    int  payDirtJustDeposited()    { return payDirtJustDeposited; }
    int  knownAfterDeposit()       { return sackValueKnownAfterLastDeposit; }
    int  preDepositSackCount()     { return lastPreDepositSackCount; }
    long lastDepositTimestamp()    { return lastDepositTimestampMs; }
}
