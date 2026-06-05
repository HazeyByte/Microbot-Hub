#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(r"C:\Users\imatt\IdeaProjects\Microbot-Hub")
mining = ROOT / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/session/MiningSession.java"
session = ROOT / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/session/Session.java"
plugin = ROOT / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/MotherloadMinePlugin.java"

m = mining.read_text(encoding="utf-8")
s = session.read_text(encoding="utf-8")

if "lastMineClickMs" not in m:
    m = m.replace(
        "    private long lastWalkCommandMs = 0L;\n",
        "    private long lastWalkCommandMs = 0L;\n"
        "    private long lastMineClickMs = 0L;\n",
    )

m = m.replace(
    "        lastWalkCommandMs = 0L;\n        rememberedRockfalls.clear();",
    "        lastWalkCommandMs = 0L;\n        lastMineClickMs     = 0L;\n        rememberedRockfalls.clear();",
)

m = m.replace(
    "        clearActionGateOnFastMode();\n\n        switch (next) {",
    "        scheduleSubStateEntryDelay();\n\n        switch (next) {",
)

# SELECTED: schedule after picking vein
m = m.replace(
    """                if (targetVein != null) {
                    log.info("[MiningSession] Vein selected at {}", targetVein);
                    transitionSub(MiningSubState.CLICKED);
                } else {""",
    """                if (targetVein != null) {
                    log.info("[MiningSession] Vein selected at {}", targetVein);
                    transitionSub(MiningSubState.CLICKED);
                    scheduleNextAdaptive(350L, 700L);
                } else {""",
)

# WALKING fast-path to CLICKED
m = m.replace(
    """                    targetVein = bestVisible.getWorldLocation();
                    transitionSub(MiningSubState.CLICKED);
                    break;""",
    """                    targetVein = bestVisible.getWorldLocation();
                    transitionSub(MiningSubState.CLICKED);
                    scheduleNextAdaptive(350L, 700L);
                    break;""",
)

# CLICKED: stronger gates at start
old_clicked_start = """            case CLICKED: {
                // Spam-click prevention: if already interacting/animating, wait
                if (Rs2Player.isInteracting() || Rs2Player.isAnimating()) {
                    scheduleNextAdaptive(120L, 400L);
                    break;
                }"""

new_clicked_start = """            case CLICKED: {
                long minClickGap = tickDelayMs(900L, 1400L);
                if (now - lastMineClickMs < minClickGap) {
                    scheduleNext(minClickGap - (now - lastMineClickMs));
                    break;
                }
                // Wait for prior interact/anim to finish before another Mine click
                if (Rs2Player.isInteracting() || Rs2Player.isAnimating(1200)) {
                    scheduleNextAdaptive(350L, 700L);
                    break;
                }"""

if old_clicked_start not in m:
    raise SystemExit("CLICKED start block missing")
m = m.replace(old_clicked_start, new_clicked_start)

m = m.replace(
    """                if (vein.click("Mine")) {
                    applyActionCooldown();
                    failedClicks     = 0;
                    lastXpTimestamp  = now;
                    sessionLockUntil = now + 5000L;""",
    """                if (vein.click("Mine")) {
                    lastMineClickMs  = now;
                    applyActionCooldown();
                    failedClicks     = 0;
                    lastXpTimestamp  = now;
                    sessionLockUntil = now + 5000L;""",
)

# CONFIRMED: interact check, early depleted, always schedule wait
old_confirmed = """            case CONFIRMED:
                // If player started moving again (e.g., click sent from too far),
                // wait - don't count movement toward the animation timeout.
                if (Rs2Player.isMoving()) {
                    scheduleNextAdaptive(180L, 600L);
                    break;
                }
                if (Rs2Player.isAnimating()) {
                    transitionSub(MiningSubState.MINING);
                    break;
                }
                // NEW: after 3 seconds of no animation, check if the vein is depleted or
                // a rockfall has spawned. If so, bail out early instead of waiting 12s.
                if (targetVein != null && subElapsed() > 3_000L) {
                    if (findVeinAt(targetVein) == null) {
                        log.debug("[MiningSession] Target vein depleted during CONFIRMED - reselecting");
                        transitionSub(MiningSubState.SELECTED);
                        break;
                    }
                    if (isPathBlockedByRockfall(Rs2Player.getWorldLocation(), targetVein)) {
                        log.debug("[MiningSession] Path blocked by rockfall during CONFIRMED - reselecting");
                        markFailed(targetVein, spot);
                        transitionSub(MiningSubState.SELECTED);
                        break;
                    }
                }
                if (subElapsed() > 12_000L) {
                    log.debug("[MiningSession] Animation timeout (12s) - re-selecting vein");
                    transitionSub(MiningSubState.SELECTED);
                }
                break;"""

new_confirmed = """            case CONFIRMED:
                if (Rs2Player.isMoving()) {
                    scheduleNextAdaptive(300L, 600L);
                    break;
                }
                if (Rs2Player.isInteracting() || Rs2Player.isAnimating(1200)) {
                    transitionSub(MiningSubState.MINING);
                    scheduleNextAdaptive(400L, 800L);
                    break;
                }
                if (targetVein != null && !isVeinPresent(targetVein)) {
                    log.debug("[MiningSession] Target vein depleted during CONFIRMED - next vein");
                    transitionSub(MiningSubState.DEPLETED);
                    break;
                }
                long xpAfterClick = Math.max(lastXpResolved, globalLastXpTime.get());
                if (xpAfterClick > lastMineClickMs) {
                    transitionSub(MiningSubState.MINING);
                    scheduleNextAdaptive(400L, 800L);
                    break;
                }
                if (targetVein != null && subElapsed() > 3_000L) {
                    if (isPathBlockedByRockfall(Rs2Player.getWorldLocation(), targetVein)) {
                        log.debug("[MiningSession] Path blocked by rockfall during CONFIRMED - reselecting");
                        markFailed(targetVein, spot);
                        transitionSub(MiningSubState.DEPLETED);
                        break;
                    }
                }
                if (subElapsed() > 12_000L) {
                    log.debug("[MiningSession] Animation timeout (12s) - re-selecting vein");
                    markFailed(targetVein, spot);
                    transitionSub(MiningSubState.DEPLETED);
                    break;
                }
                scheduleNextAdaptive(400L, 800L);
                break;"""

if old_confirmed not in m:
    raise SystemExit("CONFIRMED block missing")
m = m.replace(old_confirmed, new_confirmed)

# MINING: interact + XP grace before DEPLETED
old_mining_end = """                boolean isMining = Rs2Player.isAnimating();

                // If player is still animating, don't check vein depletion yet.
                // The animation may outlast the vein object in the cache, and the
                // player will still receive the pay-dirt. Re-check next tick.
                if (isMining) {
                    if (isHumanLikeEnabled() && Rs2Random.between(0, 1000) < 5) {
                        scheduleNext(Rs2Random.between(120, 380));
                    }
                    break;
                }

                // Player has stopped animating - check if the vein is still active
                // or if we got stuck (no pay-dirt received for 10s).
                boolean veinPresent = isVeinPresent(targetVein);

                long lastActivityResolved = Math.max(
                        Math.max(lastXpResolved, globalLastXpTime.get()),
                        subStateEnteredMs);

                // Stuck detection: 10s of no activity while vein is still there
                boolean isStuck = (now - lastActivityResolved > 10_000L)
                        && (subElapsed() > 3_000L);

                if (!veinPresent || isStuck) {
                    if (isStuck && veinPresent) {
                        log.debug("[MiningSession] Stuck on vein (no activity 10s) - reselecting");
                        markFailed(targetVein, spot);
                    }
                    transitionSub(MiningSubState.DEPLETED);
                    break;
                }

                break;"""

new_mining_end = """                if (Rs2Player.isInteracting() || Rs2Player.isAnimating(1200)) {
                    scheduleNextAdaptive(400L, 800L);
                    break;
                }

                boolean veinPresent = isVeinPresent(targetVein);
                long lastActivityResolved = Math.max(
                        Math.max(lastXpResolved, globalLastXpTime.get()),
                        subStateEnteredMs);

                // Pay-dirt XP can arrive slightly after the mining anim ends; don't re-click yet.
                long xpGraceMs = tickDelayMs(1200L, 2000L);
                if (lastActivityResolved <= lastMineClickMs && subElapsed() < xpGraceMs) {
                    scheduleNextAdaptive(400L, 800L);
                    break;
                }

                boolean isStuck = (now - lastActivityResolved > 10_000L)
                        && (subElapsed() > 3_000L);

                if (!veinPresent || isStuck) {
                    if (isStuck && veinPresent) {
                        log.debug("[MiningSession] Stuck on vein (no activity 10s) - reselecting");
                        markFailed(targetVein, spot);
                    } else {
                        log.debug("[MiningSession] Vein depleted or inactive at {} - next vein", targetVein);
                    }
                    transitionSub(MiningSubState.DEPLETED);
                    scheduleNextAdaptive(450L, 900L);
                    break;
                }

                scheduleNextAdaptive(500L, 1000L);
                break;"""

if old_mining_end not in m:
    raise SystemExit("MINING end block missing")
m = m.replace(old_mining_end, new_mining_end)

# DEPLETED
m = m.replace(
    """            case DEPLETED:
                if (targetVein != null) {
                    markFailed(targetVein, spot);
                }
                targetVein = null;
                transitionSub(MiningSubState.SELECTED);
                break;""",
    """            case DEPLETED:
                if (targetVein != null) {
                    markFailed(targetVein, spot);
                }
                targetVein = null;
                scheduleNextAdaptive(500L, 1000L);
                transitionSub(MiningSubState.SELECTED);
                break;""",
)

# Session.java
s = s.replace(
    """    /** In fast mode, allow the next tick to act immediately after a sub-state change. */
    protected void clearActionGateOnFastMode() {
        if (!isHumanLikeEnabled()) {
            nextActionMs = 0L;
        }
    }""",
    """    /** Minimum pause after sub-state changes so fast mode does not mass-click. */
    protected void scheduleSubStateEntryDelay() {
        scheduleNextAdaptive(200L, 400L);
    }

    /** @deprecated use {@link #scheduleSubStateEntryDelay()} */
    protected void clearActionGateOnFastMode() {
        scheduleSubStateEntryDelay();
    }""",
)

mining.write_text(m, encoding="utf-8", newline="\n")
session.write_text(s, encoding="utf-8", newline="\n")
plugin.write_text(plugin.read_text(encoding="utf-8").replace("2.1.45", "2.1.46"), encoding="utf-8", newline="\n")
print("OK 2.1.46")