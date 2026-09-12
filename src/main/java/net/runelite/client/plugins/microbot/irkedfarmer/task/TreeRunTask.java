package net.runelite.client.plugins.microbot.irkedfarmer.task;

import lombok.RequiredArgsConstructor;
import net.runelite.client.plugins.microbot.irkedfarmer.IrkedFarmerConfig;
import net.runelite.client.plugins.microbot.irkedfarmer.model.FarmPatch;
import net.runelite.client.plugins.microbot.irkedfarmer.model.TreeKind;
import net.runelite.client.plugins.microbot.irkedfarmer.service.FarmDue;
import net.runelite.client.plugins.microbot.irkedfarmer.service.InventoryPlanner;
import net.runelite.client.plugins.microbot.irkedfarmer.service.PatchRunner;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.CropState;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.PatchImplementation;
import net.runelite.client.plugins.timetracking.Tab;

import java.util.List;
import java.util.stream.Collectors;

/** Regular tree patches + Prifddinas crystal tree. */
@RequiredArgsConstructor
public class TreeRunTask implements FarmingTask {
    private final IrkedFarmerConfig cfg;

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
        return cfg.runWhenNothingDue() || FarmDue.anyReady(Tab.TREE);
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

    private List<FarmPatch> enabledPatches() {
        return FarmPatch.ofKind(TreeKind.TREE).stream()
                .filter(FarmPatch::hasRequiredLevel)
                .filter(FarmPatch::isEnabled)
                .filter(p -> FarmDue.predictNearest(p.getLocation(), Tab.TREE, PatchImplementation.TREE) != CropState.GROWING)
                .collect(Collectors.toList());
    }
}
