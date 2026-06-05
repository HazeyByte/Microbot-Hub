from pathlib import Path
p = Path(r"C:\Users\imatt\IdeaProjects\Microbot-Hub\src\main\java\net\runelite\client\plugins\microbot\motherloadmine\MotherloadMineAreaOverlay.java")
t = p.read_text(encoding="utf-8")
old = "        Set<WorldPoint> staticBlocked = new HashSet<>(MlmMapConstants.permanentRockfallBarriersFor(spot));\n        Set<WorldPoint> sessionBlocked"
new = """        Set<WorldPoint> staticBlocked = new HashSet<>(MlmMapConstants.permanentRockfallBarriersFor(spot));
        if (spot.isUpstairs()) {
            staticBlocked.addAll(MlmMapConstants.SHARED_UPPER_ROCKFALL_TILES);
            staticBlocked.addAll(MlmMapConstants.SHARED_UPPER_ROCKFALL_APPROACH_TILES);
        }
        Set<WorldPoint> sessionBlocked"""
if "SHARED_UPPER_ROCKFALL_TILES" not in t:
    t = t.replace(old, new)
    p.write_text(t, encoding="utf-8")
    print("overlay patched")
else:
    print("overlay already has shared")