package net.runelite.client.plugins.microbot.irkedchoppa;

import net.runelite.client.config.*;
import net.runelite.client.plugins.microbot.irkedchoppa.enums.IrkedChoppaAction;
import net.runelite.client.plugins.microbot.irkedchoppa.enums.IrkedChoppaTree;
import net.runelite.client.plugins.microbot.irkedchoppa.enums.IrkedChoppaWalkBack;

@ConfigGroup(IrkedChoppaConfig.configGroup)
@ConfigInformation(
        "<html>" +
                "<p>This script automatically cuts trees and handles the logs based on your settings.</p>" +
                "<p>Forestry support implemented by Yuof and TaF</p>" +
                "<p>If forestry is enabled, remember to use one of the forestry worlds for best results</p>" +
                "</html>")
public interface IrkedChoppaConfig extends Config {
    String configGroup = "IrkedChoppa";
    @ConfigSection(
            name = "General",
            description = "General",
            position = 0
    )
    String generalSection = "general";
    @ConfigSection(
            name = "Inventory management",
            description = "Configure how to handle full inventory",
            position = 1
    )
    String inventorySection = "inventory";

    @ConfigSection(
            name = "Forestry",
            description = "Forestry events",
            position = 2,
            closedByDefault = true
    )
    String forestrySection = "forestry";

    @ConfigItem(
            keyName = "enableWoodcutting",
            name = "Enable auto woodcutting",
            description = "Turn off to keep forestry helpers active without cutting trees automatically",
            position = 0,
            section = generalSection
    )
    default boolean enableWoodcutting() {
        return true;
    }


    @ConfigItem(
            keyName = "Tree",
            name = "Tree",
            description = "Choose the tree",
            position = 2,
            section = generalSection
    )
    default IrkedChoppaTree TREE() {
        return IrkedChoppaTree.TREE;
    }

    @ConfigItem(
            keyName = "DistanceToStray",
            name = "Distance to Stray",
            description = "Set how far you can travel from your initial position in tiles",
            position = 3,
            section = generalSection
    )
    default int distanceToStray() {
        return 20;
    }

    @ConfigItem(
            keyName = "Action",
            name = "Action",
            description = "What to do when inventory is full",
            position = 0,
            section = inventorySection
    )
    default IrkedChoppaAction action() {
        return IrkedChoppaAction.DROP;
    }





    @ConfigItem(
            keyName = "ItemsToBank",
            name = "Additional items to bank",
            description = "Extra items to bank (comma separated)",
            position = 5,
            section = inventorySection
    )
    default String itemsToBank() {
        return "logs,sturdy beehive parts,petal garland,golden pheasant egg,pheasant tail feathers,fox whistle,key,nest,fruit";
    }
    @ConfigItem(
            keyName = "ItemsToKeep",
            name = "Items to keep when dropping",
            description = "Items to keep in inventory (comma separated)",
            position = 6,
            section = inventorySection
    )
    default String itemsToKeep() {
        return "axe,tinderbox,knife,bowstring,crystal shard,demon tear,petal garland,golden pheasant egg,pheasant tail feathers,fox whistle,key, Anima-infused bark";
    }

    @ConfigItem(
            keyName = "WalkBack",
            name = "Walk back",
            description = "Walk back to initial spot or last cut down",
            position = 5,
            section = inventorySection
    )
    default IrkedChoppaWalkBack walkBack() {
        return IrkedChoppaWalkBack.LAST_LOCATION;
    }

    @ConfigItem(
            keyName = "enableForestry",
            name = "Enable forestry",
            description = "Enable forestry features",
            position = 0,
            section = forestrySection
    )
    default boolean enableForestry() {
        return false;
    }

     @ConfigItem(
             keyName = "eggEvent",
             name = "Enable Egg Event",
             description = "Enable the Egg forestry event",
             position = 1,
             section = forestrySection
     )
     default boolean eggEvent() {
         return true;
     }

     @ConfigItem(
             keyName = "entlingsEvent",
             name = "Enable Entlings Event",
             description = "Enable the Entlings forestry event",
             position = 2,
             section = forestrySection
     )
     default boolean entlingsEvent() {
         return true;
     }

     @ConfigItem(
             keyName = "flowersEvent",
             name = "Enable Flowers Event",
             description = "Enable the Flowers forestry event",
             position = 3,
             section = forestrySection,
             hidden = false //TODO: Remove this when the event is implemented
     )
     default boolean flowersEvent() {
         return false;
     }

     @ConfigItem(
             keyName = "foxEvent",
             name = "Enable Fox Event",
             description = "Enable the Fox forestry event",
             position = 4,
             section = forestrySection
     )
     default boolean foxEvent() {
         return true;
     }

     @ConfigItem(
             keyName = "hivesEvent",
             name = "Enable Hives Event",
             description = "Enable the Hives forestry event",
             position = 5,
             section = forestrySection
     )
     default boolean hivesEvent() {
         return true;
     }

     @ConfigItem(
             keyName = "leprechaunEvent",
             name = "Enable Leprechaun Event",
             description = "Enable the Leprechaun forestry event",
             position = 6,
             section = forestrySection
     )
     default boolean leprechaunEvent() {
         return true;
     }

     @ConfigItem(
             keyName = "ritualEvent",
             name = "Enable Ritual Event",
             description = "Enable the Ritual forestry event",
             position = 7,
             section = forestrySection
     )
     default boolean ritualEvent() {
         return true;
     }

     @ConfigItem(
             keyName = "rootEvent",
             name = "Enable Root Event",
             description = "Enable the Root forestry event",
             position = 8,
             section = forestrySection
     )
     default boolean rootEvent() {
         return true;
     }

     @ConfigItem(
             keyName = "saplingEvent",
             name = "Enable Struggling Sapling Event",
             description = "Enable the Struggling Sapling forestry event",
             position = 9,
             section = forestrySection
     )
     default boolean saplingEvent() {
         return true;
     }
}
