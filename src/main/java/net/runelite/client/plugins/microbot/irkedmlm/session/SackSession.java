package net.runelite.client.plugins.microbot.irkedmlm.session;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;
import net.runelite.client.plugins.microbot.irkedmlm.IrkedMLMMapConstants;
import net.runelite.client.plugins.microbot.irkedmlm.IrkedMLMConfig;
import net.runelite.client.plugins.microbot.irkedmlm.enums.DepositMethod;
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

    private static final int SACK_ID         = ObjectID.MOTHERLODE_SACK;
    private static final int SACK_ID_GRAPHIC  = ObjectID.MOTHERLODE_SACK_GRAPHIC;
    private static final int DEPOSIT_BOX_ID   = ObjectID.KR_BANK_DEPOSIT_BOX;

    private static final WorldPoint DEPOSIT_BOX_LOC = IrkedMLMMapConstants.DEPOSIT_BOX_LOC;

    @Getter
    private SackSubState sackSubState = SackSubState.IDLE;

    private MLMMiningSpot originalMiningSpot    = null;
    private boolean       gemBagEmptiedThisSession = false;
    private int           withdrawRetryCount    = 0;
    private final int[] pendingOreDepositSnapshot = new int[ORE_IDS.size()];
    /** Progress tracking to detect when withdraws are not actually reducing the sack (stale varbit
     *  or wrong action). Used to break the "Sack still has 189/189 ore — looping" forever case
     *  even after live count fixes, and to try the "Empty sack" explicit option seen in logs. */
    private int lastSeenSackCount = -1;
    private int noProgressWithdraws = 0;

    private final Rs2TileObjectCache   tileCache;

    private java.util.function.BooleanSupplier gemBagSlotSupplier;
    private java.util.function.BooleanSupplier gemBagLockSupplier;

    public SackSession(Rs2TileObjectCache tileCache, IrkedMLMConfig config) {
        super(config);
        this.tileCache = tileCache;
    }

    /** Wires the live gem-bag slot/lock facts used to gate Deposit-All. Set once from the script;
     *  keeps this session's own logic (and depositAllSafe()) testable without live client state. */
    public void setGemBagSuppliers(java.util.function.BooleanSupplier inSlot0, java.util.function.BooleanSupplier locked) {
        this.gemBagSlotSupplier = inSlot0;
        this.gemBagLockSupplier = locked;
    }

    /** The effective (possibly preflight-degraded ALL→ITEMS) deposit method. Falls back to config. */
    private java.util.function.Supplier<DepositMethod> depositMethodSupplier;
    public void setDepositMethodSupplier(java.util.function.Supplier<DepositMethod> s) { this.depositMethodSupplier = s; }
    private DepositMethod depositMethod() {
        return depositMethodSupplier != null ? depositMethodSupplier.get() : config.depositMethod();
    }

    /** Whether a Deposit-All is safe for the gem bag. Only meaningful when method == ALL. */
    public static boolean depositAllSafe(boolean useGemBag, DepositMethod method,
                                         boolean bagInInventory, boolean bagInSlot0, boolean slot0Locked) {
        if (!useGemBag) return true;                   // nothing to protect
        if (method != DepositMethod.ALL) return true;  // ITEMS path never sweeps the bag
        if (!bagInInventory) return true;              // bag already banked / not carried — nothing to sweep
        return bagInSlot0 && slot0Locked;              // protected only when in slot 0 and locked
    }

    /** {@inheritDoc} Emptying the sack is a focused bank trip, not part of the AFK mining loop. */
    @Override
    protected boolean isFocusedTask() {
        return true;
    }

    @Override
    public void reset() {
        super.reset();
        sackSubState              = SackSubState.IDLE;
        originalMiningSpot        = null;
        gemBagEmptiedThisSession  = false;
        withdrawRetryCount        = 0;
        java.util.Arrays.fill(pendingOreDepositSnapshot, 0);
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
                if (Rs2Inventory.itemQuantity(ItemID.PAYDIRT) > 0) {
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
                if (isUpperFloor()) {
                    // Safety: if we somehow entered/continued WITHDRAW while on upper floor
                    // (e.g. ladder position "in front" made previous dist check think we were close,
                    // or status transition happened at the ladder), force the floor transition
                    // instead of spamming clicks on the lower sack object (which produces no progress
                    // and the empty-option MenuEntry spam).
                    log.debug("[SackSession] WITHDRAW on upper floor — forcing floor transition before sack clicks");
                    transitionSub(SackSubState.TRANSITIONING_FLOOR);
                    break;
                }
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

                // Progress tracking: if the sack count is not strictly decreasing after a withdraw we
                // may be stuck (stale varbit, or the default click not resolving to the empty action).
                if (lastSeenSackCount > 0 && currentSackCount >= lastSeenSackCount) {
                    noProgressWithdraws++;
                }
                lastSeenSackCount = currentSackCount;

                if (noProgressWithdraws > 4) {
                    log.warn("[SackSession] No progress emptying sack (count still {} after {} withdraws). Trying explicit 'Empty sack'/'Search' action.", currentSackCount, noProgressWithdraws);
                }

                // Proactively walk toward the sack if we are too far, or it is not yet reachable or
                // clickable. This matters when EMPTY_SACK is entered from a distant mining spot (south or
                // west lower, or right after climbing down) — a long-range click alone is unreliable.
                //
                // IMPORTANT: The query() builder + terminal ops (nearest*) operate on a one-shot stream.
                // Reusing the same query instance for two .nearest* calls causes "stream has already been
                // operated upon or closed". Build independent queries.
                var sackForClick = tileCache.query()
                        .where(o -> o.getId() == SACK_ID || o.getId() == SACK_ID_GRAPHIC)
                        .nearestReachable();
                var sackForTarget = tileCache.query()
                        .where(o -> o.getId() == SACK_ID || o.getId() == SACK_ID_GRAPHIC)
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
                    // The deposit box is normally still open here — clicking the sack closes it and
                    // empties in one go, which is fine, but only if the sack is actually visible. When
                    // the panel is drawn over it the click lands on the interface, not the sack, so
                    // ensureClickable swings the camera until the sack sits beside the panel (and
                    // closes the panel if no angle can clear it).
                    if (!ensureClickable(sack)) {
                        scheduleNextAdaptive(180L, 600L);
                        break;
                    }

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

                // Empty the gem bag only when it is actually full (a slot hit 60). A partial bag keeps
                // accumulating across sack cycles — do not empty it early.
                if (config.useGemBag()
                        && !gemBagEmptiedThisSession
                        && Rs2Gembag.hasGemBag()
                        && Rs2Gembag.isGemBagOpen()
                        && Rs2Gembag.isAnyGemSlotFull()) {
                    Rs2Inventory.interact(ItemID.GEM_BAG_OPEN, "Empty");
                    gemBagEmptiedThisSession = true;
                    scheduleNextAdaptive(350L, CLICK_THROTTLE_MS);
                    log.debug("[SackSession] Emptying gem bag (a gem slot is full)");
                    break;
                }

                // Deposit ores — respect the effective (preflight-resolved) deposit method
                {
                    boolean bagIn0 = gemBagSlotSupplier != null && gemBagSlotSupplier.getAsBoolean();
                    boolean s0Lock = gemBagLockSupplier != null && gemBagLockSupplier.getAsBoolean();
                    log.info("[SackSession] Deposit decision: method={} useGemBag={} bagInInv={} bagInSlot0={} slot0Locked={}",
                            depositMethod(), config.useGemBag(), Rs2Gembag.hasGemBag(), bagIn0, s0Lock);
                }
                if (depositMethod() == DepositMethod.ALL) {
                    boolean bagInInv    = Rs2Gembag.hasGemBag();
                    boolean bagInSlot0  = gemBagSlotSupplier != null && gemBagSlotSupplier.getAsBoolean();
                    boolean slot0Locked = gemBagLockSupplier != null && gemBagLockSupplier.getAsBoolean();
                    if (!depositAllSafe(config.useGemBag(),
                                        DepositMethod.ALL, bagInInv, bagInSlot0, slot0Locked)) {
                        log.warn("[SackSession] Deposit-All unsafe (gem bag unprotected) — aborting to RECOVERY");
                        transitionSub(SackSubState.FAILED);
                        transition(State.FAILED);
                        break;
                    }
                    captureOreDepositSnapshot();
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
                            captureOreDepositSnapshot(oreId);
                            Rs2Inventory.interact(oreId, "Deposit-All");
                            applyActionCooldown();
                            scheduleNextAdaptive(180L, 600L);
                            return; // one item type per tick, come back next tick
                        }
                    }
                    // Safety net: gems carried into the sack trip occupy the slots the ore withdrawals
                    // need. The primary drop happens during mining, where they actually appear.
                    if (config.dropGems() && !config.useGemBag() && dropGemsFromInventory()) {
                        scheduleNextAdaptive(180L, 600L);
                        return;
                    }
                    // Nothing left to deposit. Nuggets are deliberately left in the inventory: they are
                    // not sent to the deposit box like ores, so they accumulate for the sack/ladder
                    // upgrades. The overlay counts them from the owned total either way.
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
                // A click on a game object (ladder, vein, hopper) while the deposit box is still open
                // just closes the interface instead of performing the action — it "eats" the click.
                // We're handing control back to the orchestrator now, which may immediately walk/climb/
                // click something else, so this is the one place that must guarantee the interface is
                // actually gone before reporting complete, regardless of what happens next.
                if (Rs2DepositBox.isOpen()) {
                    log.debug("[SackSession] Deposit box still open on completion — closing before handing back control");
                    Rs2DepositBox.closeDepositBox();
                    scheduleNextAdaptive(150L, 400L);
                    break;
                }
                transition(State.COMPLETE);
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

    /** Uncut gems the sack yields alongside ore. */
    private static final int[] GEM_IDS = {
            ItemID.UNCUT_SAPPHIRE, ItemID.UNCUT_EMERALD, ItemID.UNCUT_RUBY, ItemID.UNCUT_DIAMOND
    };

    /**
     * Drops one gem type per call, so the executor thread is never blocked dropping a full load.
     *
     * @return {@code true} if something was dropped (caller should come back next tick)
     */
    private boolean dropGemsFromInventory() {
        for (int gemId : GEM_IDS) {
            if (Rs2Inventory.hasItem(gemId) && Rs2Inventory.interact(gemId, "Drop")) {
                log.debug("[SackSession] Dropped gem {} (Drop Gems is on and no gem bag in use)", gemId);
                applyActionCooldown();
                return true;
            }
        }
        return false;
    }

    private boolean hasOreInInventory() {
        for (int oreId : ORE_IDS) {
            if (Rs2Inventory.contains(oreId)) return true;
        }
        return false;
    }

    private void captureOreDepositSnapshot() {
        for (int i = 0; i < ORE_IDS.size(); i++) {
            pendingOreDepositSnapshot[i] = Math.max(pendingOreDepositSnapshot[i], Rs2Inventory.itemQuantity(ORE_IDS.get(i)));
        }
    }

    private void captureOreDepositSnapshot(int oreId) {
        int idx = ORE_IDS.indexOf(oreId);
        if (idx >= 0) {
            pendingOreDepositSnapshot[idx] = Math.max(pendingOreDepositSnapshot[idx], Rs2Inventory.itemQuantity(oreId));
        }
    }

    public int[] consumePendingOreDepositSnapshot() {
        int[] copy = java.util.Arrays.copyOf(pendingOreDepositSnapshot, pendingOreDepositSnapshot.length);
        java.util.Arrays.fill(pendingOreDepositSnapshot, 0);
        return copy;
    }

    public WorldPoint getReturnPoint() {
        if (originalMiningSpot == null) return null;
        List<WorldPoint> pts = originalMiningSpot.getWorldPoint();
        return (pts != null && !pts.isEmpty()) ? pts.get(0) : null;
    }
}
