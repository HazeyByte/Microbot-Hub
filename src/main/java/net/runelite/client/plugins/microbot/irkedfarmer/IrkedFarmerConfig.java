package net.runelite.client.plugins.microbot.irkedfarmer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.plugins.microbot.irkedfarmer.model.AllotmentSeedType;
import net.runelite.client.plugins.microbot.irkedfarmer.model.BirdhouseLog;
import net.runelite.client.plugins.microbot.irkedfarmer.model.CompostType;
import net.runelite.client.plugins.microbot.irkedfarmer.model.FlowerSeedType;
import net.runelite.client.plugins.microbot.irkedfarmer.model.FruitTreeSpecies;
import net.runelite.client.plugins.microbot.irkedfarmer.model.HardwoodSpecies;
import net.runelite.client.plugins.microbot.irkedfarmer.model.HerbSeedType;
import net.runelite.client.plugins.microbot.irkedfarmer.model.Preset;
import net.runelite.client.plugins.microbot.irkedfarmer.model.TreeSpecies;

/**
 * irkedFarmer AIO config. Deliberately a fresh group ("irkedfarmer") — NOT the legacy "example"
 * group the old tree runner used, which caused persisted-state surprises.
 *
 * Config UX (see docs/superpowers/specs/2026-07-26-irkedfarmer-config-services-redesign.md): a Run
 * Builder section up top with a Preset picker + one toggle per activity, then one collapsible section
 * per activity holding just that activity's own settings. RuneLite's `@ConfigItem` has no conditional
 * "unhide" attribute (verified against the annotation source) — collapsible `@ConfigSection` is the
 * real, supported declutter mechanism here, not true reveal-on-demand.
 */
@ConfigGroup("irkedfarmer")
public interface IrkedFarmerConfig extends Config {

    String GROUP = "irkedfarmer";

    // ---- Run Builder ----
    @ConfigSection(
            name = "Run Builder",
            description = "Pick a preset or tick individual activities to include in the run",
            position = 0
    )
    String runBuilderSection = "runBuilder";

    @ConfigItem(
            keyName = "preset",
            name = "Preset",
            description = "One-click activity bundle. Ticks/unticks the activities below; pick Custom to set them yourself.",
            section = runBuilderSection,
            position = 0
    )
    default Preset preset() {
        return Preset.CUSTOM;
    }

    @ConfigItem(
            keyName = "treeRun",
            name = "Tree run",
            description = "Regular tree patches + Prifddinas crystal tree",
            section = runBuilderSection,
            position = 1
    )
    default boolean treeRun() {
        return false;
    }

    @ConfigItem(
            keyName = "fruitTreeRun",
            name = "Fruit tree run",
            description = "Fruit tree patches",
            section = runBuilderSection,
            position = 2
    )
    default boolean fruitTreeRun() {
        return false;
    }

    @ConfigItem(
            keyName = "hardwoodRun",
            name = "Hardwood run",
            description = "Fossil Island + Avium Savannah hardwood patches",
            section = runBuilderSection,
            position = 3
    )
    default boolean hardwoodRun() {
        return false;
    }

    @ConfigItem(
            keyName = "herbRun",
            name = "Herb run",
            description = "Herb + flower + allotment patches",
            section = runBuilderSection,
            position = 4
    )
    default boolean herbRun() {
        return false;
    }

    @ConfigItem(
            keyName = "birdhouseRun",
            name = "Birdhouse run",
            description = "Fossil Island birdhouses",
            section = runBuilderSection,
            position = 5
    )
    default boolean birdhouseRun() {
        return false;
    }

    // ---- General ----
    @ConfigSection(
            name = "General",
            description = "Global behaviour shared by every activity",
            position = 1
    )
    String generalSection = "general";

    @ConfigItem(
            keyName = "runWhenNothingDue",
            name = "Run even if nothing is due",
            description = "Off (default): auto-skip a run whose patches are all still growing. On: always attempt enabled runs.",
            section = generalSection,
            position = 0
    )
    default boolean runWhenNothingDue() {
        return false;
    }

    @ConfigItem(
            keyName = "compostType",
            name = "Compost",
            description = "Applied intelligently per patch: trees/fruit/hardwood get it only when NOT paying for protection " +
                    "(protection already eliminates disease; compost adds no tree yield), herbs/allotments/flowers always " +
                    "get it (real yield gain). One setting for every activity.",
            section = generalSection,
            position = 1
    )
    default CompostType compostType() {
        return CompostType.SUPERCOMPOST;
    }

    @ConfigItem(
            keyName = "useEnergyPotion",
            name = "Use energy potion",
            description = "Carry a stamina/energy potion for run energy",
            section = generalSection,
            position = 2
    )
    default boolean useEnergyPotion() {
        return false;
    }

    @ConfigItem(
            keyName = "useGraceful",
            name = "Wear graceful",
            description = "Equip the graceful outfit for reduced weight/run drain",
            section = generalSection,
            position = 3
    )
    default boolean useGraceful() {
        return false;
    }

    // ---- Trees ----
    @ConfigSection(name = "Trees", description = "Regular tree run settings", position = 2, closedByDefault = true)
    String treesSection = "trees";

    @ConfigItem(keyName = "selectedTree", name = "Tree sapling", description = "Which regular tree to plant",
            section = treesSection, position = 0)
    default TreeSpecies selectedTree() {
        return TreeSpecies.MAGIC;
    }

    @ConfigItem(keyName = "protectTrees", name = "Protect trees", description = "Pay the farmer to protect regular trees (optional; never aborts the run)",
            section = treesSection, position = 1)
    default boolean protectTrees() {
        return false;
    }

    // ---- Fruit trees ----
    @ConfigSection(name = "Fruit Trees", description = "Fruit tree run settings", position = 3, closedByDefault = true)
    String fruitSection = "fruit";

    @ConfigItem(keyName = "selectedFruitTree", name = "Fruit sapling", description = "Which fruit tree to plant",
            section = fruitSection, position = 0)
    default FruitTreeSpecies selectedFruitTree() {
        return FruitTreeSpecies.PAPAYA;
    }

    @ConfigItem(keyName = "protectFruitTrees", name = "Protect fruit trees", description = "Pay to protect fruit trees (optional; never aborts the run)",
            section = fruitSection, position = 1)
    default boolean protectFruitTrees() {
        return false;
    }

    // ---- Hardwood ----
    @ConfigSection(name = "Hardwood", description = "Hardwood tree run settings", position = 4, closedByDefault = true)
    String hardwoodSection = "hardwood";

    @ConfigItem(keyName = "selectedHardwood", name = "Hardwood sapling", description = "Which hardwood tree to plant",
            section = hardwoodSection, position = 0)
    default HardwoodSpecies selectedHardwood() {
        return HardwoodSpecies.MAHOGANY;
    }

    @ConfigItem(keyName = "protectHardwood", name = "Protect hardwood", description = "Pay to protect hardwood trees (optional; never aborts the run)",
            section = hardwoodSection, position = 1)
    default boolean protectHardwood() {
        return false;
    }

    // ---- Herbs ----
    @ConfigSection(name = "Herbs", description = "Herb run settings (herb + flower + allotment patches)", position = 5, closedByDefault = true)
    String herbSection = "herbs";

    @ConfigItem(keyName = "herbSeed", name = "Herb seed", description = "Herb seed to plant ('Best available' picks the highest you can plant)",
            section = herbSection, position = 0)
    default HerbSeedType herbSeed() {
        return HerbSeedType.BEST;
    }

    @ConfigItem(keyName = "herbArdougne", name = "Ardougne", description = "Ardougne herb patch", section = herbSection, position = 1)
    default boolean enableArdougne() { return true; }

    @ConfigItem(keyName = "herbCatherby", name = "Catherby", description = "Catherby herb patch", section = herbSection, position = 2)
    default boolean enableCatherby() { return true; }

    @ConfigItem(keyName = "herbFalador", name = "Falador", description = "Falador herb patch", section = herbSection, position = 3)
    default boolean enableFalador() { return true; }

    @ConfigItem(keyName = "herbGuild", name = "Farming Guild", description = "Farming Guild herb patch", section = herbSection, position = 4)
    default boolean enableGuild() { return true; }

    @ConfigItem(keyName = "herbHosidius", name = "Hosidius (Kourend)", description = "Hosidius herb patch", section = herbSection, position = 5)
    default boolean enableHosidius() { return true; }

    @ConfigItem(keyName = "herbMorytania", name = "Morytania", description = "Morytania herb patch", section = herbSection, position = 6)
    default boolean enableMorytania() { return true; }

    @ConfigItem(keyName = "herbTrollheim", name = "Troll Stronghold", description = "Troll Stronghold herb patch", section = herbSection, position = 7)
    default boolean enableTrollheim() { return false; }

    @ConfigItem(keyName = "herbWeiss", name = "Weiss", description = "Weiss herb patch", section = herbSection, position = 8)
    default boolean enableWeiss() { return false; }

    @ConfigItem(keyName = "herbVarlamore", name = "Civitas illa Fortis (Varlamore)", description = "Varlamore herb patch", section = herbSection, position = 9)
    default boolean enableVarlamore() { return false; }

    @ConfigItem(keyName = "herbEnableFlowers", name = "Do flower patches", description = "Also plant flowers at herb-run locations", section = herbSection, position = 10)
    default boolean enableFlowers() { return true; }

    @ConfigItem(keyName = "herbEnableAllotments", name = "Do allotment patches", description = "Also plant allotments at herb-run locations", section = herbSection, position = 11)
    default boolean enableAllotments() { return true; }

    @ConfigItem(keyName = "herbAllotmentSeed", name = "Allotment seed", description = "Allotment seed ('Best available' picks the highest you can plant)", section = herbSection, position = 12)
    default AllotmentSeedType allotmentSeed() { return AllotmentSeedType.BEST; }

    @ConfigItem(keyName = "herbFlowerSeed", name = "Flower seed", description = "Flower seed to plant", section = herbSection, position = 13)
    default FlowerSeedType flowerSeed() { return FlowerSeedType.MARIGOLD; }

    @ConfigItem(keyName = "herbDropEmptyBuckets", name = "Drop empty buckets", description = "Drop empty compost buckets to save space", section = herbSection, position = 14)
    default boolean dropEmptyBuckets() { return true; }

    @ConfigItem(keyName = "herbAllowPartialRuns", name = "Allow partial runs", description = "Continue even if the bank can't supply every seed", section = herbSection, position = 15)
    default boolean allowPartialRuns() { return true; }

    @ConfigItem(keyName = "herbGoToBank", name = "Bank when finished", description = "Walk to the bank and deposit after the herb run", section = herbSection, position = 16)
    default boolean herbGoToBank() { return true; }

    // ---- Birdhouses ----
    @ConfigSection(name = "Birdhouses", description = "Fossil Island birdhouse run settings", position = 6, closedByDefault = true)
    String birdhouseSection = "birdhouse";

    @ConfigItem(keyName = "birdhouseLogType", name = "Log type", description = "Log used to build the birdhouses",
            section = birdhouseSection, position = 0)
    default BirdhouseLog birdhouseLogType() {
        return BirdhouseLog.YEW_LOGS;
    }

    @ConfigItem(keyName = "birdhouseGoToBank", name = "Bank when finished", description = "Walk to the Fossil Island bank and deposit after the run",
            section = birdhouseSection, position = 1)
    default boolean birdhouseGoToBank() {
        return true;
    }
}
