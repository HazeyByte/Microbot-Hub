from pathlib import Path

p = Path(r"C:\Users\imatt\IdeaProjects\Microbot-Hub\src\main\java\net\runelite\client\plugins\microbot\motherloadmine\session\MiningSession.java")
t = p.read_text(encoding="utf-8")

is_near_old = """    /** Returns {@code true} if the player is inside the mining spot area or near its anchors. */
    private boolean isNearSpot(MLMMiningSpot spot) {
        if (spot == null) return false;
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null) return false;
        if (spot.containsInArea(here)) {
            return true;
        }
        int anchorRadius = (spot.isUpstairs() && spot.getMesh() != null && !spot.getMesh().isEmpty()) ? 4 : 10;
        return isNearAnchor(spot, here, anchorRadius);
    }"""

is_near_new = """    /** Returns {@code true} if the player is inside the mining spot area or near its anchors (correct floor). */
    private boolean isNearSpot(MLMMiningSpot spot) {
        if (spot == null) return false;
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null) return false;
        if (isPlayerInSpotArea(spot, here)) {
            return true;
        }
        if (spot.isUpstairs() && !isUpperFloor()) {
            return false;
        }
        if (spot.isDownstairs() && isUpperFloor()) {
            return false;
        }
        int anchorRadius = (spot.isUpstairs() && spot.getMesh() != null && !spot.getMesh().isEmpty()) ? 4 : 10;
        return isNearAnchor(spot, here, anchorRadius);
    }

    /** Area check plus MLM floor height (upper/lower share x,y on plane 0). */
    private boolean isPlayerInSpotArea(MLMMiningSpot spot, WorldPoint here) {
        if (spot == null || here == null || !spot.containsInArea(here)) {
            return false;
        }
        if (spot.isUpstairs()) {
            return isUpperFloor();
        }
        if (spot.isDownstairs()) {
            return !isUpperFloor();
        }
        return true;
    }"""

is_wrong_old = """    /**
     * Returns true if the player must use the ladder before mining this spot.
     * Downstairs spots never trigger a climb-up while in lower tunnels or at the spot.
     */
    private boolean isWrongFloor(MLMMiningSpot spot) {
        if (spot == null) return false;
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null) return false;

        if (spot.containsInArea(here) || isNearAnchor(spot, here, 12)) {
            return false;
        }

        if (spot.isDownstairs()) {
            if (MLMMiningSpot.isLowerMineTunnel(here)) {
                return false;
            }
            return isUpperFloor();
        }

        return !isUpperFloor();
    }
}"""

is_wrong_new = """    /**
     * Returns true if the player must use the ladder before mining this spot.
     * Proximity to an upper-spot anchor on the lower floor does not count (same x,y, different height).
     */
    private boolean isWrongFloor(MLMMiningSpot spot) {
        if (spot == null) return false;
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null) return false;

        boolean onUpper = isUpperFloor();

        if (spot.isUpstairs()) {
            if (!onUpper) {
                log.info("[MiningSession] Spot {} is upstairs but player is on lower floor — climbing up first", spot);
                return true;
            }
            if (isPlayerInSpotArea(spot, here)) {
                return false;
            }
            int anchorRadius = (spot.getMesh() != null && !spot.getMesh().isEmpty()) ? 4 : 10;
            return !isNearAnchor(spot, here, anchorRadius);
        }

        if (spot.isDownstairs()) {
            if (onUpper) {
                log.info("[MiningSession] Spot {} is downstairs but player is on upper floor — climbing down first", spot);
                return true;
            }
            if (spot.containsInArea(here) || MLMMiningSpot.isLowerMineTunnel(here)) {
                return false;
            }
            return false;
        }

        return !onUpper;
    }
}"""

if is_near_old not in t:
    raise SystemExit("isNearSpot block not found")
if is_wrong_old not in t:
    raise SystemExit("isWrongFloor block not found")

t = t.replace(is_near_old, is_near_new, 1)
t = t.replace(is_wrong_old, is_wrong_new, 1)

# Invalidate floor cache when mining session starts
if "invalidateUpperFloorCache" not in t.split("public void begin()")[1][:800]:
    t = t.replace(
        "    public void begin() {\n        if (!isIdle()) {",
        "    public void begin() {\n        invalidateUpperFloorCache();\n        if (!isIdle()) {",
        1,
    )

p.write_text(t, encoding="utf-8")

pl = Path(r"C:\Users\imatt\IdeaProjects\Microbot-Hub\src\main\java\net\runelite\client\plugins\microbot\motherloadmine\MotherloadMinePlugin.java")
pl.write_text(pl.read_text(encoding="utf-8").replace("2.1.38", "2.1.39"), encoding="utf-8")
print("OK")