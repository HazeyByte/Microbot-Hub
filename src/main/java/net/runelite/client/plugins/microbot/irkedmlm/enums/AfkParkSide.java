package net.runelite.client.plugins.microbot.irkedmlm.enums;

/**
 * Which canvas edge the mouse parks off (and returns from) during off-screen AFK.
 * Models a second monitor: a player watching the game on a side monitor only ever crosses the edge
 * adjacent to it, so the exit/return should be consistent — not a random one of all four edges.
 */
public enum AfkParkSide {
    /** Second monitor on the left — cursor leaves/returns via the left edge. */
    LEFT,
    /** Second monitor on the right — cursor leaves/returns via the right edge. */
    RIGHT,
    /** No fixed preference — pick one side per login and keep it for the whole session. */
    RANDOM;

    @Override
    public String toString() {
        // Title-case for the config dropdown (LEFT -> "Left").
        return name().charAt(0) + name().substring(1).toLowerCase();
    }
}
