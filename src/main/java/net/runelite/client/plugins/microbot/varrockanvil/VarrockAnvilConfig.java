package net.runelite.client.plugins.microbot.varrockanvil;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigInformation;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.plugins.microbot.varrockanvil.enums.AnvilItem;
import net.runelite.client.plugins.microbot.varrockanvil.enums.Bars;

@ConfigGroup("VarrockAnvil")
@ConfigInformation(
    "Smiths at the anvils south of <b>Varrock west bank</b>. Start near the bank with bars and a " +
    "hammer banked — a plain hammer or either Imcando variant, carried or worn.<br><br>" +
    "<b>Sailing keels</b>: pick a metal, then <i>Keel parts</i> or <i>Large keel parts</i>. Dragon " +
    "works for Large keel parts only; regular dragon keel parts need the Dragon Forge.<br><br>" +
    "Bugs or requests: @StickToTheScript on Discord."
)
public interface VarrockAnvilConfig extends Config {

    @ConfigItem(
            keyName = "barType",
            name = "Bar type",
            description = "The bar to smith with. For keel parts this also picks the keel's metal.",
            position = 0
    )
    default Bars sBarType() {
        return Bars.BRONZE;
    }

    @ConfigItem(
            keyName = "smithObject",
            name = "Item",
            description = "What to make at the anvil.",
            position = 1
    )
    default AnvilItem sAnvilItem() {
        return AnvilItem.SCIMITAR;
    }

    @ConfigItem(
            keyName = "logout",
            name = "Log out when finished",
            description = "Log out when the script stops — out of materials, or no hammer found.",
            position = 2
    )
    default boolean sLogout() {
        return true;
    }

    @ConfigItem(
            keyName = "showOverlay",
            name = "Show overlay",
            description = "Draw the on-screen panel with state, runtime, XP and rates.",
            position = 3
    )
    default boolean sShowOverlay() {
        return true;
    }

    @ConfigItem(
            keyName = "debug",
            name = "Verbose logging",
            description = "Log every state decision, not just milestones. Useful for bug reports.",
            position = 4
    )
    default boolean sDebug() {
        return false;
    }
}
