package net.runelite.client.plugins.microbot.varrockanvil.enums;

import lombok.Getter;

/**
 * Items selectable in the anvil interface (widget group 312).
 *
 * childId is the widget child to click. Children 9-27, 29-31, 34 and 35 are stable, named slots
 * (dagger..kiteshield, darttips, arrowheads, knives, bolts, limbs). Children 28, 32, 33, 36, 37 and
 * 38 are generic "other_1..other_6" slots that the game repopulates per metal, so their index is NOT
 * stable across bar types — note the duplicate childIds below. Anything in those slots, and every
 * Sailing keel item, uses childId 0 and is selected by matching the interface text instead.
 */
public enum AnvilItem {
    DAGGER("Dagger", 9, 1),
    SWORD("Sword", 10, 1),
    SCIMITAR("Scimitar", 11, 2),
    LONG_SWORD("Long sword", 12, 2),
    TWO_HAND_SWORD("2-hand sword", 13, 3),
    AXE("Axe", 14, 1),
    MACE("Mace", 15, 1),
    WARHAMMER("Warhammer", 16, 3),
    BATTLE_AXE("Battle axe", 17, 3),
    CLAWS("Claws", 18, 2),
    CHAIN_BODY("Chain body", 19, 3),
    PLATE_LEGS("Plate legs", 20, 3),
    PLATE_SKIRT("Plate skirt", 21, 3),
    PLATE_BODY("Plate body", 22, 5),
    NAILS("Nails", 23, 1),
    MEDIUM_HELM("Medium helm", 24, 1),
    FULL_HELM("Full helm", 25, 2),
    SQUARE_SHIELD("Square shield", 26, 2),
    KITE_SHIELD("Kite shield", 27, 3),
    DART_TIPS("Dart tips", 29, 1),
    ARROWTIPS("Arrowtips", 30, 1),
    KNIVES("Knives", 31, 1),
    BOLTS("Bolts (unf)", 34, 1),
    LIMBS("Crossbow limbs", 35, 1),

    // Generic other_N slots — selected by name, because the child index shifts per metal.
    BRONZE_WIRE("Bronze wire", 0, 1),
    OIL_LAMP("Oil lamp", 0, 1),
    IRON_SPIT("Iron spit", 0, 1),
    BULLSEYE_LAMP("Bullseye lamp", 0, 1),
    STEEL_STUDS("Studs", 0, 1),

    // Sailing. Both are made at a normal anvil; the metal word comes from Bars.getKeelMetal().
    KEEL_PARTS("Keel parts", 0, 5, KeelKind.REGULAR),
    LARGE_KEEL_PARTS("Large keel parts", 0, 5, KeelKind.LARGE);

    public enum KeelKind { NONE, REGULAR, LARGE }

    private final String itemName;
    @Getter
    private final int childId;
    @Getter
    private final int requiredBars;
    @Getter
    private final KeelKind keelKind;

    AnvilItem(final String itemName, final int childId, final int requiredBars) {
        this(itemName, childId, requiredBars, KeelKind.NONE);
    }

    AnvilItem(final String itemName, final int childId, final int requiredBars, final KeelKind keelKind) {
        this.itemName = itemName;
        this.childId = childId;
        this.requiredBars = requiredBars;
        this.keelKind = keelKind;
    }

    public String getName() {
        return itemName;
    }

    @Override
    public String toString() {
        return itemName;
    }

    /** Items with no stable child index must be found by matching the interface text. */
    public boolean hasValidChildId() {
        return childId > 0;
    }

    public boolean isKeelItem() {
        return keelKind != KeelKind.NONE;
    }

    /** Large keel parts consume 5 regular keel parts of the same metal, not raw bars. */
    public boolean isLargeKeel() {
        return keelKind == KeelKind.LARGE;
    }
}
