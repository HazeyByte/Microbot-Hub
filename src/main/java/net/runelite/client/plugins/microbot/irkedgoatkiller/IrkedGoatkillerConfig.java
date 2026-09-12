package net.runelite.client.plugins.microbot.irkedgoatkiller;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigInformation;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(IrkedGoatkillerConfig.GROUP)
@ConfigInformation(
        "Stand next to a Goat Pit with Telekinetic Grab (law + air runes, or an air staff) and your open fur pouch.<br><br>"
        + "The bot lines the pit, grabs goats from the opposite side into it, clears it for loot, and repeats — "
        + "banking or dropping loot per your Loot setting below."
)
public interface IrkedGoatkillerConfig extends Config {

    String GROUP = "irkedgoatkiller";

    /** Fur pouch tiers and their Goat-fur capacity (OSRS: Small 14 / Medium 21 / Large 28). */
    enum FurPouch {
        NONE("None", 0),
        SMALL("Small", 14),
        MEDIUM("Medium", 21),
        LARGE("Large", 28);

        private final String label;
        private final int capacity;

        FurPouch(String label, int capacity) {
            this.label = label;
            this.capacity = capacity;
        }

        public int capacity() {
            return capacity;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Which side of the pit to stand on. Goats are lured from the opposite side. */
    enum StandSide {
        SOUTH, EAST, NORTH, WEST
    }

    /** What to do with a loot item when the inventory is full. One decision per item. */
    enum LootAction {
        BANK, DROP
    }

    @ConfigSection(name = "Setup", description = "Where you stand and what you carry", position = 0)
    String setupSection = "setup";

    @ConfigSection(name = "Loot", description = "What to do with fur and horns", position = 1)
    String lootSection = "loot";

    @ConfigSection(name = "Travel", description = "Banking route", position = 2)
    String travelSection = "travel";

    @ConfigItem(
            keyName = "standSide",
            name = "Stand side",
            description = "Which side of the pit you stand on. Goats are grabbed from the opposite side.",
            position = 0,
            section = setupSection
    )
    default StandSide standSide() {
        return StandSide.SOUTH;
    }

    @ConfigItem(
            keyName = "furPouch",
            name = "Fur pouch",
            description = "The open fur pouch you're carrying. Fur fills the pouch first, then your inventory.",
            position = 1,
            section = setupSection
    )
    default FurPouch furPouch() {
        return FurPouch.LARGE;
    }

    @ConfigItem(
            keyName = "furAction",
            name = "Goat fur",
            description = "Bank it (money) or drop it (XP only). Fur fills the pouch first either way.",
            position = 0,
            section = lootSection
    )
    default LootAction furAction() {
        return LootAction.BANK;
    }

    @ConfigItem(
            keyName = "hornAction",
            name = "Goat horn",
            description = "Bank it (reagent) or drop it (junk). Set both to Drop for a pure-XP, never-bank run.",
            position = 1,
            section = lootSection
    )
    default LootAction hornAction() {
        return LootAction.DROP;
    }

    @ConfigItem(
            keyName = "useAgilityShortcut",
            name = "Agility shortcut",
            description = "On the bank trip, cross the Slippery basalt stepping stone. Falls back to the normal route on failure.",
            position = 0,
            section = travelSection
    )
    default boolean useAgilityShortcut() {
        return false;
    }

    @ConfigItem(
            keyName = "debugOverlay",
            name = "Target debug overlay",
            description = "Show a live breakdown of every nearby goat and why it's a valid target or rejected (distance, side, range, other players).",
            position = 1,
            section = travelSection
    )
    default boolean debugOverlay() {
        return false;
    }
}
