#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(r"C:\Users\imatt\IdeaProjects\Microbot-Hub")
mining = ROOT / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/session/MiningSession.java"
plugin = ROOT / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/MotherloadMinePlugin.java"

t = mining.read_text(encoding="utf-8")

if "selectNearestUpperVein" not in t:
    insert_method = '''
    /**
     * Upper chamber wall veins are often mined from an adjacent tile without engine LOS from the ladder/hub.
     */
    private WorldPoint selectNearestUpperVein(MLMMiningSpot spot, WorldPoint playerLoc) {
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
    }

'''
    anchor = "    private WorldPoint selectVein(MLMMiningSpot spot) {"
    t = t.replace(anchor, insert_method + anchor)

    t = t.replace(
        "        WorldPoint anchor = getFirstAnchor(spot);\n\n        // 1. Primary:",
        "        WorldPoint anchor = getFirstAnchor(spot);\n\n        if (spot.isUpstairs()) {\n            WorldPoint upperPick = selectNearestUpperVein(spot, playerLoc);\n            if (upperPick != null) {\n                return upperPick;\n            }\n        }\n\n        // 1. Primary:",
    )

t = t.replace(
    "        if (isPathBlockedByRockfall(playerLoc, vp)) return false;\n        return hasLineOfSightToVein(vein);",
    "        if (isPathBlockedByRockfall(playerLoc, vp)) return false;\n        if (spot.isUpstairs()) {\n            return playerLoc.distanceTo(vp) <= 20;\n        }\n        return hasLineOfSightToVein(vein);",
)

old_repo = """                if (shouldSkipSpotWalk(spot)) {
                    scheduleNextAdaptive(250L, 800L);
                    transitionSub(MiningSubState.SELECTED);
                    break;
                }"""
new_repo = """                if (shouldSkipSpotWalk(spot)) {
                    WorldPoint repoPlayer = Rs2Player.getWorldLocation();
                    var repoVein = tileCache.query()
                            .where(o -> isOreVein(o.getId())
                                    && isActiveVein(o)
                                    && spot.containsInArea(o.getWorldLocation())
                                    && !isRecentlyFailed(o.getWorldLocation(), spot))
                            .nearest();
                    if (repoVein != null && repoPlayer != null
                            && repoPlayer.distanceTo(repoVein.getWorldLocation()) > 2) {
                        WorldPoint stand = walkTargetForVein(repoVein.getWorldLocation());
                        if (stand != null) {
                            log.info("[MiningSession] Upper reposition: walking toward vein at {}", stand);
                            Rs2Walker.walkFastCanvas(stand);
                            scheduleNextAdaptive(180L, 600L);
                        }
                    }
                    transitionSub(MiningSubState.SELECTED);
                    break;
                }"""
if old_repo in t:
    t = t.replace(old_repo, new_repo)
else:
    raise SystemExit("REPOSITIONING block not found")

mining.write_text(t, encoding="utf-8", newline="\n")
print("OK MiningSession.java")

pt = plugin.read_text(encoding="utf-8").replace("2.1.42", "2.1.43")
plugin.write_text(pt, encoding="utf-8", newline="\n")
print("OK version 2.1.43")