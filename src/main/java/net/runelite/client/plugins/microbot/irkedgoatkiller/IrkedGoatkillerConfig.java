package net.runelite.client.plugins.microbot.irkedgoatkiller;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

/**
 * irkedGoatkiller config. The activity has unlimited on-site spikes; the real choices are the fur-pouch
 * tier (drives storage capacity), which side of the pit to stand on, and loot/travel preferences.
 */
@ConfigGroup(IrkedGoatkillerConfig.GROUP)
public interface IrkedGoatkillerConfig extends Config {

    String GROUP = "irkedgoatkiller";

    /** Fur pouch tiers and their Goat-fur capacity (OSRS: Small 14 / Medium 21 / Large 28). */
    enum FurPouch {
        NONE("None", 0),
        SMALL("Small (Larupia) — 14", 14),
        MEDIUM("Medium (Graahk) — 21", 21),
        LARGE("Large (Kyatt) — 28", 28);

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

    @ConfigSection(name = "Setup", description = "Core setup", position = 0)
    String setupSection = "setup";

    @ConfigSection(name = "Loot & travel", description = "Loot and banking behaviour", position = 1)
    String lootSection = "loot";

    @ConfigItem(
            keyName = "furPouch",
            name = "Fur pouch",
            description = "Which fur pouch you're carrying (open). Fur fills the pouch first, then the inventory.",
            position = 0,
            section = setupSection
    )
    default FurPouch furPouch() {
        return FurPouch.LARGE;
    }

    @ConfigItem(
            keyName = "standSide",
            name = "Stand side",
            description = "Which side of the pit to stand on. The bot stays put and only grabs goats on the opposite side.",
            position = 1,
            section = setupSection
    )
    default StandSide standSide() {
        return StandSide.SOUTH;
    }

    @ConfigItem(
            keyName = "dropGoatHorn",
            name = "Drop goat horns",
            description = "Drop Goat horns (item 9735) to free inventory space for more fur. Fur is never dropped.",
            position = 0,
            section = lootSection
    )
    default boolean dropGoatHorn() {
        return true;
    }

    @ConfigItem(
            keyName = "useAgilityShortcut",
            name = "Use agility shortcut",
            description = "Use the Slippery basalt stepping stone when travelling to/from the bank. Falls back to the normal route on any failure.",
            position = 1,
            section = lootSection
    )
    default boolean useAgilityShortcut() {
        return false;
    }
}
