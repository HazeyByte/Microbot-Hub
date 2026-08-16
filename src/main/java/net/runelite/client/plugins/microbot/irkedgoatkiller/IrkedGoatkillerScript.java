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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Wyrmscraig goat hunting (Hunter 60+, Sheep Herder). One fill cycle: take a spike (only when out) →
 * Line the pit ONCE → Telekinetic-Grab goats on the far side of the pit until it fills → Clear (this
 * harvests fur + horn AND breaks the spikes) → drop horns, keep fur → re-line → repeat. Bank when full.
 *
 * State is read straight off the pit object every tick ({@link #readPitState}). The pit keeps object id
 * 62343 in every state, but its menu action does not — Line (empty) → Check (spiked, no goats) →
 * Clear (one or more goats). That action IS the state, so we never have to guess or force a transition.
 * Chat is used only as the "pit is now filled" signal, with a grab-count fallback if that line is missed.
 *
 * Grabbing: stand anywhere by the pit; a goat is lured across the pit toward the player, so we target the
 * nearest goat with the pit between us and it. Goats already moving (being lured) or on cooldown are
 * skipped, so we never re-cast the same goat. Selection + object reads run on the client thread.
 */
@Slf4j
public class IrkedGoatkillerScript extends Script {

    private enum PitState { EMPTY, SPIKED, GOATS }

    private static final String GOAT_PIT = "Goat Pit";
    private static final String SPIKES_SUPPLY = "Spikes supply";
    private static final String GOAT = "Wyrmscraig Goat";
    private static final String WOODEN_SPIKES = "Wooden spikes";
    private static final String GOAT_HORN = "Goat horn";
    private static final String GOAT_FUR = "Wyrmscraig goat fur";
    private static final String LARGE_FUR_POUCH = "Large fur pouch";

    private static final WorldPoint PIT_TILE = new WorldPoint(2571, 2194, 0);
    private static final WorldPoint SPIKES_TILE = new WorldPoint(2578, 2202, 0);
    private static final WorldPoint BANK_TILE = new WorldPoint(2587, 2260, 0);
    private static final int PIT_HUNT_RANGE = 5;

    private static final long GRAB_COOLDOWN_MS = 10000;  // remember clicked goats long enough to not re-cast one
    private static final int MAX_PIT_CAPACITY = 24;      // fallback FULL if the chat line is missed (lvl 99 cap)
    private static final int TELEGRAB_RANGE = 14;        // Telekinetic Grab reaches 15 tiles; stay just inside

    private volatile PitState pit = PitState.EMPTY;
    private volatile boolean fullSignaled;               // chat: "the pit is now filled with goats"
    private volatile boolean spikesBroken;               // chat/clear: pit needs re-lining before it holds goats
    private volatile int lastChatId = Integer.MIN_VALUE;
    private volatile boolean chatInit;
    private int grabsSinceLine;
    private long lastTakeMs;   // throttle Take clicks on the spikes supply so we grab exactly one
    private long lastLineMs;   // throttle Line clicks on the pit

    // Stats for the overlay.
    final AtomicInteger grabs = new AtomicInteger();
    final AtomicInteger clears = new AtomicInteger();
    final AtomicInteger fursBanked = new AtomicInteger();
    volatile String state = "idle";
    long startMs;

    private final Map<Integer, Long> recentGrabs = new ConcurrentHashMap<>();

    public boolean run(IrkedGoatkillerConfig config) {
        startMs = System.currentTimeMillis();
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn() || !super.run()) return;

                // Chat is authoritative for the transitions the game announces (lined / filled / spikes broken);
                // the pit object is a helpful assist but its morphing action reads unreliably, so it can't be
                // trusted alone. Read the object first, let chat correct it, then hard-force a re-line if the
                // spikes are known-broken — that's the recovery for "you need to replace the spikes".
                PitState live = Microbot.getClientThread().invoke(this::readPitState);
                if (live != null) pit = live;
                Microbot.getClientThread().invoke(this::pollChat);
                if (spikesBroken) pit = PitState.EMPTY;

                boolean full = pit == PitState.GOATS && (fullSignaled || grabsSinceLine >= MAX_PIT_CAPACITY);

                // Bank before harvesting into a full inventory, or the fur would drop on the ground.
                if (Rs2Inventory.isFull()) {
                    state = "banking";
                    bankFur();
                    return;
                }
                if (full) {
                    state = "clearing";
                    clearPit(config);
                    return;
                }
                if (pit == PitState.EMPTY) {
                    state = "lining pit";
                    ensureLined();
                    return;
                }
                state = "hunting";
                huntGoat();
            } catch (Exception ex) {
                log.error("irkedGoatkiller tick error", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }

    /** Client thread. State = the pit's current menu action (it morphs but keeps id 62343). */
    private PitState readPitState() {
        GameObject pitObj = Rs2GameObject.getGameObject(GOAT_PIT);
        if (pitObj == null) return null;   // out of range / not rendered → keep last known
        if (Rs2GameObject.hasAction(pitObj, "Line")) return PitState.EMPTY;
        if (Rs2GameObject.hasAction(pitObj, "Clear")) return PitState.GOATS;
        if (Rs2GameObject.hasAction(pitObj, "Check")) return PitState.SPIKED;
        return null;
    }

    /**
     * Client thread. The game announces every state change we care about, so chat drives the FSM:
     *   "you line the pit…"          → freshly SPIKED, start a new fill
     *   "pit is now filled with goats" → FULL, go clear it
     *   "…replace the spikes…"        → spikes broken, must re-line (recovery if the object read missed it)
     */
    private Boolean pollChat() {
        IterableHashTable<MessageNode> msgs = Microbot.getClient().getMessages();
        if (msgs == null) return Boolean.TRUE;
        int newMax = lastChatId;
        for (MessageNode n : msgs) {
            int id = n.getId();
            if (id > newMax) newMax = id;
            if (!chatInit || id <= lastChatId) continue;   // skip history on first poll
            ChatMessageType t = n.getType();
            if (t != ChatMessageType.GAMEMESSAGE && t != ChatMessageType.SPAM && t != ChatMessageType.MESBOX) {
                continue;
            }
            String m = n.getValue().toLowerCase();
            if (m.contains("pit is now filled with goats")) {
                fullSignaled = true;
                pit = PitState.GOATS;
            } else if (m.contains("replace the spikes")) {
                spikesBroken = true;
                pit = PitState.EMPTY;
            } else if (m.contains("you line the pit")) {
                spikesBroken = false;
                pit = PitState.SPIKED;
                grabsSinceLine = 0;
                fullSignaled = false;
                recentGrabs.clear();
            }
        }
        lastChatId = newMax;
        chatInit = true;
        return Boolean.TRUE;
    }

    /** EMPTY: get a spike if out, then Line the pit. Next tick's object read confirms SPIKED. */
    private void ensureLined() {
        if (Rs2Inventory.count(WOODEN_SPIKES) < 1) {
            state = "getting spikes";
            WorldPoint me = Rs2Player.getWorldLocation();
            if (me == null) return;
            if (me.distanceTo(SPIKES_TILE) > 2) {           // walk right up to the supply before clicking
                Rs2Walker.walkFastCanvas(SPIKES_TILE);
                return;
            }
            // One spike is all a lining needs. Throttle so we click Take once and wait for it — not once per tick.
            if (System.currentTimeMillis() - lastTakeMs > 2500) {
                lastTakeMs = System.currentTimeMillis();
                Rs2GameObject.interact(SPIKES_SUPPLY, "Take");
                sleepUntil(() -> Rs2Inventory.count(WOODEN_SPIKES) >= 1, 2500);
            }
            return;
        }
        walkNear(PIT_TILE);
        // Click Line once and wait — the "you line the pit" chat (pollChat) flips us to SPIKED and resets the
        // per-fill trackers. Throttle so we don't re-click every 600ms tick while the first Line resolves.
        if (System.currentTimeMillis() - lastLineMs > 2500) {
            lastLineMs = System.currentTimeMillis();
            Rs2GameObject.interact(GOAT_PIT, "Line");
        }
    }

    private void huntGoat() {
        WorldPoint me = Rs2Player.getWorldLocation();
        if (me == null || me.distanceTo(PIT_TILE) > PIT_HUNT_RANGE) {
            walkNear(PIT_TILE);
            return;
        }
        Rs2NpcModel goat = Microbot.getClientThread().invoke(this::selectGoat);
        if (goat == null) {
            sleep(300, 600);
            return;
        }
        // A goat can despawn (potted by our own grab) between selection and cast; casting on it then builds a
        // null-target menu entry that trips other plugins (BankPlugin NPE). Re-check it's still real first.
        Boolean alive = Microbot.getClientThread().invoke(
                (java.util.function.Supplier<Boolean>)
                        () -> goat.getName() != null && goat.getWorldLocation() != null);
        if (alive == null || !alive) return;
        if (Rs2Magic.castOn(Rs2Spells.TELEKINETIC_GRAB, goat)) {
            recentGrabs.put(goat.getIndex(), System.currentTimeMillis());
            grabs.incrementAndGet();
            if (++grabsSinceLine >= MAX_PIT_CAPACITY) {
                fullSignaled = true;   // safety net if the "filled" chat was missed
            }
            // Let the cast register (player animates / goat starts moving) before picking the next one,
            // so we don't machine-gun clicks or double-cast the same goat.
            sleepUntil(() -> Rs2Player.isAnimating() || goat.isMoving(), 1200);
        }
        humanPause();
    }

    /**
     * Client thread. Pick the idle, un-cooled, unclaimed goat that sits most directly across the pit from the
     * player — pulling that one straight toward us crosses the pit cleanly and drops it in, instead of dragging
     * a side-on goat around the rim (which wastes the cast and leaves it to be re-selected).
     */
    private Rs2NpcModel selectGoat() {
        WorldPoint me = Rs2Player.getWorldLocation();
        if (me == null) return null;
        WorldPoint pit = pitAnchor();
        double dx = pit.getX() - me.getX(), dy = pit.getY() - me.getY();   // heading from us toward the pit
        double dlen = Math.hypot(dx, dy);
        if (dlen < 1) return null;   // standing on the pit — nothing is "across" from here
        long now = System.currentTimeMillis();
        recentGrabs.values().removeIf(t -> now - t > GRAB_COOLDOWN_MS);
        return Rs2Npc.getNpcs(GOAT)
                .filter(g -> g.getWorldLocation() != null)
                .filter(g -> g.getWorldLocation().distanceTo(me) <= TELEGRAB_RANGE)   // within Telegrab reach
                .filter(g -> !g.isMoving())                     // already being lured
                .filter(g -> !recentGrabs.containsKey(g.getIndex()))
                .filter(g -> !claimedByOther(g))
                .filter(g -> acrossPit(g.getWorldLocation(), pit, dx, dy, dlen) > 0.3)   // roughly opposite the pit
                .max(Comparator.comparingDouble(g -> acrossPit(g.getWorldLocation(), pit, dx, dy, dlen)))
                .orElse(null);
    }

    /**
     * How directly a goat sits across the pit from the player: cosine of the angle between (pit→goat) and the
     * (player→pit) heading. ~1 = goat dead behind the pit (best pull), ~0 = beside it, negative = same side as us.
     */
    private static double acrossPit(WorldPoint goat, WorldPoint pit, double dx, double dy, double dlen) {
        double vx = goat.getX() - pit.getX(), vy = goat.getY() - pit.getY();
        double vlen = Math.hypot(vx, vy);
        if (vlen < 0.5) return 0;   // goat basically on the pit
        return (vx * dx + vy * dy) / (vlen * dlen);
    }

    /** Client thread. Live pit location (SW tile) so geometry tracks the real object, not a stale constant. */
    private WorldPoint pitAnchor() {
        GameObject pit = Rs2GameObject.getGameObject(GOAT_PIT);
        return pit != null ? pit.getWorldLocation() : PIT_TILE;
    }

    /** Skip a goat another player has already engaged. Client thread. */
    private static boolean claimedByOther(Rs2NpcModel goat) {
        Player me = Microbot.getClient().getLocalPlayer();
        Actor goatTarget = goat.getInteracting();
        if (goatTarget != null && goatTarget != me) {
            return true;
        }
        int idx = goat.getIndex();
        return Rs2Player.getPlayers(p -> {
            Actor a = p.getInteracting();
            return a instanceof NPC && ((NPC) a).getIndex() == idx;
        }, false).findAny().isPresent();
    }

    private void clearPit(IrkedGoatkillerConfig config) {
        walkNear(PIT_TILE);
        if (!Rs2GameObject.interact(GOAT_PIT, "Clear")) {
            sleep(300, 600);
            return;
        }
        // Harvest runs one goat per tick over many ticks; horns land in the inventory as it goes (fur goes to the
        // pouch, invisible). Wait until horns stop arriving AND the player is idle before we touch anything — so we
        // don't drop horns mid-harvest, and don't re-line before it's actually empty. Break early if the object
        // read confirms EMPTY. Clearing ALWAYS breaks the spikes, so mark it: the next tick will re-line.
        sleep(1200, 1800);
        long deadline = System.currentTimeMillis() + 20000;
        int lastHorn = Rs2Inventory.count(GOAT_HORN), stable = 0;
        while (System.currentTimeMillis() < deadline) {
            if (readPitStateSafe() == PitState.EMPTY) break;
            int cur = Rs2Inventory.count(GOAT_HORN);
            if (cur != lastHorn) { lastHorn = cur; stable = 0; }
            else if (++stable >= 5 && !Rs2Player.isAnimating() && !Rs2Player.isMoving()) break;   // ~3s settled
            sleep(600);
        }
        sleep(500, 900);   // let the last harvested item land
        clears.incrementAndGet();
        spikesBroken = true;
        fullSignaled = false;
        grabsSinceLine = 0;
        recentGrabs.clear();
        if (config.dropGoatHorn()) {
            dropHorns();
        }
    }

    /** Drop the junk horns with a human cadence — an uneven beat, not a metronome, with the odd distracted pause. */
    private void dropHorns() {
        int guard = 40;
        while (Rs2Inventory.count(GOAT_HORN) > 0 && guard-- > 0) {
            Rs2Inventory.drop(GOAT_HORN);
            if (ThreadLocalRandom.current().nextInt(100) < 15) {
                sleep(650, 1500);   // glanced away mid-drop
            } else {
                sleep(160, 460);
            }
        }
    }

    /**
     * Large fur pouch holds 28 and overflows to inventory → walk to the Auchrie bank chest, deposit
     * inventory fur, empty the pouch, deposit the released fur, walk back.
     * ponytail: bank trip uses the web-walker, blocked on Wyrmscraig until the collision map is updated.
     */
    private void bankFur() {
        int before = Rs2Inventory.count(GOAT_FUR);
        Rs2Walker.walkTo(BANK_TILE, 3);
        if (!Rs2Bank.openBank()) return;
        sleepUntil(Rs2Bank::isOpen, 5000);
        Rs2Bank.depositAll(GOAT_FUR);
        Rs2Bank.depositAll(GOAT_HORN);
        if (Rs2Inventory.interact(LARGE_FUR_POUCH, "Empty")) {
            sleepUntil(() -> Rs2Inventory.count(GOAT_FUR) > 0, 2000);
            Rs2Bank.depositAll(GOAT_FUR);
        }
        fursBanked.addAndGet(Math.max(before, 0));
        sleep(400, 700);
        Rs2Bank.closeBank();
        Rs2Walker.walkTo(PIT_TILE, 3);
    }

    /** Client-thread-safe pit read for use inside sleepUntil predicates. */
    private PitState readPitStateSafe() {
        return Microbot.getClientThread().invoke(this::readPitState);
    }

    private void walkNear(WorldPoint tile) {
        WorldPoint me = Rs2Player.getWorldLocation();
        if (me == null || me.distanceTo(tile) > 3) {
            Rs2Walker.walkFastCanvas(tile);
        }
    }

    private void humanPause() {
        if (ThreadLocalRandom.current().nextInt(100) < 8) {
            sleep(1400, 2600);
        } else {
            sleep(340, 900);
        }
    }

    public void shutdown() {
        super.shutdown();
    }
}
