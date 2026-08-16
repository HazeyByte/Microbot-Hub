package net.runelite.client.plugins.microbot.irkedgoatkiller;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

/**
 * irkedGoatkiller config. Fresh group ("irkedgoatkiller"). MVP is deliberately tiny — the activity
 * has unlimited on-site spikes and no banking, so the only real choice is what to do with loot.
 */
@ConfigGroup(IrkedGoatkillerConfig.GROUP)
public interface IrkedGoatkillerConfig extends Config {

    String GROUP = "irkedgoatkiller";

    @ConfigItem(
            keyName = "dropGoatHorn",
            name = "Drop goat horns",
            description = "Drop Goat horn (item 9735) after clearing the pit — it's low-value junk unless you're crushing them for reagents.",
            position = 0
    )
    default boolean dropGoatHorn() {
        return true;
    }
}
