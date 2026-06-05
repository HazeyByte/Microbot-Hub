#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(r"C:\Users\imatt\IdeaProjects\Microbot-Hub")
mining = ROOT / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/session/MiningSession.java"
script = ROOT / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/MotherloadMineScript.java"
plugin = ROOT / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/MotherloadMinePlugin.java"

m = mining.read_text(encoding="utf-8")

# Rockfall IDs from gamevals
m = m.replace(
    "    private static final int[] ROCKFALL_IDS = {26679, 26680};",
    "    private static final int[] ROCKFALL_IDS = {\n"
    "            ObjectID.MOTHERLODE_ROCKFALL_1,\n"
    "            ObjectID.MOTHERLODE_ROCKFALL_2\n"
    "    };",
)

old_upper = """    private WorldPoint selectNearestUpperVein(MLMMiningSpot spot, WorldPoint playerLoc) {
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

new_upper = """    private WorldPoint selectNearestUpperVein(MLMMiningSpot spot, WorldPoint playerLoc) {
        if (spot == null || playerLoc == null) {
            return null;
        }
        Rs2TileObjectModel best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Rs2TileObjectModel vein : tileCache.query()
                .where(o -> isOreVein(o.getId())
                        && isActiveVein(o)
                        && spot.containsInArea(o.getWorldLocation())
                        && !isRecentlyFailed(o.getWorldLocation(), spot))
                .toList()) {
            if (!isMineableFromPlayer(vein, spot, playerLoc)) {
                continue;
            }
            int dist = playerLoc.distanceTo(vein.getWorldLocation());
            if (dist < bestDist) {
                bestDist = dist;
                best = vein;
            }
        }
        if (best == null) {
            return null;
        }
        WorldPoint chosen = best.getWorldLocation();
        log.info("[MiningSession] Upper floor: selected mineable vein at {} (dist={}, LOS+path ok)",
                chosen, bestDist);
        return chosen;
    }

    /**
     * Reachable-on-screen is not enough for MLM wall veins / rockfalls — require LOS and no rockfall on path.
     */
    private boolean isMineableFromPlayer(Rs2TileObjectModel vein, MLMMiningSpot spot, WorldPoint playerLoc) {
        if (vein == null || spot == null || playerLoc == null) {
            return false;
        }
        WorldPoint vp = vein.getWorldLocation();
        if (!spot.containsInArea(vp) || !isActiveVein(vein)) {
            return false;
        }
        if (isRecentlyFailed(vp, spot)) {
            return false;
        }
        if (isPathBlockedByRockfall(playerLoc, vp)) {
            return false;
        }
        int maxDist = spot.isUpstairs() ? UPPER_VEIN_MAX_REACHABLE_DISTANCE : 12;
        if (playerLoc.distanceTo(vp) > maxDist) {
            return false;
        }
        return hasLineOfSightToVein(vein);
    }"""

if old_upper not in m:
    raise SystemExit("upper select block missing")
m = m.replace(old_upper, new_upper)

m = m.replace(
    "        if (spot.isUpstairs()) {\n            return playerLoc.distanceTo(vp) <= UPPER_VEIN_MAX_REACHABLE_DISTANCE;\n        }\n        return hasLineOfSightToVein(vein);",
    "        return isMineableFromPlayer(vein, spot, playerLoc);",
)

m = m.replace(
    "            if (!spot.isUpstairs() && !hasLineOfSightToVein(vein)) continue;\n"
    "            if (spot.isUpstairs() && playerLoc.distanceTo(vp) > UPPER_VEIN_MAX_REACHABLE_DISTANCE) continue;\n"
    "            candidates.add(vein);",
    "            if (!isMineableFromPlayer(vein, spot, playerLoc)) continue;\n"
    "            candidates.add(vein);",
)

# CLICKED: rockfall/LOS block after failed mine attempt
m = m.replace(
    "                if (!spot.isUpstairs() && !hasLineOfSightToVein(vein)) {\n"
    "                    log.debug(\"[MiningSession] No line of sight to vein at {} - reselecting\", targetVein);",
    "                if (!hasLineOfSightToVein(vein) || isPathBlockedByRockfall(playerLocClick, targetVein)) {\n"
    "                    log.debug(\"[MiningSession] Vein at {} not mineable (LOS/path) - reselecting\", targetVein);",
)

# Reposition: use mineable filter not just nearestReachable
m = m.replace(
    """                    var repoVein = tileCache.query()
                            .where(o -> isOreVein(o.getId())
                                    && isActiveVein(o)
                                    && spot.containsInArea(o.getWorldLocation())
                                    && !isRecentlyFailed(o.getWorldLocation(), spot))
                            .nearestReachable(UPPER_VEIN_MAX_REACHABLE_DISTANCE);""",
    """                    Rs2TileObjectModel repoVein = null;
                    int repoBest = Integer.MAX_VALUE;
                    for (Rs2TileObjectModel v : tileCache.query()
                            .where(o -> isOreVein(o.getId())
                                    && isActiveVein(o)
                                    && spot.containsInArea(o.getWorldLocation())
                                    && !isRecentlyFailed(o.getWorldLocation(), spot))
                            .toList()) {
                        if (!isMineableFromPlayer(v, spot, repoPlayer)) {
                            continue;
                        }
                        int d = repoPlayer.distanceTo(v.getWorldLocation());
                        if (d < repoBest) {
                            repoBest = d;
                            repoVein = v;
                        }
                    }""",
)

mining.write_text(m, encoding="utf-8", newline="\n")
print("OK MiningSession")

s = script.read_text(encoding="utf-8")
if "SPEC_ENERGY_FULL" not in s:
    s = s.replace(
        "    private static final int SPEC_ENERGY_CRYSTAL           = 1000;\n",
        "    private static final int SPEC_ENERGY_CRYSTAL           = 1000;\n"
        "    /** Wait until spec bar is full before using special attack. */\n"
        "    private static final int SPEC_ENERGY_FULL              = 1000;\n",
    )

s = s.replace(
    "        int specEnergy = Microbot.getRs2PlayerStateCache().getVarpValue(VARP_SPEC_ENERGY);\n        boolean specUsed = false;\n",
    "        int specEnergy = Microbot.getRs2PlayerStateCache().getVarpValue(VARP_SPEC_ENERGY);\n"
    "        if (specEnergy < SPEC_ENERGY_FULL) {\n"
    "            debug(\"[MLM] Spec skipped: need full spec bar ({}/{})\", specEnergy, SPEC_ENERGY_FULL);\n"
    "            return;\n"
    "        }\n"
    "        boolean specUsed = false;\n",
)

plugin.write_text(plugin.read_text(encoding="utf-8").replace("2.1.44", "2.1.45"), encoding="utf-8", newline="\n")
script.write_text(s, encoding="utf-8", newline="\n")
print("OK script + version 2.1.45")