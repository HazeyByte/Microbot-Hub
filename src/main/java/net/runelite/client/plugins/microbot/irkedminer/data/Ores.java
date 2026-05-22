/*
 * Decompiled with CFR 0.152.
 */
package net.runelite.client.plugins.microbot.irkedminer.data;

public enum Ores {
    TIN("Tin rocks"),
    COPPER("Copper rocks"),
    CLAY("Clay rocks"),
    IRON("Iron rocks"),
    SILVER("Silver rocks"),
    COAL("Coal rocks"),
    GOLD("Gold rocks"),
    GEM("Gem rocks"),
    MITHRIL("Mithril rocks"),
    ADAMANTITE("Adamantite rocks"),
    RUNITE("Runite rocks"),
    BASALT("Basalt rocks"),
    URT_SALT("Urt salt rocks"),
    EFH_SALT("Efh salt rocks"),
    TE_SALT("Te salt rocks"),
    LEAD("Lead rocks"),
    NICKEL("Nickel rocks");

    private final String name;

    public String getKey() {
        return Ores.normalizeKey(this.name);
    }

    private static String normalizeKey(String rawName) {
        if (rawName == null) {
            return "";
        }
        String normalized = rawName.toLowerCase().trim();
        if (normalized.endsWith(" rocks")) {
            normalized = normalized.substring(0, normalized.length() - " rocks".length());
        } else if (normalized.endsWith(" rock")) {
            normalized = normalized.substring(0, normalized.length() - " rock".length());
        }
        return normalized.trim();
    }

    public String toString() {
        return this.name;
    }

    public String getName() {
        return this.name;
    }

    private Ores(String name) {
        this.name = name;
    }
}

