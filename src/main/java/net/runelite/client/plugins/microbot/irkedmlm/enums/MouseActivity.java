package net.runelite.client.plugins.microbot.irkedmlm.enums;

/**
 * How much the cursor lingers on the game screen while mining (human-like mode only).
 * Motherlode Mine is an AFK skill, so the default does the click then gets the cursor off-screen.
 */
public enum MouseActivity {
    /** Do what it needs (click the vein) then park off-screen. Minimal on-screen time — true AFK. */
    AFK,
    /** Mostly off-screen, but occasionally pre-hovers the next vein or leaves the cursor on the canvas. */
    BALANCED,
    /** Stays on the game screen and lines up the next vein — looks like an attentive, watching player. */
    ACTIVE;

    @Override
    public String toString() {
        return name().charAt(0) + name().substring(1).toLowerCase();
    }
}
