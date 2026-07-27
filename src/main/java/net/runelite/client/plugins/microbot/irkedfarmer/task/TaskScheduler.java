package net.runelite.client.plugins.microbot.irkedfarmer.task;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.irkedfarmer.IrkedFarmerConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the enabled farming tasks one at a time. Each tick advances at most one task: check due,
 * validate its ≤28-slot plan, execute, report. Banking happens per task inside execute() (Phase 1+),
 * so inventory overflow is structurally impossible — no task ever preps more than one run's worth.
 *
 * Phase 0: the queue is empty; the scheduler simply announces an empty queue and completes.
 */
@Slf4j
public class TaskScheduler {
    /** Human-readable status for the overlay. */
    public static volatile String status = "Idle";

    private final IrkedFarmerConfig cfg;
    private final List<FarmingTask> enabled = new ArrayList<>();
    private int cursor = 0;
    private boolean announced = false;

    public TaskScheduler(IrkedFarmerConfig cfg, List<FarmingTask> tasks) {
        this.cfg = cfg;
        for (FarmingTask t : tasks) {
            if (t.isEnabled(cfg)) {
                enabled.add(t);
            }
        }
    }

    /** @return true if there is more work to do; false when the queue is exhausted. */
    public boolean tick() {
        if (!announced) {
            announced = true;
            log.info("irkedFarmer queue: {} task(s) enabled{}", enabled.size(),
                    enabled.isEmpty() ? " — nothing to do" : "");
            for (FarmingTask t : enabled) {
                log.info("  - {}", t.name());
            }
        }

        if (cursor >= enabled.size()) {
            status = "All tasks complete";
            return false;
        }

        FarmingTask task = enabled.get(cursor);
        cursor++;

        if (!task.isDue()) {
            log.info("[{}] skipped — nothing due", task.name());
            status = task.name() + ": nothing due";
            return true;
        }

        InventoryPlan plan = task.plan();
        if (plan == null || !plan.feasible()) {
            int slots = plan == null ? -1 : plan.slotCount();
            log.warn("[{}] infeasible — plan needs {} slots (>{}); re-plan or split", task.name(), slots, InventoryPlan.MAX_SLOTS);
            status = task.name() + ": infeasible (" + slots + " slots)";
            return true;
        }

        status = "Running: " + task.name();
        TaskResult result = task.execute();
        log.info("[{}] {}", task.name(), result);
        status = task.name() + " → " + result.status;
        return true;
    }
}
