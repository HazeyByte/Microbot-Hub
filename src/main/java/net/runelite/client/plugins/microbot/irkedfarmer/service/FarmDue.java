package net.runelite.client.plugins.microbot.irkedfarmer.service;

import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.CropState;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.FarmingHandler;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.FarmingPatch;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.FarmingWorld;
import net.runelite.client.plugins.timetracking.Tab;

/**
 * "Is this run worth doing?" via FarmingWorld growth prediction. A run is due if ANY patch of the
 * given tab is not still GROWING (i.e. ready to check/replant, empty, diseased or dead) — so a run
 * where every patch is still growing is skipped instead of wasting a bank + walk trip.
 */
public final class FarmDue {

    private FarmDue() {}

    public static boolean anyReady(FarmingWorld farmingWorld, ClientThread clientThread, ConfigManager configManager, Tab tab) {
        FarmingHandler handler = new FarmingHandler(Microbot.getClient(), configManager);
        return Boolean.TRUE.equals(clientThread.runOnClientThreadOptional(() -> {
            var patches = farmingWorld.getTabs().get(tab);
            if (patches == null) {
                return true; // can't predict — attempt rather than silently skip
            }
            for (FarmingPatch patch : patches) {
                if (handler.predictPatch(patch) != CropState.GROWING) {
                    return true;
                }
            }
            return false;
        }).orElse(true));
    }
}
