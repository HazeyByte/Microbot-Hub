package net.runelite.client;

import net.runelite.client.plugins.microbot.irkedfarmer.model.CompostType;
import net.runelite.client.plugins.microbot.irkedfarmer.model.FarmPatch;
import net.runelite.client.plugins.microbot.irkedfarmer.model.FruitTreeSpecies;
import net.runelite.client.plugins.microbot.irkedfarmer.model.HardwoodSpecies;
import net.runelite.client.plugins.microbot.irkedfarmer.model.TreeKind;
import net.runelite.client.plugins.microbot.irkedfarmer.model.TreeSpecies;
import net.runelite.client.plugins.microbot.irkedfarmer.service.InventoryPlanner;
import net.runelite.client.plugins.microbot.irkedfarmer.task.InventoryPlan;
import net.runelite.client.plugins.microbot.irkedfarmer.task.ReservedItem;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Self-check for InventoryPlan slot accounting AND the per-task planner — the structural fix for the
 * legacy tree runner's overflow bug. Run as a plain main() (repo has no JUnit). Fails loudly if the
 * slot math or a real per-category plan regresses.
 */
public class IrkedFarmerInventoryPlanCheck {
    public static void main(String[] args) {
        slotMath();
        realPlans();
        System.out.println("IrkedFarmerInventoryPlanCheck: OK");
    }

    private static void slotMath() {
        // equipped = 0; stackable/noted = 1; loose = 1 per unit; reservation = 1
        InventoryPlan plan = new InventoryPlan()
                .addEquipped(1)                       // 0
                .addStackable(2, 1000)                // 1 (coins)
                .addNoted(3, 50)                      // 1 (noted protection payment)
                .addLoose(4, 5)                       // 5 (saplings)
                .addLoose(5, 1)                       // 1 (spade)
                .reserve(ReservedItem.DIGSITE_PENDANT); // 1
        check(plan.slotCount() == 9, "expected 9 slots, got " + plan.slotCount());
        check(plan.feasible(), "9 slots should be feasible");

        InventoryPlan atLimit = new InventoryPlan().addLoose(9, InventoryPlan.MAX_SLOTS);
        check(atLimit.slotCount() == 28 && atLimit.feasible(), "28 slots should be feasible");

        // one over — the case the legacy flat inventory silently overflowed
        InventoryPlan over = new InventoryPlan().addLoose(9, InventoryPlan.MAX_SLOTS + 1);
        check(!over.feasible(), "29 loose items must be infeasible");
    }

    private static void realPlans() {
        // Every patch of each kind, protection + energy on, ultracompost (non-reusable: 0 slots).
        List<FarmPatch> trees = FarmPatch.ofKind(TreeKind.TREE);
        List<FarmPatch> fruit = FarmPatch.ofKind(TreeKind.FRUIT_TREE);
        List<FarmPatch> hard = FarmPatch.ofKind(TreeKind.HARD_TREE);

        InventoryPlan treePlan = InventoryPlanner.planRegularTrees(TreeSpecies.MAGIC, trees, true, CompostType.ULTRACOMPOST, true);
        InventoryPlan fruitPlan = InventoryPlanner.planFruitTrees(FruitTreeSpecies.PALM, fruit, true, CompostType.ULTRACOMPOST, true);
        InventoryPlan hardPlan = InventoryPlanner.planHardwood(HardwoodSpecies.MAHOGANY, hard, true, CompostType.ULTRACOMPOST, true);

        // Each single-category run must fit — this is the whole point of per-task planning.
        check(treePlan.feasible(), "full tree run must fit 28 (got " + treePlan.slotCount() + ")");
        check(fruitPlan.feasible(), "full fruit run must fit 28 (got " + fruitPlan.slotCount() + ")");
        check(hardPlan.feasible(), "full hardwood run must fit 28 (got " + hardPlan.slotCount() + ")");

        // The legacy bug: all three at once overflows. Proven by the combined slot total > 28.
        int combined = treePlan.slotCount() + fruitPlan.slotCount() + hardPlan.slotCount();
        check(combined > InventoryPlan.MAX_SLOTS, "combined plan should exceed 28 (legacy overflow), got " + combined);

        System.out.println("  tree=" + treePlan.slotCount() + " fruit=" + fruitPlan.slotCount()
                + " hardwood=" + hardPlan.slotCount() + " combined=" + combined);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
