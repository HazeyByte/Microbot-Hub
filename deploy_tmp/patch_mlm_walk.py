from pathlib import Path

root = Path(r"C:\Users\imatt\IdeaProjects\Microbot-Hub")

# MLMMiningSpot
p = root / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/enums/MLMMiningSpot.java"
t = p.read_text(encoding="utf-8")
t = t.replace("eastUpperLassoMesh()", "eastUpperChamberMesh()")
t = t.replace("private static Set<WorldPoint> eastUpperLassoMesh()", "private static Set<WorldPoint> eastUpperChamberMesh()")
if "lasso-defined" in t:
    import re
    t = re.sub(
        r"// === EAST UPPER — lasso-defined chamber \(Lasso-\d+\); strict mesh \(no ±1 buffer\) ===",
        "// === EAST UPPER — hand-defined chamber mesh; strict membership (no ±1 buffer) ===",
        t,
    )
t = t.replace("for upper-east chamber (lasso tool capture).", "for the upper-east chamber.")
p.write_text(t, encoding="utf-8")

# MiningSession
p2 = root / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/session/MiningSession.java"
m = p2.read_text(encoding="utf-8")
if "LOWER_WEB_WALK_MIN_DISTANCE" not in m:
    m = m.replace(
        "    private static final long FAILURE_BLACKLIST_UPPER_MS = 60_000L;\n\n    private final Rs2TileObjectCache",
        "    private static final long FAILURE_BLACKLIST_UPPER_MS = 60_000L;\n\n"
        "    /** Beyond this distance, lower-mine travel uses web-walk (canvas is unreliable across tunnels). */\n"
        "    private static final int LOWER_WEB_WALK_MIN_DISTANCE = 14;\n"
        "    private static final int UPPER_WEB_WALK_MIN_DISTANCE = 20;\n\n"
        "    private final Rs2TileObjectCache",
    )

start = m.find("    private void walkTowardMiningTarget")
end = m.find("    private boolean isWrongFloor", start)
if start < 0 or end < 0:
    raise SystemExit("walkTowardMiningTarget block not found")

new_walk = """    /**
     * Walk toward a mining target: canvas for short hops, web-walk when far (including lower tunnels).
     * Clears stale web-walk targets when already at the goal (avoids pathfinder-still-null stall).
     */
    private void walkTowardMiningTarget(WorldPoint target, MLMMiningSpot spot, WorldPoint playerLoc) {
        if (target == null || playerLoc == null) return;
        int distance = playerLoc.distanceTo(target);
        if (distance <= 2) {
            Rs2Walker.setTarget(null);
            return;
        }
        Rs2Walker.setTarget(null);

        int webWalkThreshold = (spot != null && spot.isDownstairs())
                ? LOWER_WEB_WALK_MIN_DISTANCE
                : UPPER_WEB_WALK_MIN_DISTANCE;

        if (distance > webWalkThreshold) {
            log.debug("[MiningSession] Web-walk to {} ({} tiles, threshold={})",
                    target, distance, webWalkThreshold);
            Rs2Walker.walkTo(target);
        } else {
            log.debug("[MiningSession] walkFastCanvas to {} ({} tiles)", target, distance);
            Rs2Walker.walkFastCanvas(target);
        }
    }

"""
m = m[:start] + new_walk + m[end:]
p2.write_text(m, encoding="utf-8")

p3 = root / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/MotherloadMineScript.java"
s = p3.read_text(encoding="utf-8")
s = s.replace("outside a strict lasso tile but still be mined", "outside a strict mesh tile but still be mined")
p3.write_text(s, encoding="utf-8")

pl = root / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/MotherloadMinePlugin.java"
pl_text = pl.read_text(encoding="utf-8").replace("2.1.36", "2.1.37")
pl.write_text(pl_text, encoding="utf-8")
print("OK")