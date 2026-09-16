package net.runelite.client.plugins.microbot.varrockanvil;

import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.antiban.enums.Activity;
import net.runelite.client.plugins.microbot.varrockanvil.enums.AnvilItem;
import net.runelite.client.plugins.microbot.varrockanvil.enums.Bars;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;

import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

public class VarrockAnvilScript extends Script {

    enum State {
        DETERMINE,
        BANKING,
        SMITHING,
        RECOVERY
    }

    private static final int ANVIL_OBJECT_ID = 2097;
    private static final int ANVIL_WIDGET_GROUP = 312;
    private static final int ANVIL_WIDGET_ALL_CHILD = 7;

    private static final int WIDGET_OPEN_TIMEOUT = 8000;
    private static final int BANK_TIMEOUT = 3500;
    private static final int XP_DROP_TIMEOUT_MS = 7000;

    private static final int MAKE_X_QUANTITY_VARP = 2224;

    // Without a cap, an impossible task loops DETERMINE->SMITHING->RECOVERY once a second forever.
    private static final int MAX_CONSECUTIVE_RECOVERIES = 5;

    // An inventory of nails is ~27 makes, so SMITHING legitimately runs over a minute.
    private static final long STATE_WATCHDOG_MS = 240_000L;

    // Both ends on screen means Rs2TileObjectModel.click() never calls turnTo().
    // Legacy yaw: North 0/2048, East 1536, South 1024, West 512.
    private static final int YAW_EAST = 1536;
    private static final int YAW_WEST = 512;
    private static final int MIN_ZOOM = 110;          // furthest the client usefully pulls back
    private static final int MAX_PITCH = 383;         // straight down
    private static final int MAX_FRAMING_STEPS = 4;

    // GameState is already LOGGED_IN behind this screen, so isLoggedIn() lies and clicks hit it.
    private static final int WELCOME_SCREEN_GROUP = 378;
    private static final int WELCOME_SCREEN_PLAY_BUTTON = 78;

    // A repaired Imcando hammer smiths equipped or not, in either variant. The broken one (25633)
    // is deliberately absent — it does not work until repaired.
    public static final int[] HAMMER_IDS = {
            ItemID.HAMMER,
            ItemID.IMCANDO_HAMMER,
            ItemID.IMCANDO_HAMMER_OFFHAND
    };

    private static final WorldPoint ANVIL_LOCATION = new WorldPoint(3187, 3426, 0);
    private static final WorldPoint BANK_LOCATION = new WorldPoint(3185, 3436, 0);
    private static final int WALK_FAST_CANVAS_DISTANCE = 15;
    private static final int ANVIL_INTERACT_DISTANCE = 12;
    private static final int BANK_INTERACT_DISTANCE = 12;

    private static class MatContext {
        final String name;
        final boolean useName;
        final int id;

        MatContext(String name, boolean useName, int id) {
            this.name = name;
            this.useName = useName;
            this.id = id;
        }
    }

    private static class ScriptContext {
        long lastMakeStarted;
        boolean selectionComplete;
        String lastSelectedItem;
        long smithingStartTime;
        int maxFullItems;
        long makeSessionTimeoutMs;

        void resetSession() {
            // Only reset make-session timing — preserve selection state for spacebar repeat
            lastMakeStarted = 0;
            smithingStartTime = 0;
            maxFullItems = 0;
            makeSessionTimeoutMs = 0;
        }

        void resetAll() {
            resetSession();
            selectionComplete = false;
            lastSelectedItem = "";
        }

        void startSession(int maxItems) {
            maxFullItems = maxItems;
            makeSessionTimeoutMs = (maxItems * 4000L) + 15000L;
            lastMakeStarted = System.currentTimeMillis();
            smithingStartTime = lastMakeStarted;
        }
    }

    @Getter
    private State state = State.DETERMINE;
    public volatile String debug = "";
    private volatile boolean logout = true;

    private volatile long currentTaskId = 0;

    private VarrockAnvilConfig config;
    private final ScriptContext ctx = new ScriptContext();

    private int consecutiveRecoveries = 0;
    private String lastLogged = "";
    private boolean wasLoggedIn = false;
    private boolean preflightDone = false;
    private long stateEnteredMs = 0;
    private boolean cameraSettled = false;
    private int xpReadAttempts = 0;
    private final AtomicBoolean executing = new AtomicBoolean(false);

    private long startTime = 0;
    private int startXp = -1;   // -1 == not captured yet
    private final AtomicInteger itemsMade = new AtomicInteger(0);
    @Getter
    private volatile boolean running = false;

    public boolean run(VarrockAnvilConfig config) {
        if (mainScheduledFuture != null) {
            mainScheduledFuture.cancel(true);
            mainScheduledFuture = null;
        }

        currentTaskId = System.currentTimeMillis();
        long myTaskId = currentTaskId;

        this.config = config;
        logout = this.config.sLogout();
        state = State.DETERMINE;
        debug = "";
        lastLogged = "";
        wasLoggedIn = false;
        preflightDone = false;
        cameraSettled = false;
        xpReadAttempts = 0;
        consecutiveRecoveries = 0;
        ctx.resetAll();

        String rejection = rejectionReason(config.sBarType(), config.sAnvilItem());
        if (rejection != null) {
            Microbot.log("VarrockAnvil cannot start: " + rejection);
            Microbot.showMessage(rejection);
            return false;
        }

        itemsMade.set(0);
        running = true;

        Microbot.enableAutoRunOn = false;
        Rs2Antiban.resetAntibanSettings();

        Rs2AntibanSettings.antibanEnabled = true;
        Rs2AntibanSettings.usePlayStyle = true;
        Rs2AntibanSettings.randomIntervals = false;
        Rs2AntibanSettings.simulateFatigue = true;
        Rs2AntibanSettings.simulateAttentionSpan = true;
        Rs2AntibanSettings.behavioralVariability = true;
        Rs2AntibanSettings.nonLinearIntervals = true;
        Rs2AntibanSettings.profileSwitching = true;
        Rs2AntibanSettings.timeOfDayAdjust = false;
        Rs2AntibanSettings.simulateMistakes = true;
        Rs2AntibanSettings.naturalMouse = true;
        Rs2AntibanSettings.contextualVariability = true;
        Rs2AntibanSettings.dynamicIntensity = true;
        Rs2AntibanSettings.dynamicActivity = true;
        Rs2AntibanSettings.devDebug = false;
        // Minutes-long pauses read as an idle client and fight the watchdog.
        Rs2AntibanSettings.takeMicroBreaks = false;
        Rs2AntibanSettings.playSchedule = true;
        Rs2AntibanSettings.universalAntiban = false;
        Rs2AntibanSettings.microBreakDurationLow = 3;
        Rs2AntibanSettings.microBreakDurationHigh = 8;
        Rs2AntibanSettings.actionCooldownChance = 0.01;
        Rs2AntibanSettings.microBreakChance = 0.0;
        Rs2Antiban.setActivity(Activity.GENERAL_SMITHING);

        info("Started: " + config.sAnvilItem().getName() + " from " + config.sBarType());

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            if (myTaskId != currentTaskId) return;

            // A tick can block for seconds; never let two drive the interface at once.
            if (!executing.compareAndSet(false, true)) return;
            try {
                doTick();
            } finally {
                executing.set(false);
            }
        }, 0, 1000, TimeUnit.MILLISECONDS);
        return true;
    }

    private void doTick() {
        if (!Microbot.isLoggedIn() || Microbot.getClient() == null || Microbot.getClient().getLocalPlayer() == null) {
            wasLoggedIn = false;
            return;
        }

        // Coming back from a disconnect, logout or world hop: everything we remembered about the
        // in-flight make session is now a guess. Throw it away and re-derive from what the world
        // actually shows, rather than resuming a session that may have ended minutes ago.
        if (!wasLoggedIn) {
            wasLoggedIn = true;
            if (ctx.lastMakeStarted > 0 || state != State.DETERMINE) {
                info("Back online — discarding remembered progress and re-checking from scratch");
            }
            ctx.resetAll();
            consecutiveRecoveries = 0;
            preflightDone = false;
            state = State.DETERMINE;
        }

        // Must come before anything else: nothing can be clicked until this screen is gone.
        if (dismissWelcomeScreen()) return;

        if (startXp < 0) {
            try {
                // One read per tick; busy-looping here stalled the first tick for ~1s.
                Integer xp = Microbot.getClientThread().invoke(() -> {
                    Client c = Microbot.getClient();
                    if (c == null) return null;
                    return c.getSkillExperience(Skill.SMITHING);
                });

                // Accept a genuine 0 too, once a few ticks have passed — a fresh account really can
                // have no Smithing XP, and refusing it would leave the overlay stuck at zero runtime.
                if (xp != null && (xp > 0 || ++xpReadAttempts >= 5)) {
                    startXp = xp;
                    startTime = System.currentTimeMillis();
                    debug("Captured starting XP: " + startXp);
                }
            } catch (Exception e) {
                Microbot.log("VarrockAnvil: failed to capture start XP: " + e.getMessage());
            }
        }

        if (!cameraSettled) {
            settleCamera();
        }

        // Only skip if cooldown is active — do NOT call actionCooldown() unconditionally
        // every tick as that re-arms the cooldown and blocks all subsequent ticks forever.
        if (Rs2AntibanSettings.actionCooldownActive) {
            debug("Antiban: cooldown active");
            Rs2Antiban.actionCooldown();
            return;
        }

        // Establish a known-good starting state once: no stale dialogue, level is sufficient,
        // and any carried Imcando hammer is worn rather than occupying a slot.
        if (!preflightDone && !runPreflight()) {
            return;
        }

        // Nothing has changed state for a long time and we aren't deliberately paused.
        if (state != State.RECOVERY && stateEnteredMs > 0 && !Rs2AntibanSettings.microBreakActive
                && System.currentTimeMillis() - stateEnteredMs > STATE_WATCHDOG_MS) {
            info("Stuck in " + state + " for " + (STATE_WATCHDOG_MS / 60_000) + " minutes — recovering");
            ctx.resetSession();
            setState(State.RECOVERY);
            return;
        }

        Bars barType = this.config.sBarType();
        AnvilItem anvilItem = this.config.sAnvilItem();

        if (Rs2Dialogue.hasContinue()) {
            Rs2Dialogue.clickContinue();
            return;
        }

        try {
            setState(tick(state, barType, anvilItem));
        } catch (Exception e) {
            Microbot.log("VarrockAnvil tick crashed in " + state + ": " + e);
            ctx.resetSession();
            setState(State.RECOVERY);
        }

        // Only trigger antiban when idle — not during active smithing or banking
        if (ctx.lastMakeStarted == 0 && !Rs2Player.isMoving() && !Rs2Player.isAnimating()) {
            Rs2Antiban.takeMicroBreakByChance();
        }
    }

    /**
     * Frame bank and anvil together, once. Every object click calls turnTo() when its target is off
     * screen, so a zoomed-in camera snapped round on every leg of the walk; framed wide it never
     * fires. Must stay off the client thread — smoothTo() degrades to an instant jump there.
     */
    private void settleCamera() {
        // Only frame once we are actually at the work area: the tiles must be in the loaded scene to
        // turn to them, and a player sets their view up on arrival, not from across the map.
        if (!isNearBank() && !isNearAnvil()) return;

        // Resolve both tiles ONCE. Every LocalPoint lookup and visibility test is a blocking hop onto
        // the client thread, and doing them inside the framing loop is what made startup stutter.
        final LocalPoint bankTile = toLocal(BANK_LOCATION);
        final LocalPoint anvilTile = toLocal(ANVIL_LOCATION);
        if (bankTile == null || anvilTile == null) return; // scene not ready — retry next tick

        cameraSettled = true;
        try {
            // 1. Turn. Not turnTo(): it forces setCameraSpeed(3f) and snaps. Bank and anvil sit on a
            //    north-south line, so facing east or west lays it across the screen's wider axis.
            int baseYaw = Rs2Random.between(0, 1) == 0 ? YAW_EAST : YAW_WEST;
            humanYaw(((baseYaw + Rs2Random.between(-90, 90)) % 2048 + 2048) % 2048);
            sleepGaussian(360, 120);

            // 2. Zoom out. Lower value = further out; the old code asked for 284-400, which zooms IN.
            int zoom = Rs2Random.between(180, 245);
            humanZoom(zoom);
            sleepGaussian(300, 100);

            // 3. Pitch down. 128 = horizon, 383 = straight down; the top of that range shows the
            //    whole bank-to-anvil corridor without the dead-flat overhead no player actually uses.
            int pitch = Rs2Random.between(325, 372);
            humanPitch(pitch);

            // 4. Verify, and keep widening until both really are in frame. One visibility test per
            //    pass — it is a client-thread round trip, so calling it in both the loop condition
            //    and the body doubled the cost for nothing.
            boolean framed = bothVisible(bankTile, anvilTile);
            for (int step = 0; step < MAX_FRAMING_STEPS && !framed; step++) {
                // Small corrective nudges, so a single eased move each — no need for the staged
                // treatment the initial framing gets, and it keeps the client-thread cost down.
                zoom = Math.max(MIN_ZOOM, zoom - Rs2Random.between(22, 38));
                pitch = Math.min(MAX_PITCH, pitch + Rs2Random.between(5, 11));
                Rs2Camera.setZoom(zoom);
                sleepGaussian(180, 60);
                Rs2Camera.setPitch(pitch);
                sleepGaussian(240, 80);
                framed = bothVisible(bankTile, anvilTile);
            }

            if (framed) {
                info("Camera set: bank and anvil both in view (zoom " + zoom + ", pitch " + pitch + ")");
            } else {
                info("Camera set, but bank and anvil do not both fit (zoom " + zoom
                        + ", pitch " + pitch + ") — the client may still turn to reach them");
            }
        } catch (Exception e) {
            // Not fatal — the client falls back to turning toward targets itself, just less pleasantly.
            Microbot.log("VarrockAnvil: could not set the camera: " + e.getMessage());
        }
    }

    /**
     * Rs2Camera covers ANY distance in 220-780ms, so a half-turn whips. Two eased moves doubles that
     * and adds stop-start texture; more hops than this stutters the client (each is 10 steps).
     */
    private void humanYaw(int targetYaw) {
        int from = Rs2Camera.getYaw();
        // Shortest way round the 0/2048 wrap, so we never take the long way for a few degrees.
        int delta = ((targetYaw - from + 3072) % 2048) - 1024;
        int half = ((from + delta / 2) % 2048 + 2048) % 2048;
        Rs2Camera.setYaw(half);
        sleepGaussian(240, 80);
        Rs2Camera.setYaw(((targetYaw % 2048) + 2048) % 2048);
    }

    private void humanPitch(int targetPitch) {
        int from = Rs2Camera.getPitch();
        Rs2Camera.setPitch(from + (targetPitch - from) / 2);
        sleepGaussian(220, 70);
        Rs2Camera.setPitch(targetPitch);
    }

    /** Stepped like scroll-wheel notches, because that is how a player actually zooms. */
    private void humanZoom(int targetZoom) {
        int from = Rs2Camera.getZoom();
        int notches = Rs2Random.between(3, 5);
        for (int i = 1; i <= notches; i++) {
            Rs2Camera.setZoom(from + Math.round((targetZoom - from) * (i / (float) notches)));
            sleepGaussian(150, 50);
        }
    }

    /** One client-thread hop; tiles resolved by the caller rather than on every call. */
    private boolean bothVisible(LocalPoint bank, LocalPoint anvil) {
        return Microbot.getClientThread().runOnClientThreadOptional(
                () -> Rs2Camera.isTileOnScreen(bank) && Rs2Camera.isTileOnScreen(anvil)).orElse(false);
    }

    /** Without this, every click lands on the welcome overlay the client already calls LOGGED_IN. */
    private boolean dismissWelcomeScreen() {
        if (!Rs2Widget.isWidgetVisible(WELCOME_SCREEN_GROUP, WELCOME_SCREEN_PLAY_BUTTON)) {
            return false;
        }
        info("Clicking through the welcome screen");
        Rs2Widget.clickWidget(WELCOME_SCREEN_GROUP, WELCOME_SCREEN_PLAY_BUTTON);
        sleepGaussian(900, 250);
        return true;
    }

    private LocalPoint toLocal(WorldPoint worldPoint) {
        return Microbot.getClientThread().invoke(() -> {
            Client client = Microbot.getClient();
            if (client == null) return null;
            return LocalPoint.fromWorld(client.getTopLevelWorldView(), worldPoint);
        });
    }

    /** The only place state changes, so the watchdog's dwell time can never drift. */
    private void setState(State next) {
        if (next == null || next == state) return;
        State previous = state;
        state = next;
        stateEnteredMs = System.currentTimeMillis();
        lastLogged = "";
        if (config != null && config.sDebug()) {
            Microbot.log("VarrockAnvil [" + previous + " -> " + next + "]");
        }
    }

    /** One-shot startup gate. Only hard-stops on a Smithing level no retry can fix. */
    private boolean runPreflight() {
        if (Rs2Dialogue.hasContinue()) {
            debug("Preflight: clearing a leftover dialogue");
            Rs2Dialogue.clickContinue();
            return false;
        }

        int level = Microbot.getClient().getRealSkillLevel(Skill.SMITHING);
        int required = requiredLevel(config.sBarType(), config.sAnvilItem());
        if (level < required) {
            stop("Smithing " + level + " is below the " + required + " needed for "
                    + getEffectiveItemName(config.sAnvilItem(), config.sBarType()) + ".");
            return false;
        }

        if (!equipCarriedImcando()) {
            return false;
        }

        preflightDone = true;
        info("Ready — " + describeHammer() + "; banking first to start from a known inventory");
        // Always open with a bank trip. After a disconnect or a manual restart the inventory can hold
        // any mix of leftover product, part-used bars and junk; banking normalises all of it.
        setState(State.BANKING);
        return true;
    }

    /** Wear a carried Imcando hammer: same result, one more inventory slot. False = retry next tick. */
    private boolean equipCarriedImcando() {
        for (int variant : new int[]{ItemID.IMCANDO_HAMMER_OFFHAND, ItemID.IMCANDO_HAMMER}) {
            final int id = variant;
            if (Rs2Equipment.isWearing(id)) return true;
            if (!Rs2Inventory.hasItem(id)) continue;
            if (Rs2Bank.isOpen()) {
                Rs2Bank.closeBank();
                return false;
            }
            debug("Equipping the Imcando hammer to free an inventory slot");
            Rs2Inventory.interact(id, "Wield");
            return sleepUntil(() -> Rs2Equipment.isWearing(id), 2500);
        }
        return true;
    }

    private String describeHammer() {
        if (Rs2Equipment.isWearing(ItemID.IMCANDO_HAMMER_OFFHAND)) return "Imcando hammer equipped (off-hand)";
        if (Rs2Equipment.isWearing(ItemID.IMCANDO_HAMMER)) return "Imcando hammer equipped (main-hand)";
        if (Rs2Equipment.isWearing(ItemID.HAMMER)) return "hammer equipped";
        if (Rs2Inventory.hasItem(HAMMER_IDS)) return "hammer in inventory";
        return "no hammer yet, will withdraw one";
    }

    private State tick(State currentState, Bars barType, AnvilItem anvilItem) {
        switch (currentState) {
            case DETERMINE:  return doDetermine(barType, anvilItem);
            case BANKING:    return doBanking(barType, anvilItem);
            case SMITHING:   return doSmithing(barType, anvilItem);
            case RECOVERY:   return doRecovery();
            default:         return State.DETERMINE;
        }
    }

    private State doDetermine(Bars barType, AnvilItem anvilItem) {
        if (Rs2Bank.isOpen()) {
            debug("Determine: bank is open, closing first");
            Rs2Bank.closeBank();
            return State.BANKING;
        }

        MatContext mat = getMatContext(barType, anvilItem);
        int currentMat = mat.useName ? Rs2Inventory.count(mat.name) : Rs2Inventory.count(mat.id);
        int effReq = getEffectiveRequiredBars(anvilItem, barType);
        boolean hasHammer = hasHammer();

        if (currentMat >= effReq && hasHammer) {
            debug("Determine -> SMITHING (mats=" + currentMat + "/" + effReq + ", hammer=" + hasHammer + ")");
            return State.SMITHING;
        } else {
            debug("Determine -> BANKING (mats=" + currentMat + "/" + effReq + ", hammer=" + hasHammer + ")");
            return State.BANKING;
        }
    }

    private State doBanking(Bars barType, AnvilItem anvilItem) {
        ctx.resetSession();

        if (Rs2Bank.isOpen()) {
            return performBankingOperations(barType, anvilItem);
        }

        WorldPoint playerLoc = Rs2Player.getWorldLocation();
        if (playerLoc == null) {
            debug("Banking: waiting for player location to load...");
            return State.BANKING;
        }

        if (!isNearBank()) {
            debug("Walking to bank... (dist=" + playerLoc.distanceTo(BANK_LOCATION) + ")");
            walkToBank();
            return State.BANKING;
        }

        int distToBank = playerLoc.distanceTo(BANK_LOCATION);
        debug("Attempt direct bank click, dist=" + distToBank);

        sleepGaussian(250, 120);

        if (!interactWithBank()) {
            if (Rs2Player.isMoving()) return State.BANKING;
            debug("Bank interact failed");
            return State.RECOVERY;
        }

        sleepUntil(Rs2Bank::isOpen, 5000);

        if (!Rs2Bank.isOpen()) {
            debug("Bank failed to open after click");
            return State.RECOVERY;
        }

        return performBankingOperations(barType, anvilItem);
    }

    private State performBankingOperations(Bars barType, AnvilItem anvilItem) {
        MatContext material = getMatContext(barType, anvilItem);

        // Keep a hammer only if none is worn. Large keels match by name (the material is an item).
        final boolean hammerWorn = Rs2Equipment.isWearing(HAMMER_IDS);
        if (hammerWorn && Rs2Inventory.hasItem(HAMMER_IDS)) {
            debug("Banking a spare hammer — one is already equipped");
        }
        if (material.useName) {
            final String keepName = material.name;
            Rs2Bank.depositAllExcept(item ->
                    (!hammerWorn && isHammer(item.getId())) || keepName.equals(item.getName()));
        } else {
            Rs2Bank.depositAllExcept(keepOnDeposit(hammerWorn, material.id));
        }

        // An equipped Imcando hammer already satisfies this and needs no inventory slot.
        if (!hasHammer()) {
            for (int hammerId : HAMMER_IDS) {
                if (!Rs2Bank.hasItem(hammerId)) continue;
                Rs2Bank.withdrawOne(hammerId);
                sleepUntil(this::hasHammer, BANK_TIMEOUT);
                if (hasHammer()) break;
            }
            if (!hasHammer()) {
                Rs2Bank.closeBank();
                stop("No hammer in the bank — checked plain hammer and both Imcando variants.");
                return State.DETERMINE;
            }
            // Wield needs the bank closed, so this settles on the next pass.
            if (Rs2Inventory.hasItem(ItemID.IMCANDO_HAMMER, ItemID.IMCANDO_HAMMER_OFFHAND)) {
                preflightDone = false;
            }
        }

        MatContext mat = getMatContext(barType, anvilItem);
        String matName = mat.name;
        boolean useName = mat.useName;
        int id = mat.id;

        int currentMat = useName ? Rs2Inventory.count(matName) : Rs2Inventory.count(id);
        int matsPerItem = getEffectiveRequiredBars(anvilItem, barType);
        int bankCount = useName ? Rs2Bank.count(matName) : Rs2Bank.count(id);

        if (currentMat + bankCount < matsPerItem) {
            Rs2Bank.closeBank();
            stop("Out of materials.");
            return State.DETERMINE;
        }

        int slotsForMat = slotsForMaterials();
        int maxFullItems = slotsForMat / matsPerItem;
        int maxMat = maxFullItems * matsPerItem;
        int matNeeded = maxMat - currentMat;

        if (matNeeded > 0) {
            int toWithdraw = Math.min(matNeeded, bankCount);
            if (toWithdraw > 0) {
                if (useName) {
                    Rs2Bank.withdrawX(matName, toWithdraw);
                } else {
                    Rs2Bank.withdrawX(id, toWithdraw);
                }
                sleepUntil(() -> {
                    int newCount = useName ? Rs2Inventory.count(matName) : Rs2Inventory.count(id);
                    return newCount >= currentMat + toWithdraw;
                }, BANK_TIMEOUT);
                int finalCount = useName ? Rs2Inventory.count(matName) : Rs2Inventory.count(id);
                if (finalCount < currentMat + toWithdraw) {
                    debug("Withdraw of " + toWithdraw + " did not settle; will retry.");
                    return State.BANKING;
                }
            }
        }

        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen(), 3000);

        if (Rs2Bank.isOpen()) {
            debug("Bank still open after close attempt, retrying...");
            return State.BANKING;
        }

        if (hasHammer()) {
            int finalMatCount = useName ? Rs2Inventory.count(matName) : Rs2Inventory.count(id);
            if (finalMatCount >= matsPerItem) {
                debug("Banking complete -> DETERMINE (mats now " + finalMatCount + ")");
                sleepGaussian(700, 200);
                return State.DETERMINE;
            } else {
                debug("Banked but still low on mats (" + finalMatCount + " < " + matsPerItem + ")");
                return State.BANKING;
            }
        } else {
            debug("Banked but no hammer in inventory");
            return State.BANKING;
        }
    }

    private State doSmithing(Bars barType, AnvilItem anvilItem) {
        if (Rs2Bank.isOpen()) {
            debug("Smithing: bank is open, closing first");
            Rs2Bank.closeBank();
            sleepUntil(() -> !Rs2Bank.isOpen(), 3000);
            return State.DETERMINE;
        }

        MatContext mat = getMatContext(barType, anvilItem);
        int currentMat = mat.useName ? Rs2Inventory.count(mat.name) : Rs2Inventory.count(mat.id);
        int req = getEffectiveRequiredBars(anvilItem, barType);

        if (ctx.lastMakeStarted > 0) {
            if (currentMat < req) {
                info("Finished a load (" + ctx.maxFullItems + " x " + getEffectiveItemName(anvilItem, barType) + ")");
                itemsMade.addAndGet(ctx.maxFullItems);
                ctx.resetSession();
                sleepGaussian(550, 175);
                return State.DETERMINE;
            }

            // Without this, an animation that never ends parks us in SMITHING indefinitely.
            long sessionAge = System.currentTimeMillis() - ctx.smithingStartTime;
            if (ctx.makeSessionTimeoutMs > 0 && sessionAge > ctx.makeSessionTimeoutMs) {
                info("Make session ran " + (sessionAge / 1000) + "s with materials left — restarting it");
                ctx.resetSession();
                return State.DETERMINE;
            }

            if (Rs2Player.isAnimating()) {
                debug("Smithing (" + currentMat + " " + mat.name.toLowerCase() + " left)");
                return State.SMITHING;
            }

            debug("Smithing: animation stopped, waiting for XP drop...");
            if (Rs2Player.waitForXpDrop(Skill.SMITHING, XP_DROP_TIMEOUT_MS)) {
                debug("Smithing: XP drop received, still active");
                return State.SMITHING;
            }

            debug("Done: no animation + no XP drop");
            itemsMade.addAndGet(ctx.maxFullItems);
            ctx.resetSession();
            sleepGaussian(550, 175);
            return State.DETERMINE;
        }

        debug("Smithing: no active session, mats=" + currentMat + "/" + req + ", nearAnvil=" + isNearAnvil());

        if (currentMat < req) {
            debug("Low mats on entry, going to DETERMINE");
            return State.DETERMINE;
        }

        if (!isNearAnvil()) {
            debug("Walking to anvil...");
            walkToAnvil();
            return State.SMITHING;
        }

        if (Rs2Player.isInteracting() && Rs2Player.isMoving() && !isSmithingWidgetOpen()) {
            debug("Smithing: interaction in progress, waiting...");
            return State.SMITHING;
        }

        WorldPoint here = Rs2Player.getWorldLocation();
        debug("Attempt direct anvil click, dist=" + (here != null ? here.distanceTo(ANVIL_LOCATION) : -1));

        sleepGaussian(200, 100);

        if (!interactWithAnvil()) {
            if (Rs2Player.isMoving()) return State.SMITHING;
            debug("Anvil interact failed");
            return State.RECOVERY;
        }

        debug("Anvil clicked, waiting for widget...");
        sleepUntil(() -> isSmithingWidgetOpen() || Rs2Dialogue.hasContinue(), WIDGET_OPEN_TIMEOUT);

        int continues = 0;
        while (Rs2Dialogue.hasContinue() && continues < 6) {
            Rs2Dialogue.clickContinue();
            continues++;
        }

        boolean widgetOpen = isSmithingWidgetOpen();
        debug("Widget wait result: widgetOpen=" + widgetOpen + ", continues=" + continues);

        String itemKey = anvilItem.getName() + "_" + barType;

        if (widgetOpen || continues > 0) {
            String effectiveName = getEffectiveItemName(anvilItem, barType);

            if (ctx.selectionComplete && itemKey.equals(ctx.lastSelectedItem)) {
                Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
                int fullItems = slotsForMaterials() / getEffectiveRequiredBars(anvilItem, barType);
                ctx.startSession(fullItems);
                consecutiveRecoveries = 0;
                info("Smithing " + fullItems + " x " + effectiveName + " (repeat)");
                return State.SMITHING;
            }

            debug("Select: " + effectiveName);

            int slotsForMat = slotsForMaterials();
            int matsPerItem = getEffectiveRequiredBars(anvilItem, barType);
            int fullItems = slotsForMat / matsPerItem;

            // Quantity before item, as a player does. The varp remembers it, so only click when it
            // is lower than what we are about to smith - otherwise it is one item per trip.
            if (Microbot.getVarbitPlayerValue(MAKE_X_QUANTITY_VARP) < fullItems) {
                debug("Setting quantity to All");
                Rs2Widget.clickWidget(ANVIL_WIDGET_GROUP, ANVIL_WIDGET_ALL_CHILD);
                sleepGaussian(300, 90);
            }

            // Stable child index where there is one; otherwise match text (other_N slots move).
            boolean itemClicked = anvilItem.hasValidChildId()
                    ? Rs2Widget.clickWidget(ANVIL_WIDGET_GROUP, anvilItem.getChildId())
                    : !Rs2Widget.findWidgetsWithAction(effectiveName, ANVIL_WIDGET_GROUP, 0, true).isEmpty();

            if (!itemClicked) {
                debug("Could not find '" + effectiveName + "' in the anvil interface");
                return State.RECOVERY;
            }

            ctx.startSession(fullItems);
            ctx.selectionComplete = true;
            ctx.lastSelectedItem = itemKey;
            consecutiveRecoveries = 0;
            info("Smithing " + fullItems + " x " + effectiveName);
            return State.SMITHING;
        }

        debug("Smithing widget did not open after anvil click");
        return State.RECOVERY;
    }

    private State doRecovery() {
        ctx.resetSession();

        // Each pass through here without an intervening success is a failed attempt. Bail out rather
        // than loop forever on something that is never going to work.
        if (++consecutiveRecoveries > MAX_CONSECUTIVE_RECOVERIES) {
            stop("Gave up after " + MAX_CONSECUTIVE_RECOVERIES + " failed attempts in a row. "
                    + "Check that " + getEffectiveItemName(config.sAnvilItem(), config.sBarType())
                    + " can be made from " + config.sBarType() + " at your Smithing level.");
            return State.DETERMINE;
        }

        info("Recovering (attempt " + consecutiveRecoveries + "/" + MAX_CONSECUTIVE_RECOVERIES + ")");

        if (Rs2Bank.isOpen()) {
            debug("Recovery: closing bank");
            Rs2Bank.closeBank();
            return State.RECOVERY;
        }

        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null) {
            debug("Recovery: waiting for location...");
            return State.RECOVERY;
        }

        boolean nearAnvil = here.distanceTo(ANVIL_LOCATION) <= ANVIL_INTERACT_DISTANCE;
        boolean nearBank = here.distanceTo(BANK_LOCATION) <= BANK_INTERACT_DISTANCE;

        if (nearAnvil || nearBank) {
            debug("Recovery: at a known spot, re-evaluating");
            return State.DETERMINE;
        }

        debug("Recovery: walking back to the bank");
        Rs2Walker.walkTo(BANK_LOCATION);
        return State.DETERMINE;
    }

    /** Deposit-All exclusion list: every hammer variant, plus any extra ids (the material). */
    public static Integer[] keepOnDeposit(boolean hammerEquipped, int... extraIds) {
        // A hammer in the equipment slot already smiths, so any hammer in the inventory is dead
        // weight — bank it and get the slot back. Only hold one when nothing is worn.
        int[] hammers = hammerEquipped ? new int[0] : HAMMER_IDS;
        Integer[] keep = new Integer[hammers.length + extraIds.length];
        int i = 0;
        for (int id : hammers) keep[i++] = id;
        for (int id : extraIds) keep[i++] = id;
        return keep;
    }

    public static boolean isHammer(int id) {
        for (int hammerId : HAMMER_IDS) {
            if (hammerId == id) return true;
        }
        return false;
    }

    private boolean hasHammer() {
        return Rs2Inventory.hasItem(HAMMER_IDS) || Rs2Equipment.isWearing(HAMMER_IDS);
    }

    private boolean hammerInInventory() {
        return Rs2Inventory.hasItem(HAMMER_IDS);
    }

    /** Inventory slots available for materials, reserving one only for a hammer we must carry. */
    private int slotsForMaterials() {
        return Rs2Inventory.capacity() - (hammerInInventory() ? 1 : 0);
    }

    private boolean isNearAnvil() {
        WorldPoint playerLoc = Rs2Player.getWorldLocation();
        return playerLoc != null && playerLoc.distanceTo(ANVIL_LOCATION) <= ANVIL_INTERACT_DISTANCE;
    }

    private void walkToAnvil() {
        WorldPoint playerLoc = Rs2Player.getWorldLocation();
        if (playerLoc == null) return;
        int distance = playerLoc.distanceTo(ANVIL_LOCATION);
        if (distance <= WALK_FAST_CANVAS_DISTANCE) {
            boolean walked = Rs2Walker.walkFastCanvas(ANVIL_LOCATION);
            if (!walked) Rs2Walker.walkTo(ANVIL_LOCATION);
        } else {
            Rs2Walker.walkTo(ANVIL_LOCATION);
        }
        sleepUntil(() -> Rs2Player.isMoving() || isNearAnvil(), 2000);
    }

    private boolean isNearBank() {
        WorldPoint playerLoc = Rs2Player.getWorldLocation();
        return playerLoc != null && playerLoc.distanceTo(BANK_LOCATION) <= BANK_INTERACT_DISTANCE;
    }

    private void walkToBank() {
        WorldPoint playerLoc = Rs2Player.getWorldLocation();
        if (playerLoc == null) return;
        int distance = playerLoc.distanceTo(BANK_LOCATION);
        if (distance <= WALK_FAST_CANVAS_DISTANCE) {
            boolean walked = Rs2Walker.walkFastCanvas(BANK_LOCATION);
            if (!walked) Rs2Walker.walkTo(BANK_LOCATION);
        } else {
            Rs2Walker.walkTo(BANK_LOCATION);
        }
        sleepUntil(() -> Rs2Player.isMoving() || isNearBank(), 2000);
    }

    private boolean isSmithingWidgetOpen() {
        return Rs2Widget.isSmithingWidgetOpen()
                || Rs2Widget.isProductionWidgetOpen()
                || Rs2Widget.isWidgetVisible(ANVIL_WIDGET_GROUP, ANVIL_WIDGET_ALL_CHILD);
    }

    private boolean interactWithAnvil() {
        try {
            Rs2TileObjectModel anvil = Microbot.getRs2TileObjectCache().query()
                    .fromWorldView()
                    .withName("Anvil")
                    .nearestOnClientThread(ANVIL_LOCATION, 15);
            if (anvil == null) {
                anvil = Microbot.getRs2TileObjectCache().query()
                        .fromWorldView()
                        .withId(ANVIL_OBJECT_ID)
                        .nearestOnClientThread(ANVIL_LOCATION, 15);
            }
            if (anvil == null) {
                debug("No anvil found");
                return false;
            }

            boolean clicked = anvil.click("Smith");
            if (!clicked) clicked = anvil.click();
            return clicked;
        } catch (Exception ex) {
            debug("Anvil error: " + ex.getMessage());
            return false;
        }
    }

    private boolean interactWithBank() {
        try {
            Rs2TileObjectModel bank = Microbot.getRs2TileObjectCache().query()
                    .fromWorldView()
                    .withName("Bank booth")
                    .nearestOnClientThread(BANK_LOCATION, 15);
            if (bank == null) {
                bank = Microbot.getRs2TileObjectCache().query()
                        .fromWorldView()
                        .withNameContains("Bank")
                        .nearestOnClientThread(BANK_LOCATION, 15);
            }
            if (bank == null) {
                debug("No bank found");
                return false;
            }

            boolean clicked = bank.click("Bank");
            if (!clicked) clicked = bank.click();
            return clicked;
        } catch (Exception ex) {
            debug("Bank error: " + ex.getMessage());
            return false;
        }
    }

    /** Per-tick detail: overlay always, log only when verbose. */
    private void debug(String msg) {
        debug = msg;
        if (config != null && config.sDebug()) {
            write(msg);
        }
    }

    /** Milestones: sessions, loads, recoveries, stops. */
    private void info(String msg) {
        debug = msg;
        write(msg);
    }

    /** Collapses consecutive repeats and tags the state - the 1s loop would otherwise spam. */
    private void write(String msg) {
        String line = "[" + state + "] " + msg;
        if (line.equals(lastLogged)) return;
        lastLogged = line;
        Microbot.log("VarrockAnvil " + line);
    }

    public void stop(String message) {
        debug = message;
        Microbot.log("VarrockAnvil stopped: " + message
                + " (ran " + formatDuration(getElapsedTime())
                + ", " + itemsMade.get() + " items, " + getXpGained() + " xp)");
        Microbot.showMessage(message);

        if (logout) {
            Rs2Player.logout();
        }

        shutdown();
        Microbot.stopPlugin(VarrockAnvilPlugin.class);
    }

    private static String formatDuration(long ms) {
        long s = ms / 1000;
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    @Override
    public void shutdown() {
        if (mainScheduledFuture != null) {
            mainScheduledFuture.cancel(true);
            mainScheduledFuture = null;
        }
        currentTaskId = 0;
        ctx.resetSession();
        running = false;
        super.shutdown();
        Rs2Antiban.resetAntibanSettings();
    }

    public long getElapsedTime() {
        return startTime > 0 ? System.currentTimeMillis() - startTime : 0;
    }

    public int getXpGained() {
        if (startXp < 0) return 0;
        if (!Microbot.isLoggedIn() || Microbot.getClient() == null) return 0;
        return Math.max(0, Microbot.getClient().getSkillExperience(Skill.SMITHING) - startXp);
    }

    public int getXpPerHour() {
        int gained = getXpGained();
        if (gained <= 0) return 0;
        long elapsed = getElapsedTime();
        if (elapsed < 5000) return 0;   // first seconds inflate the rate
        double hours = elapsed / 3600000.0;
        return (int) (gained / hours);
    }

    public int getItemsMade() {
        return itemsMade.get();
    }

    /** The item as the game names it, so the overlay shows "Adamant keel parts", not "Keel parts". */
    public String getTargetName() {
        return config == null ? "-" : getEffectiveItemName(config.sAnvilItem(), config.sBarType());
    }

    /**
     * The item name as the anvil interface spells it.
     *
     * Keel names use the game's metal word, which is NOT the bar name minus " bar": an Adamantite bar
     * makes "Adamant keel parts" and a Runite bar makes "Rune keel parts". Large variants keep the
     * metal lowercase ("Large adamant keel parts").
     */
    private String getEffectiveItemName(AnvilItem item, Bars bar) {
        String metal = bar.getKeelMetal();
        if (!item.isKeelItem() || metal == null) return item.getName();
        return item.isLargeKeel()
                ? "Large " + metal + " keel parts"
                : capitalise(metal) + " keel parts";
    }

    /** Keel tiers take 5 materials each, except dragon which takes 2. */
    private int getEffectiveRequiredBars(AnvilItem item, Bars bar) {
        return item.isKeelItem() ? bar.getKeelQuantity() : item.getRequiredBars();
    }

    /** The Smithing level this bar/item pairing actually needs — keel tiers differ from smelting levels. */
    private int requiredLevel(Bars bar, AnvilItem item) {
        return item.isKeelItem() ? bar.getKeelLevel() : bar.getRequiredSmithingLevel();
    }

    /** What we consume: bars for everything except large keels, which eat regular keel parts. */
    private String getMaterialName(Bars bar, AnvilItem item) {
        if (item.isLargeKeel() && bar.getKeelMetal() != null) {
            return capitalise(bar.getKeelMetal()) + " keel parts";
        }
        return bar.toString();
    }

    private boolean useNameForMaterial(Bars bar, AnvilItem item) {
        return item.isLargeKeel() || bar.getId() == 0;
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Non-null describes why this bar/item pairing can never work, for an up-front stop. */
    private String rejectionReason(Bars bar, AnvilItem item) {
        if (!item.isKeelItem()) {
            if (bar == Bars.DRAGON) {
                return "Dragon is only for Large keel parts — pick a bar for anything else.";
            }
            return null;
        }
        if (!bar.hasKeelParts()) {
            return bar + " has no keel parts. Sailing keels exist for bronze, iron, steel, mithril, "
                    + "adamantite, runite and dragon.";
        }
        // Regular dragon keel parts are 2 dragon metal sheets at the Dragon Forge. Large dragon keel
        // parts are 2 Dragon keel parts at an ordinary anvil, so only the regular one is impossible.
        if (bar.regularKeelNeedsDragonForge() && !item.isLargeKeel()) {
            return "Dragon keel parts are made from dragon metal sheets at the Dragon Forge, not an "
                    + "anvil. Large keel parts with Dragon does work here (2 Dragon keel parts each).";
        }
        return null;
    }

    private MatContext getMatContext(Bars bar, AnvilItem item) {
        return new MatContext(getMaterialName(bar, item), useNameForMaterial(bar, item), bar.getId());
    }
}