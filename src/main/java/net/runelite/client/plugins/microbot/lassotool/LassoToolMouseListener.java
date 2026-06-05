package net.runelite.client.plugins.microbot.lassotool;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.input.MouseAdapter;

import javax.swing.SwingUtilities;
import java.awt.Point;
import java.awt.event.MouseEvent;

@Slf4j
public class LassoToolMouseListener extends MouseAdapter
{
    private final LassoToolPlugin plugin;

    // Used to distinguish freehand drag (button down + move) from discrete clicks for polygon vertices.
    // mouseDragged only fires while a button is held. Pure clicks (for point-by-point) won't set this.
    private boolean dragInProgress = false;
    private int pointsBeforeThisStroke = 0;

    public LassoToolMouseListener(LassoToolPlugin plugin)
    {
        this.plugin = plugin;
    }

    @Override
    public MouseEvent mousePressed(MouseEvent event)
    {
        if (!plugin.isDrawing())
        {
            return event;
        }

        boolean left = SwingUtilities.isLeftMouseButton(event);
        boolean right = SwingUtilities.isRightMouseButton(event);

        // Always consume clicks while lasso mode is active so they don't walk/interact with game objects,
        // open menus, etc. This is the critical part for "clicks dont actually allow me to interact with the game".
        // We let middle button through (camera rotate/tilt) so you can still orient view while drawing.
        if (!SwingUtilities.isMiddleMouseButton(event))
        {
            event.consume();
        }

        if (left)
        {
            // For polygon mode (click-to-add-points): only start/clear a fresh lasso on the *very first* point.
            // Subsequent clicks just add vertices. We detect "first" by checking if path is currently empty.
            // (Toggle hotkey already clears when enabling mode.)
            java.util.List<Point> current = plugin.getCurrentLassoPath();
            if (current.isEmpty())
            {
                plugin.startNewLasso();
            }

            pointsBeforeThisStroke = plugin.getCurrentLassoPath().size();
            dragInProgress = false;

            addPointToLasso(event.getPoint());
        }
        else if (right)
        {
            // Right-click while drawing = finish the current lasso (closes the polygon / ends freehand).
            // If <3 points, finishLasso() will auto-clear it (no capture).
            // This + double-click gives a clear "finish" action without forcing finish on every release.
            if (!plugin.getCurrentLassoPath().isEmpty())
            {
                plugin.finishLasso();
            }
            else
            {
                // Nothing drawn, just exit mode cleanly (unregisters listener too)
                plugin.stopLassoDrawing();
            }
        }

        return event;
    }

    @Override
    public MouseEvent mouseDragged(MouseEvent event)
    {
        if (!plugin.isDrawing() || !SwingUtilities.isLeftMouseButton(event))
        {
            return event;
        }

        dragInProgress = true;
        addPointToLasso(event.getPoint());
        event.consume();
        return event;
    }

    @Override
    public MouseEvent mouseReleased(MouseEvent event)
    {
        if (!plugin.isDrawing() || !SwingUtilities.isLeftMouseButton(event))
        {
            return event;
        }

        addPointToLasso(event.getPoint());

        // Auto-finish only for actual drags (freehand lasso behavior, like Photoshop).
        // For discrete clicks (polygon vertices), we do NOT finish on release so user can keep adding points.
        // Finish via right-click or double-click instead.
        int currentSize = plugin.getCurrentLassoPath().size();
        int addedThisStroke = currentSize - pointsBeforeThisStroke;

        if (dragInProgress && addedThisStroke > 1)
        {
            // Real drag happened (button was down and mouse moved enough to add points) → treat as freehand complete.
            plugin.finishLasso();
        }
        // else: was a point click (or very tiny movement). Point added, stay in drawing mode for more clicks.

        dragInProgress = false;
        pointsBeforeThisStroke = 0;

        event.consume();
        return event;
    }

    @Override
    public MouseEvent mouseClicked(MouseEvent event)
    {
        if (plugin.isDrawing())
        {
            // Double-click left = finish (handy for closing a polygon after last vertex).
            if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() >= 2)
            {
                if (!plugin.getCurrentLassoPath().isEmpty())
                {
                    plugin.finishLasso();
                }
            }

            // Block all clicks from reaching the game while in lasso mode.
            if (!SwingUtilities.isMiddleMouseButton(event))
            {
                event.consume();
            }
        }
        return event;
    }

    private void addPointToLasso(Point screenPoint)
    {
        if (screenPoint == null) return;
        // Always store raw screen points (AWT thread safe). Tile projection and live preview
        // happen on the client/render thread in the overlay and on lasso finish.
        plugin.addLassoPoint(screenPoint);
    }
}
