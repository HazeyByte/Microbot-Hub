package net.runelite.client.plugins.microbot.irkedfarmer.service;

import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.irkedfarmer.model.CompostType;
import net.runelite.client.plugins.microbot.irkedfarmer.model.FarmPatch;
import net.runelite.client.plugins.microbot.irkedfarmer.model.FruitTreeSpecies;
import net.runelite.client.plugins.microbot.irkedfarmer.model.HardwoodSpecies;
import net.runelite.client.plugins.microbot.irkedfarmer.model.TreeSpecies;
import net.runelite.client.plugins.microbot.irkedfarmer.task.InventoryPlan;
import net.runelite.client.plugins.microbot.irkedfarmer.task.ReservedItem;

import java.util.List;

/**
 * Builds a validated, single-run {@link InventoryPlan} for one tree category. This is the structural
 * fix for the legacy flat-inventory overflow: each category preps only what THAT run needs, so the
 * plan is checked against 28 slots before any banking. Items whose exact id depends on what the
 * player owns (energy dose, teleport tablet, digsite pendant charge) are slot-reserved and resolved
 * at bank time.
 */
public final class InventoryPlanner {

    private InventoryPlanner() {}

    /** Coins + tools + teleport runes + optional energy + reusable compost — needed by every run. */
    private static void addBase(InventoryPlan plan, CompostType compost, boolean useEnergyPotion) {
        plan.addStackable(ItemID.COINS, 10000);
        plan.addLoose(ItemID.SPADE, 1);
        plan.addLoose(ItemID.RAKE, 1);
        plan.addStackable(ItemID.LAWRUNE, 10);
        plan.addStackable(ItemID.FIRERUNE, 30);
        plan.addStackable(ItemID.AIRRUNE, 30);
        plan.addStackable(ItemID.EARTHRUNE, 30);
        plan.addStackable(ItemID.WATERRUNE, 30);
        if (useEnergyPotion) {
            plan.reserve(ReservedItem.RUN_ENERGY);
        }
        // Non-reusable compost is drawn from the patch leprechaun during the run (0 inventory slots).
        // Only a bottomless bucket rides along in the inventory.
        if (compost != null && compost.isReusable()) {
            plan.addLoose(compost.getItemId(), 1);
        }
    }

    public static InventoryPlan planRegularTrees(TreeSpecies species, List<FarmPatch> patches,
                                                 boolean protect, CompostType compost, boolean useEnergyPotion) {
        InventoryPlan plan = new InventoryPlan();
        addBase(plan, compost, useEnergyPotion);

        boolean priff = patches.contains(FarmPatch.PRIFDDINAS_CRYSTAL);
        int regular = (int) patches.stream().filter(p -> p != FarmPatch.PRIFDDINAS_CRYSTAL).count();

        if (regular > 0) {
            plan.addLoose(species.getSaplingId(), regular);
        }
        if (priff) {
            plan.addLoose(ItemID.PLANTPOT_CRYSTAL_TREE_SAPLING, 1); // crystal patch uses its own sapling
        }
        if (protect && regular > 0) {
            plan.addNoted(species.getPaymentId(), species.getPaymentAmount() * regular); // withdrawn noted = 1 slot
        }
        if (patches.contains(FarmPatch.TAVERLEY_TREE)) {
            plan.reserve(ReservedItem.TAVERLEY_TELEPORT);
        }
        if (patches.contains(FarmPatch.FARMING_GUILD_TREE)) {
            plan.reserve(ReservedItem.SKILLS_NECKLACE);
        }
        return plan;
    }

    public static InventoryPlan planFruitTrees(FruitTreeSpecies species, List<FarmPatch> patches,
                                               boolean protect, CompostType compost, boolean useEnergyPotion) {
        InventoryPlan plan = new InventoryPlan();
        addBase(plan, compost, useEnergyPotion);

        if (!patches.isEmpty()) {
            plan.addLoose(species.getSaplingId(), patches.size());
        }
        if (protect && !patches.isEmpty()) {
            plan.addNoted(species.getPaymentId(), species.getPaymentAmount() * patches.size());
        }
        if (patches.contains(FarmPatch.LLETYA_FRUIT)) {
            plan.reserve(ReservedItem.LLETYA_CRYSTAL);
        }
        if (patches.contains(FarmPatch.FARMING_GUILD_FRUIT)) {
            plan.reserve(ReservedItem.SKILLS_NECKLACE);
        }
        return plan;
    }

    public static InventoryPlan planHardwood(HardwoodSpecies species, List<FarmPatch> patches,
                                             boolean protect, CompostType compost, boolean useEnergyPotion) {
        InventoryPlan plan = new InventoryPlan();
        addBase(plan, compost, useEnergyPotion);

        if (!patches.isEmpty()) {
            plan.addLoose(species.getSaplingId(), patches.size());
        }
        if (protect && !patches.isEmpty()) {
            plan.addNoted(species.getPaymentId(), species.getPaymentAmount() * patches.size());
        }
        boolean fossil = patches.stream().anyMatch(p ->
                p == FarmPatch.FOSSIL_TREE_A || p == FarmPatch.FOSSIL_TREE_B || p == FarmPatch.FOSSIL_TREE_C);
        if (fossil) {
            plan.reserve(ReservedItem.DIGSITE_PENDANT);
        }
        return plan;
    }
}
