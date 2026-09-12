package net.runelite.client.plugins.microbot.irkedfarmer.task;

import java.util.ArrayList;
import java.util.List;

/**
 * What a single farming run needs in the inventory, with correct slot accounting so a plan can
 * be validated against the 28-slot limit BEFORE any banking. This is the structural fix for the
 * legacy tree runner's flat-inventory overflow.
 *
 * Slot rules:
 *  - EQUIPPED             -> 0 slots (worn)
 *  - STACKABLE / NOTED    -> 1 slot regardless of quantity (coins, runes; noted payment stacks)
 *  - RESERVED             -> 1 slot (item resolved at bank time)
 *  - LOOSE                -> 1 slot per unit (saplings, tools)
 */
public final class InventoryPlan {
    public static final int MAX_SLOTS = 28;

    public enum Kind { LOOSE, STACKABLE, NOTED, EQUIPPED, RESERVED }

    public static final class Entry {
        public final Kind kind;
        public final int itemId;         // -1 for RESERVED
        public final int quantity;
        public final ReservedItem reserved; // non-null only for RESERVED

        Entry(Kind kind, int itemId, int quantity, ReservedItem reserved) {
            this.kind = kind;
            this.itemId = itemId;
            this.quantity = quantity;
            this.reserved = reserved;
        }

        int slots() {
            switch (kind) {
                case EQUIPPED: return 0;
                case STACKABLE:
                case NOTED:
                case RESERVED: return 1;
                case LOOSE:
                default: return quantity;
            }
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    /** Loose, unstackable — one slot per unit (saplings, spade, rake). */
    public InventoryPlan addLoose(int itemId, int quantity) {
        entries.add(new Entry(Kind.LOOSE, itemId, quantity, null));
        return this;
    }

    /** Stackable — a single slot regardless of quantity (coins, runes). Withdrawn normally. */
    public InventoryPlan addStackable(int itemId, int quantity) {
        entries.add(new Entry(Kind.STACKABLE, itemId, quantity, null));
        return this;
    }

    /** Noted — a single slot, but MUST be withdrawn noted or it overflows (e.g. 25 limpwurt roots). */
    public InventoryPlan addNoted(int itemId, int quantity) {
        entries.add(new Entry(Kind.NOTED, itemId, quantity, null));
        return this;
    }

    /** Worn — no inventory slot. */
    public InventoryPlan addEquipped(int itemId) {
        entries.add(new Entry(Kind.EQUIPPED, itemId, 1, null));
        return this;
    }

    /** Reserve a slot for a bank-time-resolved item (energy dose, teleport, pendant charge). */
    public InventoryPlan reserve(ReservedItem item) {
        entries.add(new Entry(Kind.RESERVED, -1, 1, item));
        return this;
    }

    public List<Entry> entries() {
        return entries;
    }

    public int slotCount() {
        int total = 0;
        for (Entry e : entries) {
            total += e.slots();
        }
        return total;
    }

    public boolean feasible() {
        return slotCount() <= MAX_SLOTS;
    }
}
