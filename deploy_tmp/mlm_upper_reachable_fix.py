#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(r"C:\Users\imatt\IdeaProjects\Microbot-Hub")
mining = ROOT / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/session/MiningSession.java"
plugin = ROOT / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/MotherloadMinePlugin.java"

t = mining.read_text(encoding="utf-8")

# Constants after UPPER_WEB_WALK_MIN_DISTANCE
if "UPPER_VEIN_MAX_REACHABLE_DISTANCE" not in t:
    t = t.replace(
        "    private static final int UPPER_WEB_WALK_MIN_DISTANCE = 20;\n",
        "    private static final int UPPER_WEB_WALK_MIN_DISTANCE = 20;\n"
        "    /** Max tiles for upper-chamber reachable vein selection (compact room). */\n"
        "    private static final int UPPER_VEIN_MAX_REACHABLE_DISTANCE = 8;\n",
    )

old_upper_select = """    private WorldPoint selectNearestUpperVein(MLMMiningSpot spot, WorldPoint playerLoc) {
        if (spot == null || playerLoc == null) {
            return null;
        }
        Rs2TileObjectModel nearest = null;
        int bestDist = Integer.MAX_VALUE;
        for (Rs2TileObjectModel vein : tileCache.query()
                .where(o -> isOreVein(o.getId())
                        && isActiveVein(o)
                        && spot.containsInArea(o.getWorldLocation())
                        && !isRecentlyFailed(o.getWorldLocation(), spot))
                .toList()) {
            WorldPoint vp = vein.getWorldLocation();
            if (isPathBlockedByRockfall(playerLoc, vp)) {
                continue;
            }
            int dist = playerLoc.distanceTo(vp);
            if (dist < bestDist) {
                bestDist = dist;
                nearest = vein;
            }
        }
        if (nearest == null) {
            return null;
        }
        WorldPoint chosen = nearest.getWorldLocation();
        log.info("[MiningSession] Upper floor: selected nearest vein at {} (dist={}, no LOS gate)",
                chosen, bestDist);
        return chosen;
    }"""

new_upper_select = """    private WorldPoint selectNearestUpperVein(MLMMiningSpot spot, WorldPoint playerLoc) {
        if (spot == null || playerLoc == null) {
            return null;
        }
        var reachable = tileCache.query()
                .where(o -> isOreVein(o.getId())
                        && isActiveVein(o)
                        && spot.containsInArea(o.getWorldLocation())
                        && !isPathBlockedByRockfall(playerLoc, o.getWorldLocation())
                        && !isRecentlyFailed(o.getWorldLocation(), spot))
                .nearestReachable(UPPER_VEIN_MAX_REACHABLE_DISTANCE);
        if (reachable == null) {
            return null;
        }
        WorldPoint chosen = reachable.getWorldLocation();
        log.info("[MiningSession] Upper floor: selected reachable vein at {} (dist={})",
                chosen, playerLoc.distanceTo(chosen));
        return chosen;
    }"""

if old_upper_select not in t:
    raise SystemExit("selectNearestUpperVein block not found")
t = t.replace(old_upper_select, new_upper_select)

t = t.replace(
    "        if (spot.isUpstairs()) {\n            return playerLoc.distanceTo(vp) <= 20;\n        }\n        return hasLineOfSightToVein(vein);",
    "        if (spot.isUpstairs()) {\n            return playerLoc.distanceTo(vp) <= UPPER_VEIN_MAX_REACHABLE_DISTANCE;\n        }\n        return hasLineOfSightToVein(vein);",
)

t = t.replace(
    "                if (!hasLineOfSightToVein(vein)) {\n                    log.debug(\"[MiningSession] No line of sight to vein at {} - reselecting\", targetVein);",
    "                if (!isVeinPresent(targetVein)) {\n                    log.debug(\"[MiningSession] Vein at {} depleted before click - reselecting\", targetVein);\n                    markFailed(targetVein, spot);\n                    targetVein = null;\n                    failedClicks = 0;\n                    transitionSub(MiningSubState.SELECTED);\n                    break;\n                }\n                if (!spot.isUpstairs() && !hasLineOfSightToVein(vein)) {\n                    log.debug(\"[MiningSession] No line of sight to vein at {} - reselecting\", targetVein);",
)

t = t.replace(
    "            case DEPLETED:\n                targetVein = null;\n                transitionSub(MiningSubState.SELECTED);",
    "            case DEPLETED:\n                if (targetVein != null) {\n                    markFailed(targetVein, spot);\n                }\n                targetVein = null;\n                transitionSub(MiningSubState.SELECTED);",
)

t = t.replace(
    """    private Rs2TileObjectModel findVeinAt(WorldPoint wp) {
        if (wp == null) return null;
        Rs2TileObjectModel vein = tileCache.query()
                .where(o -> isOreVein(o.getId()) && o.getWorldLocation().distanceTo(wp) <= 2)
                .nearest();
        return vein;
    }""",
    """    private Rs2TileObjectModel findVeinAt(WorldPoint wp) {
        if (wp == null) return null;
        Rs2TileObjectModel vein = tileCache.query()
                .where(o -> isOreVein(o.getId())
                        && isActiveVein(o)
                        && o.getWorldLocation().equals(wp))
                .nearest();
        return vein != null && isActiveVein(vein) ? vein : null;
    }""",
)

t = t.replace(
    "        return vein != null && isOreVein(vein.getId());",
    "        return vein != null && isActiveVein(vein);",
)

t = t.replace(
    "            if (!hasLineOfSightToVein(vein)) continue;\n            candidates.add(vein);",
    "            if (!spot.isUpstairs() && !hasLineOfSightToVein(vein)) continue;\n"
    "            if (spot.isUpstairs() && playerLoc.distanceTo(vp) > UPPER_VEIN_MAX_REACHABLE_DISTANCE) continue;\n"
    "            candidates.add(vein);",
)

# Reposition: reachable not raw nearest
t = t.replace(
    """                    var repoVein = tileCache.query()
                            .where(o -> isOreVein(o.getId())
                                    && isActiveVein(o)
                                    && spot.containsInArea(o.getWorldLocation())
                                    && !isRecentlyFailed(o.getWorldLocation(), spot))
                            .nearest();""",
    """                    var repoVein = tileCache.query()
                            .where(o -> isOreVein(o.getId())
                                    && isActiveVein(o)
                                    && spot.containsInArea(o.getWorldLocation())
                                    && !isRecentlyFailed(o.getWorldLocation(), spot))
                            .nearestReachable(UPPER_VEIN_MAX_REACHABLE_DISTANCE);""",
)

mining.write_text(t, encoding="utf-8", newline="\n")
plugin.write_text(plugin.read_text(encoding="utf-8").replace("2.1.43", "2.1.44"), encoding="utf-8", newline="\n")
print("OK 2.1.44")
