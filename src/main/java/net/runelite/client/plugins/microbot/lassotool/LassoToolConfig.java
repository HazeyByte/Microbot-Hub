package net.runelite.client.plugins.microbot.lassotool;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;

@ConfigGroup(LassoToolConfig.CONFIG_GROUP)
public interface LassoToolConfig extends Config
{
    String CONFIG_GROUP = "microbot-lassotool";

    @ConfigItem(
            keyName = "toggleLassoHotkey",
            name = "Toggle Lasso Mode",
            description = "Hotkey to start/stop drawing a lasso area",
            position = 0
    )
    default Keybind toggleLassoHotkey()
    {
        return Keybind.NOT_SET;
    }

    @ConfigItem(
            keyName = "lassoColor",
            name = "Lasso Color",
            description = "Color of the lasso outline",
            position = 1
    )
    default java.awt.Color lassoColor()
    {
        return java.awt.Color.CYAN;
    }

    @ConfigItem(
            keyName = "fillColor",
            name = "Fill Color",
            description = "Color of the lasso fill (with alpha)",
            position = 2
    )
    default java.awt.Color fillColor()
    {
        return new java.awt.Color(0, 255, 255, 60);
    }

    @ConfigItem(
            keyName = "autoCopy",
            name = "Auto Copy to Clipboard",
            description = "Automatically copy the captured WorldPoint list to clipboard on lasso complete",
            position = 3
    )
    default boolean autoCopy()
    {
        return true;
    }

    @ConfigItem(
            keyName = "autoSaveToFile",
            name = "Auto Save to File",
            description = "Automatically save the lasso capture (tiles as JSON, Java mesh code snippet, and full data report) to a file in ~/.microbot/lasso-captures/ (e.g. lasso-20260603-201530.txt) when you finish drawing. Use the hotkey to activate drawing mode, draw your selection, then use the hotkey (or right/double-click) to finish and save.",
            position = 4
    )
    default boolean autoSaveToFile()
    {
        return false;
    }

    @ConfigItem(
            keyName = "snapToTiles",
            name = "Snap to Tiles",
            description = "Snap the internal selection polygon to tile centers for precise 'which tiles are inside'. The visible stroke remains freehand. Live yellow preview + final capture both respect this.",
            position = 5
    )
    default boolean snapToTiles()
    {
        return true;
    }
}
