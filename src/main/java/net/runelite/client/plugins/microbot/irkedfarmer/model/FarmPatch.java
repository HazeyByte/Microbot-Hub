package net.runelite.client.plugins.microbot.irkedfarmer.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Every tree/fruit/hardwood patch this plugin can service. Ported verbatim (object ids, coords,
 * farming-level gates, leprechaun ids) from the legacy tree runner's inner Patch enum — this is the
 * hard-won farming knowledge the rewrite preserves.
 *
 * {@link #configKey} is the stable key the config uses for this patch's enable toggle.
 */
@Getter
@RequiredArgsConstructor
public enum FarmPatch {
    GNOME_STRONGHOLD_FRUIT(7962, new WorldPoint(2473, 3446, 0), TreeKind.FRUIT_TREE, 1, 0, "gnomeStrongholdFruit"),
    GNOME_STRONGHOLD_TREE(19147, new WorldPoint(2437, 3417, 0), TreeKind.TREE, 1, 0, "gnomeStrongholdTree"),
    TREE_GNOME_VILLAGE_FRUIT(7963, new WorldPoint(2490, 3181, 0), TreeKind.FRUIT_TREE, 1, 0, "treeGnomeVillageFruit"),
    FARMING_GUILD_TREE(33732, new WorldPoint(1234, 3734, 0), TreeKind.TREE, 65, 0, "farmingGuildTree"),
    FARMING_GUILD_FRUIT(34007, new WorldPoint(1244, 3757, 0), TreeKind.FRUIT_TREE, 85, 0, "farmingGuildFruit"),
    TAVERLEY_TREE(8388, new WorldPoint(2936, 3440, 0), TreeKind.TREE, 1, 0, "taverleyTree"),
    FALADOR_TREE(8389, new WorldPoint(3001, 3374, 0), TreeKind.TREE, 1, 0, "faladorTree"),
    LUMBRIDGE_TREE(8391, new WorldPoint(3195, 3228, 0), TreeKind.TREE, 1, 0, "lumbridgeTree"),
    VARROCK_TREE(8390, new WorldPoint(3226, 3458, 0), TreeKind.TREE, 1, 0, "varrockTree"),
    BRIMHAVEN_FRUIT(7964, new WorldPoint(2765, 3213, 0), TreeKind.FRUIT_TREE, 1, 0, "brimhavenFruit"),
    CATHERBY_FRUIT(7965, new WorldPoint(2858, 3432, 0), TreeKind.FRUIT_TREE, 1, 0, "catherbyFruit"),
    LLETYA_FRUIT(26579, new WorldPoint(2345, 3163, 0), TreeKind.FRUIT_TREE, 1, 0, "lletyaFruit"),
    FOSSIL_TREE_A(30482, new WorldPoint(3718, 3835, 0), TreeKind.HARD_TREE, 1, 0, "fossilHardwood"),
    FOSSIL_TREE_B(30480, new WorldPoint(3709, 3836, 0), TreeKind.HARD_TREE, 1, 0, "fossilHardwood"),
    FOSSIL_TREE_C(30481, new WorldPoint(3701, 3840, 0), TreeKind.HARD_TREE, 1, 0, "fossilHardwood"),
    AUBURNVALE_TREE(56953, new WorldPoint(1365, 3320, 0), TreeKind.TREE, 1, 0, "auburnvaleTree"),
    KASTORI_FRUIT(56955, new WorldPoint(1349, 3058, 0), TreeKind.FRUIT_TREE, 1, 12765, "kastoriFruit"),
    PRIFDDINAS_CRYSTAL(34906, new WorldPoint(3291, 6117, 0), TreeKind.TREE, 74, 0, "prifddinasCrystal"),
    AVIUM_SAVANNAH_HARDWOOD(50692, new WorldPoint(1684, 2974, 0), TreeKind.HARD_TREE, 1, 0, "aviumHardwood");

    private final int objectId;
    private final WorldPoint location;
    private final TreeKind kind;
    private final int farmingLevel;
    private final int leprechaunId;
    private final String configKey;

    public boolean hasRequiredLevel() {
        if (Rs2Player.getSkillRequirement(Skill.FARMING, this.farmingLevel)) {
            return true;
        }
        Microbot.log(name() + " requires level " + this.farmingLevel + " farming.");
        return false;
    }

    public static List<FarmPatch> ofKind(TreeKind kind) {
        return Arrays.stream(values()).filter(p -> p.kind == kind).collect(Collectors.toList());
    }
}
