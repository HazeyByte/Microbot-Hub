from pathlib import Path

ROOT = Path(r"C:\Users\imatt\IdeaProjects\Microbot-Hub\src\main\java\net\runelite\client\plugins\microbot\motherloadmine")

# MlmMapConstants
p = ROOT / "MlmMapConstants.java"
t = p.read_text(encoding="utf-8")
if "MLMMiningSpot" not in t:
    t = t.replace(
        "import net.runelite.api.coords.WorldPoint;\n",
        "import net.runelite.api.coords.WorldPoint;\n"
        "import net.runelite.client.plugins.microbot.motherloadmine.enums.MLMMiningSpot;\n",
    )
needle = "    private static Set<WorldPoint> buildWestUpperRockfallBlockedTiles() {"
if "permanentRockfallBarriersFor" not in t:
    insert = """    public static Set<WorldPoint> permanentRockfallBarriersFor(MLMMiningSpot spot) {
        if (spot == MLMMiningSpot.WEST_UPPER) {
            return WEST_UPPER_ROCKFALL_BLOCKED_TILES;
        }
        if (spot == MLMMiningSpot.EAST_UPPER) {
            return EAST_UPPER_ROCKFALL_BLOCKED_TILES;
        }
        return Collections.emptySet();
    }

    """
    t = t.replace(needle, insert + needle)
p.write_text(t, encoding="utf-8")

# Config
p2 = ROOT / "MotherloadMineConfig.java"
t2 = p2.read_text(encoding="utf-8")
if "default boolean showMiningAreas()" not in t2:
    block = """\t@ConfigItem(
\t\t\tkeyName = showMiningAreas,
\t\t\tname = "Show Mining Areas",
\t\t\tdescription = "Draw selectable zone tiles (green) and rockfall-blocked tiles (red = static map, orange = session memory) on the game map for the configured/active mining spot.",
\t\t\tposition = 3,
\t\t\tsection = coreSection
\t)
\tdefault boolean showMiningAreas()
\t{
\t\treturn false;
\t}

"""
    t2 = t2.replace(
        "\t@ConfigItem(\n\t\t\tkeyName = useUpstairsHopper,",
        block + "\t@ConfigItem(\n\t\t\tkeyName = useUpstairsHopper,",
    )
    t2 = t2.replace(
        "position = 3,\n\t\t\tsection = coreSection\n\t)\n\tdefault boolean upstairsHopperUnlocked()",
        "position = 4,\n\t\t\tsection = coreSection\n\t)\n\tdefault boolean upstairsHopperUnlocked()",
    )
p2.write_text(t2, encoding="utf-8")
print("patched")