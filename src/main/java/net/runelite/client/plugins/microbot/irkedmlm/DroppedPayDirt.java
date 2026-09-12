package net.runelite.client.plugins.microbot.irkedmlm;

import net.runelite.api.coords.WorldPoint;

/**
 * Remembers pay-dirt dropped to make inventory room for emptying the sack, so it can be picked back
 * up once the sack is clear.
 *
 * <p>Dropping is unavoidable: withdrawing ore from the sack needs free slots, and a full load of
 * pay-dirt has none to give. But walking away and leaving 27 pay-dirt on the floor is both wasteful
 * and conspicuous — a real player drops it, empties the sack, and immediately picks it back up,
 * because that pay-dirt is several minutes of mining.
 *
 * <p>Own drops stay visible only to you for a couple of minutes and then vanish, so the claim has an
 * expiry: past {@link #CLAIM_TTL_MS} the pile is written off rather than chased. All timing state is
 * plain fields with no client access, so the decision logic is unit-testable.
 */
public final class DroppedPayDirt {

    /** How long a dropped pile is worth going back for. Beyond this it has likely despawned. */
    static final long CLAIM_TTL_MS = 150_000L;

    /** Give up collecting after this long, so a blocked or unreachable pile can't stall the run. */
    static final long COLLECT_TIMEOUT_MS = 30_000L;

    private WorldPoint where;
    private int count;
    private long droppedAtMs;
    private long collectStartedMs;

    /** Records a drop. A second drop before the first is collected replaces the claim. */
    public void note(WorldPoint at, int amount, long nowMs) {
        if (amount <= 0) {
            return;
        }
        where = at;
        count = amount;
        droppedAtMs = nowMs;
        collectStartedMs = 0L;
    }

    public void clear() {
        where = null;
        count = 0;
        droppedAtMs = 0L;
        collectStartedMs = 0L;
    }

    public WorldPoint getWhere() {
        return where;
    }

    public int getCount() {
        return count;
    }

    /** True while there is a pile we still intend to collect. */
    public boolean isPending(long nowMs) {
        return where != null && count > 0 && !isExpired(nowMs);
    }

    /** True once the pile is old enough that it has probably despawned. */
    public boolean isExpired(long nowMs) {
        return droppedAtMs > 0L && nowMs - droppedAtMs > CLAIM_TTL_MS;
    }

    /** Starts (or continues) the collection attempt, returning how long it has been running. */
    public long beginCollecting(long nowMs) {
        if (collectStartedMs == 0L) {
            collectStartedMs = nowMs;
        }
        return nowMs - collectStartedMs;
    }

    /** True when the collection attempt has run long enough that we should stop trying. */
    public boolean collectTimedOut(long nowMs) {
        return collectStartedMs > 0L && nowMs - collectStartedMs > COLLECT_TIMEOUT_MS;
    }

    /**
     * Whether it is worth walking back for this pile right now.
     *
     * @param nowMs      current time
     * @param freeSlots  inventory slots available to receive it
     * @param atDropSite whether the player is already near where it was dropped
     */
    public boolean shouldCollect(long nowMs, int freeSlots, boolean atDropSite) {
        if (!isPending(nowMs)) {
            return false;
        }
        if (freeSlots <= 0) {
            return false; // nowhere to put it — the sack emptying clearly did not free anything
        }
        if (collectTimedOut(nowMs)) {
            return false;
        }
        // Only chase it if we're still in the area. Once the run has moved on, the walk back costs
        // more than the pay-dirt is worth and looks nothing like a player.
        return atDropSite;
    }
}
