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
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.magic.Rs2Spells;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.npc.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Wyrmscraig goat hunting — reliable, state-driven. The player stands on ONE fixed pit-side tile and NEVER
 * moves to grab a goat: only goats on the opposite side of the pit, within Telegrab range of the player's
 * actual position, are valid (out-of-range casts make the game walk you, so they are rejected up front).
 *
 * Loop: line the pit (one spike) → Telegrab opposite-side goats until full → Clear (harvests fur+horn, breaks
 * spikes) → manage loot → re-line. Fur fills the pouch first (invisible) then overflows to the inventory;
 * horns are dropped only to make room; banking happens only when fur genuinely can't be stored.
 *
 * State is coarse-read from the pit object action (Line=EMPTY, Check=SPIKED, Clear=has goats) and made precise
 * by chat ("you line the pit"=SPIKED, "pit is now filled"=FULL, "replace the spikes"=needs re-line). Clearing
 * always breaks the spikes, so clearPit asserts that. Every interaction is one click → wait → verify, and a
 * watchdog stops the script with a reason instead of spinning.
 */
@Slf4j
public class IrkedGoatkillerScript extends Script {

    private enum PitState { EMPTY, SPIKED, GOATS }
    enum Task { STARTING, GETTING_SPIKES, LINING, HUNTING, CLEARING, DROPPING_HORNS, BANKING, STOPPED }

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

    // The 12 authoritative standing tiles; we use the middle of the chosen side.
    private static final WorldPoint STAND_SOUTH = new WorldPoint(2572, 2193, 0);
    private static final WorldPoint STAND_EAST = new WorldPoint(2574, 2195, 0);
    private static final WorldPoint STAND_NORTH = new WorldPoint(2572, 2197, 0);
    private static final WorldPoint STAND_WEST = new WorldPoint(2570, 2195, 0);

    private static final int MAX_GRAB_DISTANCE = 13;   // Telegrab reaches ~15; stay inside so a cast never walks us
    private static final double ACROSS_MIN = 0.35;     // cosine: goat must sit clearly across the pit from us
    private static final int MAX_PIT_CAPACITY = 24;    // grab-count fallback if the "filled" chat is missed

    private static final long GRAB_COOLDOWN_MS = 8000; // don't re-cast a goat that hasn't been pulled in yet
    private static final long LINE_THROTTLE_MS = 5000; // > line confirm time, so we click Line exactly once
    private static final long TAKE_THROTTLE_MS = 3000;
    private static final long BANK_THROTTLE_MS = 4000;
    private static final long STUCK_SOFT_MS = 60000;   // no progress → log + re-anchor
    private static final long STUCK_HARD_MS = 180000;  // no progress → stop with a reason
    private static final int CAST_FAIL_LIMIT = 6;      // repeated cast failures → out of runes / blocked → stop

    private IrkedGoatkillerConfig config;
    private WorldPoint standTile;

    private volatile PitState pit = PitState.EMPTY;
    private volatile boolean fullSignaled;
    private volatile boolean spikesBroken;
    private volatile int lastChatId = Integer.MIN_VALUE;
    private volatile boolean chatInit;
    private int grabsSinceLine;
    private int castFailStreak;
    private int bankAttempts;
    private long lastTakeMs, lastLineMs, lastBankMs, lastProgressMs, lastSoftWarnMs;

    // Overlay stats.
    final AtomicInteger grabs = new AtomicInteger();
    final AtomicInteger clears = new AtomicInteger();
    final AtomicInteger fursBanked = new AtomicInteger();
    volatile Task task = Task.STARTING;
    volatile String stopReason = "";
    long startMs;

    private final Map<Integer, Long> recentGrabs = new ConcurrentHashMap<>();

    // Selection diagnostics (log only).
    private volatile int diagSeen, diagEligible, diagDist;
    private volatile double diagScore;
    private long lastCastMs;

    public boolean run(IrkedGoatkillerConfig config) {
        this.config = config;
        this.standTile = standTileFor(config.standSide());
        startMs = System.currentTimeMillis();
        lastProgressMs = startMs;
        log.info("[goat] started v1.0.0 | side={} tile={} pouch={} dropHorns={} agility={}",
                config.standSide(), standTile, config.furPouch(), config.dropGoatHorn(), config.useAgilityShortcut());
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
        // Read pit state: object action (coarse) then chat (authoritative), then force re-line if spikes broke.
        PitState live = Microbot.getClientThread().invoke(this::readPitState);
        if (live != null) pit = live;
        Microbot.getClientThread().invoke(this::pollChat);
        if (spikesBroken) pit = PitState.EMPTY;

        if (watchdogTripped()) return;

        boolean invFull = Rs2Inventory.isFull();
        int horns = Rs2Inventory.count(GOAT_HORN);
        boolean full = pit == PitState.GOATS && (fullSignaled || grabsSinceLine >= MAX_PIT_CAPACITY);

        // Inventory decisions come first: never harvest/hunt into a full inventory (fur would drop).
        if (invFull && horns == 0) {           // genuinely no room and nothing disposable → bank
            setTask(Task.BANKING);
            bankFur();
            return;
        }
        if (invFull && horns > 0) {             // make room from junk horns, keep hunting the pit
            setTask(Task.DROPPING_HORNS);
            dropHorns();
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

    /** Client thread. Coarse state from the pit's current menu action (it morphs but keeps id 62343). */
    private PitState readPitState() {
        GameObject pitObj = Rs2GameObject.getGameObject(GOAT_PIT_ID);
        if (pitObj == null) return null;
        if (Rs2GameObject.hasAction(pitObj, "Line")) return PitState.EMPTY;
        if (Rs2GameObject.hasAction(pitObj, "Clear")) return PitState.GOATS;
        if (Rs2GameObject.hasAction(pitObj, "Check")) return PitState.SPIKED;
        return null;
    }

    /** Client thread. Chat is authoritative for the announced transitions. */
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
                progress();
            }
        }
        lastChatId = newMax;
        chatInit = true;
        return Boolean.TRUE;
    }

    // --- LINE: get a spike, stand on the tile, line once ---

    private void lineCycle() {
        if (Rs2Inventory.count(WOODEN_SPIKES) < 1) {
            setTask(Task.GETTING_SPIKES);
            getSpike();
            return;
        }
        setTask(Task.LINING);
        if (!ensureAtTile()) return;   // walk back to our tile first (never toward a goat)
        if (System.currentTimeMillis() - lastLineMs > LINE_THROTTLE_MS) {
            lastLineMs = System.currentTimeMillis();
            log.info("[goat] line pit (one click)");
            Rs2GameObject.interact(GOAT_PIT_ID, "Line");   // "you line the pit" chat confirms → SPIKED next tick
        }
    }

    private void getSpike() {
        WorldPoint me = Rs2Player.getWorldLocation();
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
            if (sleepUntil(() -> Rs2Inventory.count(WOODEN_SPIKES) > had, 3000)) progress();
            else log.info("[goat] spike not acquired within 3s — will retry");
        }
    }

    // --- HUNT: stationary, opposite-side, in-range only ---

    private void huntCycle() {
        if (!ensureAtTile()) return;   // if we ever drifted off our tile, re-anchor (not toward a goat)
        Rs2NpcModel goat = Microbot.getClientThread().invoke(this::selectGoat);
        if (goat == null) {
            sleep(300, 600);   // no valid opposite-side target in range → wait and re-evaluate
            return;
        }
        WorldPoint me = Rs2Player.getWorldLocation();
        // Re-validate on the client thread immediately before casting: still alive AND still in range from where
        // we actually stand (a moved/despawned goat would make the game walk us or build a null-target entry).
        Boolean ok = Microbot.getClientThread().invoke((java.util.function.Supplier<Boolean>) () -> {
            WorldPoint g = goat.getWorldLocation();
            WorldPoint p = Rs2Player.getWorldLocation();
            return goat.getName() != null && g != null && p != null && p.distanceTo(g) <= MAX_GRAB_DISTANCE;
        });
        if (ok == null || !ok) return;

        if (Rs2Magic.castOn(Rs2Spells.TELEKINETIC_GRAB, goat)) {
            long now = System.currentTimeMillis();
            long gap = lastCastMs == 0 ? 0 : now - lastCastMs;
            lastCastMs = now;
            castFailStreak = 0;
            recentGrabs.put(goat.getIndex(), now);
            grabs.incrementAndGet();
            grabsSinceLine++;
            progress();
            log.info("[goat] grab #{} idx={} score={} dist={} eligible={}/{} sinceLine={} pos={},{} gapMs={}",
                    grabs.get(), goat.getIndex(), String.format("%.2f", diagScore), diagDist, diagEligible, diagSeen,
                    grabsSinceLine, me != null ? me.getX() : -1, me != null ? me.getY() : -1, gap);
            if (grabsSinceLine >= MAX_PIT_CAPACITY) fullSignaled = true;
            sleepUntil(() -> Rs2Player.isAnimating() || goat.isMoving(), 1200);   // let the cast register
        } else {
            castFailStreak++;
            log.info("[goat] cast returned false (streak={}) — out of runes / blocked?", castFailStreak);
            if (castFailStreak >= CAST_FAIL_LIMIT) {
                stop("telegrab failed " + castFailStreak + " times in a row (out of runes / spell blocked)");
            }
        }
        humanPause();
    }

    /**
     * Client thread. The idle, un-cooled, unclaimed goat sitting most directly across the pit from us, within
     * Telegrab range of our actual position. Never returns a goat that would require walking to reach.
     */
    private Rs2NpcModel selectGoat() {
        diagSeen = 0;
        diagEligible = 0;
        WorldPoint me = Rs2Player.getWorldLocation();
        if (me == null) return null;
        double dx = PIT_CENTER.getX() - me.getX(), dy = PIT_CENTER.getY() - me.getY();   // us → pit heading
        double dlen = Math.hypot(dx, dy);
        if (dlen < 1) return null;   // standing on the pit — nothing is "across"
        long now = System.currentTimeMillis();
        recentGrabs.values().removeIf(t -> now - t > GRAB_COOLDOWN_MS);

        List<Rs2NpcModel> seen = Rs2Npc.getNpcs(GOAT)
                .filter(g -> g.getWorldLocation() != null)
                .collect(Collectors.toList());
        List<Rs2NpcModel> eligible = seen.stream()
                .filter(g -> me.distanceTo(g.getWorldLocation()) <= MAX_GRAB_DISTANCE)   // in range → no walk
                .filter(g -> !g.isMoving())                                              // already being lured
                .filter(g -> !recentGrabs.containsKey(g.getIndex()))
                .filter(g -> !claimedByOther(g))
                .filter(g -> across(g.getWorldLocation(), dx, dy, dlen) > ACROSS_MIN)     // opposite side of the pit
                .collect(Collectors.toList());
        Rs2NpcModel chosen = eligible.stream()
                .max(Comparator.comparingDouble(g -> across(g.getWorldLocation(), dx, dy, dlen)))
                .orElse(null);

        diagSeen = seen.size();
        diagEligible = eligible.size();
        if (chosen != null) {
            diagScore = across(chosen.getWorldLocation(), dx, dy, dlen);
            diagDist = me.distanceTo(chosen.getWorldLocation());
        }
        return chosen;
    }

    /** Cosine of the angle between (pit→goat) and the (us→pit) heading: ~1 = dead across, ≤0 = same side as us. */
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

    // --- CLEAR: harvest, wait for genuine empty, drop horns per config ---

    private void clearPit() {
        if (!ensureAtTile()) return;
        if (System.currentTimeMillis() - lastLineMs < 1200) return;   // small settle
        if (!Rs2GameObject.interact(GOAT_PIT_ID, "Clear")) {
            log.info("[goat] clear click failed — retrying");
            sleep(300, 600);
            return;
        }
        log.info("[goat] clear pit (grabsSinceLine={} full={})", grabsSinceLine, fullSignaled);
        long start = System.currentTimeMillis();
        sleep(1200, 1800);
        // Harvest is one goat/tick; horns land in the inventory (fur goes to the pouch). Wait for genuine empty:
        // the pit action reverts to Line, OR horns stop arriving and the player is idle.
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
        spikesBroken = true;   // clearing always breaks the spikes → next tick re-lines
        fullSignaled = false;
        grabsSinceLine = 0;
        recentGrabs.clear();
        progress();
        log.info("[goat] harvest done via {} in {}ms | clears={} horns={} invFur={}",
                why, System.currentTimeMillis() - start, clears.get(),
                Rs2Inventory.count(GOAT_HORN), Rs2Inventory.count(GOAT_FUR));
    }

    /** Drop horns to free room for more fur. One drop → short human beat → verify count fell. */
    private void dropHorns() {
        if (!config.dropGoatHorn()) {
            // Config says keep horns, but the inventory is full of them with no fur room → nothing to do but bank.
            setTask(Task.BANKING);
            bankFur();
            return;
        }
        int had = Rs2Inventory.count(GOAT_HORN);
        log.info("[goat] inventory full — dropping {} horns to make room", had);
        int guard = 40;
        while (Rs2Inventory.count(GOAT_HORN) > 0 && guard-- > 0) {
            int before = Rs2Inventory.count(GOAT_HORN);
            Rs2Inventory.drop(GOAT_HORN);
            sleepUntil(() -> Rs2Inventory.count(GOAT_HORN) < before, 1500);   // one drop → wait → verify
            if (ThreadLocalRandom.current().nextInt(100) < 15) sleep(650, 1500); else sleep(160, 460);
        }
        progress();
        log.info("[goat] dropped horns {} -> {}", had, Rs2Inventory.count(GOAT_HORN));
    }

    // --- BANK: only when fur genuinely can't be stored ---

    private void bankFur() {
        WorldPoint me = Rs2Player.getWorldLocation();
        if (me == null) return;
        if (me.distanceTo(BANK_TILE) > 4) {
            if (config.useAgilityShortcut()) tryCrossStone();
            walkStep(BANK_TILE, 3);
            return;
        }
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
        // Return is handled next tick: lining/hunting needs our tile, which walks us back.
    }

    /** Best-effort agility assist: only Cross when the route already put us next to a stone anchor. */
    private void tryCrossStone() {
        WorldPoint me = Rs2Player.getWorldLocation();
        if (me == null) return;
        if (me.distanceTo(STONE_NORTH) <= 1 || me.distanceTo(STONE_SOUTH) <= 1) {
            log.info("[goat] agility: crossing stepping stone");
            if (Rs2GameObject.interact(STEPPING_STONE_ID, "Cross")) sleep(1500, 2500);
        }
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

    /** True when we're on (or within 1 tile of) our standing tile; otherwise walk back (never toward a goat). */
    private boolean ensureAtTile() {
        WorldPoint me = Rs2Player.getWorldLocation();
        if (me == null) return false;
        if (me.distanceTo(standTile) <= 1) return true;   // range/side use the real position, so 1 tile is fine
        walkStep(standTile, 0);
        return false;
    }

    private void walkStep(WorldPoint target, int distance) {
        Rs2Walker.walkTo(target, distance);
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

    /** Anti-stuck: re-anchor on a soft stall, stop with a reason on a hard stall. */
    private boolean watchdogTripped() {
        long idle = System.currentTimeMillis() - lastProgressMs;
        if (idle > STUCK_HARD_MS) {
            stop("watchdog: no progress for " + (idle / 1000) + "s in task " + task);
            return true;
        }
        if (idle > STUCK_SOFT_MS && System.currentTimeMillis() - lastSoftWarnMs > 15000) {
            lastSoftWarnMs = System.currentTimeMillis();
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
        log.info("[goat] shutdown | runtime={}s grabs={} clears={} fursBanked={} task={} stopReason='{}'",
                secs, grabs.get(), clears.get(), fursBanked.get(), task, stopReason);
        super.shutdown();
    }
}
