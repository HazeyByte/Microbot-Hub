package net.runelite.client.plugins.microbot.irkedfarmer.model;

import lombok.Getter;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.irkedfarmer.IrkedFarmerConfig;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.CropState;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.FarmingHandler;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.FarmingPatch;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.enums.Herbs;

import java.util.HashMap;
import java.util.Objects;

/**
 * One herb-run region (its herb patch + FarmingWorld prediction + teleport target). Ported from the
 * herbrun HerbPatch; region enable flags now read from {@link IrkedFarmerConfig}.
 */
@Getter
public class HerbPatch {
    private final FarmingPatch patch;
    private final String regionName;
    private final CropState prediction;
    private final WorldPoint location;
    private boolean enabled;
    private final HashMap<String, Integer> items = new HashMap<>();

    public HerbPatch(FarmingPatch patch, IrkedFarmerConfig config, FarmingHandler farmingHandler) {
        this.patch = patch;
        this.regionName = patch.getRegion().getName();
        this.prediction = farmingHandler.predictPatch(patch);
        this.location = getHerbFromName(regionName).getWorldPoint();
        switch (regionName) {
            case "Ardougne":
                this.enabled = config.enableArdougne();
                break;
            case "Catherby":
                this.items.put("Camelot teleport", 1);
                this.enabled = config.enableCatherby();
                break;
            case "Civitas illa Fortis":
                this.items.put("Civitas illa fortis teleport", 1);
                this.enabled = config.enableVarlamore();
                break;
            case "Falador":
                this.items.put("Explorer's ring", 1);
                this.enabled = config.enableFalador();
                break;
            case "Farming Guild":
                this.items.put("Skills necklace(", 1);
                this.enabled = config.enableGuild();
                break;
            case "Kourend":
                this.items.put("Xeric's talisman", 1);
                this.enabled = config.enableHosidius();
                break;
            case "Morytania":
                this.items.put("Ectophial", 1);
                this.enabled = config.enableMorytania();
                break;
            case "Troll Stronghold":
                this.items.put("Stony basalt", 1);
                this.enabled = config.enableTrollheim();
                break;
            case "Weiss":
                this.items.put("Icy basalt", 1);
                this.enabled = config.enableWeiss();
                break;
            case "Harmony":
                this.enabled = false;
                break;
            default:
                this.enabled = false;
        }
    }

    private static Herbs getHerbFromName(String regionName) {
        for (Herbs herb : Herbs.values()) {
            if (herb.getName().equalsIgnoreCase(regionName)) {
                return herb;
            }
        }
        return Herbs.NONE;
    }

    public boolean isInRange(int distance) {
        if (Objects.equals(regionName, "Weiss")) {
            return Rs2Player.getWorldLocation().getRegionID() == 11325;
        } else if (Objects.equals(regionName, "Troll Stronghold")) {
            return Rs2Player.getWorldLocation().getRegionID() == 11321;
        } else {
            return Rs2Player.getWorldLocation().distanceTo(location) < distance;
        }
    }
}
