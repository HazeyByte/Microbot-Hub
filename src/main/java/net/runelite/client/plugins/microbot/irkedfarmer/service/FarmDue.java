package net.runelite.client.plugins.microbot.irkedfarmer.service;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.CropState;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.FarmingPatch;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.PatchImplementation;
import net.runelite.client.plugins.microbot.util.farming.Rs2Farming;
import net.runelite.client.plugins.timetracking.Tab;

import java.util.List;
import java.util.Map;

/**
 * "Is this run worth doing?" via FarmingWorld growth prediction, through the existing {@link Rs2Farming}
 * wrapper rather than hand-rolling FarmingWorld/FarmingHandler/client-thread wiring a second time. A run
 * is due if ANY patch of the given tab is not still GROWING (ready to check/replant, empty, diseased or
 * dead) — a run where every patch is still growing is skipped instead of wasting a bank + walk trip.
 */
public final class FarmDue {

    private FarmDue() {}

    /**
     * Nearest-match tolerance in tiles between a {@code FarmPatch}'s hardcoded location and the
     * QuestHelper {@link FarmingPatch}'s tracked anchor tile. See docs/PLUGIN_DEBUGGING_NOTES.md §7:
     * hardcoded-coordinate drift is handled with a tolerance elsewhere in this codebase, not exact
     * equality. Regional patches of the same kind are hundreds of tiles apart, so this can't cross-match
     * the wrong patch.
     */
    private static final int MATCH_TOLERANCE = 10;

    public static boolean anyReady(Tab tab) {
        List<FarmingPatch> patches = Rs2Farming.getPatchesByTab(tab);
        if (patches.isEmpty()) {
            return true; // can't predict — attempt rather than silently skip
        }
        Map<FarmingPatch, CropState> states = Rs2Farming.batchPredictAll(patches);
        for (FarmingPatch patch : patches) {
            if (states.get(patch) != CropState.GROWING) {
                return true;
            }
        }
        return false;
    }

    /**
     * Predicted state of whichever tracked {@link FarmingPatch} is nearest {@code location} (within
     * {@link #MATCH_TOLERANCE} tiles) among patches of the given tab/implementation, or {@code null} if
     * none is tracked yet (never visited, or Timetracking hasn't observed it). Callers must treat
     * {@code null} as "attempt anyway", not "skip".
     */
    public static CropState predictNearest(WorldPoint location, Tab tab, PatchImplementation implementation) {
        FarmingPatch nearest = null;
        int bestDistance = MATCH_TOLERANCE + 1;
        for (FarmingPatch patch : Rs2Farming.getPatchesByTab(tab)) {
            if (patch.getImplementation() != implementation) {
                continue;
            }
            int distance = location.distanceTo(patch.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = patch;
            }
        }
        return nearest == null ? null : Rs2Farming.predictPatchState(nearest);
    }
}
