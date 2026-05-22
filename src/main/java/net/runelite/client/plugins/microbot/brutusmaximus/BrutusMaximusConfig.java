package net.runelite.client.plugins.microbot.brutusmaximus;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigInformation;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.plugins.microbot.util.misc.Rs2Food;

@ConfigGroup("BrutusMaximus")
@ConfigInformation(
    "<div style='font-family:sans-serif;line-height:1.35;'>" +
        "<div style='color:#ffc864;font-size:16px;font-weight:bold;'>BrutusMaximus</div>" +
        "<div style='color:#cdbb95;margin:1px 0 6px 0;'>Cow boss automation</div>" +
        "<div style='border-top:1px solid #4f4636;margin:0 0 6px 0;'></div>" +
        "<div style='color:#ddd2be;'>" +
            "1) Start near a bank.<br/>" +
            "2) Configure food + amount.<br/>" +
            "3) Enable Cowbell teleport to route to Brutus gate.<br/>" +
            "4) Script will bank when food is low, travel, enter, fight, dodge, and loot." +
        "</div>" +
    "</div>"
)
public interface BrutusMaximusConfig extends Config {

    @ConfigSection(
        name = "General",
        description = "Core behavior",
        position = 0
    )
    String generalSection = "generalSection";

    @ConfigSection(
        name = "Travel",
        description = "Travel and gate entry",
        position = 1
    )
    String travelSection = "travelSection";

    @ConfigSection(
        name = "Food",
        description = "Food and banking",
        position = 2
    )
    String foodSection = "foodSection";

    @ConfigSection(
        name = "Loot",
        description = "Looting rules",
        position = 3
    )
    String lootSection = "lootSection";

    @ConfigSection(
        name = "Debug",
        description = "Debug options",
        position = 4
    )
    String debugSection = "debugSection";

    @ConfigItem(
        keyName = "enableMechanicDodging",
        name = "Enable Mechanic Dodging",
        description = "Dodge Brutus mechanics from Brutus animations (Snort/Growl).",
        position = 0,
        section = generalSection
    )
    default boolean enableMechanicDodging() {
        return true;
    }

    @ConfigItem(
        keyName = "useSpecialAttack",
        name = "Use Special Attack",
        description = "Enable weapon special attack usage during Brutus fights.",
        position = 1,
        section = generalSection
    )
    default boolean useSpecialAttack() {
        return true;
    }

    @ConfigItem(
        keyName = "ringCowbellAfterKill",
        name = "Ring Cowbell After Kill",
        description = "Ring Cowbell amulet after each Brutus kill to help trigger faster respawn.",
        position = 2,
        section = generalSection
    )
    default boolean ringCowbellAfterKill() {
        return false;
    }

    @ConfigItem(
        keyName = "specialAttackEnergyPercent",
        name = "Spec Energy %",
        description = "Minimum special attack energy required before triggering spec.",
        position = 3,
        section = generalSection
    )
    @Range(min = 0, max = 100)
    default int specialAttackEnergyPercent() {
        return 50;
    }

    @ConfigItem(
        keyName = "useCowbellTeleport",
        name = "Use Cowbell Teleport",
        description = "Use Cowbell amulet teleport to reach Brutus area.",
        position = 0,
        section = travelSection
    )
    default boolean useCowbellTeleport() {
        return true;
    }

    @ConfigItem(
        keyName = "foodSelection",
        name = "Food Selection",
        description = "Food to withdraw and eat.",
        position = 0,
        section = foodSection
    )
    default Rs2Food foodSelection() {
        return Rs2Food.SHARK;
    }

    @ConfigItem(
        keyName = "foodAmount",
        name = "Food Amount",
        description = "How many food items to withdraw per bank.",
        position = 1,
        section = foodSection
    )
    @Range(min = 1, max = 28)
    default int foodAmount() {
        return 16;
    }

    @ConfigItem(
        keyName = "eatAtPercent",
        name = "Eat At %",
        description = "Eat configured food when HP percent is at or below this threshold.",
        position = 2,
        section = foodSection
    )
    @Range(min = 1, max = 99)
    default int eatAtPercent() {
        return 55;
    }

    @ConfigItem(
        keyName = "bankWhenFoodBelow",
        name = "Bank When Food Below",
        description = "Bank/resupply when configured food count is below this amount.",
        position = 3,
        section = foodSection
    )
    @Range(max = 28)
    default int bankWhenFoodBelow() {
        return 1;
    }

    @ConfigItem(
        keyName = "enableLooting",
        name = "Enable Looting",
        description = "Loot by name/value while waiting or between attacks.",
        position = 0,
        section = lootSection
    )
    default boolean enableLooting() {
        return true;
    }

    @ConfigItem(
        keyName = "lootItems",
        name = "Loot Items",
        description = "Comma-separated item names to always loot.",
        position = 1,
        section = lootSection
    )
    default String lootItems() {
        return "";
    }

    @ConfigItem(
        keyName = "lootValueThreshold",
        name = "Loot Value Threshold",
        description = "Loot items worth at least this value.",
        position = 2,
        section = lootSection
    )
    default int lootValueThreshold() {
        return 5000;
    }

    @ConfigItem(
        keyName = "enableDebugLogging",
        name = "Enable Debug Logging",
        description = "Log detailed state/cue messages.",
        position = 0,
        section = debugSection
    )
    default boolean enableDebugLogging() {
        return false;
    }
}
