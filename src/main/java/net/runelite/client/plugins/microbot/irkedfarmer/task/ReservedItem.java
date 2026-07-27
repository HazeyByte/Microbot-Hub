package net.runelite.client.plugins.microbot.irkedfarmer.task;

/**
 * An inventory slot the plan reserves for an item whose concrete id is chosen at bank time from what
 * the player actually owns (a dose, a charge, a tablet). BankService resolves each to a candidate
 * list and withdraws the first available. Type-safe so a typo can't silently skip a teleport.
 */
public enum ReservedItem {
    RUN_ENERGY,        // energy/stamina potion (optional)
    TAVERLEY_TELEPORT, // Taverley teleport tablet
    LLETYA_CRYSTAL,    // teleport crystal (Lletya) — NOT used for Tree Gnome Village
    DIGSITE_PENDANT,   // Fossil Island access
    SKILLS_NECKLACE    // Farming Guild access (operated from inventory; walker uses it as a transport)
}
