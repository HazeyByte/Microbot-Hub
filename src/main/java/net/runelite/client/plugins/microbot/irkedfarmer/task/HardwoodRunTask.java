package net.runelite.client.plugins.microbot.irkedfarmer.task;

import lombok.RequiredArgsConstructor;
import net.runelite.client.plugins.microbot.irkedfarmer.IrkedFarmerConfig;
import net.runelite.client.plugins.microbot.irkedfarmer.model.FarmPatch;
import net.runelite.client.plugins.microbot.irkedfarmer.model.TreeKind;
import net.runelite.client.plugins.microbot.irkedfarmer.service.InventoryPlanner;
import net.runelite.client.plugins.microbot.irkedfarmer.service.PatchRunner;

import java.util.List;
import java.util.stream.Collectors;

/** Fossil Island + Avium Savannah hardwood patches. */
@RequiredArgsConstructor
public class HardwoodRunTask implements FarmingTask {
    private final IrkedFarmerConfig cfg;

    @Override
    public String name() {
        return "Hardwood run";
    }

    @Override
    public boolean isEnabled(IrkedFarmerConfig cfg) {
        return cfg.hardwoodRun();
    }

    @Override
    public boolean isDue() {
        return true; // no FarmingWorld tab tracks hardwood (Fossil/Avium); always attempt
    }

    @Override
    public InventoryPlan plan() {
        return InventoryPlanner.planHardwood(cfg.selectedHardwood(), enabledPatches(),
                cfg.protectHardwood(), cfg.compostType(), cfg.useEnergyPotion());
    }

    @Override
    public TaskResult execute() {
        return PatchRunner.run(name(), cfg, plan(), enabledPatches());
    }

    private List<FarmPatch> enabledPatches() {
        return FarmPatch.ofKind(TreeKind.HARD_TREE).stream()
                .filter(FarmPatch::hasRequiredLevel)
                .filter(FarmPatch::isEnabled)
                .collect(Collectors.toList());
    }
}
