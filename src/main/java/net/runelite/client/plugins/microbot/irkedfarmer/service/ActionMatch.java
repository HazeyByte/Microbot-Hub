package net.runelite.client.plugins.microbot.irkedfarmer.service;

import net.runelite.api.ObjectComposition;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;

/** Null-safe scans over an object's menu actions — shared by PatchInteractor and HerbRunTask. */
public final class ActionMatch {

    private ActionMatch() {}

    /**
     * {@code Rs2TileObjectModel.getObjectComposition()} isn't null-safe (unlike the deprecated static
     * helper it replaced) — it can NPE internally if {@code getObjectDefinition} returns null. That
     * would otherwise propagate all the way up through the scheduler and abort the whole task instead
     * of just skipping one patch, so every direct (non-sleepUntil-wrapped) call site should go through
     * this wrapper instead of calling {@code .getObjectComposition()} directly.
     */
    public static ObjectComposition safeComposition(Rs2TileObjectModel obj) {
        if (obj == null) return null;
        try {
            return obj.getObjectComposition();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Exact match — used to detect when an object's state has moved on from the action just taken. */
    public static boolean exact(ObjectComposition comp, String action) {
        if (comp == null || comp.getActions() == null) return false;
        for (String a : comp.getActions()) {
            if (action.equals(a)) return true;
        }
        return false;
    }

    /**
     * Case-insensitive substring match — Gielinor names actions inconsistently (Pick-fruit vs
     * Pick-banana, Chop vs Chop-down). Matches the deprecated {@code Rs2GameObject.hasAction(comp,
     * action, exact=false)} this replaces (verified against the real source: non-exact is {@code
     * contains}, not {@code startsWith}).
     */
    public static boolean contains(ObjectComposition comp, String needle) {
        if (comp == null || comp.getActions() == null) return false;
        String lower = needle.toLowerCase();
        for (String a : comp.getActions()) {
            if (a != null && a.toLowerCase().contains(lower)) return true;
        }
        return false;
    }
}
