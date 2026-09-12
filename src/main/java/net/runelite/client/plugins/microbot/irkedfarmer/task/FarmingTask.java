package net.runelite.client.plugins.microbot.irkedfarmer.task;

import net.runelite.client.plugins.microbot.irkedfarmer.IrkedFarmerConfig;

/**
 * One independent farming run (tree run, herb run, birdhouse run, ...). Each task is understandable
 * and testable in isolation: it knows what it does, whether it is enabled, whether anything is due,
 * exactly what inventory it needs, and how to run itself with its own recovery.
 *
 * New farm types become drop-in: implement this interface, add a config toggle, register the task.
 * No existing task changes.
 */
public interface FarmingTask {
    /** Display name, e.g. "Tree run". */
    String name();

    /** Whether the user enabled this activity. */
    boolean isEnabled(IrkedFarmerConfig cfg);

    /** True if at least one patch is ready to act on (not still growing). Uses FarmingWorld prediction. */
    boolean isDue();

    /** Exactly what THIS run needs; self-validates against 28 slots. */
    InventoryPlan plan();

    /** Runs the whole run and owns its recovery. */
    TaskResult execute();
}
