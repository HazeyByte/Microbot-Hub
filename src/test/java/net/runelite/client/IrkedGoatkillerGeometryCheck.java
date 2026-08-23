package net.runelite.client;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.irkedgoatkiller.IrkedGoatkillerScript;

/**
 * Self-check for the goat selector's geometry — the two gates behind the targeting fixes:
 *   euclid()         : straight-line range, so a diagonal goat can't read as "9 tiles" and walk us.
 *   pullCrossesPit() : the lured goat only drops in if the goat->player line passes through the 3x3 pit
 *                      (OSRS wiki: "be on the opposite side of the pit"). Replaces the old cosine cone.
 * Cases below are drawn from live agent-server data (player on the south stand tile). Run as a plain main()
 * (repo has no JUnit). Fails loudly if a sign, metric, or box bound regresses.
 */
public class IrkedGoatkillerGeometryCheck {
    public static void main(String[] args) {
        range();
        side();
        System.out.println("IrkedGoatkillerGeometryCheck: OK");
    }

    private static void range() {
        WorldPoint me = new WorldPoint(2572, 2195, 0);
        check(IrkedGoatkillerScript.euclid(me, new WorldPoint(2581, 2195, 0)) <= 9, "straight-9 should be in range");
        check(IrkedGoatkillerScript.euclid(me, new WorldPoint(2579, 2202, 0)) > 9, "diagonal 7x7 (~9.9) must be out of range");
        check(IrkedGoatkillerScript.euclid(me, new WorldPoint(2573, 2195, 1)) == Double.MAX_VALUE, "cross-plane must be unreachable");
    }

    private static void side() {
        WorldPoint me = new WorldPoint(2572, 2193, 0);   // south stand tile
        // North of the pit: lure line crosses it → valid.
        check(pit(new WorldPoint(2572, 2199, 0), me), "due-north goat: pull crosses pit");
        check(pit(new WorldPoint(2573, 2197, 0), me), "NNE goat (2573,2197): pull crosses pit");
        check(pit(new WorldPoint(2570, 2196, 0), me), "near-NW goat (2570,2196): pull clips pit → valid");
        // Beside / same side: lure line misses the pit → wrong side.
        check(!pit(new WorldPoint(2567, 2195, 0), me), "due-west goat: pull misses pit");
        check(!pit(new WorldPoint(2572, 2190, 0), me), "south (same-side) goat: pull misses pit");
        check(!pit(new WorldPoint(2567, 2197, 0), me), "far-NW goat (2567,2197): pull misses pit");
    }

    private static boolean pit(WorldPoint goat, WorldPoint me) {
        return IrkedGoatkillerScript.pullCrossesPit(goat, me);
    }

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }
}
