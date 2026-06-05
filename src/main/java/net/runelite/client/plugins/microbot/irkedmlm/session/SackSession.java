package net.runelite.client.plugins.microbot.irkedmlm.session;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.motherloadmine.IrkedMLMMapConstants;
import net.runelite.client.plugins.microbot.motherloadmine.IrkedMLMConfig;
import net.runelite.client.plugins.microbot.irkedmlm.enums.MLMMiningSpot;
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
 * <p>Handles walking to the sack if necessary (using explicit target from cache or
 * known location), then the withdraw loop, then deposit box (which has its own close-range
 * walk logic). Do not rely on click() for long distance travel.
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
            ItemID.GOLD_ORE, ItemID.COAL
    );

    private static final int SACK_ID       = ObjectID.MOTHERLODE_SACK;
    private static final int SACK_ID_ALT   = 26687;
    private static final int SACK_ID_ALT2  = 26688;
    private static final int DEPOSIT_BOX_ID = 25937;

    private static final WorldPoint DEPOSIT_BOX_LOC = IrkedMLMMapConstants.DEPOSIT_BOX_LOC;

    @Getter
    private SackSubState sackSubState = SackSubState.IDLE;

    private MLMMiningSpot originalMiningSpot    = null;
    private boolean       gemBagEmptiedThisSession = false;
    private int           withdrawRetryCount    = 0;

    /** Progress tracking to detect when withdraws are not actually reducing the sack (stale varbit
     *  or wrong action). Used to break the "Sack still has 189/189 ore — looping" forever case
     *  even after live count fixes, and to try the "Empty sack" explicit option seen in logs. */
    private int lastSeenSackCount = -1;
    private int noProgressWithdraws = 0;

    private final Rs2TileObjectCache   tileCache;

    public SackSession(Rs2TileObjectCache tileCache, IrkedMLMConfig config) {
        super(config);
        this.tileCache = tileCache;
    }

    @Override
    public void reset() {
        super.reset();
        sackSubState              = SackSubState.IDLE;
        originalMiningSpot        = null;
        gemBagEmptiedThisSession  = false;
        withdrawRetryCount        = 0;
        lastSeenSackCount         = -1;
        noProgressWithdraws       = 0;
    }

    public void begin(MLMMiningSpot currentSpot) {
        if (!isIdle()) {
            log.warn("[SackSession] begin() called while not idle");
            return;
        }
        this.originalMiningSpot = currentSpot;
        lastSeenSackCount = -1;
        noProgressWithdraws = 0;
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
                if (Rs2Player.isMoving() || Rs2Player.isAnimating(5000)) {
                    scheduleNextAdaptive(180L, 600L);
                    break;
                }
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
                // If we somehow have pay-dirt, abort sack session and let main script reroute
                if (Rs2Inventory.count(ItemID.PAYDIRT) > 0) {
                    log.warn("[SackSession] Pay-dirt detected in inventory during sack emptying — aborting to deposit");
                    transition(State.FAILED); // Let main script reroute to DEPOSIT_HOPPER
                    break;
                }
                if (currentSackCount <= 0 && !hasOreInInventory()) {
                    log.debug("[SackSession] Sack empty ({}/{}), no ore in inventory — done", currentSackCount, maxSackSize);
                    noProgressWithdraws = 0;
                    lastSeenSackCount = currentSackCount;
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
                    scheduleNextAdaptive(180L, 600L);
                    break;
                }

                // Progress tracking: if the passed sack count (now live during empty thanks to script fixes)
                // is not strictly decreasing after a withdraw, we may be stuck (stale even with force,
                // or default click not using the effective action). This directly addresses the
                // "Sack stayed full while we were in the empty sack state" + repeated "back to the sack
                // to check when inventory wasnt full of ores".
                if (lastSeenSackCount > 0 && currentSackCount >= lastSeenSackCount) {
                    noProgressWithdraws++;
                }
                lastSeenSackCount = currentSackCount;

                if (noProgressWithdraws > 4) {
                    log.warn("[SackSession] No progress emptying sack (count still {} after {} withdraws). Trying explicit 'Empty sack'/'Search' action.", currentSackCount, noProgressWithdraws);
                }

                // Proactively walk toward the sack if we are too far or it is not yet reachable/clickable.
                // This is critical when EMPTY_SACK is entered from a distant mining spot (south/west lower
                // or right after climbing down from upper). Unlike the deposit box path below, the sack
                // previously had no explicit walk logic and relied on unreliable long-range click behavior.
                //
                // IMPORTANT: The query() builder + terminal ops (nearest*) operate on a one-shot stream.
                // Reusing the same query instance for two .nearest* calls causes "stream has already been
                // operated upon or closed". Build independent queries.
                var sackForClick = tileCache.query()
                        .where(o -> o.getId() == SACK_ID || o.getId() == SACK_ID_ALT || o.getId() == SACK_ID_ALT2)
                        .nearestReachable();
                var sackForTarget = tileCache.query()
                        .where(o -> o.getId() == SACK_ID || o.getId() == SACK_ID_ALT || o.getId() == SACK_ID_ALT2)
                        .nearest(); // any sack in scene for walk target (full scene is scanned)

                WorldPoint sackLoc = (sackForTarget != null) ? sackForTarget.getWorldLocation() : null;
                int distToSack = (sackLoc != null && Rs2Player.getWorldLocation() != null)
                        ? Rs2Player.getWorldLocation().distanceTo(sackLoc) : Integer.MAX_VALUE;

                // Only proactively walk if we have no reachable sack *and* we are not already
                // in the deposit-box / sack area. After finishing a deposit (interface still open
                // or just acted on), a human will just click the sack object directly from here.
                // The sack click will auto-close the deposit box interface. This avoids the
                // "walk to sack loc then click" behavior when we are already standing at the box.
                boolean nearDepositBox = Rs2Player.getWorldLocation() != null
                        && Rs2Player.getWorldLocation().distanceTo(DEPOSIT_BOX_LOC) <= 7;
                boolean shouldWalkFirst = (sackForClick == null)
                        && (sackLoc != null)
                        && (distToSack > 8)
                        && !nearDepositBox;

                if (shouldWalkFirst) {
                    if (distToSack > 15) {
                        log.debug("[SackSession] Sack {} tiles away — using webwalker to target", distToSack);
                        Rs2Walker.walkTo(sackLoc);
                    } else {
                        log.debug("[SackSession] Sack {} tiles away — walking closer", distToSack);
                        Rs2Walker.walkFastCanvas(sackLoc);
                    }
                    scheduleNextAdaptive(350L, 1200L);
                    break;
                }

                // Now close enough — use the reachable one for the actual click if available
                var sack = (sackForClick != null) ? sackForClick : sackForTarget;

                if (sack != null) {
                    // Prefer explicit action names observed in game ("Empty sack" when it becomes the
                    // primary option, or "Search"). Fall back to default click(). This helps when
                    // bare click() doesn't do what we expect on the sack object.
                    boolean actionTaken = false;
                    if (noProgressWithdraws > 4) {
                        actionTaken = sack.click("Empty sack") || sack.click("Search");
                    }
                    if (!actionTaken) {
                        actionTaken = sack.click();
                    }
                    if (actionTaken) {
                        applyActionCooldown();
                        scheduleNextAdaptive(350L, CLICK_THROTTLE_MS);
                        withdrawRetryCount++;
                        if (withdrawRetryCount >= MAX_WITHDRAW_RETRIES) {
                            transitionSub(SackSubState.CHECK_EMPTY);
                        }
                        // If we had high no-progress, give one more cycle then let the CHECK_EMPTY
                        // + next WITHDRAW re-evaluate the (hopefully now live + reduced) count.
                        if (noProgressWithdraws > 4) {
                            noProgressWithdraws = 3; // back off a bit, not zero
                        }
                    } else {
                        // click() returned false — player is walking toward sack
                        scheduleNextAdaptive(250L, 800L);
                    }
                } else {
                    log.debug("[SackSession] Sack not in cache — waiting for scene load");
                    scheduleNextAdaptive(250L, 800L);
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
                        scheduleNextAdaptive(180L, 600L);
                        break;
                    }

                    var box = tileCache.query()
                            .where(o -> o.getId() == DEPOSIT_BOX_ID)
                            .nearestReachable();

                    if (box == null) {
                        // Deposit box not in scene — walk closer
                        int dist = Rs2Player.getWorldLocation().distanceTo(DEPOSIT_BOX_LOC);
                        if (dist > 15) {
                            log.debug("[SackSession] Deposit box {} tiles away — using webwalker", dist);
                            Rs2Walker.walkTo(DEPOSIT_BOX_LOC);
                        } else if (dist > 3) {
                            log.debug("[SackSession] Deposit box {} tiles away — walking closer", dist);
                            Rs2Walker.walkFastCanvas(DEPOSIT_BOX_LOC);
                        } else {
                            log.debug("[SackSession] Deposit box very close ({}) but not in cache — waiting", dist);
                        }
                        scheduleNextAdaptive(350L, 1200L);
                        if (isStalled(STALL_THRESHOLD_MS)) {
                            log.warn("[SackSession] Deposit box not found for {}ms — failing", STALL_THRESHOLD_MS);
                            transitionSub(SackSubState.FAILED);
                            transition(State.FAILED);
                        }
                        break;
                    }

                    if (isHumanLikeEnabled()) {
                        scheduleNext(java.util.concurrent.ThreadLocalRandom.current().nextInt(400, 900));
                    } else {
                        scheduleNextAdaptive(80L, 400L);
                    }

                    log.info("[SackSession] Opening deposit box (ID {})", DEPOSIT_BOX_ID);
                    if (box.click("Deposit")) {
                        applyActionCooldown();
                        scheduleNext(1800L); // longer wait after click for interface to open
                    } else {
                        scheduleNext(1000L);
                    }
                    break;
                }

                // Deposit box is open — handle gem bag first (only if the "Use Gem Bag" feature is enabled)
                if (config.useGemBag()
                        && !gemBagEmptiedThisSession && Rs2Gembag.hasGemBag()
                        && (Rs2Inventory.contains(ItemID.UNCUT_SAPPHIRE)
                        || Rs2Inventory.contains(ItemID.UNCUT_EMERALD)
                        || Rs2Inventory.contains(ItemID.UNCUT_RUBY)
                        || Rs2Inventory.contains(ItemID.UNCUT_DIAMOND))) {
                    Rs2Inventory.interact(ItemID.GEM_BAG_OPEN, "Empty");
                    gemBagEmptiedThisSession = true;
                    scheduleNextAdaptive(350L, CLICK_THROTTLE_MS);
                    log.debug("[SackSession] Emptying gem bag (gems detected)");
                    break;
                }

                // Deposit ores — respect "Use Deposit All" config
                if (config.useDepositAll()) {
                    Rs2DepositBox.depositAll();
                    scheduleNextAdaptive(350L, CLICK_THROTTLE_MS);
                    transitionSub(SackSubState.CHECK_EMPTY);
                } else {
                    // Natural mouse + human variety: deposit one item type per tick.
                    // Shuffle a copy of the list each time we enter this phase so the order
                    // isn't robotic (humans don't always do runite first, then adamant, etc.).
                    java.util.List<Integer> depositOrder = new java.util.ArrayList<>(ORE_IDS);
                    java.util.Collections.shuffle(depositOrder, java.util.concurrent.ThreadLocalRandom.current());

                    for (int oreId : depositOrder) {
                        if (Rs2Inventory.contains(oreId)) {
                            Rs2Inventory.interact(oreId, "Deposit-All");
                            applyActionCooldown();
                            scheduleNextAdaptive(180L, 600L);
                            return; // one item type per tick, come back next tick
                        }
                    }
                    // Nothing left to deposit (nuggets are deliberately left in inventory;
                    // they are not sent to the deposit box like ores — they accumulate for
                    // upgrades/ladder and are visible as batch sizes after each withdraw.
                    // The "Nuggets" tracker value in the overlay is populated from chat loot
                    // announcements for a pure session total, not inv detection on empty.)
                    scheduleNextAdaptive(350L, CLICK_THROTTLE_MS);
                    transitionSub(SackSubState.CHECK_EMPTY);
                }
                break;

            case CHECK_EMPTY:
                // NOTE: We do NOT explicitly close the deposit box here.
                // Clicking the sack (in WITHDRAW state) automatically closes any
                // open interface. This saves a tick and avoids invoke-based menu
                // interactions that bypass natural mouse movement.
                if (currentSackCount < lastSeenSackCount) {
                    noProgressWithdraws = 0;
                    lastSeenSackCount = currentSackCount;
                }
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
