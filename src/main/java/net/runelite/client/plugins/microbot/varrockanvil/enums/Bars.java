package net.runelite.client.plugins.microbot.varrockanvil.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.ItemID;

@Getter
@RequiredArgsConstructor
public enum Bars {
    // keelMetal is the word the game uses in Sailing keel item names, which is NOT always the bar's
    // own name: an "Adamantite bar" makes "Adamant keel parts", a "Runite bar" makes "Rune keel parts".
    // null means this metal has no keel parts at all.
    //
    // keelLevel / keelQuantity come from the OSRS Wiki keel tables and are per-tier — they are NOT the
    // bar's own smelting level, and dragon takes 2 where every other tier takes 5.
    //
    //     name                  id                      smelt  keelMetal   keelLvl  keelQty
    BRONZE("Bronze bar",         ItemID.BRONZE_BAR,      1,     "bronze",   10,      5),
    IRON("Iron bar",             ItemID.IRON_BAR,        15,    "iron",     22,      5),
    SILVER("Silver bar",         ItemID.SILVER_BAR,      20,    null,       0,       0),
    STEEL("Steel bar",           ItemID.STEEL_BAR,       30,    "steel",    38,      5),
    GOLD("Gold bar",             ItemID.GOLD_BAR,        40,    null,       0,       0),
    MITHRIL("Mithril bar",       ItemID.MITHRIL_BAR,     50,    "mithril",  56,      5),
    ADAMANTITE("Adamantite bar", ItemID.ADAMANTITE_BAR,  70,    "adamant",  74,      5),
    RUNITE("Runite bar",         ItemID.RUNITE_BAR,      85,    "rune",     86,      5),

    // Dragon is keel-only and large-keel-only. Regular Dragon keel parts come from 2 dragon metal
    // sheets at the DRAGON FORGE, which no anvil can do — so this plugin rejects that pairing. Large
    // dragon keel parts, however, are 2 Dragon keel parts at an ordinary anvil, which is fine here.
    // The id is 0 because the material is matched by name ("Dragon keel parts"), never by bar id.
    DRAGON("Dragon",             0,                      94,    "dragon",   94,      2);

    private final String name;
    private final int id;
    private final int requiredSmithingLevel;
    private final String keelMetal;
    private final int keelLevel;
    private final int keelQuantity;

    @Override
    public String toString() {
        return name;
    }

    /** True if Sailing keel parts exist for this metal. */
    public boolean hasKeelParts() {
        return keelMetal != null;
    }

    /** Regular keel parts for this metal need the Dragon Forge, not an anvil. */
    public boolean regularKeelNeedsDragonForge() {
        return this == DRAGON;
    }
}
