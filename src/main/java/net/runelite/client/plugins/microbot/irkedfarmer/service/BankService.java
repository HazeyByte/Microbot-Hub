package net.runelite.client.plugins.microbot.irkedfarmer.service;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.questhelper.collections.ItemCollections;
import net.runelite.client.plugins.microbot.irkedfarmer.IrkedFarmerConfig;
import net.runelite.client.plugins.microbot.irkedfarmer.model.CompostType;
import net.runelite.client.plugins.microbot.irkedfarmer.task.InventoryPlan;
import net.runelite.client.plugins.microbot.irkedfarmer.task.ReservedItem;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Prepares the inventory for a single farming run from a validated {@link InventoryPlan}: opens the
 * bank, deposits everything the plan doesn't need, withdraws the concrete items (verified), and
 * resolves each slot reservation to a concrete item the player actually owns.
 *
 * Non-fatal by design: a missing optional item (energy, a teleport, protection payment) is logged
 * and skipped — it never shuts the run down. Returns false only if the bank could not be opened.
 */
@Slf4j
public final class BankService {

    private BankService() {}

    public static boolean prepare(InventoryPlan plan, IrkedFarmerConfig cfg) {
        if (!Rs2Bank.isOpen() && !Rs2Bank.openBank() && !Rs2Bank.walkToBank()) {
            log.warn("irkedFarmer: could not open bank");
            return false;
        }
        if (!Rs2Bank.isOpen()) {
            return false;
        }

        // Keep the plan's concrete ids and every candidate id a reservation might resolve to.
        Set<Integer> keep = new LinkedHashSet<>();
        for (InventoryPlan.Entry e : plan.entries()) {
            if (e.kind == InventoryPlan.Kind.RESERVED) {
                keep.addAll(candidates(e.reserved));
            } else {
                keep.add(e.itemId);
            }
        }
        if (cfg.compostType() == CompostType.BOTTOMLESS_BUCKET) {
            keep.add(cfg.compostType().getEmptyItemId());
        }
        if (!keep.isEmpty()) {
            Rs2Bank.depositAllExcept(keep.toArray(new Integer[0]));
            Rs2Inventory.waitForInventoryChanges(1500);
        }

        // Item-mode withdrawals: loose + stackable concrete items, then reservations.
        Rs2Bank.setWithdrawAsItem();
        for (InventoryPlan.Entry e : plan.entries()) {
            if (e.kind == InventoryPlan.Kind.LOOSE || e.kind == InventoryPlan.Kind.STACKABLE) {
                withdrawTo(e.itemId, e.quantity);
            }
        }
        if (cfg.compostType() == CompostType.BOTTOMLESS_BUCKET) {
            withdrawBottomlessBucket(cfg.compostType());
        }
        for (InventoryPlan.Entry e : plan.entries()) {
            if (e.kind == InventoryPlan.Kind.RESERVED) {
                resolveReservation(e.reserved);
            }
        }

        // Noted withdrawals (protection payments) so they occupy a single slot.
        boolean anyNoted = plan.entries().stream().anyMatch(e -> e.kind == InventoryPlan.Kind.NOTED);
        if (anyNoted && Rs2Bank.setWithdrawAsNote()) {
            for (InventoryPlan.Entry e : plan.entries()) {
                if (e.kind == InventoryPlan.Kind.NOTED) {
                    if (Rs2Bank.hasItem(e.itemId)) {
                        Rs2Bank.withdrawX(e.itemId, e.quantity);
                        Rs2Inventory.waitForInventoryChanges(1200);
                    } else {
                        log.info("irkedFarmer: no protection payment {} in bank — will plant unprotected", e.itemId);
                    }
                }
            }
            Rs2Bank.setWithdrawAsItem();
        }

        if (cfg.useGraceful()) {
            equipGraceful();
        }
        EquipmentService.equipFarmingGear();

        verify(plan);
        Rs2Bank.closeBank();
        return true;
    }

    private static final String[] GRACEFUL_PIECES = {
            "Graceful hood", "Graceful top", "Graceful legs", "Graceful gloves", "Graceful boots", "Graceful cape"
    };

    /**
     * Wear the graceful set (weight reduction) if not already on. Name-based: recoloured graceful
     * (Zeah-house/Hallowed variants) keep the base name, so one withdraw-and-equip per piece both
     * matches all recolours AND is fast (no per-id bank scan over the huge variant list). Non-fatal.
     * {@code Rs2Bank.withdrawAndEquip} withdraws THEN wears — the previous by-id path was slow and
     * left pieces unworn.
     */
    private static void equipGraceful() {
        for (String piece : GRACEFUL_PIECES) {
            if (!Rs2Equipment.isWearing(piece)) {
                Rs2Bank.withdrawAndEquip(piece);
            }
        }
    }

    /**
     * The bottomless bucket's plan entry only lists the filled item id, so the normal
     * {@link #withdrawTo} pass above misses it whenever the bank holds the empty one (the common
     * case — it needs refilling at a compost bin, which isn't automated here). Fall back to the
     * empty variant so the bucket at least ends up in the inventory instead of being silently
     * skipped.
     */
    private static void withdrawBottomlessBucket(CompostType compost) {
        if (Rs2Inventory.hasItem(compost.getItemId()) || Rs2Inventory.hasItem(compost.getEmptyItemId())) {
            return;
        }
        withdrawTo(compost.getEmptyItemId(), 1);
    }

    /** Withdraw up to {@code quantity}, accounting for what's already carried. Non-fatal. */
    private static void withdrawTo(int itemId, int quantity) {
        if (Rs2Inventory.itemQuantity(itemId) >= quantity) {
            return;
        }
        if (!Rs2Bank.hasItem(itemId)) {
            log.warn("irkedFarmer: bank missing item {} (need {})", itemId, quantity);
            return;
        }
        Rs2Bank.withdrawDeficit(itemId, quantity);
        Rs2Inventory.waitForInventoryChanges(1200);
    }

    /**
     * Resolve a reservation to a concrete item across ALL of its variants/charges. Two passes so we
     * never withdraw a low-charge variant while already carrying a charged one:
     *  1. already carrying any variant (any charge) → done;
     *  2. else withdraw the first variant the bank has.
     * If nothing with charges exists anywhere, continue without it (the patch that needed it is then
     * skipped by the router — "no charges, can't use it").
     */
    private static void resolveReservation(ReservedItem reserved) {
        List<Integer> ids = candidates(reserved);
        // Already have a charged variant WORN? Teleport jewellery operates from the equipment slot too,
        // so a worn necklace/pendant counts — don't withdraw a duplicate.
        int[] variantIds = ids.stream().mapToInt(Integer::intValue).toArray();
        if (variantIds.length > 0 && Rs2Equipment.isWearing(variantIds)) {
            return;
        }
        for (int id : ids) {
            if (Rs2Inventory.hasItem(id)) {
                return; // already carrying a charged variant
            }
        }
        for (int id : ids) {
            if (Rs2Bank.hasItem(id)) {
                Rs2Bank.withdrawOne(id);
                Rs2Inventory.waitForInventoryChanges(1200);
                return;
            }
        }
        log.info("irkedFarmer: no {} (any charge) available — continuing without it", reserved);
    }

    /** Candidate item ids for a reservation, best-first. */
    private static List<Integer> candidates(ReservedItem reserved) {
        switch (reserved) {
            case DIGSITE_PENDANT:
                return ItemCollections.DIGSITE_PENDANTS.getItems();
            case LLETYA_CRYSTAL:
                return ItemCollections.TELEPORT_CRYSTAL.getItems();
            case RUN_ENERGY:
                // ponytail: stamina is the modern run-energy item; no ENERGY_POTIONS collection exists.
                return ItemCollections.STAMINA_POTIONS.getItems();
            case TAVERLEY_TELEPORT:
                return Collections.singletonList(ItemID.NZONE_TELETAB_TAVERLEY);
            case SKILLS_NECKLACE:
                return ItemCollections.SKILLS_NECKLACES.getItems();
            default:
                return new ArrayList<>();
        }
    }

    /** Light verification — warn on shortfalls, never abort (missing items are handled in-run). */
    private static void verify(InventoryPlan plan) {
        for (InventoryPlan.Entry e : plan.entries()) {
            if (e.kind == InventoryPlan.Kind.LOOSE || e.kind == InventoryPlan.Kind.STACKABLE) {
                int have = Rs2Inventory.itemQuantity(e.itemId);
                if (have < e.quantity) {
                    log.warn("irkedFarmer: after banking have {}x{} (wanted {})", have, e.itemId, e.quantity);
                }
            }
        }
    }
}
