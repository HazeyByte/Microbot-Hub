#!/usr/bin/env python3
"""One-shot MLM audit fixes."""
from pathlib import Path

ROOT = Path(r"C:\Users\imatt\IdeaProjects\Microbot-Hub")

def patch(path: Path, replacements: list[tuple[str, str]]) -> None:
    text = path.read_text(encoding="utf-8")
    for old, new in replacements:
        if old not in text:
            raise SystemExit(f"MISSING in {path.name}: {old[:80]}...")
        text = text.replace(old, new, 1)
    path.write_text(text, encoding="utf-8", newline="\n")
    print(f"OK {path.name}")

# Plugin version
plugin = ROOT / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/MotherloadMinePlugin.java"
pt = plugin.read_text(encoding="utf-8")
pt = pt.replace('version = "1.9.4"', 'version = "2.1.42"', 2)
plugin.write_text(pt, encoding="utf-8", newline="\n")
print("OK MotherloadMinePlugin.java version")

script = ROOT / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/MotherloadMineScript.java"
patch(script, [
    (
        "            if (isHumanLikeEnabled() && Rs2AntibanSettings.actionCooldownActive) return;\n\n            updateSackSize();",
        "            updateSackSize();\n\n            final boolean antibanDispatchPause = isHumanLikeEnabled()\n                    && Rs2AntibanSettings.actionCooldownActive;",
    ),
    (
        "            determineStatus();\n            logStatusTransitionIfChanged();\n\n            // -------------------------------------------------------------------------\n            // PRIORITY 1 — tick active sessions",
        "            if (!antibanDispatchPause) {\n                determineStatus();\n                logStatusTransitionIfChanged();\n            }\n\n            // -------------------------------------------------------------------------\n            // PRIORITY 1 — tick active sessions",
    ),
    (
        "            if (!repairSession.isIdle()) {\n                repairSession.tick();\n                if (repairSession.isComplete() || repairSession.isFailed()) {\n                    repairSession.reset();\n                    status = determineNextStatusAfterRepair();\n                }\n                return;\n            }\n\n            // -------------------------------------------------------------------------\n            // Animation guard",
        "            if (!repairSession.isIdle()) {\n                repairSession.tick();\n                if (repairSession.isComplete() || repairSession.isFailed()) {\n                    repairSession.reset();\n                    status = determineNextStatusAfterRepair();\n                }\n                return;\n            }\n\n            if (antibanDispatchPause) {\n                return;\n            }\n\n            // -------------------------------------------------------------------------\n            // Animation guard",
    ),
    (
        "        switch (status) {\n            case IDLE:",
        "        switch (status) {\n            case MINING:\n                return;\n            case IDLE:",
    ),
    (
        """        if (varbit == 0 && payDirtJustDeposited > 0 &&
                System.currentTimeMillis() - lastHopperDepositTimestampMs < SACK_PROJECTION_WINDOW_MS) {
            debug("[MLM] Using projected sack count: {}", payDirtJustDeposited);
            return payDirtJustDeposited;
        }

        // Sanity check: if varbit suddenly dropped to 0 but we had a high count
        // recently and haven't emptied the sack, the varbit is likely lagged.
        // Use the last known non-zero count as a minimum.
        // (The known-after-deposit value in getEffective helps here too.)
        if (varbit == 0 && lastHopperDepositTimestampMs > 0
                && System.currentTimeMillis() - lastHopperDepositTimestampMs < SACK_PROJECTION_WINDOW_MS
                && payDirtJustDeposited > 0) {
            debug("[MLM] Varbit 0 but recent deposit detected — using projected: {}", payDirtJustDeposited);
            return payDirtJustDeposited;
        }""",
        """        if (varbit == 0 && payDirtJustDeposited > 0
                && System.currentTimeMillis() - lastHopperDepositTimestampMs < SACK_PROJECTION_WINDOW_MS) {
            int projectedTotal = sackValueKnownAfterLastDeposit > 0
                    ? sackValueKnownAfterLastDeposit
                    : lastPreDepositSackCount + payDirtJustDeposited;
            debug("[MLM] Varbit 0 after deposit — using projected sack total {} (batch={}, pre={})",
                    projectedTotal, payDirtJustDeposited, lastPreDepositSackCount);
            return projectedTotal;
        }""",
    ),
])

hopper = ROOT / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/session/HopperSession.java"
patch(hopper, [
    (
        """                if (ensureFloor(desiredFloorIsUpper())) {
                    log.info("[HopperSession] Now on correct hopper floor (upper={}) after ladder", desiredFloorIsUpper());
                    transitionSub(HopperSubState.IDLE);
                }
                break;""",
        """                invalidateUpperFloorCache();
                if (ensureFloor(desiredFloorIsUpper())) {
                    invalidateUpperFloorCache();
                    log.info("[HopperSession] Now on correct hopper floor (upper={}) after ladder", desiredFloorIsUpper());
                    transitionSub(HopperSubState.IDLE);
                }
                if (isStalled(20_000L)) {
                    log.warn("[HopperSession] Floor transition stalled — failing");
                    transitionSub(HopperSubState.FAILED);
                    transition(State.FAILED);
                }
                break;""",
    ),
    (
        """                    if (currentCount >= initialPayDirtCount || depositRetryCount >= 1) {
                        log.warn("[HopperSession] Verify stalled with no progress ({}→{}) or after retry — failing session (sack likely full, avoid click spam)",
                                initialPayDirtCount, currentCount);
                        transitionSub(HopperSubState.FAILED);
                        transition(State.FAILED);
                        break;
                    }
                    log.warn("[HopperSession] Verify stalled — retrying deposit (retry={})", depositRetryCount);
                    initialPayDirtCount = currentCount; // Reset baseline for next attempt
                    transitionSub(HopperSubState.DEPOSITING);""",
        """                    if (currentCount >= initialPayDirtCount || incrementAndCheckRetryLimit()) {
                        log.warn("[HopperSession] Verify stalled with no progress ({}→{}) or retries exhausted — failing session (sack likely full, avoid click spam)",
                                initialPayDirtCount, currentCount);
                        transitionSub(HopperSubState.FAILED);
                        transition(State.FAILED);
                        break;
                    }
                    log.warn("[HopperSession] Verify stalled — retrying deposit (retry={})", depositRetryCount);
                    initialPayDirtCount = currentCount; // Reset baseline for next attempt
                    transitionSub(HopperSubState.DEPOSITING);""",
    ),
])

mining = ROOT / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/session/MiningSession.java"
patch(mining, [
    (
        """    private void updateRememberedRockfalls() {
        List<Rs2TileObjectModel> current = tileCache.query()
                .where(o -> isRockfall(o.getId()))
                .toList();

        for (Rs2TileObjectModel rock : current) {
            WorldPoint base = rock.getWorldLocation();
            // Rockfalls are 2×2 objects in MLM. The cache stores the SW origin,
            // so we mark all 4 occupied tiles as impassable.
            for (int dx = 0; dx < 2; dx++) {
                for (int dy = 0; dy < 2; dy++) {
                    rememberedRockfalls.add(new WorldPoint(
                            base.getX() + dx,
                            base.getY() + dy,
                            base.getPlane()));
                }
            }
        }
    }""",
        """    private void updateRememberedRockfalls() {
        List<Rs2TileObjectModel> current = tileCache.query()
                .where(o -> isRockfall(o.getId()))
                .toList();

        Set<WorldPoint> activeTiles = new HashSet<>();
        for (Rs2TileObjectModel rock : current) {
            WorldPoint base = rock.getWorldLocation();
            // Rockfalls are 2x2 objects in MLM. The cache stores the SW origin,
            // so we mark all 4 occupied tiles as impassable.
            for (int dx = 0; dx < 2; dx++) {
                for (int dy = 0; dy < 2; dy++) {
                    activeTiles.add(new WorldPoint(
                            base.getX() + dx,
                            base.getY() + dy,
                            base.getPlane()));
                }
            }
        }
        rememberedRockfalls.retainAll(activeTiles);
        rememberedRockfalls.addAll(activeTiles);
    }""",
    ),
    (
        "            if (anchor == null || p.distanceTo(anchor) <= 12) {",
        "            if (spot.isUpstairs() || anchor == null || p.distanceTo(anchor) <= 12) {",
    ),
    (
        "            if (anchor != null && vp.distanceTo(anchor) > 12) continue;",
        "            if (!spot.isUpstairs() && anchor != null && vp.distanceTo(anchor) > 12) continue;",
    ),
])

session = ROOT / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/session/Session.java"
st = session.read_text(encoding="utf-8")
if "import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;" in st:
    st = st.replace("import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;\n", "")
    session.write_text(st, encoding="utf-8", newline="\n")
    print("OK Session.java import")

print("All patches applied.")