from pathlib import Path

p = Path(r"C:\Users\imatt\IdeaProjects\Microbot-Hub\src\main\java\net\runelite\client\plugins\microbot\motherloadmine\session\MiningSession.java")
text = p.read_text(encoding="utf-8")

# imports
old_imp = "import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;\n\nimport net.runelite.api.gameval.ObjectID;"
new_imp = """import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import net.runelite.api.gameval.ObjectID;"""
if old_imp not in text:
    raise SystemExit("imports block not found")
text = text.replace(old_imp, new_imp)

# constants
text = text.replace(
    "    private static final long FAILURE_BLACKLIST_MS = 10_000L;\n",
    "    private static final long FAILURE_BLACKLIST_MS = 10_000L;\n"
    "    private static final long FAILURE_BLACKLIST_UPPER_MS = 60_000L;\n",
)

# cleanupRecentFailures
text = text.replace(
    "        recentFailures.entrySet().removeIf(e -> now - e.getValue() > FAILURE_BLACKLIST_MS);",
    "        recentFailures.entrySet().removeIf(e -> now - e.getValue() > FAILURE_BLACKLIST_UPPER_MS);",
)

# failure helpers block
old_helpers = """    private boolean isRecentlyFailed(WorldPoint wp) {
        if (wp == null) return false;
        Long ts = recentFailures.get(wp);
        if (ts == null) return false;
        if (System.currentTimeMillis() - ts > FAILURE_BLACKLIST_MS) {
            recentFailures.remove(wp);
            return false;
        }
        return true;
    }

    private void markFailed(WorldPoint wp) {
        if (wp != null) recentFailures.put(wp, System.currentTimeMillis());
    }"""

new_helpers = """    private long failureBlacklistMs(MLMMiningSpot spot) {
        return spot != null && spot.isUpstairs() ? FAILURE_BLACKLIST_UPPER_MS : FAILURE_BLACKLIST_MS;
    }

    private boolean isRecentlyFailed(WorldPoint wp, MLMMiningSpot spot) {
        if (wp == null) return false;
        Long ts = recentFailures.get(wp);
        if (ts == null) return false;
        if (System.currentTimeMillis() - ts > failureBlacklistMs(spot)) {
            recentFailures.remove(wp);
            return false;
        }
        return true;
    }

    private void markFailed(WorldPoint wp, MLMMiningSpot spot) {
        if (wp != null) {
            recentFailures.put(wp, System.currentTimeMillis());
        }
    }

    private boolean hasLineOfSightToVein(Rs2TileObjectModel vein) {
        if (vein == null) return false;
        return Microbot.getClientThread()
                .runOnClientThreadOptional(() -> Rs2GameObject.hasLineOfSight(vein))
                .orElse(false);
    }

    private boolean isVeinCandidate(Rs2TileObjectModel vein, MLMMiningSpot spot, WorldPoint playerLoc) {
        if (vein == null || spot == null || playerLoc == null) return false;
        if (!isOreVein(vein.getId()) || !isActiveVein(vein)) return false;
        WorldPoint vp = vein.getWorldLocation();
        if (!spot.containsInArea(vp)) return false;
        if (isRecentlyFailed(vp, spot)) return false;
        if (isPathBlockedByRockfall(playerLoc, vp)) return false;
        return hasLineOfSightToVein(vein);
    }

    private WorldPoint walkTargetForVein(WorldPoint veinLoc) {
        if (veinLoc == null) return null;
        WorldPoint stand = Rs2Tile.getNearestWalkableTileWithLineOfSight(veinLoc);
        return stand != null ? stand : veinLoc;
    }"""

if old_helpers not in text:
    raise SystemExit("failure helpers not found")
text = text.replace(old_helpers, new_helpers)

# markFailed(targetVein) -> markFailed(targetVein, spot)
text = text.replace("markFailed(targetVein);", "markFailed(targetVein, spot);")

# WALKING visible + nearest block
old_walking = """                // Fast-path: if veins are already visible and close, skip walking.
                WorldPoint anchor = getFirstAnchor(spot);
                WorldPoint playerLoc = Rs2Player.getWorldLocation();
                var visibleVeins = tileCache.query()
                        .where(o -> isOreVein(o.getId())
                                && isActiveVein(o)
                                && spot.containsInArea(o.getWorldLocation()))
                        .toList();
                Rs2TileObjectModel bestVisible = null;
                long bestScore = Long.MAX_VALUE;
                for (Rs2TileObjectModel v : visibleVeins) {
                    if (v.getWorldLocation().distanceTo(playerLoc) > 10) continue;

                    // NEW: ignore veins that are in the area but blocked by a remembered rockfall
                    if (isPathBlockedByRockfall(playerLoc, v.getWorldLocation())) {
                        log.debug("[MiningSession] Visible vein at {} blocked by rockfall — skipping",
                                v.getWorldLocation());
                        continue;
                    }

                    long score = (anchor != null) ? anchor.distanceTo(v.getWorldLocation()) * 2L : 0L;
                    score += playerLoc.distanceTo(v.getWorldLocation()) * 2L;

                    if (score < bestScore) {
                        bestScore = score;
                        bestVisible = v;
                    }
                }
                if (bestVisible != null) {
                    log.info("[MiningSession] Vein visible at {} (score={}) — skipping walk",
                            bestVisible.getWorldLocation(), bestScore);
                    targetVein = bestVisible.getWorldLocation();
                    transitionSub(MiningSubState.CLICKED);
                    break;
                }

                // Walking cooldown & logic
                if (!Rs2Player.isMoving() && (now - lastWalkCommandMs >= 1200L)) {
                    var nearestVein = tileCache.query()
                            .where(o -> isOreVein(o.getId())
                                    && isActiveVein(o)
                                    && spot.containsInArea(o.getWorldLocation()))
                            .nearest();

                    // NEW: if the nearest vein is behind a rockfall, don't walk toward it.
                    if (nearestVein != null && isPathBlockedByRockfall(playerLoc, nearestVein.getWorldLocation())) {
                        log.debug("[MiningSession] Nearest vein at {} blocked by rockfall — using anchor instead",
                                nearestVein.getWorldLocation());
                        nearestVein = null;
                    }

                    WorldPoint walkTarget;
                    if (nearestVein != null) {
                        walkTarget = nearestVein.getWorldLocation();
                    } else {
                        walkTarget = anchor;
                    }"""

new_walking = """                // Fast-path: adjacent reachable vein with LOS — skip walking.
                WorldPoint anchor = getFirstAnchor(spot);
                WorldPoint playerLoc = Rs2Player.getWorldLocation();
                var visibleVeins = tileCache.query()
                        .where(o -> isOreVein(o.getId())
                                && isActiveVein(o)
                                && spot.containsInArea(o.getWorldLocation()))
                        .toList();
                Rs2TileObjectModel bestVisible = null;
                long bestScore = Long.MAX_VALUE;
                for (Rs2TileObjectModel v : visibleVeins) {
                    if (v.getWorldLocation().distanceTo(playerLoc) > 2) continue;
                    if (!isVeinCandidate(v, spot, playerLoc)) continue;

                    long score = (anchor != null) ? anchor.distanceTo(v.getWorldLocation()) * 2L : 0L;
                    score += playerLoc.distanceTo(v.getWorldLocation()) * 2L;

                    if (score < bestScore) {
                        bestScore = score;
                        bestVisible = v;
                    }
                }
                if (bestVisible != null) {
                    log.info("[MiningSession] Vein adjacent with LOS at {} (score={}) — skipping walk",
                            bestVisible.getWorldLocation(), bestScore);
                    targetVein = bestVisible.getWorldLocation();
                    transitionSub(MiningSubState.CLICKED);
                    break;
                }

                // Walking cooldown & logic
                if (!Rs2Player.isMoving() && (now - lastWalkCommandMs >= 1200L)) {
                    var nearestVein = tileCache.query()
                            .where(o -> isOreVein(o.getId())
                                    && isActiveVein(o)
                                    && spot.containsInArea(o.getWorldLocation())
                                    && !isRecentlyFailed(o.getWorldLocation(), spot)
                                    && !isPathBlockedByRockfall(playerLoc, o.getWorldLocation()))
                            .nearestReachable();

                    if (nearestVein != null && !isVeinCandidate(nearestVein, spot, playerLoc)) {
                        nearestVein = null;
                    }

                    WorldPoint walkTarget;
                    if (nearestVein != null) {
                        walkTarget = walkTargetForVein(nearestVein.getWorldLocation());
                    } else {
                        walkTarget = anchor;
                    }"""

if old_walking not in text:
    raise SystemExit("walking block not found")
text = text.replace(old_walking, new_walking)

# CLICKED block - add LOS and distance before camera
old_clicked = """                Rs2TileObjectModel vein = findVeinAt(targetVein);
                if (vein == null) {
                    log.debug("[MiningSession] Vein at {} lost before click — clearing and reselecting", targetVein);
                    markFailed(targetVein, spot);
                    targetVein = null;
                    failedClicks = 0;
                    transitionSub(MiningSubState.SELECTED);
                    break;
                }

                // Reactive camera: Turn to vein if not on screen"""

new_clicked = """                Rs2TileObjectModel vein = findVeinAt(targetVein);
                if (vein == null) {
                    log.debug("[MiningSession] Vein at {} lost before click — clearing and reselecting", targetVein);
                    markFailed(targetVein, spot);
                    targetVein = null;
                    failedClicks = 0;
                    transitionSub(MiningSubState.SELECTED);
                    break;
                }

                WorldPoint playerLocClick = Rs2Player.getWorldLocation();
                if (!hasLineOfSightToVein(vein)) {
                    log.debug("[MiningSession] No line of sight to vein at {} — reselecting", targetVein);
                    markFailed(targetVein, spot);
                    targetVein = null;
                    failedClicks = 0;
                    transitionSub(MiningSubState.SELECTED);
                    break;
                }
                if (playerLocClick != null && playerLocClick.distanceTo(targetVein) > 2) {
                    WorldPoint stand = walkTargetForVein(targetVein);
                    if (stand != null) {
                        log.debug("[MiningSession] Too far from vein — walking to stand tile {}", stand);
                        Rs2Walker.walkFastCanvas(stand);
                    }
                    scheduleNext(600L);
                    break;
                }

                // Reactive camera: Turn to vein if not on screen"""

if old_clicked not in text:
    raise SystemExit("clicked block not found")
text = text.replace(old_clicked, new_clicked)

# click failure threshold
old_fail = """                } else {
                    failedClicks++;
                    log.debug("[MiningSession] Click failed ({}/3)", failedClicks);
                    if (failedClicks > 3) {
                        log.warn("[MiningSession] Too many failed clicks on vein at {} — picking a new one", targetVein);
                        markFailed(targetVein, spot);
                        targetVein = null;
                        failedClicks = 0;
                        transitionSub(MiningSubState.SELECTED);
                    } else {
                        scheduleNext(800L);
                    }
                }"""

new_fail = """                } else {
                    failedClicks++;
                    int maxClickFails = spot.isUpstairs() ? 1 : 3;
                    log.debug("[MiningSession] Click failed ({}/{})", failedClicks, maxClickFails);
                    if (failedClicks > maxClickFails) {
                        log.warn("[MiningSession] Too many failed clicks on vein at {} — picking a new one", targetVein);
                        markFailed(targetVein, spot);
                        targetVein = null;
                        failedClicks = 0;
                        transitionSub(MiningSubState.SELECTED);
                    } else {
                        scheduleNext(800L);
                    }
                }"""

if old_fail not in text:
    raise SystemExit("click fail block not found")
text = text.replace(old_fail, new_fail)

# selectVein primary
old_primary = """                        && !isRecentlyFailed(o.getWorldLocation()))
                .nearestReachable();

        if (primary != null) {
            WorldPoint p = primary.getWorldLocation();
            // Only accept if it stays within the designated area (prevents edge drift)
            if (anchor == null || p.distanceTo(anchor) <= 12) {
                log.info("[MiningSession] Selected reachable vein at {} (dist={}, pool=reachable)",
                        p, playerLoc.distanceTo(p));
                return p;
            }
        }"""

new_primary = """                        && !isRecentlyFailed(o.getWorldLocation(), spot))
                .nearestReachable();

        if (primary != null && isVeinCandidate(primary, spot, playerLoc)) {
            WorldPoint p = primary.getWorldLocation();
            if (anchor == null || p.distanceTo(anchor) <= 12) {
                log.info("[MiningSession] Selected reachable+LOS vein at {} (dist={}, pool=reachable)",
                        p, playerLoc.distanceTo(p));
                return p;
            }
        }"""

if old_primary not in text:
    raise SystemExit("selectVein primary not found")
text = text.replace(old_primary, new_primary)

# selectVein fallback filter
text = text.replace(
    "            if (isRecentlyFailed(vp)) continue;\n",
    "            if (isRecentlyFailed(vp, spot)) continue;\n"
    "            if (!hasLineOfSightToVein(vein)) continue;\n",
)

# javadoc blacklist
text = text.replace(
    "     * for 10 seconds to prevent the \"mine same tile over and over\" loop.",
    "     * for 10s (lower) or 60s (upper) to prevent retry loops on bad wall veins.",
)

p.write_text(text, encoding="utf-8", newline="\n")
print("OK")