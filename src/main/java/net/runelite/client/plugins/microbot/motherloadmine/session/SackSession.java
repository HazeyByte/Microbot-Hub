package net.runelite.client.plugins.microbot.motherloadmine.session;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.motherloadmine.MotherloadMineConfig;
import net.runelite.client.plugins.microbot.motherloadmine.enums.MLMMiningSpot;
import net.runelite.client.plugins.microbot.util.depositbox.Rs2DepositBox;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Gembag;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.Arrays;
import java.util.List;

/**
 * Tick-progressive SackSession for Motherlode Mine.
 * Handles the withdraw-from-sack → deposit-to-deposit-box loop until the sack is empty.
 *
 * <p>Simplified flow: no dedicated walk states. The sack and deposit box are clicked
 * directly; {@link Rs2TileObjectModel#click()} auto-walks if out of range.
 */
@Slf4j
public class SackSession extends Session {

    public enum SackSubState {
        IDLE,
        TRANSITIONING_FLOOR,
        EVALUATE,
        WITHDRAW,
        DEPOSIT,
        CHECK_EMPTY,
        RETURN_TO_SPOT,
        COMPLETE,
        FAILED
    }

    private static final long CLICK_THROTTLE_MS  = 1000L;
    private static final long STALL_THRESHOLD_MS = 12_000L;

    private static final int MAX_WITHDRAW_RETRIES = 3;

    private static final List<Integer> ORE_IDS = Arrays.asList(
            ItemID.RUNITE_ORE, ItemID.ADAMANTITE_ORE, ItemID.MITHRIL_ORE,
            ItemID.GOLD_ORE, ItemID.COAL, ItemID.MOTHERLODE_NUGGET
    );

    private static final int SACK_ID       = ObjectID.MOTHERLODE_SACK;
    private static final int SACK_ID_ALT   = 26687;
    private static final int SACK_ID_ALT2  = 26688;
    private static final int DEPOSIT_BOX_ID = 25937;

    @Getter
    private SackSubState sackSubState = SackSubState.IDLE;

    private MLMMiningSpot originalMiningSpot    = null;
    private boolean       gemBagEmptiedThisSession = false;
    private int           withdrawRetryCount    = 0;

    private final Rs2TileObjectCache   tileCache;
    private final MotherloadMineConfig config;

    public SackSession(Rs2TileObjectCache tileCache, MotherloadMineConfig config) {
        this.tileCache = tileCache;
        this.config    = config;
    }

    @Override
    public void reset() {
        super.reset();
        sackSubState              = SackSubState.IDLE;
        originalMiningSpot        = null;
        gemBagEmptiedThisSession  = false;
        withdrawRetryCount        = 0;
    }

    public void begin(MLMMiningSpot currentSpot) {
        if (!isIdle()) {
            log.warn("[SackSession] begin() called while not idle");
            return;
        }
        this.originalMiningSpot = currentSpot;
        transitionSub(SackSubState.IDLE);
        transition(State.ACTIVE);
    }

    @Override
    protected void tickInternal() {
        // Uses tick(int, int) overload
    }

    public void tick(int currentSackCount, int maxSackSize) {
        if (!isActive()) return;
        if (System.currentTimeMillis() < nextActionMs) return;

        try {
            tickSackInternal(currentSackCount, maxSackSize);
        } catch (Exception e) {
            log.error("[SackSession] Tick crash", e);
            transitionSub(SackSubState.FAILED);
            transition(State.FAILED);
        }
    }

    private void tickSackInternal(int currentSackCount, int maxSackSize) {
        switch (sackSubState) {

            case IDLE:
                if (isUpperFloor()) {
                    transitionSub(SackSubState.TRANSITIONING_FLOOR);
                } else {
                    transitionSub(SackSubState.EVALUATE);
                }
                break;

            case TRANSITIONING_FLOOR:
                if (ensureFloor(false)) {
                    transitionSub(SackSubState.EVALUATE);
                }
                if (isStalled(20_000L)) {
                    log.warn("[SackSession] Floor transition stalled — failing");
                    transitionSub(SackSubState.FAILED);
                    transition(State.FAILED);
                }
                break;

            case EVALUATE:
                if (currentSackCount <= 0 && !hasOreInInventory()) {
                    log.debug("[SackSession] Sack empty ({}/{}), no ore in inventory — done", currentSackCount, maxSackSize);
                    transitionSub(SackSubState.RETURN_TO_SPOT);
                    break;
                }
                if (hasOreInInventory()) {
                    log.debug("[SackSession] Ore in inventory — depositing");
                    transitionSub(SackSubState.DEPOSIT);
                } else {
                    withdrawRetryCount = 0;
                    transitionSub(SackSubState.WITHDRAW);
                }
                break;

            case WITHDRAW:
                if (Rs2Inventory.isFull()) {
                    transitionSub(SackSubState.DEPOSIT);
                    break;
                }
                if (currentSackCount <= 0) {
                    log.info("[SackSession] Sack empty (varbit) — checking");
                    transitionSub(SackSubState.CHECK_EMPTY);
                    break;
                }
                if (Rs2Player.isInteracting() || Rs2Player.isAnimating() || Rs2Player.isMoving()) {
                    scheduleNext(600L);
                    break;
                }

                var sack = tileCache.query()
                        .where(o -> o.getId() == SACK_ID || o.getId() == SACK_ID_ALT || o.getId() == SACK_ID_ALT2)
                        .nearestReachable();

                if (sack != null) {
                    if (sack.click()) {
                        applyActionCooldown();
                        scheduleNext(CLICK_THROTTLE_MS);
                        withdrawRetryCount++;
                        if (withdrawRetryCount >= MAX_WITHDRAW_RETRIES) {
                            transitionSub(SackSubState.CHECK_EMPTY);
                        }
                    } else {
                        // click() returned false — player is walking toward sack
                        scheduleNext(800L);
                    }
                } else {
                    log.debug("[SackSession] Sack not in cache — waiting for scene load");
                    scheduleNext(800L);
                    if (isStalled(STALL_THRESHOLD_MS)) {
                        log.warn("[SackSession] Sack not found for {}ms — failing", STALL_THRESHOLD_MS);
                        transitionSub(SackSubState.FAILED);
                        transition(State.FAILED);
                    }
                }
                break;

            case DEPOSIT:
                if (!Rs2DepositBox.isOpen()) {
                    // Wait for any movement/animation to finish before clicking
                    if (Rs2Player.isInteracting() || Rs2Player.isAnimating() || Rs2Player.isMoving()) {
                        scheduleNext(600L);
                        break;
                    }

                    var box = tileCache.query()
                            .where(o -> o.getId() == DEPOSIT_BOX_ID)
                            .nearestReachable();

                    if (box == null) {
                        // Deposit box not in scene — walk closer
                        WorldPoint depositBoxLoc = new WorldPoint(3759, 5665, 0);
                        int dist = Rs2Player.getWorldLocation().distanceTo(depositBoxLoc);
                        if (dist > 15) {
                            log.debug("[SackSession] Deposit box {} tiles away — using webwalker", dist);
                            Rs2Walker.walkTo(depositBoxLoc);
                        } else if (dist > 3) {
                            log.debug("[SackSession] Deposit box {} tiles away — walking closer", dist);
                            Rs2Walker.walkFastCanvas(depositBoxLoc);
                        } else {
                            log.debug("[SackSession] Deposit box very close ({}) but not in cache — waiting", dist);
                        }
                        scheduleNext(1200L);
                        if (isStalled(STALL_THRESHOLD_MS)) {
                            log.warn("[SackSession] Deposit box not found for {}ms — failing", STALL_THRESHOLD_MS);
                            transitionSub(SackSubState.FAILED);
                            transition(State.FAILED);
                        }
                        break;
                    }

                    // Small random pause before clicking — human reaction time
                    scheduleNext(java.util.concurrent.ThreadLocalRandom.current().nextInt(400, 900));

                    log.info("[SackSession] Opening deposit box (ID {})", DEPOSIT_BOX_ID);
                    if (box.click("Deposit")) {
                        applyActionCooldown();
                        scheduleNext(1800L); // longer wait after click for interface to open
                    } else {
                        scheduleNext(1000L);
                    }
                    break;
                }

                // Deposit box is open — handle gem bag first
                if (!gemBagEmptiedThisSession && Rs2Gembag.hasGemBag()
                        && (Rs2Inventory.contains(ItemID.UNCUT_SAPPHIRE)
                        || Rs2Inventory.contains(ItemID.UNCUT_EMERALD)
                        || Rs2Inventory.contains(ItemID.UNCUT_RUBY)
                        || Rs2Inventory.contains(ItemID.UNCUT_DIAMOND))) {
                    Rs2Inventory.interact(ItemID.GEM_BAG_OPEN, "Empty");
                    gemBagEmptiedThisSession = true;
                    scheduleNext(CLICK_THROTTLE_MS);
                    log.debug("[SackSession] Emptying gem bag (gems detected)");
                    break;
                }

                // Deposit ores — respect "Use Deposit All" config
                if (config.useDepositAll()) {
                    Rs2DepositBox.depositAll();
                    scheduleNext(CLICK_THROTTLE_MS);
                    transitionSub(SackSubState.CHECK_EMPTY);
                } else {
                    // Natural mouse: deposit one item type per tick
                    for (int oreId : ORE_IDS) {
                        if (Rs2Inventory.contains(oreId)) {
                            Rs2Inventory.interact(oreId, "Deposit-All");
                            applyActionCooldown();
                            scheduleNext(600L);
                            return; // one item type per tick, come back next tick
                        }
                    }
                    if (Rs2Inventory.contains(ItemID.MOTHERLODE_NUGGET)) {
                        Rs2Inventory.interact(ItemID.MOTHERLODE_NUGGET, "Deposit-All");
                        applyActionCooldown();
                        scheduleNext(600L);
                        return;
                    }
                    // Nothing left to deposit
                    scheduleNext(CLICK_THROTTLE_MS);
                    transitionSub(SackSubState.CHECK_EMPTY);
                }
                break;

            case CHECK_EMPTY:
                // NOTE: We do NOT explicitly close the deposit box here.
                // Clicking the sack (in WITHDRAW state) automatically closes any
                // open interface. This saves a tick and avoids invoke-based menu
                // interactions that bypass natural mouse movement.
                if (currentSackCount > 0) {
                    log.info("[SackSession] Sack still has {}/{} ore — looping", currentSackCount, maxSackSize);
                    transitionSub(SackSubState.EVALUATE);
                } else if (hasOreInInventory()) {
                    transitionSub(SackSubState.DEPOSIT);
                } else {
                    log.info("[SackSession] Sack empty — session complete");
                    transitionSub(SackSubState.RETURN_TO_SPOT);
                }
                break;

            case RETURN_TO_SPOT:
                transition(State.COMPLETE);
                break;

            case FAILED:
                transition(State.FAILED);
                break;

            default:
                break;
        }
    }

    private void transitionSub(SackSubState next) {
        log.debug("[SackSession] Sub-state: {} -> {}", sackSubState, next);
        sackSubState      = next;
        subStateEnteredMs = System.currentTimeMillis();

        switch (next) {
            case TRANSITIONING_FLOOR: updateStatus("Climbing Ladder");          break;
            case WITHDRAW:            updateStatus("Withdrawing Ores");          break;
            case DEPOSIT:             updateStatus("Depositing Ores");         break;
            case RETURN_TO_SPOT:      updateStatus("Returning to Mining Spot"); break;
            default:                  updateStatus(formatEnum(next.name()));     break;
        }
    }

    private boolean hasOreInInventory() {
        for (int oreId : ORE_IDS) {
            if (Rs2Inventory.contains(oreId)) return true;
        }
        return false;
    }

    public WorldPoint getReturnPoint() {
        if (originalMiningSpot == null) return null;
        List<WorldPoint> pts = originalMiningSpot.getWorldPoint();
        return (pts != null && !pts.isEmpty()) ? pts.get(0) : null;
    }
}
