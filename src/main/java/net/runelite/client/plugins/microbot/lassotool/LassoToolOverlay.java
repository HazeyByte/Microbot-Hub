package net.runelite.client.plugins.microbot.lassotool;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.*;
import java.awt.geom.GeneralPath;
import java.util.List;
import java.util.Set;

public class LassoToolOverlay extends Overlay
{
    private final Client client;
    private final LassoToolPlugin plugin;

    @Inject
    public LassoToolOverlay(Client client, LassoToolPlugin plugin)
    {
        this.client = client;
        this.plugin = plugin;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(Overlay.PRIORITY_LOW);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        LassoArea area = plugin.getCurrentArea();
        List<Point> path = plugin.getCurrentLassoPath();
        boolean drawing = plugin.isDrawing();

        // Always draw prominent instructions when lasso mode is active, even before first point.
        // Critical for discoverability + reminding user that their clicks are being captured
        // and will not cause game interactions (walking, object clicks, etc.).
        if (drawing)
        {
            graphics.setColor(new Color(255, 0, 0, 230));
            graphics.setFont(graphics.getFont().deriveFont(Font.BOLD, 14f));
            int y = 28;
            graphics.drawString("LASSO MODE ACTIVE — CLICKS ARE CAPTURED (no game walk / interact / menus)", 10, y);
            y += 17;
            graphics.setFont(graphics.getFont().deriveFont(Font.PLAIN, 12f));
            graphics.setColor(Color.WHITE);
            graphics.drawString("Left-drag = freehand   |   Left-click = add exact points (poly)   |   Right-click or double-click = finish + capture tiles", 10, y);
            y += 15;
            graphics.drawString("Toggle hotkey again = finish or cancel   |   Yellow highlights = tiles that will be captured   |   MLM: use toJsonList() or toMeshCode() → paste in MotherloadMine 'Custom Mining Mesh'", 10, y);
            y += 15;
            int n = (path != null ? path.size() : 0);
            graphics.setColor(Color.YELLOW);
            graphics.drawString("Points: " + n + (n > 2 ? "  (ready — right/double-click to finish)" : "  (start dragging or clicking points)"), 10, y);
        }

        if (path == null || path.isEmpty())
        {
            // Render saved area if any (post-clear after a finished lasso)
            if (area != null && !area.getTiles().isEmpty())
            {
                renderArea(graphics, area.getTiles(), plugin.getConfig().fillColor(), plugin.getConfig().lassoColor());
            }
            return null;
        }

        // Render current drawing path (or just-finished lasso stroke until cleared by client thread work)
        GeneralPath lassoPath = new GeneralPath();
        boolean first = true;
        for (Point p : path)
        {
            if (first)
            {
                lassoPath.moveTo(p.x, p.y);
                first = false;
            }
            else
            {
                lassoPath.lineTo(p.x, p.y);
            }
        }

        if (drawing && path.size() > 2)
        {
            lassoPath.closePath();
        }

        // Draw outline
        graphics.setColor(plugin.getConfig().lassoColor());
        graphics.setStroke(new BasicStroke(2f));
        graphics.draw(lassoPath);

        // Fill if closed or during preview
        if (path.size() > 2)
        {
            Color fill = plugin.getConfig().fillColor();
            graphics.setColor(fill);
            graphics.fill(lassoPath);
        }

        // Live tile preview: compute on this render (guaranteed client thread) so we get accurate
        // Perspective projection without ever touching client APIs from AWT mouse events.
        // This gives "live" yellow highlights of enclosed tiles as you drag, and right after release
        // until the path is cleared.
        Set<WorldPoint> liveTiles = plugin.computeTilesInLasso(path);
        if (liveTiles != null && !liveTiles.isEmpty())
        {
            renderArea(graphics, liveTiles, new Color(255, 255, 0, 40), Color.YELLOW);
        }
        else
        {
            // Fallback to any previously stored preview (e.g. after a finish where path cleared)
            Set<WorldPoint> currentTiles = plugin.getCurrentPreviewTiles();
            if (currentTiles != null && !currentTiles.isEmpty())
            {
                renderArea(graphics, currentTiles, new Color(255, 255, 0, 40), Color.YELLOW);
            }
        }

        return null;
    }

    private void renderArea(Graphics2D graphics, Set<WorldPoint> tiles, Color fill, Color outline)
    {
        graphics.setColor(fill);
        for (WorldPoint wp : tiles)
        {
            LocalPoint lp = LocalPoint.fromWorld(client, wp);
            if (lp == null) continue;

            Polygon poly = Perspective.getCanvasTilePoly(client, lp);
            if (poly == null) continue;

            graphics.fill(poly);
        }

        graphics.setColor(outline);
        graphics.setStroke(new BasicStroke(1f));
        for (WorldPoint wp : tiles)
        {
            LocalPoint lp = LocalPoint.fromWorld(client, wp);
            if (lp == null) continue;

            Polygon poly = Perspective.getCanvasTilePoly(client, lp);
            if (poly == null) continue;

            graphics.draw(poly);
        }
    }
}
