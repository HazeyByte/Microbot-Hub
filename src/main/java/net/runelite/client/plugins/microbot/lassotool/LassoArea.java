package net.runelite.client.plugins.microbot.lassotool;

import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Represents a user-drawn lasso area as a set of WorldPoints (tiles inside or on the boundary).
 * Can be used as a filter for queries, e.g. in MLM for custom mining spots.
 */
@Getter
public class LassoArea
{
    private final String name;
    private final Set<WorldPoint> tiles;
    private final List<java.awt.Point> screenPath; // for re-rendering if needed

    public LassoArea(String name, Set<WorldPoint> tiles, List<java.awt.Point> screenPath)
    {
        this.name = name != null ? name : "Lasso Area";
        this.tiles = tiles != null ? Collections.unmodifiableSet(new HashSet<>(tiles)) : Collections.emptySet();
        this.screenPath = screenPath != null ? Collections.unmodifiableList(screenPath) : Collections.emptyList();
    }

    public boolean contains(WorldPoint point)
    {
        return tiles.contains(point);
    }

    public int size()
    {
        return tiles.size();
    }

    /**
     * Returns a string that can be copied into MLMMiningSpot or similar for mesh.
     */
    public String toMeshCode()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("// Lasso captured area: ").append(name).append("\n");
        sb.append("Set<WorldPoint> mesh = new HashSet<>();\n");
        for (WorldPoint wp : tiles)
        {
            sb.append("mesh.add(new WorldPoint(")
              .append(wp.getX()).append(", ")
              .append(wp.getY()).append(", ")
              .append(wp.getPlane()).append("));\n");
        }
        sb.append(" // Use in containsInArea or pass to a custom spot");
        return sb.toString();
    }

    public String toJsonList()
    {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (WorldPoint wp : tiles)
        {
            if (!first) sb.append(",");
            sb.append("{\"x\":").append(wp.getX())
              .append(",\"y\":").append(wp.getY())
              .append(",\"plane\":").append(wp.getPlane()).append("}");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }
}
