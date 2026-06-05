package net.runelite.client.plugins.microbot.irkedmlm.session;

import lombok.Value;
import net.runelite.client.plugins.microbot.irkedmlm.enums.MLMMiningSpot;
import net.runelite.client.plugins.microbot.irkedmlm.enums.MLMStatus;

/**
 * Immutable snapshot of the current Motherlode Mine session state.
 * Used by the overlay to display real-time information without
 * directly accessing mutable session internals.
 */
@Value
public class SessionSnapshot {

    // -----------------------------------------------------------------------
    // Core status
    // -----------------------------------------------------------------------

    MLMStatus      status;
    MLMMiningSpot  miningSpot;

    // -----------------------------------------------------------------------
    // Sack
    // -----------------------------------------------------------------------

    int  sackCount;
    int  maxSackSize;

    // -----------------------------------------------------------------------
    // Timing
    // -----------------------------------------------------------------------

    long startTimeMs;
    long timestampMs;

    // -----------------------------------------------------------------------
    // Mining
    // -----------------------------------------------------------------------

    /** String representation of the target vein WorldPoint, or {@code null}. */
    String  targetVein;
    boolean activelyMining;
    int     failedClicks;
    int     recoveryAttempts;

    // -----------------------------------------------------------------------
    // Hopper
    // -----------------------------------------------------------------------

    boolean depositingHopper;
    long    lastHopperDepositTimestampMs;

    // -----------------------------------------------------------------------
    // Repair
    // -----------------------------------------------------------------------

    boolean repairing;
    String  repairPhase;
    int     brokenStrutCount;
    long    repairSuppressedUntilMs;

    // -----------------------------------------------------------------------
    // Economy
    // -----------------------------------------------------------------------

    long totalValueGained;
    int  gainedNuggets;
    int  startXp;
    int  currentXp;

    // -----------------------------------------------------------------------
    // Ore session counts
    // -----------------------------------------------------------------------

    int runiteCount;
    int adamantiteCount;
    int mithrilCount;
    int goldCount;
    int coalCount;

    // -----------------------------------------------------------------------
    // Sub-state labels (never null — default to "Idle")
    // -----------------------------------------------------------------------

    String subStateLabel;

    // -----------------------------------------------------------------------
    // Navigation
    // -----------------------------------------------------------------------

    boolean returningToSpot;

    // -----------------------------------------------------------------------
    // Factory
    // -----------------------------------------------------------------------

    /**
     * Returns a fully-initialised empty snapshot suitable for the overlay
     * before the script has started.
     */
    public static SessionSnapshot empty() {
        long now = System.currentTimeMillis();
        return new SessionSnapshot(
                /* status                */ MLMStatus.IDLE,
                /* miningSpot            */ null,
                /* currentSackCount      */ 0,
                /* maxSackSize           */ 108,
                /* startTimeMs           */ now,
                /* timestampMs           */ now,
                /* targetVein            */ null,
                /* activelyMining        */ false,
                /* failedClicks          */ 0,
                /* recoveryAttempts      */ 0,
                /* depositingHopper      */ false,
                /* lastHopperDepositTimestampMs*/ 0L,
                /* repairing             */ false,
                /* repairPhase           */ "idle",
                /* brokenStrutCount      */ 0,
                /* repairSuppressedUntilMs*/ 0L,
                /* totalValueGained      */ 0L,
                /* gainedNuggets         */ 0,
                /* startXp               */ 0,
                /* currentXp             */ 0,
                /* runiteCount           */ 0,
                /* adamantiteCount       */ 0,
                /* mithrilCount          */ 0,
                /* goldCount             */ 0,
                /* coalCount             */ 0,
                /* subStateLabel         */ "Idle",
                /* returningToSpot       */ false
        );
    }
}
