package net.runelite.client.plugins.microbot.irkedfarmer;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.FarmingWorld;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity;

import javax.inject.Inject;
import net.runelite.client.plugins.microbot.irkedfarmer.task.BirdhouseRunTask;
import net.runelite.client.plugins.microbot.irkedfarmer.task.FarmingTask;
import net.runelite.client.plugins.microbot.irkedfarmer.task.FruitTreeRunTask;
import net.runelite.client.plugins.microbot.irkedfarmer.task.HardwoodRunTask;
import net.runelite.client.plugins.microbot.irkedfarmer.task.HerbRunTask;
import net.runelite.client.plugins.microbot.irkedfarmer.task.TaskScheduler;
import net.runelite.client.plugins.microbot.irkedfarmer.task.TreeRunTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Drives the {@link TaskScheduler} on the scheduled executor. One task advances per tick.
 *
 * Phase 0: no tasks are registered yet, so the scheduler announces an empty queue and stops.
 * Phase 1 populates {@link #buildTasks} with the Tree / Fruit / Hardwood tasks.
 */
@Slf4j
public class IrkedFarmerScript extends Script {

    private TaskScheduler scheduler;

    @Inject
    private FarmingWorld farmingWorld;
    @Inject
    private ClientThread clientThread;
    @Inject
    private ConfigManager configManager;

    public boolean run(IrkedFarmerConfig config) {
        Microbot.enableAutoRunOn = false;
        Rs2Antiban.resetAntibanSettings();
        Rs2AntibanSettings.naturalMouse = true;
        Rs2Antiban.setActivityIntensity(ActivityIntensity.LOW);

        scheduler = new TaskScheduler(config, buildTasks(config));

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return;

                if (!scheduler.tick()) {
                    log.info("irkedFarmer: all tasks complete");
                    shutdown();
                }
            } catch (Exception ex) {
                log.error("irkedFarmer tick error", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }

    /**
     * Registers concrete tasks in run order. Phase 1a: the three tree tasks (plan() is real;
     * execute() is stubbed until Phase 1b ports walking/planting). Herb + birdhouse land in Phases 3–4.
     */
    private List<FarmingTask> buildTasks(IrkedFarmerConfig config) {
        return new ArrayList<>(Arrays.asList(
                new TreeRunTask(config, farmingWorld, clientThread, configManager),
                new FruitTreeRunTask(config, farmingWorld, clientThread, configManager),
                new HardwoodRunTask(config),
                new HerbRunTask(config, farmingWorld, clientThread, configManager),
                new BirdhouseRunTask(config)
        ));
    }

    @Override
    public void shutdown() {
        super.shutdown();
    }
}
