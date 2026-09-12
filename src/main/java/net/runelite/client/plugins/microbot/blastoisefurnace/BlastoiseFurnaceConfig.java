



package net.runelite.client.plugins.microbot.blastoisefurnace;

import net.runelite.client.config.*;
import net.runelite.client.plugins.microbot.blastoisefurnace.enums.Bars;

@ConfigGroup("blastoisefurnace")
@ConfigInformation(
        "<b>Setup</b><br />" +
        "1. Start at the Blast Furnace in Keldagrim (or on the stairs above it).<br />" +
        "2. In the bank: ore for your chosen bar, a coal bag (all bars except gold), ice gloves or smiths gloves (i), and stamina / energy potions.<br />" +
        "3. Gold or hybrid bars: also bank goldsmith gauntlets.<br />" +
        "4. Keep coins in the bank for the coffer. Under 60 Smithing the 2,500 gp / 10 min foreman fee is paid automatically.<br /><br />" +
        "<b>Options</b><br />" +
        "&bull; <b>Bars</b> - which bar to smelt.<br />" +
        "&bull; <b>Human-like behaviour</b> - reaction delays, the antiban smithing profile and occasional mistakes.<br />" +
        "&bull; <b>Coffer top-up</b> - coins kept in the coffer; refilled only when it runs empty.<br /><br />" +
        "The bot tops up the coffer, cools the bars with ice gloves, and walks out via the stairs when the bank runs out of ore.")
public interface BlastoiseFurnaceConfig extends Config {
    @ConfigSection(
            name = "Blast Furnace Settings",
            description = "Blast Furnace Settings",
            position = 0,
            closedByDefault = false
    )
    String bFSettingsSection = "bFSettings";

    @ConfigItem(
            keyName = "Bars",
            name = "Bars",
            description = "Bars",
            position = 1,
            section = "bFSettings"
    )
    default Bars getBars() {
        return Bars.STEEL_BAR;
    }

    @ConfigItem(
            keyName = "humanisation",
            name = "Human-like behaviour",
            description = "Adds reaction delays, occasional micro-pauses, small mistakes and varied rhythm so the bot reads less robotically.",
            position = 2,
            section = "bFSettings"
    )
    default boolean humanisation() {
        return true;
    }

    @Range(min = 5000, max = 20_000_000)
    @ConfigItem(
            keyName = "cofferTarget",
            name = "Coffer top-up (coins)",
            description = "How many coins to keep in the furnace coffer. Only refilled when the coffer runs empty.",
            position = 3,
            section = "bFSettings"
    )
    default int cofferTarget() {
        return 72000;
    }

    @ConfigSection(
            name = "Credits",
            description = "Credits",
            position = 2,
            closedByDefault = false
    )
    String Credits = "Credits";
    @ConfigItem(
            keyName = "Credits",
            name = "Credits",
            description = "Credits",
            position = 3,
            section = "Credits"
    )
    default String Credits() {
        return "Created by: Fishy \n\nUpdated by: Acun, Wassuppzzz";
    }
}