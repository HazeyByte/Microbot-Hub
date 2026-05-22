package net.runelite.client.plugins.microbot.irkedSlayer.combat;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import javax.inject.Singleton;

@Singleton
public class IrkedSlayerCannonHandler {
    private static final String CANNON_OBJECT_NAME = "Dwarf multicannon";
    private static final String CANNON_PICKUP_ACTION = "Pick-up";
    private static final String CANNON_BASE_ITEM = "Cannon base";

    public boolean hasCannonParts() {
        return Rs2Inventory.hasItem("Cannon base")
                && Rs2Inventory.hasItem("Cannon stand")
                && Rs2Inventory.hasItem("Cannon barrels")
                && Rs2Inventory.hasItem("Cannon furnace");
    }

    public int getCannonballCount() {
        Rs2ItemModel cannonballs = Rs2Inventory.get("Cannonball");
        if (cannonballs != null) {
            return cannonballs.getQuantity();
        }

        Rs2ItemModel graniteCannonballs = Rs2Inventory.get("Granite cannonball");
        if (graniteCannonballs != null) {
            return graniteCannonballs.getQuantity();
        }
        return 0;
    }

    public boolean setupCannon() {
        return Rs2Inventory.interact(CANNON_BASE_ITEM, "Set-up");
    }

    public boolean isCannonPlacedNearby(WorldPoint anchor, int radius) {
        if (anchor == null) {
            return false;
        }
        return Microbot.getRs2TileObjectCache().query()
                .withName(CANNON_OBJECT_NAME)
                .within(anchor, radius)
                .nearest() != null;
    }

    public WorldPoint findCannonLocation(WorldPoint anchor, int radius) {
        if (anchor == null) {
            return null;
        }

        var cannon = Microbot.getRs2TileObjectCache().query()
                .withName(CANNON_OBJECT_NAME)
                .within(anchor, radius)
                .nearest();
        return cannon != null ? cannon.getWorldLocation() : null;
    }

    public boolean pickupCannon(WorldPoint anchor, int radius) {
        if (anchor == null) {
            return false;
        }

        var cannon = Microbot.getRs2TileObjectCache().query()
                .withName(CANNON_OBJECT_NAME)
                .within(anchor, radius)
                .nearest();
        return cannon != null && cannon.click(CANNON_PICKUP_ACTION);
    }

    public boolean walkToCannon(WorldPoint cannonLocation) {
        if (cannonLocation == null) {
            return false;
        }
        return Rs2Walker.walkTo(cannonLocation, 2);
    }
}
