package net.runelite.client.plugins.microbot.irkedgoatkiller;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;

/**
 * Draws the live targeting decision onto the game scene (debug only): the stand tile, and every nearby goat
 * coloured by why it is or isn't a valid Telegrab target, with the current target lined to the player.
 * Renders on the client thread (ABOVE_SCENE), so live NPC/player access is safe.
 */
public class IrkedGoatkillerSceneOverlay extends Overlay {

    private final Client client;
    private final IrkedGoatkillerScript script;

    @Inject
    IrkedGoatkillerSceneOverlay(Client client, IrkedGoatkillerScript script) {
        this.client = client;
        this.script = script;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D g) {
        IrkedGoatkillerConfig cfg = script.config;
        if (cfg == null || !cfg.debugOverlay()) return null;

        drawTile(g, script.standTile, new Color(0, 200, 255));   // where we should be planted

        Player me = client.getLocalPlayer();
        WorldPoint mwp = me != null ? me.getWorldLocation() : null;
        Point meCanvas = me != null ? canvasOf(me.getLocalLocation()) : null;

        Rs2Npc.getNpcs(IrkedGoatkillerScript.GOAT).forEach(goat -> {
            LocalPoint lp = goat.getLocalLocation();
            WorldPoint gwp = goat.getWorldLocation();
            if (lp == null || gwp == null) return;

            String status = script.goatStatus(goat);
            Color c = colorFor(status);
            boolean isTarget = goat.getIndex() == script.targetIndex;

            Polygon poly = Perspective.getCanvasTilePoly(client, lp);
            if (poly != null) {
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), isTarget ? 70 : 35));
                g.fillPolygon(poly);
                g.setStroke(new BasicStroke(isTarget ? 3f : 1f));
                g.setColor(c);
                g.drawPolygon(poly);
            }

            int d = mwp != null ? mwp.distanceTo(gwp) : -1;
            Point txt = Perspective.getCanvasTextLocation(client, g, lp, status + " d" + d, 0);
            if (txt != null) {
                g.setColor(c);
                g.drawString(status + " d" + d, txt.getX(), txt.getY());
            }

            if (isTarget && meCanvas != null) {   // line from us to the goat we're grabbing
                Point gc = canvasOf(lp);
                if (gc != null) {
                    g.setStroke(new BasicStroke(2f));
                    g.setColor(Color.WHITE);
                    g.drawLine(meCanvas.getX(), meCanvas.getY(), gc.getX(), gc.getY());
                }
            }
        });
        return null;
    }

    private static Color colorFor(String status) {
        switch (status) {
            case "VALID":        return Color.GREEN;
            case "VALID_MOV":    return new Color(180, 255, 90);   // valid, but moving
            case "OTHER_PLAYER": return Color.ORANGE;
            case "OUT_OF_RANGE": return Color.RED;
            case "WRONG_SIDE":   return new Color(255, 140, 0);
            case "MOVING":       return Color.CYAN;
            case "COOLDOWN":     return Color.GRAY;
            case "STUCK":        return Color.MAGENTA;
            default:             return Color.DARK_GRAY;
        }
    }

    private Point canvasOf(LocalPoint lp) {
        return lp == null ? null : Perspective.localToCanvas(client, lp, client.getTopLevelWorldView().getPlane());
    }

    private void drawTile(Graphics2D g, WorldPoint wp, Color c) {
        if (wp == null) return;
        LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), wp);
        if (lp == null) return;
        Polygon poly = Perspective.getCanvasTilePoly(client, lp);
        if (poly == null) return;
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 40));
        g.fillPolygon(poly);
        g.setStroke(new BasicStroke(1f));
        g.setColor(c);
        g.drawPolygon(poly);
    }
}
