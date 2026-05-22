/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel
 */
package net.runelite.client.plugins.microbot.irkedminer.priority;

import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.irkedminer.data.Ores;

public final class PrioritySelection {
    private final boolean priorityDriving;
    private final Ores selectedOre;
    private final Rs2TileObjectModel targetRock;

    private PrioritySelection(boolean priorityDriving, Ores selectedOre, Rs2TileObjectModel targetRock) {
        this.priorityDriving = priorityDriving;
        this.selectedOre = selectedOre;
        this.targetRock = targetRock;
    }

    public static PrioritySelection disabled() {
        return new PrioritySelection(false, null, null);
    }

    public static PrioritySelection noTargets() {
        return new PrioritySelection(true, null, null);
    }

    public static PrioritySelection target(Ores selectedOre, Rs2TileObjectModel targetRock) {
        return new PrioritySelection(true, selectedOre, targetRock);
    }

    public boolean isPriorityDriving() {
        return this.priorityDriving;
    }

    public Ores getSelectedOre() {
        return this.selectedOre;
    }

    public Rs2TileObjectModel getTargetRock() {
        return this.targetRock;
    }
}

