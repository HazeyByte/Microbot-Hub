package net.runelite.client.plugins.microbot.irkedfarmer.service;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;

/**
 * Auto-detects and equips the high-value farming gear a run benefits from, if the player owns it — no
 * user micromanagement. Name-based (variant-robust) and non-fatal: anything missing is simply skipped.
 *
 * Called at bank time (bank open) by every run's prep.
 */
@Slf4j
public final class EquipmentService {

    private EquipmentService() {}

    /**
     * Farming cape variants — all four act as magic secateurs when worn (max cape inherits every
     * skill cape's perk). Only the plain/trimmed farming cape carries the "Teleport" action to the
     * Farming Guild (confirmed against teleportation_items.tsv) — max cape does NOT, despite matching
     * the secateurs perk, so Rs2Walker simply won't route through it; that's a silent non-fatal miss,
     * not a bug.
     */
    private static final String[] FARMING_CAPES = {"Farming cape(t)", "Farming cape", "Max cape(t)", "Max cape"};
    /** Explorer's ring — Falador/cabbage-patch teleport + run-energy restore (dose 2+ teleports). */
    private static final String[] EXPLORER_RINGS = {"Explorer's ring 4", "Explorer's ring 3", "Explorer's ring 2"};

    public static void equipFarmingGear() {
        if (!Rs2Bank.isOpen()) {
            return;
        }
        boolean farmingCape = equipFirstAvailable(FARMING_CAPES);
        equipFirstAvailable(EXPLORER_RINGS);

        // Magic secateurs boost herb/fruit/allotment yield. The farming cape already acts as one, so
        // only fetch the secateurs when a cape isn't providing the effect.
        if (!farmingCape
                && !Rs2Inventory.hasItem("Magic secateurs")
                && !Rs2Equipment.isWearing("Magic secateurs")
                && Rs2Bank.hasItem("Magic secateurs")) {
            Rs2Bank.withdrawOne("Magic secateurs");
        }
    }

    private static boolean equipFirstAvailable(String[] names) {
        for (String name : names) {
            if (Rs2Equipment.isWearing(name)) {
                return true;
            }
            if (Rs2Bank.hasItem(name)) {
                Rs2Bank.withdrawAndEquip(name); // withdraws then wears
                return true;
            }
        }
        return false;
    }
}
