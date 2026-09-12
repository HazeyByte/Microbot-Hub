package net.runelite.client.plugins.microbot.irkedmlm.session;

import java.awt.Rectangle;

import static net.runelite.client.plugins.microbot.irkedmlm.MlmAssert.assertFalse;
import static net.runelite.client.plugins.microbot.irkedmlm.MlmAssert.assertTrue;

/**
 * Guards {@link Session#clickboxMostlyOnScreen}, the pure geometry behind {@code hasSafeClickbox}.
 * The regression this locks in: a large/near clickbox that legitimately overflows a canvas edge must
 * still be considered clickable, or the click guards spin the camera forever (the old full-containment
 * check did exactly that). Only a null / degenerate / mostly-off-canvas box should be rejected.
 */
public class ClickboxSafetyTest {

    private static final int W = 765, H = 503;

    public static void main(String[] args) {
        ClickboxSafetyTest t = new ClickboxSafetyTest();
        t.fullyFramedBoxIsClickable();
        t.nullOrDegenerateBoxIsRejected();
        t.tallNearBoxOverflowingTopIsStillClickable();
        t.edgeClippedButMostlyVisibleIsClickable();
        t.heavilyClippedBoxIsRejected();
        t.fullyOffscreenBoxIsRejected();
        t.panelCoveringTargetIsRejected();
        t.targetBesidePanelIsClickable();
        System.out.println("ClickboxSafetyTest: OK");
    }

    public void fullyFramedBoxIsClickable() {
        assertTrue(Session.clickboxMostlyOnScreen(new Rectangle(300, 200, 80, 90), W, H));
    }

    public void nullOrDegenerateBoxIsRejected() {
        assertFalse(Session.clickboxMostlyOnScreen(null, W, H));
        assertFalse(Session.clickboxMostlyOnScreen(new Rectangle(300, 200, 0, 50), W, H));
    }

    public void tallNearBoxOverflowingTopIsStillClickable() {
        // The deadlock case: standing next to a vein/ladder at high zoom, its box overflows the top.
        // Old full-containment rejected this and turnTo could never fix it. Now: majority on-canvas.
        assertTrue(Session.clickboxMostlyOnScreen(new Rectangle(120, -50, 100, 600), W, H));
    }

    public void edgeClippedButMostlyVisibleIsClickable() {
        assertTrue(Session.clickboxMostlyOnScreen(new Rectangle(-30, 150, 100, 100), W, H)); // ~64% shown
    }

    public void heavilyClippedBoxIsRejected() {
        assertFalse(Session.clickboxMostlyOnScreen(new Rectangle(-80, 150, 100, 100), W, H)); // ~14% shown
    }

    public void fullyOffscreenBoxIsRejected() {
        assertFalse(Session.clickboxMostlyOnScreen(new Rectangle(-500, 150, 100, 100), W, H));
    }

    /** The deposit box panel is drawn over the viewport: a box it touches at all is not clickable,
     *  because the click point is sampled anywhere inside the box and would hit the interface. */
    public void panelCoveringTargetIsRejected() {
        Rectangle panel = new Rectangle(140, 100, 480, 300);
        assertFalse(Session.clickboxClearOfPanel(new Rectangle(300, 200, 80, 90), panel));  // fully behind
        assertFalse(Session.clickboxClearOfPanel(new Rectangle(100, 380, 80, 90), panel));  // clips a corner
        assertFalse(Session.clickboxClearOfPanel(null, panel));
    }

    public void targetBesidePanelIsClickable() {
        Rectangle panel = new Rectangle(140, 100, 480, 300);
        assertTrue(Session.clickboxClearOfPanel(new Rectangle(640, 200, 80, 90), panel));   // right of panel
        assertTrue(Session.clickboxClearOfPanel(new Rectangle(300, 200, 80, 90), null));    // no panel open
    }
}
