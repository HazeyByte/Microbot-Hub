from pathlib import Path

p = Path(r"C:\Users\imatt\IdeaProjects\Microbot-Hub\src\main\java\net\runelite\client\plugins\microbot\motherloadmine\MlmMapConstants.java")
t = p.read_text(encoding="utf-8")

shared = """
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

"""

if "SHARED_UPPER_ROCKFALL_TILES" not in t:
    t = t.replace(
        "    public static final Set<WorldPoint> EAST_UPPER_ROCKFALL_BLOCKED_TILES = buildEastUpperRockfallBlockedTiles();\n\n    private MlmMapConstants()",
        "    public static final Set<WorldPoint> EAST_UPPER_ROCKFALL_BLOCKED_TILES = buildEastUpperRockfallBlockedTiles();\n"
        + shared
        + "    private MlmMapConstants()",
    )

old_check = """        return WEST_UPPER_ROCKFALL_BLOCKED_TILES.contains(point)
                || EAST_UPPER_ROCKFALL_BLOCKED_TILES.contains(point);
    }"""

new_check = """        return WEST_UPPER_ROCKFALL_BLOCKED_TILES.contains(point)
                || EAST_UPPER_ROCKFALL_BLOCKED_TILES.contains(point)
                || SHARED_UPPER_ROCKFALL_TILES.contains(point)
                || SHARED_UPPER_ROCKFALL_APPROACH_TILES.contains(point);
    }"""

t = t.replace(old_check, new_check)

old_build = """        private static Set<WorldPoint> buildWestUpperRockfallBlockedTiles() {
        Set<WorldPoint> blocked = new HashSet<>();
        addWestUpperTilesBehindRockfall(blocked);
        addSharedUpperRockfallLaneTiles(blocked);
        return Collections.unmodifiableSet(blocked);
    }

    private static Set<WorldPoint> buildEastUpperRockfallBlockedTiles() {
        Set<WorldPoint> blocked = new HashSet<>();
        addEastUpperTilesBehindRockfall(blocked);
        addSharedUpperRockfallLaneTiles(blocked);
        blocked.add(new WorldPoint(3758, 5677, 0));
        return Collections.unmodifiableSet(blocked);
    }

    /** Corridor tiles blocked when rockfall is up between upper-west and upper-east. */
    private static void addSharedUpperRockfallLaneTiles(Set<WorldPoint> out) {
        out.add(new WorldPoint(3757, 5677, 0));
    }"""

new_build = """    private static Set<WorldPoint> buildWestUpperRockfallBlockedTiles() {
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
    }"""

t = t.replace(old_build, new_build)
p.write_text(t, encoding="utf-8")
print("ok")