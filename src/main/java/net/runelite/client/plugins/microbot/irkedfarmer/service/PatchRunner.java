package net.runelite.client.plugins.microbot.irkedfarmer.service;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.irkedfarmer.IrkedFarmerConfig;
import net.runelite.client.plugins.microbot.irkedfarmer.model.FarmPatch;
import net.runelite.client.plugins.microbot.irkedfarmer.task.InventoryPlan;
import net.runelite.client.plugins.microbot.irkedfarmer.task.TaskResult;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.List;

/**
 * Shared execution loop for the tree/fruit/hardwood tasks: bank the run's inventory, then for each
 * patch route to it and drive {@link PatchInteractor} to completion. Bounded at every level so it can
 * never hang — a patch the walker can't reach (e.g. the Tree Gnome Village maze, pending proper
 * routing) is skipped after a timeout instead of getting stuck.
 */
@Slf4j
public final class PatchRunner {

    private PatchRunner() {}

    private static final int ARRIVE_DISTANCE = 6;
    private static final int MAX_PASSES_PER_PATCH = 25;

    public static TaskResult run(String taskName, IrkedFarmerConfig cfg, InventoryPlan plan, List<FarmPatch> patches) {
        if (!plan.feasible()) {
            return TaskResult.infeasible(taskName + " plan needs " + plan.slotCount() + " slots (>28)");
        }
        if (!BankService.prepare(plan, cfg)) {
            return TaskResult.failed(taskName + ": could not open bank to prepare inventory");
        }

        int handled = 0;
        int skipped = 0;
        for (FarmPatch patch : patches) {
            if (!Microbot.isLoggedIn()) {
                break;
            }
            if (!patch.hasRequiredLevel()) {
                continue;
            }
            if (!route(patch)) {
                log.warn("[{}] could not reach {} — skipping", taskName, patch.name());
                skipped++;
                continue;
            }
            if (interactToCompletion(cfg, patch)) {
                handled++;
            } else {
                skipped++;
            }
        }
        return TaskResult.completed(taskName + ": handled " + handled + ", skipped " + skipped);
    }

    /** Walk to the patch; returns true once within interaction range. Walker has its own timeout. */
    private static boolean route(FarmPatch patch) {
        WorldPoint loc = patch.getLocation();
        if (Rs2Player.distanceTo(loc) >= 0 && Rs2Player.distanceTo(loc) <= ARRIVE_DISTANCE) {
            return true;
        }
        // ponytail: default walker routing (handles teleports/transports). Tree Gnome Village maze +
        // instanced patches (Prifddinas/Fossil) need dedicated routing — tracked as slice 2b-next;
        // until then they degrade to a logged skip rather than a stuck bot.
        Rs2Walker.walkTo(loc, ARRIVE_DISTANCE);
        int d = Rs2Player.distanceTo(loc);
        return d >= 0 && d <= ARRIVE_DISTANCE + 2;
    }

    /** Drive the patch FSM until it settles; bounded so it can't loop forever. */
    private static boolean interactToCompletion(IrkedFarmerConfig cfg, FarmPatch patch) {
        for (int pass = 0; pass < MAX_PASSES_PER_PATCH; pass++) {
            if (!Microbot.isLoggedIn()) {
                return false;
            }
            PatchInteractor.Outcome outcome = PatchInteractor.handle(cfg, patch);
            switch (outcome) {
                case DONE:
                    return true;
                case NOT_FOUND:
                case GROWING:
                    return false;
                case RETRY:
                default:
                    Global.sleep(600, 1200);
            }
        }
        log.info("[{}] gave up after {} passes — moving on", patch.name(), MAX_PASSES_PER_PATCH);
        return false;
    }
}
