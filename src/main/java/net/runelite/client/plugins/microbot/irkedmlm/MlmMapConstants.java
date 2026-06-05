package net.runelite.client.plugins.microbot.irkedmlm;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.motherloadmine.enums.MLMMiningSpot;

/**
 * Fixed Motherlode Mine world coordinates and static blocked tiles (map truth only — no gameplay rules).
 */
public final class IrkedMLMMapConstants {

    public static final int UPPER_HUB_MIN_Y = 5670;

    public static final WorldPoint LADDER_BOTTOM_WALK = new WorldPoint(3755, 5673, 0);
    public static final WorldPoint LADDER_TOP_WALK = new WorldPoint(3755, 5675, 0);
    public static final WorldPoint LADDER_BOTTOM_SAFE = new WorldPoint(3755, 5673, 0);

    public static final WorldPoint LOWER_HOPPER_WALK = new WorldPoint(3748, 5672, 0);
    public static final WorldPoint UPPER_HOPPER_WALK = new WorldPoint(3755, 5677, 0);

    public static final WorldPoint SUPPLY_CRATE_POINT = new WorldPoint(3752, 5674, 0);
    public static final WorldPoint DEPOSIT_BOX_LOC = new WorldPoint(3759, 5665, 0);
    public static final WorldPoint WATERWHEEL_AREA = new WorldPoint(3747, 5672, 0);

    public static final int HOPPER_OBJECT_ID = 26674;
    public static final int HOPPER_LOC_TOLERANCE = 2;

    /** The 2x2 rockfall that blocks the corridor between upper west and upper east. */
    public static final Set<WorldPoint> SHARED_UPPER_ROCKFALL_TILES = Set.of(
            new WorldPoint(3757, 5677, 0),
            new WorldPoint(3758, 5677, 0),
            new WorldPoint(3757, 5678, 0),
            new WorldPoint(3758, 5678, 0)
    );

    /** Flanking tiles: approaching the rockfall from either side is blocked for pathing. */
    public static final Set<WorldPoint> SHARED_UPPER_ROCKFALL_APPROACH_TILES = Set.of(
            new WorldPoint(3756, 5677, 0),
            new WorldPoint(3759, 5677, 0),
            new WorldPoint(3756, 5678, 0),
            new WorldPoint(3759, 5678, 0),
            new WorldPoint(3757, 5676, 0),
            new WorldPoint(3758, 5676, 0)
    );

    /** Tiles unreachable behind rockfall on upper-west (static survey). */
    public static final Set<WorldPoint> WEST_UPPER_ROCKFALL_BLOCKED_TILES = buildWestUpperRockfallBlockedTiles();
    /** Tiles unreachable behind rockfall on upper-east (static survey). */
    public static final Set<WorldPoint> EAST_UPPER_ROCKFALL_BLOCKED_TILES = buildEastUpperRockfallBlockedTiles();


    private IrkedMLMMapConstants() {
    }

    public static boolean isLowerMineTunnel(WorldPoint p) {
        return p != null && p.getPlane() == 0 && p.getY() < UPPER_HUB_MIN_Y;
    }

    public static boolean isRockfallBlockedTile(WorldPoint point) {
        if (point == null) {
            return false;
        }
        return WEST_UPPER_ROCKFALL_BLOCKED_TILES.contains(point)
                || EAST_UPPER_ROCKFALL_BLOCKED_TILES.contains(point)
                || SHARED_UPPER_ROCKFALL_TILES.contains(point)
                || SHARED_UPPER_ROCKFALL_APPROACH_TILES.contains(point);
    }

    public static Set<WorldPoint> permanentRockfallBarriersFor(MLMMiningSpot spot) {
        if (spot == MLMMiningSpot.WEST_UPPER) {
            return WEST_UPPER_ROCKFALL_BLOCKED_TILES;
        }
        if (spot == MLMMiningSpot.EAST_UPPER) {
            return EAST_UPPER_ROCKFALL_BLOCKED_TILES;
        }
        return Collections.emptySet();
    }

    private static Set<WorldPoint> buildWestUpperRockfallBlockedTiles() {
        Set<WorldPoint> blocked = new HashSet<>();
        addWestUpperTilesBehindRockfall(blocked);
        addSharedUpperRockfallLaneTiles(blocked);
        return Collections.unmodifiableSet(blocked);
    }

    private static Set<WorldPoint> buildEastUpperRockfallBlockedTiles() {
        Set<WorldPoint> blocked = new HashSet<>();
        addEastUpperTilesBehindRockfall(blocked);
        addSharedUpperRockfallLaneTiles(blocked);
        return Collections.unmodifiableSet(blocked);
    }

    private static void addSharedUpperRockfallLaneTiles(Set<WorldPoint> out) {
        out.addAll(SHARED_UPPER_ROCKFALL_TILES);
        out.addAll(SHARED_UPPER_ROCKFALL_APPROACH_TILES);
    }

    /** North / east face of upper-east chamber — not reachable from operating side. */
    private static void addEastUpperTilesBehindRockfall(Set<WorldPoint> out) {
        out.add(new WorldPoint(3760, 5678, 0));
        out.add(new WorldPoint(3763, 5679, 0));
        out.add(new WorldPoint(3766, 5681, 0));
        out.add(new WorldPoint(3760, 5679, 0));
        out.add(new WorldPoint(3763, 5678, 0));
        out.add(new WorldPoint(3766, 5680, 0));
        out.add(new WorldPoint(3763, 5677, 0));
        out.add(new WorldPoint(3766, 5679, 0));
        out.add(new WorldPoint(3765, 5680, 0));
        out.add(new WorldPoint(3767, 5677, 0));
        out.add(new WorldPoint(3762, 5677, 0));
        out.add(new WorldPoint(3765, 5679, 0));
        out.add(new WorldPoint(3762, 5676, 0));
        out.add(new WorldPoint(3765, 5678, 0));
        out.add(new WorldPoint(3760, 5677, 0));
        out.add(new WorldPoint(3761, 5676, 0));
        out.add(new WorldPoint(3764, 5678, 0));
        out.add(new WorldPoint(3762, 5679, 0));
        out.add(new WorldPoint(3762, 5678, 0));
        out.add(new WorldPoint(3761, 5679, 0));
        out.add(new WorldPoint(3759, 5677, 0));
        out.add(new WorldPoint(3761, 5678, 0));
        out.add(new WorldPoint(3764, 5680, 0));
        out.add(new WorldPoint(3759, 5678, 0));
        out.add(new WorldPoint(3761, 5677, 0));
        out.add(new WorldPoint(3764, 5679, 0));
    }

    /** Central / west face of upper-west chamber — not reachable from operating side. */
    private static void addWestUpperTilesBehindRockfall(Set<WorldPoint> out) {
        out.add(new WorldPoint(3761, 5682, 0));
        out.add(new WorldPoint(3759, 5686, 0));
        out.add(new WorldPoint(3758, 5682, 0));
        out.add(new WorldPoint(3762, 5685, 0));
        out.add(new WorldPoint(3757, 5678, 0));
        out.add(new WorldPoint(3758, 5683, 0));
        out.add(new WorldPoint(3756, 5680, 0));
        out.add(new WorldPoint(3761, 5685, 0));
        out.add(new WorldPoint(3758, 5678, 0));
        out.add(new WorldPoint(3759, 5683, 0));
        out.add(new WorldPoint(3756, 5681, 0));
        out.add(new WorldPoint(3761, 5684, 0));
        out.add(new WorldPoint(3759, 5684, 0));
        out.add(new WorldPoint(3756, 5682, 0));
        out.add(new WorldPoint(3761, 5683, 0));
        out.add(new WorldPoint(3759, 5685, 0));
        out.add(new WorldPoint(3764, 5685, 0));
        out.add(new WorldPoint(3756, 5683, 0));
        out.add(new WorldPoint(3760, 5684, 0));
        out.add(new WorldPoint(3757, 5682, 0));
        out.add(new WorldPoint(3760, 5685, 0));
        out.add(new WorldPoint(3763, 5685, 0));
        out.add(new WorldPoint(3757, 5683, 0));
        out.add(new WorldPoint(3760, 5686, 0));
        out.add(new WorldPoint(3757, 5684, 0));
        out.add(new WorldPoint(3761, 5686, 0));
        out.add(new WorldPoint(3762, 5684, 0));
        out.add(new WorldPoint(3757, 5679, 0));
        out.add(new WorldPoint(3758, 5684, 0));
        out.add(new WorldPoint(3757, 5680, 0));
        out.add(new WorldPoint(3758, 5685, 0));
        out.add(new WorldPoint(3760, 5683, 0));
        out.add(new WorldPoint(3757, 5681, 0));
    }
}
