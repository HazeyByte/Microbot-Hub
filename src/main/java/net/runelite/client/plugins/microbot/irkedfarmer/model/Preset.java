package net.runelite.client.plugins.microbot.irkedfarmer.model;

import lombok.RequiredArgsConstructor;

/** One-click Run Builder bundle. Ticks/unticks the 5 activity toggles; CUSTOM leaves them alone. */
@RequiredArgsConstructor
public enum Preset {
    CUSTOM("Custom", false, false, false, false, false),
    FULL("Full run", true, true, true, true, true),
    HERBS("Herbs", false, false, false, true, false),
    TREES("Trees", true, false, false, false, false),
    FRUIT("Fruit", false, true, false, false, false),
    HARDWOOD("Hardwood", false, false, true, false, false),
    BIRDHOUSES("Birdhouses", false, false, false, false, true);

    private final String label;
    public final boolean trees;
    public final boolean fruit;
    public final boolean hardwood;
    public final boolean herbs;
    public final boolean birdhouses;

    @Override
    public String toString() {
        return label;
    }
}
