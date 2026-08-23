package net.runelite.client.plugins.microbot.irkedgoatkiller;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameObject;
import net.runelite.api.IterableHashTable;
import net.runelite.api.MessageNode;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.LocalPoint;
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
    static final String GOAT = "Wyrmscraig Goat";
    private static final String WOODEN_SPIKES = "Wooden spikes";
    private static final String GOAT_HORN = "Goat horn";
    private static final String GOAT_FUR = "Wyrmscraig goat fur";

    static final WorldPoint PIT_CENTER = new WorldPoint(2572, 2195, 0);
    private static final WorldPoint SPIKES_TILE = new WorldPoint(2578, 2202, 0);
    private static final WorldPoint BANK_TILE = new WorldPoint(2587, 2259, 0);
    private static final WorldPoint STONE_NORTH = new WorldPoint(2565, 2221, 0);
    private static final WorldPoint STONE_SOUTH = new WorldPoint(2565, 2217, 0);
    private static final int GAP_Y = 2219;   // the stepping-stone gap; south < GAP_Y < north

    // Each side has 3 standing tiles; we pick one at random each time we return, for variety.
    private static final WorldPoint[] STAND_SOUTH = {
            new WorldPoint(2571, 2193, 0), new WorldPoint(2572, 2193, 0), new WorldPoint(2573, 2193, 0) };
    private static final WorldPoint[] STAND_EAST = {
            new WorldPoint(2574, 2194, 0), new WorldPoint(2574, 2195, 0), new WorldPoint(2574, 2196, 0) };
    private static final WorldPoint[] STAND_NORTH = {
            new WorldPoint(2571, 2197, 0), new WorldPoint(2572, 2197, 0), new WorldPoint(2573, 2197, 0) };
    private static final WorldPoint[] STAND_WEST = {
            new WorldPoint(2570, 2194, 0), new WorldPoint(2570, 2195, 0), new WorldPoint(2570, 2196, 0) };

    // True castable range is ~10 tiles (standard Telegrab), measured STRAIGHT-LINE (Euclidean) — proven empirically:
    // Chebyshev-9 walked (a diagonal goat at Chebyshev 9 is Euclidean ~12.7 > 10) and Euclidean-13 walked (cardinal
    // goats 11-13 > 10). Euclidean <= 9 is the safe cap: no goat, cardinal or diagonal, is ever beyond the real range,
    // so the game never walks us to it. Do NOT raise toward "15" — that number is wrong; anything > 10 walks.
    static final int MAX_GRAB_DISTANCE = 9;
    private static final int MOVING_BUFFER = 1; // moving goats need 1 extra tile of margin (they may step once mid-cast)
    // The pit is a 3x3 object (base 2571,2194 → tiles centred on PIT_CENTER ±1). A goat only drops in if the straight
    // line it's lured along — from its tile to ours — passes through the pit (OSRS wiki: "be on the opposite side of
    // the pit so the goat is lured toward you, into the pit"). See pullCrossesPit(); replaces the old cosine cone.
    private static final int PIT_HALF = 1;
    /** Pit capacity by Hunter level (OSRS): 60-68→16, 69-76→18, 77-84→20, 85-92→22, 93-99→24. */
    private static int capacityFor(int hunterLevel) {
        if (hunterLevel >= 93) return 24;
        if (hunterLevel >= 85) return 22;
        if (hunterLevel >= 77) return 20;
        if (hunterLevel >= 69) return 18;
        return 16;
    }
    private static final int LOCAL_WALK_TILES = 16;    // within this, canvas-walk instead of the web-walker

    private static final long GRAB_COOLDOWN_MS = 8000;
    private static final long TAKE_THROTTLE_MS = 3000;
    private static final long BANK_THROTTLE_MS = 4000;
    private static final long STUCK_SOFT_MS = 60000;
    private static final long STUCK_HARD_MS = 180000;
    private static final long IDLE_HARVEST_MS = 30000;   // hunt idle this long with goats in the pit → harvest + reline
    private static final int CAST_FAIL_LIMIT = 6;

    IrkedGoatkillerConfig config;   // package-private: the overlay reads config flags
    volatile WorldPoint standTile;

    private volatile PitState pit = PitState.EMPTY;
    private volatile boolean fullSignaled;
    private volatile boolean spikesBroken;
    private volatile int lastChatId = Integer.MIN_VALUE;
    private volatile boolean chatInit;
    private boolean agilityDisabledThisTrip;
    private boolean returnCommitted;   // true once we've picked a stand tile for the current return trip
    volatile int grabsSinceLine;       // goats put into the pit since the last line (≈ current pit fill)
    volatile int pitCapacity = 24;     // real capacity for the player's Hunter level (set at start)
    private int castFailStreak;
    private int bankAttempts;
    private long lastTakeMs, lastBankMs, lastProgressMs, lastSoftWarnMs;

    // Hunter XP tracking for the overlay (captured at start, on the client thread).
    volatile int startHunterXp;
    volatile int startHunterLevel;

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
    volatile int targetIndex = -1;       // index of the goat we're currently targeting (scene overlay)
    volatile String stopReason = "";
    volatile String debugSummary = "";   // per-goat target breakdown (debug overlay only)
    long startMs;

    private final Map<Integer, Long> recentGrabs = new ConcurrentHashMap<>();
    // How many times we've cast on each goat this fill. A goat that pots despawns after one grab; if the same index
    // keeps coming back it isn't potting (pull lands beside the pit) — blacklist it so we don't lock onto it.
    // Only *quick* repeats (within STUCK_WINDOW_MS) count: RS recycles NPC indices, so a fresh goat that inherits a
    // potted goat's index must NOT inherit its count, or it gets falsely flagged STUCK. lastGrabAt gates that.
    private final Map<Integer, Integer> grabCount = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastGrabAt = new ConcurrentHashMap<>();
    private static final int MAX_GRABS_PER_GOAT = 2;
    private static final long STUCK_WINDOW_MS = 20000;   // repeat grab beyond this = new goat on a reused index, not stuck
    private volatile int diagDist, diagSeen;
    private long lastCastMs;

    public boolean run(IrkedGoatkillerConfig config) {
        this.config = config;
        this.standTile = standTileFor(config.standSide());
        startMs = System.currentTimeMillis();
        lastProgressMs = startMs;
        Microbot.getClientThread().invoke(() -> {
            startHunterXp = Microbot.getClient().getSkillExperience(Skill.HUNTER);
            startHunterLevel = Microbot.getClient().getRealSkillLevel(Skill.HUNTER);
            pitCapacity = capacityFor(startHunterLevel);
        });
        log.info("[goat] started v1.2.2 | side={} tile={} pouch={} fur={} horn={} agility={}",
                config.standSide(), standTile, config.furPouch(), config.furAction(), config.hornAction(),
                config.useAgilityShortcut());
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn() || !super.run() || task == Task.STOPPED) return;
                tick();
            } catch (Exception ex) {
                if (isInterruption(ex)) {          // plugin stopped/reloaded mid-tick — expected, not an error
                    Thread.currentThread().interrupt();
                    log.info("[goat] tick interrupted — stopping");
                } else {
                    log.error("[goat] tick error", ex);
                }
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }

    private void tick() {
        PitState live = Microbot.getClientThread().invoke(this::readPitState);
        if (live != null) pit = live;
        Microbot.getClientThread().invoke(this::pollChat);
        // A pit offering "Clear" (GOATS) still holds this fill's furs+horns — that object read is ground truth and
        // MUST win: never let the spikesBroken/"replace the spikes" chat flag skip a pending harvest (it did, sending
        // us to fetch spikes with the loot still in the pit). Only trust the flag to force EMPTY once Clear is gone.
        if (live == PitState.GOATS) pit = PitState.GOATS;
        else if (spikesBroken) pit = PitState.EMPTY;
        pitLabel = pit.name();

        if (watchdogTripped()) return;

        boolean invFull = Rs2Inventory.isFull();
        boolean full = pit == PitState.GOATS && (fullSignaled || grabsSinceLine >= pitCapacity);

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
            // If the harvest won't fit, drop disposables first so it lands cleanly (the pit won't revert to EMPTY
            // while remains can't be picked up). Never BANK here — banking belongs only to a genuinely full
            // inventory (handled above), not to "a bit tight before a clear".
            if (hasDisposables() && Rs2Inventory.emptySlotCount() < pitCapacity) {
                setTask(Task.MANAGING_LOOT);
                makeRoom();
                return;
            }
            setTask(Task.CLEARING);
            clearPit();
            return;
        }
        if (pit == PitState.EMPTY) {
            // Just harvested → dump disposables in one batch before re-lining, so we hunt with a lean inventory
            // (best XP/hr in drop-everything mode; nothing to carry, no mid-harvest fill next cycle).
            if (hasDisposables()) {
                setTask(Task.MANAGING_LOOT);
                makeRoom();
                return;
            }
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
                resetFillTracking();
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
        // One click → wait → verify. Block here until the pit reads SPIKED so we never re-click a pit that's
        // already being lined. If it doesn't confirm, the next tick tries once more (no rapid spam).
        log.info("[goat] line pit (one click)");
        Rs2GameObject.interact(GOAT_PIT_ID, "Line");
        if (sleepUntil(() -> readPitStateSafe() == PitState.SPIKED, 9000)) {
            spikesBroken = false;
            pit = PitState.SPIKED;
            grabsSinceLine = 0;
            fullSignaled = false;
            resetFillTracking();
            relines.incrementAndGet();
            progress();
            log.info("[goat] lined confirmed → SPIKED");
        } else {
            log.info("[goat] line not confirmed in 9s — will retry");
        }
    }

    private void getSpike() {
        if (Rs2Inventory.count(WOODEN_SPIKES) >= 1) return;
        if (System.currentTimeMillis() - lastTakeMs < TAKE_THROTTLE_MS) return;   // let the last click resolve
        final int had = Rs2Inventory.count(WOODEN_SPIKES);
        // Just click the supply — the game walks us there and Takes in one action. No separate walk-to-tile step.
        if (Rs2GameObject.interact(SPIKES_SUPPLY_ID, "Take")) {
            lastTakeMs = System.currentTimeMillis();
            log.info("[goat] take spike (one click on supply, have {})", had);
            if (sleepUntil(() -> Rs2Inventory.count(WOODEN_SPIKES) > had, 8000)) {
                spikesTaken.incrementAndGet();
                progress();
            } else {
                log.info("[goat] spike not acquired within 8s — will retry");
            }
        } else {
            // Supply isn't in the loaded scene (e.g. just came back from the bank) — get near, then click next tick.
            walkStep(SPIKES_TILE, 5);
        }
    }

    // --- HUNT ---

    private void huntCycle() {
        if (!ensureAtTile()) return;
        Rs2NpcModel goat = Microbot.getClientThread().invoke(this::selectGoat);
        if (goat == null) {
            // No grabbable goat right now. If we've already put some in the pit but can't find more for a while
            // (dead/contested spot, or a stuck goat we've blacklisted), harvest what we have and start a fresh
            // fill — which also re-rolls the stand tile — rather than idling until the watchdog.
            if (grabsSinceLine >= 1 && lastCastMs > 0 && System.currentTimeMillis() - lastCastMs > IDLE_HARVEST_MS) {
                log.info("[goat] hunt idle {}s with {} in pit — harvesting partial fill to recover",
                        IDLE_HARVEST_MS / 1000, grabsSinceLine);
                fullSignaled = true;   // next tick → CLEAR → drop → reline → fresh hunt
            }
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
            // Only a *quick* repeat counts toward "stuck". A slow repeat is a new goat reusing the index → reset to 1.
            long prevGrab = lastGrabAt.getOrDefault(goat.getIndex(), 0L);
            if (now - prevGrab <= STUCK_WINDOW_MS) grabCount.merge(goat.getIndex(), 1, Integer::sum);
            else grabCount.put(goat.getIndex(), 1);
            lastGrabAt.put(goat.getIndex(), now);
            grabs.incrementAndGet();
            grabsSinceLine++;
            progress();
            log.info("[goat] grab #{} idx={} dist={} valid={}/{} sinceLine={} pos={},{} gapMs={}",
                    grabs.get(), goat.getIndex(), diagDist, validGoats, diagSeen,
                    grabsSinceLine, me != null ? me.getX() : -1, me != null ? me.getY() : -1, gap);
            if (grabsSinceLine >= pitCapacity) fullSignaled = true;
            sleepUntil(() -> Rs2Player.isAnimating() || goat.isMoving(), 1200);
        } else if (result < 0) {
            castFailStreak++;
            recentGrabs.put(goat.getIndex(), System.currentTimeMillis());   // don't hammer a goat whose click failed
            // Out of Telegrab runes is terminal — log out and stop, don't spin casting/banking forever.
            if (!Microbot.getClientThread().invoke(
                    (Supplier<Boolean>) () -> Rs2Magic.canCast(Rs2Spells.TELEKINETIC_GRAB))) {
                stopAndLogout("out of Telegrab runes");
            } else {
                log.info("[goat] cast failed (streak={}) — spell blocked?", castFailStreak);
                if (castFailStreak >= CAST_FAIL_LIMIT) {
                    stop("telegrab failed " + castFailStreak + " times in a row (spell blocked)");
                }
            }
        }
        // No extra pause here: a successful grab already waited for the cast to start, and the no-target/abort
        // paths pause themselves. The old trailing humanPause() double-waited and was the main telegrab slowdown.
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
        sleep(200, 550);   // brief human reaction between selecting the spell and clicking the target
        Boolean ok = Microbot.getClientThread().invoke((Supplier<Boolean>) () -> {
            WorldPoint g = goat.getWorldLocation();
            WorldPoint p = Rs2Player.getWorldLocation();
            // Moving goats are fine to grab — but re-check range HERE (right before the click) so one that drifted
            // out during the reaction is dropped rather than walked to. Still bail if another player claimed it.
            if (goat.getName() == null || g == null || p == null || euclid(p, g) > grabCap(goat)) return false;
            if (claimedByOther(goat)) return false;
            if (!Rs2Camera.isTileOnScreen(goat.getLocalLocation())) Rs2Camera.turnTo(goat.getLocalLocation());
            return true;
        });
        if (ok == null || !ok) return 0;   // goat moved/despawned/taken during the reaction → abort, keep spell selected
        return Rs2Npc.interact(goat) ? 1 : -1;
    }

    private Rs2NpcModel selectGoat() {
        diagSeen = 0;
        validGoats = 0;
        WorldPoint me = pos();
        if (me == null) return null;
        long now = System.currentTimeMillis();
        recentGrabs.values().removeIf(t -> now - t > GRAB_COOLDOWN_MS);
        lastGrabAt.values().removeIf(t -> now - t > STUCK_WINDOW_MS);   // decay stuck-tracking so reused indices reset
        grabCount.keySet().retainAll(lastGrabAt.keySet());

        List<Rs2NpcModel> seen = Rs2Npc.getNpcs(GOAT)
                .filter(g -> g.getWorldLocation() != null)
                .collect(Collectors.toList());
        List<Rs2NpcModel> eligible = seen.stream()
                .filter(g -> euclid(me, g.getWorldLocation()) <= grabCap(g))               // straight-line range (moving: -1 tile)
                .filter(g -> !recentGrabs.containsKey(g.getIndex()))
                .filter(g -> grabCount.getOrDefault(g.getIndex(), 0) < MAX_GRABS_PER_GOAT)   // not a stuck goat
                .filter(g -> !claimedByOther(g))
                .filter(g -> pullCrossesPit(g.getWorldLocation(), me))                     // lure line drops it into the pit
                .collect(Collectors.toList());
        // Nearest valid goat: least travel for the lure (fewer ticks to land) and least chance of drifting out of range.
        Rs2NpcModel chosen = eligible.stream()
                .min(Comparator.comparingDouble(g -> euclid(me, g.getWorldLocation())))
                .orElse(null);

        diagSeen = seen.size();
        validGoats = eligible.size();
        targetIndex = chosen != null ? chosen.getIndex() : -1;
        if (chosen != null) diagDist = me.distanceTo(chosen.getWorldLocation());
        if (config.debugOverlay()) buildDebugSummary(seen, chosen, me);
        return chosen;
    }

    /** Live status of a goat from the player's current position — for the scene overlay (runs on the client thread). */
    String goatStatus(Rs2NpcModel g) {
        WorldPoint me = pos(), wp = g.getWorldLocation();
        if (me == null || wp == null) return "?";
        return goatReason(g, me);
    }

    /** Why each nearby goat is a target or not — same checks the selector uses, surfaced for the debug overlay.
     *  Only built when the debug overlay is on, so the normal loop stays cheap. */
    private void buildDebugSummary(List<Rs2NpcModel> seen, Rs2NpcModel chosen, WorldPoint me) {
        StringBuilder sb = new StringBuilder();
        sb.append(chosen != null
                ? "TARGET idx=" + chosen.getIndex() + " d=" + me.distanceTo(chosen.getWorldLocation()) + " → TELEGRAB"
                : "NO TARGET (" + seen.size() + " seen)");
        seen.stream()
                .sorted(Comparator.comparingInt(g -> me.distanceTo(g.getWorldLocation())))
                .limit(8)
                .forEach(g -> sb.append('\n')
                        .append(g == chosen ? "▶ " : "  ")
                        .append('#').append(g.getIndex())
                        .append(" d=").append(me.distanceTo(g.getWorldLocation()))
                        .append(' ').append(goatReason(g, me)));
        debugSummary = sb.toString();
    }

    /** First failing check (priority-ordered), or VALID. Mirrors the selectGoat filters. */
    private String goatReason(Rs2NpcModel g, WorldPoint me) {
        WorldPoint wp = g.getWorldLocation();
        if (wp.getPlane() != me.getPlane()) return "WRONG_PLANE";
        if (claimedByOther(g)) return "OTHER_PLAYER";
        if (euclid(me, wp) > grabCap(g)) return "OUT_OF_RANGE";
        if (!pullCrossesPit(wp, me)) return "WRONG_SIDE";
        if (grabCount.getOrDefault(g.getIndex(), 0) >= MAX_GRABS_PER_GOAT) return "STUCK";
        if (recentGrabs.containsKey(g.getIndex())) return "COOLDOWN";
        return g.isMoving() ? "VALID_MOV" : "VALID";
    }

    /** Max grab distance for a goat: moving goats get a tighter cap so a step mid-cast keeps them in real range. */
    private static int grabCap(Rs2NpcModel g) {
        return g.isMoving() ? MAX_GRAB_DISTANCE - MOVING_BUFFER : MAX_GRAB_DISTANCE;
    }

    /** Straight-line (Euclidean) distance, same plane only. distanceTo() is Chebyshev, which lets a diagonal goat at
     *  "distance 9" actually sit ~12.7 tiles away — an out-of-range cast that walks us. MAX_VALUE for cross-plane. */
    public static double euclid(WorldPoint a, WorldPoint b) {
        if (a.getPlane() != b.getPlane()) return Double.MAX_VALUE;
        double dx = a.getX() - b.getX(), dy = a.getY() - b.getY();
        return Math.hypot(dx, dy);
    }

    /** True when the goat, lured in a straight line to the player, is dragged through the pit and drops in — i.e. the
     *  pit sits between goat and player. Correct for any stand side; no cosine threshold, no tilt from our stand tile. */
    public static boolean pullCrossesPit(WorldPoint goat, WorldPoint me) {
        return segmentIntersectsBox(me.getX(), me.getY(), goat.getX(), goat.getY(),
                PIT_CENTER.getX() - PIT_HALF, PIT_CENTER.getY() - PIT_HALF,
                PIT_CENTER.getX() + PIT_HALF, PIT_CENTER.getY() + PIT_HALF);
    }

    /** Liang–Barsky segment vs axis-aligned box: does segment (x0,y0)->(x1,y1) touch [xmin,ymin]..[xmax,ymax]? */
    static boolean segmentIntersectsBox(double x0, double y0, double x1, double y1,
                                        double xmin, double ymin, double xmax, double ymax) {
        double dx = x1 - x0, dy = y1 - y0;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {x0 - xmin, xmax - x0, y0 - ymin, ymax - y0};
        double u1 = 0, u2 = 1;
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) return false;          // parallel and outside this slab
            } else {
                double t = q[i] / p[i];
                if (p[i] < 0) { if (t > u2) return false; if (t > u1) u1 = t; }
                else          { if (t < u1) return false; if (t < u2) u2 = t; }
            }
        }
        return u1 <= u2;
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
        if (!Rs2GameObject.interact(GOAT_PIT_ID, "Clear")) {
            log.info("[goat] clear click failed — retrying");
            sleep(300, 600);
            return;
        }
        log.info("[goat] clear pit (grabsSinceLine={} full={})", grabsSinceLine, fullSignaled);
        long start = System.currentTimeMillis();
        sleep(1200, 1800);
        // Harvest is one goat per tick and the player does NOT animate continuously, so any "stopped moving"
        // heuristic fires mid-harvest — which made us drop junk / resume hunting before the pit was empty.
        // The ONLY reliable completion signal is the pit reverting to EMPTY (Line action back = spikes broken,
        // all remains collected). Wait for exactly that (30s safety timeout for a full 24-goat pit).
        long deadline = System.currentTimeMillis() + 30000;
        String why = "timeout";
        while (System.currentTimeMillis() < deadline) {
            if (readPitStateSafe() == PitState.EMPTY) { why = "obj-empty"; break; }
            sleep(600);
        }
        sleep(500, 900);
        clears.incrementAndGet();
        spikesBroken = true;
        fullSignaled = false;
        grabsSinceLine = 0;
        resetFillTracking();
        progress();
        log.info("[goat] harvest done via {} in {}ms | clears={} horns={} invFur={}",
                why, System.currentTimeMillis() - start, clears.get(),
                Rs2Inventory.count(GOAT_HORN), Rs2Inventory.count(GOAT_FUR));
    }

    // --- INVENTORY POLICY ---

    /** Loot the player wants dropped (per-item Bank/Drop choice). If both are dropped it never banks. */
    private List<String> disposableNames() {
        List<String> d = new ArrayList<>();
        if (config.hornAction() == IrkedGoatkillerConfig.LootAction.DROP) d.add(GOAT_HORN);
        if (config.furAction() == IrkedGoatkillerConfig.LootAction.DROP) d.add(GOAT_FUR);
        return d;
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
        log.info("[goat] inventory full — dropping {} to make room", disposableNames());
        Rs2Inventory.dropAll(disposableNames().toArray(new String[0]));
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
        if (!ensurePouch()) return;   // withdraw the configured pouch if we're missing it (or stop if unavailable)
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
            // Bring the tile on-screen so walkFastCanvas clicks the actual tile (not the minimap fallback).
            LocalPoint lp = LocalPoint.fromWorld(Microbot.getClient().getTopLevelWorldView(), target);
            if (lp != null && !Rs2Camera.isTileOnScreen(lp)) Rs2Camera.turnTo(lp);
            Rs2Walker.walkFastCanvas(target);
            // walkFastCanvas is fire-and-forget — block until we've actually arrived AND stopped moving,
            // so callers never fire a Take/Line/Clear click mid-walk (the "misclick"). Wait until here.
            int arrive = Math.max(distance, 1);
            sleepUntil(() -> {
                WorldPoint p = pos();
                return p != null && p.distanceTo(target) <= arrive && !Rs2Player.isMoving();
            }, 5000);
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

    /** A random one of the chosen side's 3 standing tiles. */
    private WorldPoint standTileFor(IrkedGoatkillerConfig.StandSide side) {
        WorldPoint[] tiles;
        switch (side) {
            case EAST: tiles = STAND_EAST; break;
            case NORTH: tiles = STAND_NORTH; break;
            case WEST: tiles = STAND_WEST; break;
            case SOUTH:
            default: tiles = STAND_SOUTH; break;
        }
        return tiles[ThreadLocalRandom.current().nextInt(tiles.length)];
    }

    private boolean ensureAtTile() {
        WorldPoint me = pos();
        if (me == null) return false;
        if (me.distanceTo(standTile) <= 1) {
            agilityDisabledThisTrip = false;   // home again
            returnCommitted = false;           // next time we leave, pick a fresh tile
            return true;
        }
        if (!returnCommitted) {                // starting a fresh return → roll a new tile for this side
            standTile = standTileFor(config.standSide());
            returnCommitted = true;
        }
        walkStep(standTile, 0);
        return false;
    }

    private WorldPoint pos() {
        return Rs2Player.getWorldLocation();
    }

    private String openPouchName() {
        String base = pouchName();
        return base == null ? null : base + " (open)";
    }

    /** Base (closed) name of the configured pouch — matches both the closed and "(open)" variants by substring. */
    private String pouchName() {
        switch (config.furPouch()) {
            case SMALL: return "Small fur pouch";
            case MEDIUM: return "Medium fur pouch";
            case LARGE: return "Large fur pouch";
            default: return null;   // NONE
        }
    }

    /**
     * Make sure the configured fur pouch is in the inventory before we rely on it. Runs at the bank, where we can
     * withdraw one. Already-present (open or closed) → nothing to do; missing but in the bank → withdraw + verify;
     * missing everywhere → clean stop (don't silently hunt without the pouch the user asked for).
     */
    private boolean ensurePouch() {
        String name = pouchName();
        if (name == null) return true;                       // NONE selected
        if (Rs2Inventory.hasItem(name)) return true;         // already carrying it
        if (!Rs2Bank.hasBankItem(name)) {
            stop("configured fur pouch '" + name + "' is not in the inventory or bank");
            return false;
        }
        log.info("[goat] fur pouch missing — withdrawing {}", name);
        Rs2Bank.withdrawItem(name);
        if (!sleepUntil(() -> Rs2Inventory.hasItem(name), 3000)) {
            stop("failed to withdraw fur pouch '" + name + "'");
            return false;
        }
        Rs2Inventory.interact(name, "Open");   // fur only auto-stores in an OPEN pouch
        return true;
    }

    private PitState readPitStateSafe() {
        return Microbot.getClientThread().invoke(this::readPitState);
    }

    /** True if this throwable is (or wraps) a thread interruption — a normal stop/reload, not a bug. */
    private static boolean isInterruption(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof InterruptedException) return true;
            String m = c.getMessage();
            if (m != null && m.contains("Interrupted waiting for client thread")) return true;
        }
        return Thread.currentThread().isInterrupted();
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

    /** Clear all per-fill goat tracking. One place so the three maps never drift out of sync across reset sites. */
    private void resetFillTracking() {
        recentGrabs.clear();
        grabCount.clear();
        lastGrabAt.clear();
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

    /** Terminal failure that shouldn't leave the account logged in (e.g. out of runes): log out, then stop. */
    private void stopAndLogout(String reason) {
        log.warn("[goat] TERMINAL: {} — logging out and stopping", reason);
        Rs2Player.logout();
        stop(reason);
    }

    public void shutdown() {
        long secs = (System.currentTimeMillis() - startMs) / 1000;
        log.info("[goat] shutdown | runtime={}s grabs={} clears={} fursBanked={} hornsDropped={} task={} stop='{}'",
                secs, grabs.get(), clears.get(), fursBanked.get(), hornsDropped.get(), task, stopReason);
        super.shutdown();
    }
}
