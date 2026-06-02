package net.runelite.client.plugins.microbot.motherloadmine.enums;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

@Getter
@RequiredArgsConstructor
public enum MLMMiningSpot {

    WEST_LOWER(Arrays.asList(
            new WorldPoint(3731, 5659, 0),
            new WorldPoint(3731, 5663, 0)
    ), false, Arrays.asList(
            new WorldArea(3725, 5655, 12, 10, 0)
    ), null),

    WEST_MID(Arrays.asList(
            new WorldPoint(3730, 5666, 0),
            new WorldPoint(3731, 5669, 0)
    ), false, Arrays.asList(
            new WorldArea(3725, 5665, 12, 10, 0)
    ), null),

    SOUTH_EAST(Arrays.asList(
            new WorldPoint(3753, 5650, 0),
            new WorldPoint(3756, 5653, 0)
    ), false, Arrays.asList(
            new WorldArea(3748, 5645, 12, 12, 0)
    ), null),

    SOUTH_WEST(Arrays.asList(
            new WorldPoint(3740, 5648, 0),
            new WorldPoint(3740, 5648, 0)
    ), false, Arrays.asList(
            new WorldArea(3732, 5645, 15, 10, 0)
    ), null),

    // === WEST UPPER — meshed chamber (no overlap with EAST_UPPER) ===
    WEST_UPPER(Arrays.asList(
            new WorldPoint(3752, 5683, 0),
            new WorldPoint(3752, 5680, 0)
    ), true, Arrays.asList(
            new WorldArea(3748, 5678, 8, 8, 0)
    ), mesh(
            new WorldPoint(3748, 5678, 0), new WorldPoint(3749, 5678, 0),
            new WorldPoint(3750, 5678, 0), new WorldPoint(3751, 5678, 0),
            new WorldPoint(3752, 5678, 0), new WorldPoint(3753, 5678, 0),
            new WorldPoint(3748, 5679, 0), new WorldPoint(3749, 5679, 0),
            new WorldPoint(3750, 5679, 0), new WorldPoint(3751, 5679, 0),
            new WorldPoint(3752, 5679, 0), new WorldPoint(3753, 5679, 0),
            new WorldPoint(3748, 5680, 0), new WorldPoint(3749, 5680, 0),
            new WorldPoint(3750, 5680, 0), new WorldPoint(3751, 5680, 0),
            new WorldPoint(3752, 5680, 0), new WorldPoint(3753, 5680, 0),
            new WorldPoint(3748, 5681, 0), new WorldPoint(3749, 5681, 0),
            new WorldPoint(3750, 5681, 0), new WorldPoint(3751, 5681, 0),
            new WorldPoint(3752, 5681, 0), new WorldPoint(3753, 5681, 0),
            new WorldPoint(3748, 5682, 0), new WorldPoint(3749, 5682, 0),
            new WorldPoint(3750, 5682, 0), new WorldPoint(3751, 5682, 0),
            new WorldPoint(3752, 5682, 0), new WorldPoint(3753, 5682, 0),
            new WorldPoint(3748, 5683, 0), new WorldPoint(3749, 5683, 0),
            new WorldPoint(3750, 5683, 0), new WorldPoint(3751, 5683, 0),
            new WorldPoint(3752, 5683, 0), new WorldPoint(3753, 5683, 0),
            new WorldPoint(3748, 5684, 0), new WorldPoint(3749, 5684, 0),
            new WorldPoint(3750, 5684, 0), new WorldPoint(3751, 5684, 0),
            new WorldPoint(3752, 5684, 0), new WorldPoint(3753, 5684, 0)
    )),

    // === EAST UPPER — meshed chamber (x starts at 3756, no overlap with WEST_UPPER) ===
    EAST_UPPER(Arrays.asList(
            new WorldPoint(3760, 5673, 0),
            new WorldPoint(3759, 5673, 0)
    ), true, Arrays.asList(
            new WorldArea(3755, 5670, 10, 8, 0)
    ), mesh(
            new WorldPoint(3756, 5670, 0), new WorldPoint(3757, 5670, 0),
            new WorldPoint(3758, 5670, 0), new WorldPoint(3759, 5670, 0),
            new WorldPoint(3760, 5670, 0), new WorldPoint(3761, 5670, 0),
            new WorldPoint(3756, 5671, 0), new WorldPoint(3757, 5671, 0),
            new WorldPoint(3758, 5671, 0), new WorldPoint(3759, 5671, 0),
            new WorldPoint(3760, 5671, 0), new WorldPoint(3761, 5671, 0),
            new WorldPoint(3756, 5672, 0), new WorldPoint(3757, 5672, 0),
            new WorldPoint(3758, 5672, 0), new WorldPoint(3759, 5672, 0),
            new WorldPoint(3760, 5672, 0), new WorldPoint(3761, 5672, 0),
            new WorldPoint(3756, 5673, 0), new WorldPoint(3757, 5673, 0),
            new WorldPoint(3758, 5673, 0), new WorldPoint(3759, 5673, 0),
            new WorldPoint(3760, 5673, 0), new WorldPoint(3761, 5673, 0),
            new WorldPoint(3756, 5674, 0), new WorldPoint(3757, 5674, 0),
            new WorldPoint(3758, 5674, 0), new WorldPoint(3759, 5674, 0),
            new WorldPoint(3760, 5674, 0), new WorldPoint(3761, 5674, 0),
            new WorldPoint(3756, 5675, 0), new WorldPoint(3757, 5675, 0),
            new WorldPoint(3758, 5675, 0), new WorldPoint(3759, 5675, 0),
            new WorldPoint(3760, 5675, 0), new WorldPoint(3761, 5675, 0),
            new WorldPoint(3756, 5676, 0), new WorldPoint(3757, 5676, 0),
            new WorldPoint(3758, 5676, 0), new WorldPoint(3759, 5676, 0),
            new WorldPoint(3760, 5676, 0), new WorldPoint(3761, 5676, 0)
    ));

    private final List<WorldPoint> worldPoint;
    private final Boolean isUpstairs;
    private final List<WorldArea> worldAreas;
    private final Set<WorldPoint> mesh;

    private static Set<WorldPoint> mesh(WorldPoint... points) {
        if (points == null || points.length == 0) return null;
        Set<WorldPoint> set = new HashSet<>(Arrays.asList(points));
        return Collections.unmodifiableSet(set);
    }

    public boolean isUpstairs() {
        return Boolean.TRUE.equals(isUpstairs);
    }

    public boolean isDownstairs() {
        return Boolean.FALSE.equals(isUpstairs);
    }

    /**
     * Returns true if the point is inside the defined area for this spot.
     * <p>
     * If a mesh is defined, it uses a 1-tile adjacency buffer: the point itself
     * OR any tile within 1 tile of a recorded mesh tile counts. This lets you
     * record walkable floor tiles while still matching vein objects that sit on
     * the adjacent wall tiles.
     * <p>
     * If no mesh is defined, falls back to the WorldArea rectangles.
     */
    public boolean containsInArea(WorldPoint point) {
        if (point == null) return false;

        if (mesh != null && !mesh.isEmpty()) {
            int px = point.getX();
            int py = point.getY();
            int plane = point.getPlane();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (mesh.contains(new WorldPoint(px + dx, py + dy, plane))) {
                        return true;
                    }
                }
            }
            return false;
        }

        if (worldAreas != null) {
            for (WorldArea area : worldAreas) {
                if (area.contains(point)) return true;
            }
        }
        return false;
    }

    @Deprecated
    public boolean contains(WorldPoint point) {
        if (worldPoint == null || point == null) return false;
        return worldPoint.stream().anyMatch(wp -> wp.distanceTo(point) <= 15);
    }
}
