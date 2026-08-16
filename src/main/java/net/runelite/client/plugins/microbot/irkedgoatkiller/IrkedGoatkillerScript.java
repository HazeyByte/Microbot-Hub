package net.runelite.client.plugins.microbot.irkedgoatkiller;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
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
 * Wyrmscraig goat hunting (Hunter 60+, Sheep Herder). One cycle: take spikes (only when out) → Line the
 * pit ONCE → Telekinetic-Grab goats on the far side of the pit until it fills → Clear → drop horns, keep
 * fur → bank when full → repeat.
 *
 * State is an explicit {@link PitState} driven by POLLING the chat buffer ({@link #pollChat}), because:
 *   (a) EventBus @Subscribe throws LambdaConversionException for this sideloaded plugin (Plugin or Script),
 *   (b) the pit object keeps id 62343 in every state, so object-id detection is useless.
 * Chat lines: "you line the pit…"→SPIKED, "pit is now filled…"→FULL, "need to replace the spikes…"→EMPTY.
 *
 * Targeting uses the LIVE player position: goats are all around, so we grab the nearest one with the pit
 * between us and it (pulled across the pit). Selection runs in one client-thread hop.
 */
@Slf4j
public class IrkedGoatkillerScript extends Script {

    private enum PitState { EMPTY, SPIKED, FULL }

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

    private static final long GRAB_COOLDOWN_MS = 3000;
    private static final long LINE_CONFIRM_MS = 4000;   // wait for "you line the pit" before retrying
    private static final int MAX_PIT_CAPACITY = 24;     // safety: force FULL if chat missed (lvl 99 cap)

    private volatile PitState pit = PitState.EMPTY;
    private volatile int lastChatId = Integer.MIN_VALUE;
    private volatile boolean chatInit = false;
    private long lastLineMs;
    private int lineAttempts;
    private int grabsSinceLine;

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
                Microbot.getClientThread().invoke(this::pollChat);

                if (pit == PitState.FULL) {
                    state = "clearing";
                    clearPit(config);
                    return;
                }
                if (Rs2Inventory.isFull()) {
                    state = "banking";
                    bankFur();
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

    /** Runs on the client thread. Scan new chat lines and update pit state. */
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
                pit = PitState.FULL;
            } else if (m.contains("you line the pit")) {
                pit = PitState.SPIKED;
                grabsSinceLine = 0;
                lineAttempts = 0;
            } else if (m.contains("need to replace the spikes")) {
                pit = PitState.EMPTY;
                lineAttempts = 0;
            }
        }
        lastChatId = newMax;
        chatInit = true;   // first pass just establishes the high-water mark
        return Boolean.TRUE;
    }

    /** EMPTY: get spikes if out, then Line the pit exactly once (chat flips us to SPIKED). */
    private void ensureLined() {
        if (Rs2Inventory.count(WOODEN_SPIKES) < 1) {
            state = "getting spikes";
            walkNear(SPIKES_TILE);
            Rs2GameObject.interact(SPIKES_SUPPLY, "Take");
            sleepUntil(() -> Rs2Inventory.count(WOODEN_SPIKES) >= 1, 5000);
            return;
        }
        walkNear(PIT_TILE);
        if (System.currentTimeMillis() - lastLineMs < LINE_CONFIRM_MS) {
            sleep(300, 600);   // awaiting the "you line the pit" confirmation — don't spam Line
            return;
        }
        boolean clicked = Rs2GameObject.interact(GOAT_PIT, "Line");
        lastLineMs = System.currentTimeMillis();
        // No Line option (already spiked) or two tries with no confirm → assume lined and start hunting.
        if (!clicked || ++lineAttempts >= 2) {
            pit = PitState.SPIKED;
            grabsSinceLine = 0;
            lineAttempts = 0;
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
        Rs2Magic.castOn(Rs2Spells.TELEKINETIC_GRAB, goat);   // click spell, then click goat
        recentGrabs.put(goat.getIndex(), System.currentTimeMillis());
        grabs.incrementAndGet();
        if (++grabsSinceLine >= MAX_PIT_CAPACITY) {
            pit = PitState.FULL;   // safety net if the "filled" chat was missed
        }
        humanPause();
    }

    /** Client thread. Nearest un-cooled, unclaimed goat on the far side of the pit from the player. */
    private Rs2NpcModel selectGoat() {
        WorldPoint me = Rs2Player.getWorldLocation();
        if (me == null) return null;
        long now = System.currentTimeMillis();
        recentGrabs.values().removeIf(t -> now - t > GRAB_COOLDOWN_MS);
        return Rs2Npc.getNpcs(GOAT)
                .filter(g -> g.getWorldLocation() != null)
                .filter(g -> !recentGrabs.containsKey(g.getIndex()))
                .filter(g -> !claimedByOther(g))
                .filter(g -> pitBetween(me, g.getWorldLocation()))
                .min(Comparator.comparingInt(g -> g.getWorldLocation().distanceTo(me)))
                .orElse(null);
    }

    /** True when the pit sits between the player and the goat — so the grab drags the goat across it. */
    private static boolean pitBetween(WorldPoint me, WorldPoint goat) {
        int ax = me.getX() - PIT_TILE.getX(), ay = me.getY() - PIT_TILE.getY();
        int gx = goat.getX() - PIT_TILE.getX(), gy = goat.getY() - PIT_TILE.getY();
        return (ax * gx + ay * gy) < 0;
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
        sleep(1500, 2500);
        // Harvest is one tick per goat; wait until the player settles.
        sleepUntil(() -> !Rs2Player.isAnimating() && !Rs2Player.isMoving(), 15000);
        clears.incrementAndGet();
        pit = PitState.EMPTY;     // spikes broke; re-line next cycle
        grabsSinceLine = 0;
        recentGrabs.clear();
        if (config.dropGoatHorn()) {
            int guard = 30;
            while (Rs2Inventory.count(GOAT_HORN) > 0 && guard-- > 0) {
                Rs2Inventory.drop(GOAT_HORN);
                sleep(200, 500);
            }
        }
    }

    /**
     * Both full (Large fur pouch holds 28, inventory overflows) → walk to the Auchrie bank chest, deposit
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
