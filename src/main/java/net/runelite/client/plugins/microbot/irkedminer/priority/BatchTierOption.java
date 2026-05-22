/*
 * Decompiled with CFR 0.152.
 */
package net.runelite.client.plugins.microbot.irkedminer.priority;

import net.runelite.client.plugins.microbot.irkedminer.data.Ores;

public enum BatchTierOption {
    NONE("None", null),
    TIN("Tin", Ores.TIN),
    COPPER("Copper", Ores.COPPER),
    CLAY("Clay", Ores.CLAY),
    IRON("Iron", Ores.IRON),
    SILVER("Silver", Ores.SILVER),
    COAL("Coal", Ores.COAL),
    GOLD("Gold", Ores.GOLD),
    GEM("Gem", Ores.GEM),
    MITHRIL("Mithril", Ores.MITHRIL),
    ADAMANTITE("Adamantite", Ores.ADAMANTITE),
    RUNITE("Runite", Ores.RUNITE),
    BASALT("Basalt", Ores.BASALT),
    URT_SALT("Urt salt", Ores.URT_SALT),
    EFH_SALT("Efh salt", Ores.EFH_SALT),
    TE_SALT("Te salt", Ores.TE_SALT),
    LEAD("Lead", Ores.LEAD),
    NICKEL("Nickel", Ores.NICKEL);

    private final String label;
    private final Ores ore;

    public String toString() {
        return this.label;
    }

    public String getLabel() {
        return this.label;
    }

    public Ores getOre() {
        return this.ore;
    }

    private BatchTierOption(String label, Ores ore) {
        this.label = label;
        this.ore = ore;
    }
}

