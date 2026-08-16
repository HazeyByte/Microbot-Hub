package net.runelite.client.plugins.microbot.irkedgoatkiller;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameObject;
import net.runelite.api.IterableHashTable;
import net.runelite.api.MessageNode;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.magic.Rs2Spells;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.npc.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Wyrmscraig goat hunting — reliable, state-driven. The player stands on ONE fixed pit-side tile and NEVER
 * moves to grab a goat: only goats on the opposite side of the pit, within Telegrab range (10 tiles; capped
 * at 9) of the player's actual position, are valid. Out-of-range casts make the game walk you, so they are
 * rejected before the interaction, and each cast re-validates range after a short human reaction.
 *
 * Loot: fur fills the pouch first (auto, invisible) then overflows to the inventory. An inventory policy
 * classifies items (required / protected / disposable) from config; disposables are bulk-dropped to make room,
 * and banking only happens when fur genuinely can't be stored. Movement uses fast canvas walking for short
 * local hops and the web-walker only for the far bank trip (optionally via the agility stepping stone).
 */
@Singleton
@Slf4j
public class IrkedGoatkillerScript extends Script {

    private enum PitState { EMPTY, SPIKED, GOATS }
    enum Task { STARTING, GETTING_SPIKES, LINING, HUNTING, CLEARING, MANAGING_LOOT, BANKING, STOPPED }

    private static final int GOAT_PIT_ID = 62343;
    private static final int SPIKES_SUPPLY_ID = 62349;
    private static final int BANK_CHEST_ID = 62390;
    private static final int STEPPING_STONE_ID = 62262;
    private static final String GOAT = "Wyrmscraig Goat";
    private static final String WOODEN_SPIKES = "Wooden spikes";
    private static final String GOAT_HORN = "Goat horn";
    private static final String GOAT_FUR = "Wyrmscraig goat fur";

    private static final WorldPoint PIT_CENTER = new WorldPoint(2572, 2195, 0);
    private static final WorldPoint SPIKES_TILE = new WorldPoint(2578, 2202, 0);
    private static final WorldPoint BANK_TILE = new WorldPoint(2587, 2259, 0);
    private static final WorldPoint STONE_NORTH = new WorldPoint(2565, 2221, 0);
    private static final WorldPoint STONE_SOUTH = new WorldPoint(2565, 2217, 0);
    private static final int GAP_Y = 2219;   // the stepping-stone gap; south < GAP_Y < north

    private static final WorldPoint STAND_SOUTH = new WorldPoint(2572, 2193, 0);
    private static final WorldPoint STAND_EAST = new WorldPoint(2574, 2195, 0);
    private static final WorldPoint STAND_NORTH = new WorldPoint(2572, 2197, 0);
    private static final WorldPoint STAND_WEST = new WorldPoint(2570, 2195, 0);

    private static final int MAX_GRAB_DISTANCE = 9;    // Telegrab reaches 10 tiles; 1-tile buffer so a cast never walks us
    private static final double ACROSS_MIN = 0.35;     // cosine: goat must sit clearly across the pit from us
    private static final int MAX_PIT_CAPACITY = 24;    // grab-count fallback if the "filled" chat is missed
    private static final int LOCAL_WALK_TILES = 16;    // within this, canvas-walk instead of the web-walker

    private static final long GRAB_COOLDOWN_MS = 8000;
    private static final long LINE_THROTTLE_MS = 5000;
    private static final long TAKE_THROTTLE_MS = 3000;
    private static final long BANK_THROTTLE_MS = 4000;
    private static final long STUCK_SOFT_MS = 60000;
    private static final long STUCK_HARD_MS = 180000;
    private static final int CAST_FAIL_LIMIT = 6;

    IrkedGoatkillerConfig config;   // package-private: the overlay reads config flags
    private WorldPoint standTile;

    private volatile PitState pit = PitState.EMPTY;
    private volatile boolean fullSignaled;
    private volatile boolean spikesBroken;
    private volatile int lastChatId = Integer.MIN_VALUE;
    private volatile boolean chatInit;
    private boolean agilityDisabledThisTrip;
    private int grabsSinceLine;
    private int castFailStreak;
    private int bankAttempts;
    private long lastTakeMs, lastLineMs, lastBankMs, lastProgressMs, lastSoftWarnMs;

    // Overlay state (package-private).
    final AtomicInteger grabs = new AtomicInteger();
    final AtomicInteger clears = new AtomicInteger();
    final AtomicInteger fursBanked = new AtomicInteger();
    final AtomicInteger hornsDropped = new AtomicInteger();
    final AtomicInteger furDropped = new AtomicInteger();
    final AtomicInteger spikesTaken = new AtomicInteger();
    final AtomicInteger relines = new AtomicInteger();
    final AtomicInteger recoveries = new AtomicInteger();
    volatile Task task = Task.STARTING;
    volatile String pitLabel = "?";
    volatile String route = "-";
    volatile int validGoats;
    volatile String stopReason = "";
    long startMs;

    private final Map<Integer, Long> recentGrabs = new ConcurrentHashMap<>();
    private volatile double diagScore;
    private volatile int diagDist, diagSeen;
    private long lastCastMs;

    public boolean run(IrkedGoatkillerConfig config) {
        this.config = config;
        this.standTile = standTileFor(config.standSide());
        startMs = System.currentTimeMillis();
        lastProgressMs = startMs;
        log.info("[goat] started v1.1.0 | side={} tile={} pouch={} keepHorns={} dropFur={} dropAll={} agility={}",
                config.standSide(), standTile, config.furPouch(), config.keepGoatHorns(), config.dropGoatFur(),
                config.dropEverything(), config.useAgilityShortcut());
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn() || !super.run() || task == Task.STOPPED) return;
                tick();
            } catch (Exception ex) {
                log.error("[goat] tick error", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }

    private void tick() {
        PitState live = Microbot.getClientThread().invoke(this::readPitState);
        if (live != null) pit = live;
        Microbot.getClientThread().invoke(this::pollChat);
        if (spikesBroken) pit = PitState.EMPTY;
        pitLabel = pit.name();

        if (watchdogTripped()) return;

        boolean invFull = Rs2Inventory.isFull();
        boolean full = pit == PitState.GOATS && (fullSignaled || grabsSinceLine >= MAX_PIT_CAPACITY);

        // Inventory first: never harvest/hunt into a full inventory. Make room from disposables, else bank.
        if (invFull) {
            if (hasDisposables()) {
                setTask(Task.MANAGING_LOOT);
                makeRoom();
            } else {
                setTask(Task.BANKING);
                bankFur();
            }
            return;
        }
        if (full) {
            setTask(Task.CLEARING);
            clearPit();
            return;
        }
        if (pit == PitState.EMPTY) {
            lineCycle();
            return;
        }
        setTask(Task.HUNTING);
        huntCycle();
    }

    // --- pit state ---

    private PitState readPitState() {
        GameObject pitObj = Rs2GameObject.getGameObject(GOAT_PIT_ID);
        if (pitObj == null) return null;
        if (Rs2GameObject.hasAction(pitObj, "Line")) return PitState.EMPTY;
        if (Rs2GameObject.hasAction(pitObj, "Clear")) return PitState.GOATS;
        if (Rs2GameObject.hasAction(pitObj, "Check")) return PitState.SPIKED;
        return null;
    }

    private Boolean pollChat() {
        IterableHashTable<MessageNode> msgs = Microbot.getClient().getMessages();
        if (msgs == null) return Boolean.TRUE;
        int newMax = lastChatId;
        for (MessageNode n : msgs) {
            int id = n.getId();
            if (id > newMax) newMax = id;
            if (!chatInit || id <= lastChatId) continue;
            ChatMessageType t = n.getType();
            if (t != ChatMessageType.GAMEMESSAGE && t != ChatMessageType.SPAM && t != ChatMessageType.MESBOX) continue;
            String m = n.getValue().toLowerCase();
            if (m.contains("pit is now filled with goats")) {
                if (!fullSignaled) log.info("[goat] chat FULL (grabsSinceLine={})", grabsSinceLine);
                fullSignaled = true;
                pit = PitState.GOATS;
            } else if (m.contains("replace the spikes")) {
                if (!spikesBroken) log.info("[goat] chat REPLACE-SPIKES → re-line (was {})", pit);
                spikesBroken = true;
                pit = PitState.EMPTY;
            } else if (m.contains("you line the pit")) {
                log.info("[goat] chat LINED → new fill");
                spikesBroken = false;
                pit = PitState.SPIKED;
                grabsSinceLine = 0;
                fullSignaled = false;
                recentGrabs.clear();
                relines.incrementAndGet();
                progress();
            }
        }
        lastChatId = newMax;
        chatInit = true;
        return Boolean.TRUE;
    }

    // --- LINE ---

    private void lineCycle() {
        if (Rs2Inventory.count(WOODEN_SPIKES) < 1) {
            setTask(Task.GETTING_SPIKES);
            getSpike();
            return;
        }
        setTask(Task.LINING);
        if (!ensureAtTile()) return;
        if (System.currentTimeMillis() - lastLineMs > LINE_THROTTLE_MS) {
            lastLineMs = System.currentTimeMillis();
            log.info("[goat] line pit (one click)");
            Rs2GameObject.interact(GOAT_PIT_ID, "Line");   // "you line the pit" confirms → SPIKED next tick
        }
    }

    private void getSpike() {
        WorldPoint me = pos();
        if (me == null) return;
        if (me.distanceTo(SPIKES_TILE) > 2) {
            walkStep(SPIKES_TILE, 2);
            return;
        }
        if (System.currentTimeMillis() - lastTakeMs > TAKE_THROTTLE_MS) {
            lastTakeMs = System.currentTimeMillis();
            final int had = Rs2Inventory.count(WOODEN_SPIKES);
            log.info("[goat] take spike (one click, have {})", had);
            Rs2GameObject.interact(SPIKES_SUPPLY_ID, "Take");
            if (sleepUntil(() -> Rs2Inventory.count(WOODEN_SPIKES) > had, 3000)) {
                spikesTaken.incrementAndGet();
                progress();
            } else {
                log.info("[goat] spike not acquired within 3s — will retry");
            }
        }
    }

    // --- HUNT ---

    private void huntCycle() {
        if (!ensureAtTile()) return;
        Rs2NpcModel goat = Microbot.getClientThread().invoke(this::selectGoat);
        if (goat == null) {
            sleep(300, 600);
            return;
        }
        WorldPoint me = pos();
        int result = telegrab(goat);   // 1 = grabbed, 0 = aborted (goat moved), -1 = failed (runes/click)
        if (result > 0) {
            long now = System.currentTimeMillis();
            long gap = lastCastMs == 0 ? 0 : now - lastCastMs;
            lastCastMs = now;
            castFailStreak = 0;
            recentGrabs.put(goat.getIndex(), now);
            grabs.incrementAndGet();
            grabsSinceLine++;
            progress();
            log.info("[goat] grab #{} idx={} score={} dist={} valid={}/{} sinceLine={} pos={},{} gapMs={}",
                    grabs.get(), goat.getIndex(), String.format("%.2f", diagScore), diagDist, validGoats, diagSeen,
                    grabsSinceLine, me != null ? me.getX() : -1, me != null ? me.getY() : -1, gap);
            if (grabsSinceLine >= MAX_PIT_CAPACITY) fullSignaled = true;
            sleepUntil(() -> Rs2Player.isAnimating() || goat.isMoving(), 1200);
        } else if (result < 0) {
            castFailStreak++;
            log.info("[goat] cast failed (streak={}) — out of runes / blocked?", castFailStreak);
            if (castFailStreak >= CAST_FAIL_LIMIT) {
                stop("telegrab failed " + castFailStreak + " times in a row (out of runes / spell blocked)");
            }
        }
        humanPause();
    }

    /**
     * Telegrab with a humanised, verified two-step: select the spell → confirm selected → short bounded reaction →
     * re-validate the goat is still alive AND still in range from our actual position → click it. If it drifted out
     * of range during the reaction, abort (never let it reach the movement system). Returns 1/0/-1 as above.
     */
    private int telegrab(Rs2NpcModel goat) {
        // Reuse an already-selected spell (e.g. left over from a prior abort) instead of re-casting (which toggles).
        if (!Microbot.getClient().isWidgetSelected()) {
            if (!Rs2Magic.cast(Rs2Spells.TELEKINETIC_GRAB)) return -1;
            if (!sleepUntil(() -> Microbot.getClient().isWidgetSelected(), 1500)) return -1;
        }
        sleep(60, 260);   // bounded human reaction between selecting the spell and clicking the target
        Boolean ok = Microbot.getClientThread().invoke((Supplier<Boolean>) () -> {
            WorldPoint g = goat.getWorldLocation();
            WorldPoint p = Rs2Player.getWorldLocation();
            if (goat.getName() == null || g == null || p == null || p.distanceTo(g) > MAX_GRAB_DISTANCE) return false;
            if (!Rs2Camera.isTileOnScreen(goat.getLocalLocation())) Rs2Camera.turnTo(goat.getLocalLocation());
            return true;
        });
        if (ok == null || !ok) return 0;   // goat moved/despawned during the reaction → abort, keep spell selected
        return Rs2Npc.interact(goat) ? 1 : -1;
    }

    private Rs2NpcModel selectGoat() {
        diagSeen = 0;
        validGoats = 0;
        WorldPoint me = pos();
        if (me == null) return null;
        double dx = PIT_CENTER.getX() - me.getX(), dy = PIT_CENTER.getY() - me.getY();
        double dlen = Math.hypot(dx, dy);
        if (dlen < 1) return null;
        long now = System.currentTimeMillis();
        recentGrabs.values().removeIf(t -> now - t > GRAB_COOLDOWN_MS);

        List<Rs2NpcModel> seen = Rs2Npc.getNpcs(GOAT)
                .filter(g -> g.getWorldLocation() != null)
                .collect(Collectors.toList());
        List<Rs2NpcModel> eligible = seen.stream()
                .filter(g -> me.distanceTo(g.getWorldLocation()) <= MAX_GRAB_DISTANCE)   // in range → no walk
                .filter(g -> !g.isMoving())
                .filter(g -> !recentGrabs.containsKey(g.getIndex()))
                .filter(g -> !claimedByOther(g))
                .filter(g -> across(g.getWorldLocation(), dx, dy, dlen) > ACROSS_MIN)     // opposite side
                .collect(Collectors.toList());
        Rs2NpcModel chosen = eligible.stream()
                .max(Comparator.comparingDouble(g -> across(g.getWorldLocation(), dx, dy, dlen)))
                .orElse(null);

        diagSeen = seen.size();
        validGoats = eligible.size();
        if (chosen != null) {
            diagScore = across(chosen.getWorldLocation(), dx, dy, dlen);
            diagDist = me.distanceTo(chosen.getWorldLocation());
        }
        return chosen;
    }

    private static double across(WorldPoint goat, double dx, double dy, double dlen) {
        double vx = goat.getX() - PIT_CENTER.getX(), vy = goat.getY() - PIT_CENTER.getY();
        double vlen = Math.hypot(vx, vy);
        if (vlen < 0.5) return 0;
        return (vx * dx + vy * dy) / (vlen * dlen);
    }

    private static boolean claimedByOther(Rs2NpcModel goat) {
        Player me = Microbot.getClient().getLocalPlayer();
        Actor goatTarget = goat.getInteracting();
        if (goatTarget != null && goatTarget != me) return true;
        int idx = goat.getIndex();
        return Rs2Player.getPlayers(p -> {
            Actor a = p.getInteracting();
            return a instanceof NPC && ((NPC) a).getIndex() == idx;
        }, false).findAny().isPresent();
    }

    // --- CLEAR ---

    private void clearPit() {
        if (!ensureAtTile()) return;
        if (System.currentTimeMillis() - lastLineMs < 1200) return;
        if (!Rs2GameObject.interact(GOAT_PIT_ID, "Clear")) {
            log.info("[goat] clear click failed — retrying");
            sleep(300, 600);
            return;
        }
        log.info("[goat] clear pit (grabsSinceLine={} full={})", grabsSinceLine, fullSignaled);
        long start = System.currentTimeMillis();
        sleep(1200, 1800);
        long deadline = System.currentTimeMillis() + 20000;
        int lastHorn = Rs2Inventory.count(GOAT_HORN), stable = 0;
        String why = "timeout";
        while (System.currentTimeMillis() < deadline) {
            if (readPitStateSafe() == PitState.EMPTY) { why = "obj-empty"; break; }
            int cur = Rs2Inventory.count(GOAT_HORN);
            if (cur != lastHorn) { lastHorn = cur; stable = 0; }
            else if (++stable >= 5 && !Rs2Player.isAnimating() && !Rs2Player.isMoving()) { why = "horns-stable"; break; }
            sleep(600);
        }
        sleep(500, 900);
        clears.incrementAndGet();
        spikesBroken = true;
        fullSignaled = false;
        grabsSinceLine = 0;
        recentGrabs.clear();
        progress();
        log.info("[goat] harvest done via {} in {}ms | clears={} horns={} invFur={}",
                why, System.currentTimeMillis() - start, clears.get(),
                Rs2Inventory.count(GOAT_HORN), Rs2Inventory.count(GOAT_FUR));
    }

    // --- INVENTORY POLICY ---

    /** Item names that are disposable right now, per config (horns unless kept, fur if drop-fur). */
    private List<String> disposableNames() {
        List<String> d = new ArrayList<>();
        if (!config.keepGoatHorns()) d.add(GOAT_HORN);
        if (config.dropGoatFur()) d.add(GOAT_FUR);
        return d;
    }

    /** Items we must keep for "Drop everything": required supplies + config-protected loot. */
    private String[] protectedNames() {
        List<String> p = new ArrayList<>();
        p.add(WOODEN_SPIKES);
        p.add("Law rune");
        p.add("Air rune");
        p.add("fur pouch");   // contains-match keeps every pouch variant
        if (config.keepGoatHorns()) p.add(GOAT_HORN);
        if (!config.dropGoatFur()) p.add(GOAT_FUR);
        return p.toArray(new String[0]);
    }

    private boolean hasDisposables() {
        for (String n : disposableNames()) {
            if (Rs2Inventory.hasItem(n)) return true;
        }
        return false;
    }

    /** Bulk-drop disposables to free room (fast, one verify). Fur always fills the pouch first, so any inv fur here
     *  means the pouch is already full. Never drops required supplies or config-protected loot. */
    private void makeRoom() {
        int hornsBefore = Rs2Inventory.count(GOAT_HORN);
        int furBefore = Rs2Inventory.count(GOAT_FUR);
        log.info("[goat] inventory full — making room (dropEverything={} keepHorns={} dropFur={})",
                config.dropEverything(), config.keepGoatHorns(), config.dropGoatFur());
        if (config.dropEverything()) {
            Rs2Inventory.dropAllExcept(protectedNames());
        } else {
            Rs2Inventory.dropAll(disposableNames().toArray(new String[0]));
        }
        sleepUntil(() -> !Rs2Inventory.isFull(), 4000);
        int hornsGone = Math.max(0, hornsBefore - Rs2Inventory.count(GOAT_HORN));
        int furGone = Math.max(0, furBefore - Rs2Inventory.count(GOAT_FUR));
        if (hornsGone > 0) hornsDropped.addAndGet(hornsGone);
        if (furGone > 0) furDropped.addAndGet(furGone);
        progress();
        log.info("[goat] dropped horns={} fur={} | invFull now={}", hornsGone, furGone, Rs2Inventory.isFull());
    }

    // --- BANK ---

    private void bankFur() {
        WorldPoint me = pos();
        if (me == null) return;
        if (me.distanceTo(BANK_TILE) > 4) {
            walkStep(BANK_TILE, 3);   // handles agility crossing + fast/web routing
            return;
        }
        agilityDisabledThisTrip = false;   // arrived at bank
        if (!Rs2Bank.isOpen()) {
            if (System.currentTimeMillis() - lastBankMs > BANK_THROTTLE_MS) {
                lastBankMs = System.currentTimeMillis();
                bankAttempts++;
                log.info("[goat] open bank (attempt {}, one click)", bankAttempts);
                if (!Rs2GameObject.interact(BANK_CHEST_ID, "Use")) Rs2Bank.openBank();
                sleepUntil(Rs2Bank::isOpen, 5000);
            }
            return;
        }
        int before = Rs2Inventory.count(GOAT_FUR);
        Rs2Bank.depositAll(GOAT_FUR);
        Rs2Bank.depositAll(GOAT_HORN);
        String pouch = openPouchName();
        if (pouch != null && Rs2Inventory.interact(pouch, "Empty")) {
            sleepUntil(() -> Rs2Inventory.count(GOAT_FUR) > 0, 2000);
            Rs2Bank.depositAll(GOAT_FUR);
        }
        fursBanked.addAndGet(Math.max(before, 0));
        bankAttempts = 0;
        progress();
        log.info("[goat] banked | invFurBefore={} fursBankedTotal={}", before, fursBanked.get());
        sleep(400, 700);
        Rs2Bank.closeBank();
        agilityDisabledThisTrip = false;   // fresh trip home
    }

    // --- movement ---

    /** Short local hops use fast canvas walking; only far cross-region trips use the web-walker (via the agility
     *  stepping stone when enabled). Tracks the route for the overlay. */
    private void walkStep(WorldPoint target, int distance) {
        WorldPoint me = pos();
        if (me == null) {
            route = "WEBWALKER";
            Rs2Walker.walkTo(target, distance);
            return;
        }
        if (me.distanceTo(target) <= LOCAL_WALK_TILES) {
            route = "FAST";
            Rs2Walker.walkFastCanvas(target);
            return;
        }
        if (crossGapIfNeeded(target)) return;   // agility leg this tick
        route = "WEBWALKER";
        Rs2Walker.walkTo(target, distance);
    }

    /** If the agility stone lies between us and the destination (opposite sides of the gap), cross it. */
    private boolean crossGapIfNeeded(WorldPoint dest) {
        if (!config.useAgilityShortcut() || agilityDisabledThisTrip) return false;
        WorldPoint me = pos();
        if (me == null) return false;
        boolean meSouth = me.getY() < GAP_Y, destSouth = dest.getY() < GAP_Y;
        if (meSouth == destSouth) return false;   // same side — no crossing needed
        WorldPoint anchor = meSouth ? STONE_SOUTH : STONE_NORTH;
        route = "AGILITY";
        if (me.distanceTo(anchor) > 1) {
            if (me.distanceTo(anchor) <= LOCAL_WALK_TILES) Rs2Walker.walkFastCanvas(anchor);
            else Rs2Walker.walkTo(anchor, 0);
            return true;
        }
        log.info("[goat] agility: crossing stepping stone ({})", meSouth ? "S→N" : "N→S");
        if (Rs2GameObject.interact(STEPPING_STONE_ID, "Cross")) {
            boolean crossed = sleepUntil(() -> {
                WorldPoint p = pos();
                return p != null && (p.getY() < GAP_Y) != meSouth;
            }, 6000);
            if (crossed) {
                progress();
                return true;
            }
        }
        log.info("[goat] agility: cross failed — falling back to web-walker for this trip");
        agilityDisabledThisTrip = true;
        recoveries.incrementAndGet();
        return false;
    }

    // --- helpers ---

    private WorldPoint standTileFor(IrkedGoatkillerConfig.StandSide side) {
        switch (side) {
            case EAST: return STAND_EAST;
            case NORTH: return STAND_NORTH;
            case WEST: return STAND_WEST;
            case SOUTH:
            default: return STAND_SOUTH;
        }
    }

    private boolean ensureAtTile() {
        WorldPoint me = pos();
        if (me == null) return false;
        if (me.distanceTo(standTile) <= 1) {
            agilityDisabledThisTrip = false;   // home again
            return true;
        }
        walkStep(standTile, 0);
        return false;
    }

    private WorldPoint pos() {
        return Rs2Player.getWorldLocation();
    }

    private String openPouchName() {
        switch (config.furPouch()) {
            case SMALL: return "Small fur pouch (open)";
            case MEDIUM: return "Medium fur pouch (open)";
            case LARGE: return "Large fur pouch (open)";
            default: return null;
        }
    }

    private PitState readPitStateSafe() {
        return Microbot.getClientThread().invoke(this::readPitState);
    }

    private void setTask(Task t) {
        if (t != task) {
            log.info("[goat] task {} -> {} | pit={} spikes={} horns={} invFull={} sinceLine={}",
                    task, t, pit, Rs2Inventory.count(WOODEN_SPIKES), Rs2Inventory.count(GOAT_HORN),
                    Rs2Inventory.isFull(), grabsSinceLine);
            task = t;
        }
    }

    private void progress() {
        lastProgressMs = System.currentTimeMillis();
    }

    private boolean watchdogTripped() {
        long idle = System.currentTimeMillis() - lastProgressMs;
        if (idle > STUCK_HARD_MS) {
            stop("watchdog: no progress for " + (idle / 1000) + "s in task " + task);
            return true;
        }
        if (idle > STUCK_SOFT_MS && System.currentTimeMillis() - lastSoftWarnMs > 15000) {
            lastSoftWarnMs = System.currentTimeMillis();
            recoveries.incrementAndGet();
            log.info("[goat] WATCHDOG soft stall {}s in {} — re-evaluating (pit={} bankAttempts={})",
                    idle / 1000, task, pit, bankAttempts);
        }
        return false;
    }

    private void stop(String reason) {
        stopReason = reason;
        setTask(Task.STOPPED);
        log.warn("[goat] STOP: {}", reason);
        if (mainScheduledFuture != null) mainScheduledFuture.cancel(false);
    }

    private void humanPause() {
        if (ThreadLocalRandom.current().nextInt(100) < 8) sleep(1400, 2600); else sleep(340, 900);
    }

    public void shutdown() {
        long secs = (System.currentTimeMillis() - startMs) / 1000;
        log.info("[goat] shutdown | runtime={}s grabs={} clears={} fursBanked={} hornsDropped={} task={} stop='{}'",
                secs, grabs.get(), clears.get(), fursBanked.get(), hornsDropped.get(), task, stopReason);
        super.shutdown();
    }
}
