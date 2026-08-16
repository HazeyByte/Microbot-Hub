package net.runelite.client.plugins.microbot.irkedgoatkiller;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

/**
 * irkedGoatkiller config. The activity has unlimited on-site spikes; the real choices are the fur-pouch
 * tier (storage), which side of the pit to stand on, and how loot/inventory space is managed.
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

    @ConfigSection(
            name = "Setup (read me)",
            description = "How to start the plugin",
            position = 0
    )
    String setupSection = "setup";

    @ConfigSection(name = "Inventory & loot", description = "How space is managed", position = 1)
    String lootSection = "loot";

    @ConfigItem(
            keyName = "instructions",
            name = "Before you start",
            description = "Stand on a supported Goat Pit tile • have Telegrab + law/air runes (or air staff) • carry your "
                    + "fur pouch (open) • pick your pouch and side below. The bot stays on your side and Telegrabs goats "
                    + "from the opposite side, fills the pouch first, drops horns to make room, empties the pit before "
                    + "re-lining, and only banks when fur can't be stored.",
            position = 0,
            section = setupSection
    )
    default boolean instructions() {
        return false;
    }

    @ConfigItem(
            keyName = "furPouch",
            name = "Fur pouch",
            description = "Which fur pouch you're carrying (open). Fur fills the pouch first, then the inventory.",
            position = 1,
            section = setupSection
    )
    default FurPouch furPouch() {
        return FurPouch.LARGE;
    }

    @ConfigItem(
            keyName = "standSide",
            name = "Stand side",
            description = "Which side of the pit to stand on. The bot stays put and only grabs goats on the opposite side.",
            position = 2,
            section = setupSection
    )
    default StandSide standSide() {
        return StandSide.SOUTH;
    }

    @ConfigItem(
            keyName = "keepGoatHorns",
            name = "Keep goat horns",
            description = "ON: horns are protected and never dropped (you'll bank sooner). OFF: horns are junk and are "
                    + "dropped to make room for more fur.",
            position = 0,
            section = lootSection
    )
    default boolean keepGoatHorns() {
        return false;
    }

    @ConfigItem(
            keyName = "dropGoatFur",
            name = "Drop goat fur",
            description = "ON: goat fur becomes disposable — dropped to keep hunting without banking (XP only). "
                    + "The pouch still fills first. OFF (recommended): fur is kept and banked.",
            position = 1,
            section = lootSection
    )
    default boolean dropGoatFur() {
        return false;
    }

    @ConfigItem(
            keyName = "dropEverything",
            name = "Drop everything",
            description = "ON: to make room, drop ALL non-essential items (still protects runes, spikes, the fur pouch, "
                    + "and anything protected by the options above). Only enable if you understand what gets discarded.",
            position = 2,
            section = lootSection
    )
    default boolean dropEverything() {
        return false;
    }

    @ConfigItem(
            keyName = "useAgilityShortcut",
            name = "Use agility shortcut",
            description = "Cross the Slippery basalt stepping stone when travelling to/from the bank. Falls back to the "
                    + "normal route on any failure.",
            position = 3,
            section = lootSection
    )
    default boolean useAgilityShortcut() {
        return false;
    }
}
