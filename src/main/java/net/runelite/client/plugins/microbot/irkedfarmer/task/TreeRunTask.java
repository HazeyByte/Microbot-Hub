package net.runelite.client.plugins.microbot.irkedfarmer.task;

import lombok.RequiredArgsConstructor;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.irkedfarmer.IrkedFarmerConfig;
import net.runelite.client.plugins.microbot.irkedfarmer.model.FarmPatch;
import net.runelite.client.plugins.microbot.irkedfarmer.model.TreeKind;
import net.runelite.client.plugins.microbot.irkedfarmer.service.FarmDue;
import net.runelite.client.plugins.microbot.irkedfarmer.service.InventoryPlanner;
import net.runelite.client.plugins.microbot.irkedfarmer.service.PatchRunner;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.FarmingWorld;
import net.runelite.client.plugins.timetracking.Tab;

import java.util.List;
import java.util.stream.Collectors;

/** Regular tree patches + Prifddinas crystal tree. */
@RequiredArgsConstructor
public class TreeRunTask implements FarmingTask {
    private final IrkedFarmerConfig cfg;
    private final FarmingWorld farmingWorld;
    private final ClientThread clientThread;
    private final ConfigManager configManager;

    @Override
    public String name() {
        return "Tree run";
    }

    @Override
    public boolean isEnabled(IrkedFarmerConfig cfg) {
        return cfg.treeRun();
    }

    @Override
    public boolean isDue() {
        return cfg.runWhenNothingDue() || FarmDue.anyReady(farmingWorld, clientThread, configManager, Tab.TREE);
    }

    @Override
    public InventoryPlan plan() {
        return InventoryPlanner.planRegularTrees(cfg.selectedTree(), enabledPatches(),
                cfg.protectTrees(), cfg.compostType(), cfg.useEnergyPotion());
    }

    @Override
    public TaskResult execute() {
        return PatchRunner.run(name(), cfg, plan(), enabledPatches());
    }

    /** ponytail: all level-eligible tree patches for now; per-patch config toggles land in Phase 1b. */
    private List<FarmPatch> enabledPatches() {
        return FarmPatch.ofKind(TreeKind.TREE).stream()
                .filter(FarmPatch::hasRequiredLevel)
                .collect(Collectors.toList());
    }
}
