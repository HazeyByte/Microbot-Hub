package net.runelite.client.plugins.microbot.irkedmlm;

import static net.runelite.client.plugins.microbot.irkedmlm.MlmAssert.assertEquals;
import static net.runelite.client.plugins.microbot.irkedmlm.MlmAssert.assertTrue;

import net.runelite.client.plugins.microbot.irkedmlm.enums.MLMMiningSpot;
import net.runelite.client.plugins.microbot.irkedmlm.enums.MLMStatus;
import net.runelite.client.plugins.microbot.irkedmlm.session.SessionSnapshot;

/**
 * Pins the overlay's pure text formatting. These strings drive the panel's measured width, so a
 * regression here is what produces the two failure modes the redesign exists to kill: a value column
 * that collides with its label, and a rate that silently loses precision as the session grows.
 */
class OverlayFormatTest {

    public static void main(String[] args) {
        OverlayFormatTest t = new OverlayFormatTest();
        t.abbreviateKeepsNumbersShortAtEveryMagnitude();
        t.abbreviateNeverWidensPastSixCharacters();
        t.shortDurationsDropTheMinutePartUntilItExists();
        t.clockGrowsAnHourFieldOnlyWhenNeeded();
        t.areaNamesRenderAsWordsNotEnumConstants();
        t.xpRateNeedsARealBaselineBeforeItReportsAnything();
        t.xpRateIsSteadyWhenXpKeepsPaceWithRuntime();
        System.out.println("OverlayFormatTest: OK");
    }

    void abbreviateKeepsNumbersShortAtEveryMagnitude() {
        assertEquals("0", IrkedMLMOverlay.abbreviate(0));
        assertEquals("999", IrkedMLMOverlay.abbreviate(999));
        assertEquals("1.0K", IrkedMLMOverlay.abbreviate(1_000));
        // Rounds up across the threshold: must not render the wider, off-pattern "10.0K".
        assertEquals("10K", IrkedMLMOverlay.abbreviate(9_950));
        assertEquals("9.9K", IrkedMLMOverlay.abbreviate(9_940));
        // Past 10K the decimal is noise, and dropping it is what keeps the rate column narrow.
        assertEquals("52K", IrkedMLMOverlay.abbreviate(52_100));
        assertEquals("390K", IrkedMLMOverlay.abbreviate(390_000));
        assertEquals("1.2M", IrkedMLMOverlay.abbreviate(1_240_000));
        assertEquals("12M", IrkedMLMOverlay.abbreviate(12_400_000));
    }

    void abbreviateNeverWidensPastSixCharacters() {
        // The panel reserves room for the rate column; an unbounded number would push it into the label.
        // Upper bound is far above any achievable MLM GP/hr; past ~1e9 the M suffix stops helping.
        long[] samples = {0, 999, 1_000, 9_940, 9_950, 9_999, 10_000, 999_949, 999_999, 1_000_000, 999_999_999};
        for (long n : samples) {
            String s = IrkedMLMOverlay.abbreviate(n);
            assertTrue(s.length() <= 6, "abbreviate(" + n + ") = \"" + s + "\" is too wide for the rate column");
        }
    }

    void shortDurationsDropTheMinutePartUntilItExists() {
        assertEquals("0s", IrkedMLMOverlay.formatShort(0));
        assertEquals("12s", IrkedMLMOverlay.formatShort(12_400));
        assertEquals("1m 02s", IrkedMLMOverlay.formatShort(62_000));
        assertEquals("72m 00s", IrkedMLMOverlay.formatShort(72 * 60_000L));
    }

    void clockGrowsAnHourFieldOnlyWhenNeeded() {
        assertEquals("00:00", IrkedMLMOverlay.formatClock(0));
        assertEquals("02:24", IrkedMLMOverlay.formatClock(144_000));
        assertEquals("59:59", IrkedMLMOverlay.formatClock(3_599_000));
        assertEquals("1:00:00", IrkedMLMOverlay.formatClock(3_600_000));
        assertEquals("13:04:05", IrkedMLMOverlay.formatClock((13 * 3600 + 4 * 60 + 5) * 1000L));
    }

    void areaNamesRenderAsWordsNotEnumConstants() {
        assertEquals("—", IrkedMLMOverlay.areaLabel(null));
        assertEquals("West Upper", IrkedMLMOverlay.areaLabel(MLMMiningSpot.WEST_UPPER));
        assertEquals("South East", IrkedMLMOverlay.areaLabel(MLMMiningSpot.SOUTH_EAST));
    }

    /**
     * The counting-down XP/hr bug: started from the login screen the script could not baseline
     * startXp, leaving it 0. currentXp - 0 is then the account's entire Mining XP, divided by a few
     * seconds of runtime — a huge number that shrinks every frame as the runtime denominator grows.
     */
    void xpRateNeedsARealBaselineBeforeItReportsAnything() {
        assertEquals(IrkedMLMOverlay.NO_VALUE, IrkedMLMOverlay.xpPerHour(snapshot(0, 8_000_000, 60_000)));
        assertEquals(IrkedMLMOverlay.NO_VALUE, IrkedMLMOverlay.xpPerHour(snapshot(-1, 8_000_000, 60_000)));
    }

    void xpRateIsSteadyWhenXpKeepsPaceWithRuntime() {
        // 1,000 xp in 1 minute and 10,000 in 10 must both report the same 60K/hr, not a decaying one.
        assertEquals("60K", IrkedMLMOverlay.xpPerHour(snapshot(500_000, 501_000, 60_000)));
        assertEquals("60K", IrkedMLMOverlay.xpPerHour(snapshot(500_000, 510_000, 600_000)));
    }

    private static SessionSnapshot snapshot(int startXp, int currentXp, long runMs) {
        return new SessionSnapshot(
                MLMStatus.MINING, MLMMiningSpot.WEST_UPPER,
                0, 189,
                System.currentTimeMillis() - runMs,
                0L, 0, startXp, currentXp,
                0, 0, 0, 0, 0,
                "Mining Vein", System.currentTimeMillis(), 0L);
    }
}
