package net.runelite.client.plugins.microbot.irkedfarmer.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.irkedfarmer.IrkedFarmerConfig;
import net.runelite.client.plugins.microbot.shortestpath.Restriction;
import net.runelite.client.plugins.microbot.shortestpath.ShortestPathPlugin;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fossil Island birdhouse run. Ported from the standalone FornBirdhouseRuns plugin: an explicit VARP
 * state machine (dismantle → build → seed, ×4) with confirmed transitions and a stall timeout. Runs
 * its own banking (chisel, hammer, digsite pendant, 4 logs, 40 birdhouse seeds) since its inventory
 * is fixed and small — the shared tree BankService/PatchInteractor don't apply here.
 */
@Slf4j
@RequiredArgsConstructor
public class BirdhouseRunTask implements FarmingTask {
    private final IrkedFarmerConfig cfg;

    private static final WorldPoint HOUSE_1 = new WorldPoint(3763, 3755, 0); // Verdant SW
    private static final WorldPoint HOUSE_2 = new WorldPoint(3768, 3761, 0); // Verdant NE
    private static final WorldPoint HOUSE_3 = new WorldPoint(3677, 3882, 0); // Meadow N
    private static final WorldPoint HOUSE_4 = new WorldPoint(3679, 3815, 0); // Meadow S
    private static final WorldPoint SOUTH_ROWBOAT = new WorldPoint(3724, 3807, 0);
    private static final int MUSHTREE_OBJECT_ID = 30924;
    private static final int VARP_HOUSE_1 = VarPlayerID.BIRDHOUSE_TRANSMIT_D;
    private static final int VARP_HOUSE_2 = VarPlayerID.BIRDHOUSE_TRANSMIT_C;
    private static final int VARP_HOUSE_3 = VarPlayerID.BIRDHOUSE_TRANSMIT_A;
    private static final int VARP_HOUSE_4 = VarPlayerID.BIRDHOUSE_TRANSMIT_B;
    private static final int ARRIVAL_RADIUS = 4;
    private static final int SCENE_INTERACT_RANGE = 25;
    private static final Set<Integer> FOSSIL_ISLAND_REGIONS = Set.of(
            14650, 14651, 14652, 14906, 14907, 14908, 15162, 15163);
    private static final Set<String> BIRDHOUSE_SEED_NAMES = Set.of(
            "potato seed", "onion seed", "cabbage seed", "tomato seed", "sweetcorn seed",
            "strawberry seed", "barley seed", "hammerstone seed", "asgarnian seed", "jute seed",
            "yanillian seed", "krandorian seed", "wildblood seed", "marigold seed", "rosemary seed",
            "nasturtium seed", "woad seed", "limpwurt seed");
    private static final long STATE_STALL_TIMEOUT_MS = 120_000L;
    private static final int MAX_ITERATIONS = 2000;

    private enum State {
        DISMANTLE_1, BUILD_1, SEED_1, DISMANTLE_2, BUILD_2, SEED_2, MUSHROOM_TELEPORT,
        DISMANTLE_3, BUILD_3, SEED_3, DISMANTLE_4, BUILD_4, SEED_4, FINISHING, FINISHED
    }

    @Override
    public String name() {
        return "Birdhouse run";
    }

    @Override
    public boolean isEnabled(IrkedFarmerConfig cfg) {
        return cfg.birdhouseRun();
    }

    @Override
    public boolean isDue() {
        return true; // ponytail: birdhouses are time-based; prediction/timer wired later
    }

    @Override
    public InventoryPlan plan() {
        // Representative, always-feasible plan (execute() does its own dynamic banking).
        return new InventoryPlan()
                .addLoose(ItemID.CHISEL, 1)
                .addLoose(ItemID.HAMMER, 1)
                .addLoose(cfg.birdhouseLogType().getItemId(), 4)
                .reserve(ReservedItem.DIGSITE_PENDANT);
    }

    @Override
    public TaskResult execute() {
        if (Rs2Player.getQuestState(Quest.BONE_VOYAGE) != QuestState.FINISHED) {
            return TaskResult.failed("Birdhouse run needs the quest 'Bone Voyage' finished");
        }
        if (!hasRequiredInventory() && !setupManualInventory()) {
            return TaskResult.failed("Birdhouse run: " + setupErrorMessage);
        }

        try {
            return runStateMachine();
        } finally {
            Rs2Walker.disableTeleports = false;
            ShortestPathPlugin.getPathfinderConfig().setRestrictedTiles();
        }
    }

    private TaskResult runStateMachine() {
        State state = State.DISMANTLE_1;
        State last = null;
        long stateEnteredAtMs = System.currentTimeMillis();
        boolean fossilPrepared = false;

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            if (!Microbot.isLoggedIn()) {
                return TaskResult.failed("logged out mid-run");
            }
            if (!fossilPrepared && isOnFossilIsland()) {
                Rs2Walker.disableTeleports = true;
                blockRubberCapMushrooms();
                fossilPrepared = true;
            }
            if (state != last) {
                last = state;
                stateEnteredAtMs = System.currentTimeMillis();
            } else if (state != State.FINISHED
                    && System.currentTimeMillis() - stateEnteredAtMs > STATE_STALL_TIMEOUT_MS) {
                return TaskResult.failed("stalled in state " + state + " for >" + STATE_STALL_TIMEOUT_MS + "ms");
            }

            switch (state) {
                case DISMANTLE_1: if (dismantle(HOUSE_1, VARP_HOUSE_1)) state = State.BUILD_1; break;
                case BUILD_1:     if (build(HOUSE_1, VARP_HOUSE_1)) state = State.SEED_1; break;
                case SEED_1:      if (seed(HOUSE_1, VARP_HOUSE_1)) state = State.DISMANTLE_2; break;
                case DISMANTLE_2: if (dismantle(HOUSE_2, VARP_HOUSE_2)) state = State.BUILD_2; break;
                case BUILD_2:     if (build(HOUSE_2, VARP_HOUSE_2)) state = State.SEED_2; break;
                case SEED_2:      if (seed(HOUSE_2, VARP_HOUSE_2)) state = State.MUSHROOM_TELEPORT; break;
                case MUSHROOM_TELEPORT:
                    if (!interactById(MUSHTREE_OBJECT_ID, "Use")) {
                        break; // out of click range — walked toward it (if found); retry next tick
                    }
                    Global.sleepUntil(() -> Rs2Widget.findWidget("Mycelium Transportation System") != null, 5000);
                    Rs2Widget.clickWidget("Mushroom Meadow");
                    Global.sleepUntil(() -> Rs2Player.distanceTo(HOUSE_3) < 20, 10000);
                    state = State.DISMANTLE_3;
                    break;
                case DISMANTLE_3: if (dismantle(HOUSE_3, VARP_HOUSE_3)) state = State.BUILD_3; break;
                case BUILD_3:     if (build(HOUSE_3, VARP_HOUSE_3)) state = State.SEED_3; break;
                case SEED_3:      if (seed(HOUSE_3, VARP_HOUSE_3)) state = State.DISMANTLE_4; break;
                case DISMANTLE_4: if (dismantle(HOUSE_4, VARP_HOUSE_4)) state = State.BUILD_4; break;
                case BUILD_4:     if (build(HOUSE_4, VARP_HOUSE_4)) state = State.SEED_4; break;
                case SEED_4:      if (seed(HOUSE_4, VARP_HOUSE_4)) state = State.FINISHING; break;
                case FINISHING:
                    emptyNests();
                    if (cfg.birdhouseGoToBank()) {
                        Rs2Walker.walkTo(SOUTH_ROWBOAT, 3);
                        Rs2Walker.walkTo(BankLocation.FOSSIL_ISLAND_WRECK.getWorldPoint());
                        if (!Rs2Bank.isOpen()) Rs2Bank.openBank();
                        Rs2Bank.depositAll();
                    }
                    state = State.FINISHED;
                    break;
                case FINISHED:
                    return TaskResult.completed("Birdhouse run finished");
            }
            Global.sleep(300, 600);
        }
        return TaskResult.failed("birdhouse run exceeded max iterations");
    }

    // ---- state predicates (match RuneLite BirdHouseState.fromVarpValue) ----
    private static boolean isEmpty(int varp)  { return varp == 0; }
    private static boolean isBuilt(int varp)  { return varp > 0 && varp % 3 != 0; }
    private static boolean isSeeded(int varp) { return varp > 0 && varp % 3 == 0; }

    private boolean dismantle(WorldPoint loc, int varpId) {
        if (!isOnFossilIsland() && !arrivedAndStill(loc)) return false;
        int varp = Microbot.getVarbitPlayerValue(varpId);
        if (!isSeeded(varp)) return true; // nothing to empty
        if (!interactAt(loc, "Empty")) {
            return arrivedAndStill(loc) && false;
        }
        return Global.sleepUntil(() -> isEmpty(Microbot.getVarbitPlayerValue(varpId)), 10000);
    }

    private boolean build(WorldPoint loc, int varpId) {
        if (!isOnFossilIsland() && !arrivedAndStill(loc)) return false;
        int varp = Microbot.getVarbitPlayerValue(varpId);
        if (!isEmpty(varp)) return true; // already built/seeded
        if (Rs2Inventory.count(cfg.birdhouseLogType().getItemId()) == 0) {
            log.error("Birdhouse build: out of {} — aborting", cfg.birdhouseLogType().getItemName());
            return false;
        }
        if (!interactAt(loc, "Build")) {
            return arrivedAndStill(loc) && false;
        }
        return Global.sleepUntil(() -> !isEmpty(Microbot.getVarbitPlayerValue(varpId)), 15000);
    }

    private boolean seed(WorldPoint loc, int varpId) {
        if (!isOnFossilIsland() && !arrivedAndStill(loc)) return false;
        int varp = Microbot.getVarbitPlayerValue(varpId);
        if (isEmpty(varp)) return false;      // can't seed an empty space
        if (isSeeded(varp)) return true;      // already seeded
        Rs2ItemModel seed = findInventorySeed(10).orElse(null);
        if (seed == null) {
            log.error("Birdhouse seed: no seed stack ≥10 in inventory");
            return false;
        }
        int seedId = seed.getId();
        int before = seed.getQuantity();
        if (!Rs2Inventory.use(seedId)) return false;
        if (!Global.sleepUntil(() -> Rs2Inventory.getSelectedItemId() == seedId, 2000)) return false;
        if (!interactAt(loc, null)) {
            return arrivedAndStill(loc) && false;
        }
        return Global.sleepUntil(() ->
                findInventorySeed(1).map(Rs2ItemModel::getQuantity).orElse(0) < before, 10000);
    }

    private void emptyNests() {
        List<Integer> nestIds = List.of(
                ItemID.BIRD_NEST_EGG_RED, ItemID.BIRD_NEST_EGG_GREEN, ItemID.BIRD_NEST_EGG_BLUE,
                ItemID.BIRD_NEST_SEEDS, ItemID.BIRD_NEST_RING,
                ItemID.BIRD_NEST_SEEDS_JAN2019, ItemID.BIRD_NEST_DECENTSEEDS_JAN2019);
        Rs2Inventory.items().forEachOrdered(item -> {
            if (nestIds.contains(item.getId())) {
                Rs2Inventory.interact(item, "Search");
            }
        });
    }

    /**
     * Birdhouse structures change object id as they cycle empty/built/seeded — find by tile instead.
     * Unlike the deprecated {@code Rs2GameObject.interact(WorldPoint, action)} this replaced, the
     * queryable's {@code click()} does NOT auto-walk to a distant target (documented Microbot gotcha).
     * The isOnFossilIsland() bypass in dismantle/build/seed skips the normal arrivedAndStill() walk
     * gate, and houses 3→4 are ~67 tiles apart with no teleport between them, so walk here explicitly
     * or the state machine would silently stall until the 120s timeout.
     */
    private static boolean interactAt(WorldPoint loc, String action) {
        Rs2TileObjectModel obj = Microbot.getRs2TileObjectCache().query()
                .where(o -> loc.equals(o.getWorldLocation()))
                .nearest();
        return clickOrWalkTo(obj, loc, action);
    }

    /** Same not-yet-in-range gap as interactAt, for objects looked up by id (e.g. the mushtree). */
    private static boolean interactById(int id, String action) {
        Rs2TileObjectModel obj = Microbot.getRs2TileObjectCache().query().withId(id).nearest();
        return clickOrWalkTo(obj, obj != null ? obj.getWorldLocation() : null, action);
    }

    private static boolean clickOrWalkTo(Rs2TileObjectModel obj, WorldPoint fallbackLoc, String action) {
        if (obj == null || Rs2Player.distanceTo(obj.getWorldLocation()) > SCENE_INTERACT_RANGE) {
            if (fallbackLoc != null) {
                Rs2Walker.walkTo(fallbackLoc, SCENE_INTERACT_RANGE);
            }
            return false;
        }
        return action == null ? obj.click() : obj.click(action);
    }

    private long lastArrivedLogMs;
    private WorldPoint lastArrivedLogTarget;

    private boolean arrivedAndStill(WorldPoint loc) {
        if (Rs2Player.distanceTo(loc) <= ARRIVAL_RADIUS) return true;
        if (Rs2Player.isMoving()) return false;
        Rs2Walker.walkTo(loc, SCENE_INTERACT_RANGE);
        return false;
    }

    private static void blockRubberCapMushrooms() {
        ShortestPathPlugin.getPathfinderConfig().setRestrictedTiles(
                new Restriction(3663, 3808, 0), new Restriction(3664, 3808, 0),
                new Restriction(3665, 3808, 0), new Restriction(3666, 3809, 0),
                new Restriction(3666, 3810, 0));
    }

    private boolean isOnFossilIsland() {
        WorldPoint loc = Rs2Player.getWorldLocation();
        return loc != null && FOSSIL_ISLAND_REGIONS.contains(loc.getRegionID());
    }

    private static boolean isBirdhouseSeed(Rs2ItemModel item) {
        return item != null && item.getName() != null
                && BIRDHOUSE_SEED_NAMES.contains(item.getName().toLowerCase());
    }

    private static Optional<Rs2ItemModel> findInventorySeed(int minQty) {
        return Rs2Inventory.items()
                .filter(BirdhouseRunTask::isBirdhouseSeed)
                .filter(item -> item.getQuantity() >= minQty)
                .findFirst();
    }

    private static Optional<Rs2ItemModel> findBankSeed(int minQty) {
        return Rs2Bank.bankItems().stream()
                .filter(BirdhouseRunTask::isBirdhouseSeed)
                .filter(item -> item.getQuantity() >= minQty)
                .findFirst();
    }

    private boolean hasRequiredInventory() {
        if (Rs2Inventory.count(ItemID.CHISEL) < 1 || Rs2Inventory.count(ItemID.HAMMER) < 1) return false;
        if (!isOnFossilIsland() && findInventoryDigsite() == null) return false;
        if (Rs2Inventory.count(cfg.birdhouseLogType().getItemId()) < 4) return false;
        return findInventorySeed(40).isPresent();
    }

    private Integer findInventoryDigsite() {
        for (int id : DIGSITE_IDS) {
            if (Rs2Inventory.count(id) >= 1) return id;
        }
        return null;
    }

    private static final List<Integer> DIGSITE_IDS = Arrays.asList(
            ItemID.NECKLACE_OF_DIGSITE_1, ItemID.NECKLACE_OF_DIGSITE_2, ItemID.NECKLACE_OF_DIGSITE_3,
            ItemID.NECKLACE_OF_DIGSITE_4, ItemID.NECKLACE_OF_DIGSITE_5);

    private String setupErrorMessage = "";

    private boolean setupManualInventory() {
        Rs2Walker.walkTo(Rs2Bank.getNearestBank().getWorldPoint(), 20);
        if (!Rs2Bank.openBank()) {
            setupErrorMessage = "could not open bank";
            return false;
        }
        Global.sleepUntil(Rs2Bank::isOpen);
        Rs2Bank.depositAll();
        Rs2Inventory.waitForInventoryChanges(5000);

        if (!Rs2Bank.withdrawX(ItemID.CHISEL, 1)) { setupErrorMessage = "missing chisel in bank"; return false; }
        Rs2Inventory.waitForInventoryChanges(2000);
        if (!Rs2Bank.withdrawX(ItemID.HAMMER, 1)) { setupErrorMessage = "missing hammer in bank"; return false; }
        Rs2Inventory.waitForInventoryChanges(2000);

        boolean pendantOk = isOnFossilIsland(); // on-island the pendant is dead weight
        if (!pendantOk) {
            for (int id : DIGSITE_IDS) {
                if (!Microbot.isLoggedIn()) break;
                if (Rs2Bank.withdrawX(id, 1)) {
                    Rs2Inventory.waitForInventoryChanges(2000);
                    pendantOk = true;
                    break;
                }
            }
        }
        if (!pendantOk) { setupErrorMessage = "missing digsite pendant in bank"; return false; }

        int logId = cfg.birdhouseLogType().getItemId();
        if (Rs2Bank.count(logId) < 4) {
            setupErrorMessage = "need 4 " + cfg.birdhouseLogType().getItemName() + " in bank";
            return false;
        }
        if (!Rs2Bank.withdrawX(logId, 4)) { setupErrorMessage = "failed to withdraw logs"; return false; }
        Rs2Inventory.waitForInventoryChanges(2000);

        Rs2ItemModel bankSeed = findBankSeed(40).orElse(null);
        if (bankSeed == null) { setupErrorMessage = "no birdhouse seed type with 40+ in bank"; return false; }
        if (!Rs2Bank.withdrawX(bankSeed.getId(), 40)) { setupErrorMessage = "failed to withdraw seeds"; return false; }
        Rs2Inventory.waitForInventoryChanges(3000);
        if (findInventorySeed(40).map(Rs2ItemModel::getQuantity).orElse(0) < 40) {
            setupErrorMessage = "withdrew seeds but got fewer than 40";
            return false;
        }

        Rs2Bank.closeBank();
        Global.sleepUntil(() -> !Rs2Bank.isOpen());
        return true;
    }
}
