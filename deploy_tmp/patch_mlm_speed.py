from pathlib import Path

root = Path(r"C:\Users\imatt\IdeaProjects\Microbot-Hub")

# --- Session.java ---
session_path = root / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/session/Session.java"
s = session_path.read_text(encoding="utf-8")

if "scheduleNextAdaptive" not in s:
    s = s.replace(
        """    protected boolean isHumanLikeEnabled() {
        return config != null && config.enableHumanLikeBehavior();
    }

    // ------------------------------------------------------------------
    // Floor helpers""",
        """    protected boolean isHumanLikeEnabled() {
        return config != null && config.enableHumanLikeBehavior();
    }

    /** Milliseconds to wait before the next action (fast vs human-like). */
    protected long tickDelayMs(long fastMs, long humanMs) {
        return isHumanLikeEnabled() ? humanMs : fastMs;
    }

    protected void scheduleNextAdaptive(long fastMs, long humanMs) {
        scheduleNext(tickDelayMs(fastMs, humanMs));
    }

    /** In fast mode, allow the next tick to act immediately after a sub-state change. */
    protected void clearActionGateOnFastMode() {
        if (!isHumanLikeEnabled()) {
            nextActionMs = 0L;
        }
    }

    // ------------------------------------------------------------------
    // Floor helpers""",
    )

    s = s.replace(
        """    protected void applyActionCooldown() {
        try {
            Rs2Antiban.actionCooldown();
        } catch (IllegalArgumentException e) {""",
        """    protected void applyActionCooldown() {
        if (!isHumanLikeEnabled()) {
            return;
        }
        try {
            Rs2Antiban.actionCooldown();
        } catch (IllegalArgumentException e) {""",
    )

session_path.write_text(s, encoding="utf-8")
print("Session.java OK")

# --- MiningSession.java ---
mining_path = root / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/session/MiningSession.java"
m = mining_path.read_text(encoding="utf-8")

replacements = [
    ("scheduleNext(600L)", "scheduleNextAdaptive(180L, 600L)"),
    ("scheduleNext(1200L)", "scheduleNextAdaptive(350L, 1200L)"),
    ("scheduleNext(2000L)", "scheduleNextAdaptive(500L, 2000L)"),
    ("scheduleNext(400L)", "scheduleNextAdaptive(120L, 400L)"),
    ("scheduleNext(800L)", "scheduleNextAdaptive(250L, 800L)"),
]
for old, new in replacements:
    m = m.replace(old, new)

m = m.replace(
    "                if (!Rs2Player.isMoving() && (now - lastWalkCommandMs >= 1200L)) {",
    "                long walkCmdInterval = tickDelayMs(400L, 1200L);\n"
    "                if (!Rs2Player.isMoving() && (now - lastWalkCommandMs >= walkCmdInterval)) {",
)

if "clearActionGateOnFastMode" not in m:
    m = m.replace(
        "        subState          = next;\n        subStateEnteredMs = System.currentTimeMillis();\n\n        switch (next) {",
        "        subState          = next;\n        subStateEnteredMs = System.currentTimeMillis();\n"
        "        clearActionGateOnFastMode();\n\n        switch (next) {",
    )

# Fast vein selection: first LOS candidate wins
old_loop = """        List<Rs2TileObjectModel> candidates = new ArrayList<>();
        for (Rs2TileObjectModel vein : allVeins) {
            WorldPoint vp = vein.getWorldLocation();
            if (isPathBlockedByRockfall(playerLoc, vp)) continue;
            if (isRecentlyFailed(vp, spot)) continue;
            if (!hasLineOfSightToVein(vein)) continue;
            if (anchor != null && vp.distanceTo(anchor) > 12) continue;
            candidates.add(vein);
        }"""

new_loop = """        List<Rs2TileObjectModel> candidates = new ArrayList<>();
        for (Rs2TileObjectModel vein : allVeins) {
            WorldPoint vp = vein.getWorldLocation();
            if (isPathBlockedByRockfall(playerLoc, vp)) continue;
            if (isRecentlyFailed(vp, spot)) continue;
            if (anchor != null && vp.distanceTo(anchor) > 12) continue;
            if (!hasLineOfSightToVein(vein)) continue;
            candidates.add(vein);
            if (!isHumanLikeEnabled() && candidates.size() >= 4) {
                break;
            }
        }"""

if old_loop in m:
    m = m.replace(old_loop, new_loop)

mining_path.write_text(m, encoding="utf-8")
print("MiningSession.java OK")

# --- HopperSession.java ---
hopper_path = root / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/session/HopperSession.java"
h = hopper_path.read_text(encoding="utf-8")

h = h.replace("scheduleNext(600L)", "scheduleNextAdaptive(180L, 600L)")
h = h.replace("scheduleNext(600);", "scheduleNextAdaptive(180L, 600L);")
h = h.replace("scheduleNext(WALK_THROTTLE_MS);", "scheduleNextAdaptive(250L, WALK_THROTTLE_MS);")
h = h.replace("scheduleNext(CLICK_THROTTLE_MS);", "scheduleNextAdaptive(280L, CLICK_THROTTLE_MS);")

if "mayAct(WALK_THROTTLE_MS)" in h:
    h = h.replace(
        "                if (mayAct(WALK_THROTTLE_MS)) {",
        "                if (mayAct(tickDelayMs(250L, WALK_THROTTLE_MS))) {",
    )

# Remove unused shouldGoDown
if "shouldGoDown" in h:
    h = h.replace(
        "\n    private boolean shouldGoDown() {\n"
        "        // Legacy name kept for any external, but now delegates to general check.\n"
        "        return needsFloorTransition() && !desiredFloorIsUpper();\n"
        "    }\n",
        "\n",
    )

hopper_path.write_text(h, encoding="utf-8")
print("HopperSession.java OK")

# --- SackSession.java ---
sack_path = root / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/session/SackSession.java"
sk = sack_path.read_text(encoding="utf-8")
sk = sk.replace("scheduleNext(600L)", "scheduleNextAdaptive(180L, 600L)")
sk = sk.replace("scheduleNext(1200L)", "scheduleNextAdaptive(350L, 1200L)")
sk = sk.replace("scheduleNext(800L)", "scheduleNextAdaptive(250L, 800L)")
sk = sk.replace("scheduleNext(CLICK_THROTTLE_MS);", "scheduleNextAdaptive(350L, CLICK_THROTTLE_MS);")

old_sack_pause = """                    // Small random pause before clicking — human reaction time
                    scheduleNext(java.util.concurrent.ThreadLocalRandom.current().nextInt(400, 900));"""
new_sack_pause = """                    if (isHumanLikeEnabled()) {
                        scheduleNext(java.util.concurrent.ThreadLocalRandom.current().nextInt(400, 900));
                    } else {
                        scheduleNextAdaptive(80L, 400L);
                    }"""
if old_sack_pause in sk:
    sk = sk.replace(old_sack_pause, new_sack_pause)

sack_path.write_text(sk, encoding="utf-8")
print("SackSession.java OK")

# --- RepairSession.java ---
repair_path = root / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/session/RepairSession.java"
r = repair_path.read_text(encoding="utf-8")
r = r.replace("scheduleNext(600L)", "scheduleNextAdaptive(180L, 600L)")
r = r.replace("scheduleNext(600);", "scheduleNextAdaptive(180L, 600L);")
r = r.replace("scheduleNext(1800L)", "scheduleNextAdaptive(400L, 1800L)")
r = r.replace("scheduleNext(SETTLE_DELAY_MS)", "scheduleNextAdaptive(200L, SETTLE_DELAY_MS)")
r = r.replace("if (mayAct(WALK_THROTTLE_MS))", "if (mayAct(tickDelayMs(400L, WALK_THROTTLE_MS)))")
repair_path.write_text(r, encoding="utf-8")
print("RepairSession.java OK")

# --- MotherloadMineScript.java ---
script_path = root / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/MotherloadMineScript.java"
sc = script_path.read_text(encoding="utf-8")

sc = sc.replace(
    """        log.info("[MLM] Main executor started — ticking every 600ms");
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(
                this::executeTask, 0, 600, TimeUnit.MILLISECONDS);""",
    """        long tickIntervalMs = isHumanLikeEnabled() ? 600L : 350L;
        log.info("[MLM] Main executor started — ticking every {}ms (humanLike={})",
                tickIntervalMs, isHumanLikeEnabled());
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(
                this::executeTask, 0, tickIntervalMs, TimeUnit.MILLISECONDS);""",
)

old_antiban = """    private void configureAntibanSettings() {
        Rs2Antiban.resetAntibanSettings();
        Rs2Antiban.antibanSetupTemplates.applyMiningSetup();

        Rs2AntibanSettings.usePlayStyle = true;

        Rs2Antiban.setActivity(Activity.GENERAL_MINING);
        Rs2Antiban.setActivityIntensity(ActivityIntensity.MODERATE);

        Rs2AntibanSettings.dynamicIntensity = true;
        Rs2AntibanSettings.dynamicActivity = true;

        Rs2AntibanSettings.naturalMouse = true;
        Rs2AntibanSettings.simulateMistakes = true;
        Rs2AntibanSettings.simulateFatigue = true;

        Rs2AntibanSettings.moveMouseOffScreen = true;
        Rs2AntibanSettings.moveMouseOffScreenChance = 0.25;

        Rs2AntibanSettings.moveMouseRandomly = true;
        Rs2AntibanSettings.moveMouseRandomlyChance = 0.15;

        Rs2AntibanSettings.actionCooldownChance = 0.15;

        Rs2AntibanSettings.behavioralVariability = true;

        Rs2AntibanSettings.nonLinearIntervals = true;

        Rs2AntibanSettings.takeMicroBreaks = false;

        Rs2AntibanSettings.contextualVariability = true;

        log.info("MLM Antiban configured — usePlayStyle=true, dynamicIntensity=true, dynamicActivity=true, takeMicroBreaks=false (disabled to prevent stall when Break Handler not enabled)");
    }"""

new_antiban = """    private void configureAntibanSettings() {
        Rs2Antiban.resetAntibanSettings();
        Rs2Antiban.antibanSetupTemplates.applyMiningSetup();

        boolean human = isHumanLikeEnabled();
        Rs2AntibanSettings.usePlayStyle = human;

        Rs2Antiban.setActivity(Activity.GENERAL_MINING);
        Rs2Antiban.setActivityIntensity(human ? ActivityIntensity.MODERATE : ActivityIntensity.HIGH);

        Rs2AntibanSettings.dynamicIntensity = human;
        Rs2AntibanSettings.dynamicActivity = human;

        Rs2AntibanSettings.naturalMouse = true;
        Rs2AntibanSettings.simulateMistakes = human;
        Rs2AntibanSettings.simulateFatigue = human;

        Rs2AntibanSettings.moveMouseOffScreen = human;
        Rs2AntibanSettings.moveMouseOffScreenChance = human ? 0.25 : 0.0;

        Rs2AntibanSettings.moveMouseRandomly = human;
        Rs2AntibanSettings.moveMouseRandomlyChance = human ? 0.15 : 0.0;

        Rs2AntibanSettings.actionCooldownChance = human ? 0.15 : 0.0;

        Rs2AntibanSettings.behavioralVariability = human;
        Rs2AntibanSettings.nonLinearIntervals = human;
        Rs2AntibanSettings.takeMicroBreaks = false;
        Rs2AntibanSettings.contextualVariability = human;

        log.info("MLM Antiban configured — humanLike={}, actionCooldownChance={}",
                human, Rs2AntibanSettings.actionCooldownChance);
    }"""

if old_antiban in sc:
    sc = sc.replace(old_antiban, new_antiban)

sc = sc.replace(
    "            if (Rs2AntibanSettings.actionCooldownActive) return;",
    "            if (isHumanLikeEnabled() && Rs2AntibanSettings.actionCooldownActive) return;",
)

sc = sc.replace(
    """    private void humanPause(int minMs, int maxMs, boolean urgent) {
        int delay = getHumanizedDelay(minMs, maxMs, urgent);
        debug("[MLM] humanPause: {}ms (min={}, max={}, urgent={}) [humanLike={}]", delay, minMs, maxMs, urgent, isHumanLikeEnabled());
        try {
            Rs2Antiban.actionCooldown();
        } catch (Exception ignored) {}
        sleep(delay);
    }

    private int getHumanizedDelay(int minMs, int maxMs, boolean urgent) {
        if (!isHumanLikeEnabled()) {
            return Rs2Random.between(35, 110);
        }""",
    """    private void humanPause(int minMs, int maxMs, boolean urgent) {
        if (!isHumanLikeEnabled()) {
            return;
        }
        int delay = getHumanizedDelay(minMs, maxMs, urgent);
        debug("[MLM] humanPause: {}ms (min={}, max={}, urgent={})", delay, minMs, maxMs, urgent);
        try {
            Rs2Antiban.actionCooldown();
        } catch (IllegalArgumentException e) {
            debug("[MLM] humanPause antiban cooldown skipped: {}", e.getMessage());
        }
        sleep(delay);
    }

    private int getHumanizedDelay(int minMs, int maxMs, boolean urgent) {
        if (!isHumanLikeEnabled()) {
            return 0;
        }""",
)

sc = sc.replace(
    """    private long ladderInteractionCooldownMs() {
        if (isHumanLikeEnabled()) {
            return Rs2Random.between(1_500, 2_600);
        }
        return Rs2Random.between(800, 1_300);
    }""",
    """    private long ladderInteractionCooldownMs() {
        if (isHumanLikeEnabled()) {
            return Rs2Random.between(1_500, 2_600);
        }
        return 350L;
    }""",
)

# Spec sleep when fast
sc = sc.replace(
    """            if (isHumanLikeEnabled()) {
                sleep(Rs2Random.between(800, 1200));
            } else {
                sleep(Rs2Random.between(350, 650));
            }""",
    """            if (isHumanLikeEnabled()) {
                sleep(Rs2Random.between(800, 1200));
            } else {
                sleep(Rs2Random.between(120, 220));
            }""",
)

script_path.write_text(sc, encoding="utf-8")
print("MotherloadMineScript.java OK")

# --- MotherloadMineConfig.java ---
cfg_path = root / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/MotherloadMineConfig.java"
cfg = cfg_path.read_text(encoding="utf-8")
cfg = cfg.replace(
    "When disabled: small consistent non-robotic delays (e.g. 35-110ms) + always-optimal/attentive micro-choices",
    "When disabled: no script pauses, faster tick rate, minimal session throttles, no antiban action-cooldown blocking + optimal choices",
)
cfg_path.write_text(cfg, encoding="utf-8")
print("Config OK")

# --- MLMMiningSpot.java polish ---
spot_path = root / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/enums/MLMMiningSpot.java"
spot = spot_path.read_text(encoding="utf-8")
if "Arrays.asList" in spot:
    spot = spot.replace("import java.util.Arrays;\n", "")
    spot = spot.replace("Arrays.asList(", "List.of(")
    # mesh() still uses Arrays.asList internally
    if "Arrays.asList" not in spot:
        spot = spot.replace(
            "import java.util.Collections;\nimport java.util.HashSet;\nimport java.util.List;\nimport java.util.Set;",
            "import java.util.Arrays;\nimport java.util.Collections;\nimport java.util.HashSet;\nimport java.util.List;\nimport java.util.Set;",
        )
spot_path.write_text(spot, encoding="utf-8")
print("MLMMiningSpot.java OK")

# Version
plugin_path = root / "src/main/java/net/runelite/client/plugins/microbot/motherloadmine/MotherloadMinePlugin.java"
pl = plugin_path.read_text(encoding="utf-8")
pl = pl.replace('version = "2.1.35"', 'version = "2.1.36"')
pl = pl.replace('public static final String version = "2.1.35";', 'public static final String version = "2.1.36";')
plugin_path.write_text(pl, encoding="utf-8")
print("Plugin version OK")