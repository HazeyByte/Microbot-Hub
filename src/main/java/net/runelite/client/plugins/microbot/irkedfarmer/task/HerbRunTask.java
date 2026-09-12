package net.runelite.client.plugins.microbot.irkedfarmer.task;

import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Rs2Leprechaun;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileitem.models.Rs2TileItemModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.irkedfarmer.IrkedFarmerConfig;
import net.runelite.client.plugins.microbot.irkedfarmer.model.AllotmentSeedType;
import net.runelite.client.plugins.microbot.irkedfarmer.model.CompostType;
import net.runelite.client.plugins.microbot.irkedfarmer.model.FlowerSeedType;
import net.runelite.client.plugins.microbot.irkedfarmer.model.HerbPatch;
import net.runelite.client.plugins.microbot.irkedfarmer.model.HerbSeedType;
import net.runelite.client.plugins.microbot.irkedfarmer.service.ActionMatch;
import net.runelite.client.plugins.microbot.irkedfarmer.service.EquipmentService;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.CropState;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.FarmingHandler;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.FarmingPatch;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.FarmingWorld;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.timetracking.Tab;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Herb run (herb + flower + allotment patches across enabled regions). Ported from the standalone
 * herbrun plugin: a per-region LocationPhase state machine (HERB → FLOWER → ALLOTMENT → DONE) driven
 * by FarmingWorld prediction, with leprechaun noting and its own banking.
 *
 * FIELD BUG FIX: compost is now BEST-EFFORT. The legacy {@code applyCompost} returned false when the
 * bottomless bucket was missing, and the patch handler bailed on that — so the plant never happened
 * and the run stalled after harvest. Here a missing compost logs and plants WITHOUT it, never blocking.
 */
public class HerbRunTask implements FarmingTask {
    private final IrkedFarmerConfig cfg;
    private final FarmingWorld farmingWorld;
    private final ClientThread clientThread;
    private final ConfigManager configManager;

    private FarmingHandler farmingHandler;
    private HerbPatch currentPatch;
    private LocationPhase currentPhase = LocationPhase.HERB;

    private final List<HerbPatch> herbPatches = new ArrayList<>();
    private final Map<String, List<FarmingPatch>> allotmentsByRegion = new HashMap<>();
    private final Map<String, FarmingPatch> flowerByRegion = new HashMap<>();
    private final Set<Integer> handledAllotmentIds = new HashSet<>();
    private int currentAllotmentId = -1;

    private static final int MAX_ITERATIONS = 4000;

    private enum LocationPhase { HERB, FLOWER, ALLOTMENT, DONE }

    private static final Set<String> ALLOTMENT_FLOWER_REGIONS = Set.of(
            "Ardougne", "Catherby", "Civitas illa Fortis", "Falador", "Kourend", "Morytania");

    public HerbRunTask(IrkedFarmerConfig cfg, FarmingWorld farmingWorld, ClientThread clientThread, ConfigManager configManager) {
        this.cfg = cfg;
        this.farmingWorld = farmingWorld;
        this.clientThread = clientThread;
        this.configManager = configManager;
    }

    @Override
    public String name() {
        return "Herb run";
    }

    @Override
    public boolean isEnabled(IrkedFarmerConfig cfg) {
        return cfg.herbRun();
    }

    @Override
    public boolean isDue() {
        return true; // ponytail: populatePatches already filters GROWING patches; whole run skips if empty
    }

    @Override
    public InventoryPlan plan() {
        return new InventoryPlan(); // herb run does its own dynamic banking
    }

    @Override
    public TaskResult execute() {
        populatePatches();
        if (herbPatches.isEmpty()) {
            return TaskResult.skipped("no herb patches ready");
        }
        if (!setupAutoInventory()) {
            return TaskResult.failed("Herb run: inventory setup failed");
        }

        currentPatch = null;
        currentPhase = LocationPhase.HERB;
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            if (!Microbot.isLoggedIn()) {
                return TaskResult.failed("logged out mid-run");
            }
            if (currentPatch == null) {
                getNextPatch();
                currentPhase = LocationPhase.HERB;
            }
            if (currentPatch == null) {
                if (cfg.herbGoToBank()) {
                    Rs2Walker.walkTo(Rs2Bank.getNearestBank().getWorldPoint());
                    if (!Rs2Bank.isOpen()) Rs2Bank.openBank();
                    Rs2Bank.depositAll();
                }
                return TaskResult.completed("Herb run finished");
            }
            if (!currentPatch.isEnabled()) {
                currentPatch = null;
                continue;
            }
            if (!currentPatch.isInRange(40)) {
                Rs2Walker.walkTo(currentPatch.getLocation(), 20);
                Global.sleep(600, 1000);
                continue;
            }

            String region = currentPatch.getRegionName();
            switch (currentPhase) {
                case HERB:
                    if (handleHerbPatch()) currentPhase = LocationPhase.FLOWER;
                    break;
                case FLOWER:
                    if (!cfg.enableFlowers() || !flowerByRegion.containsKey(region)) {
                        currentPhase = LocationPhase.ALLOTMENT;
                    } else if (handleFlowerPatch(region)) {
                        currentPhase = LocationPhase.ALLOTMENT;
                    }
                    break;
                case ALLOTMENT:
                    if (!cfg.enableAllotments() || !allotmentsByRegion.containsKey(region)) {
                        currentPhase = LocationPhase.DONE;
                    } else if (handleAllotmentPatches(region)) {
                        currentPhase = LocationPhase.DONE;
                    }
                    break;
                case DONE:
                    currentPatch = null;
                    currentPhase = LocationPhase.HERB;
                    handledAllotmentIds.clear();
                    currentAllotmentId = -1;
                    break;
            }
            Global.sleep(400, 800);
        }
        return TaskResult.failed("herb run exceeded max iterations");
    }

    private void populatePatches() {
        this.farmingHandler = new FarmingHandler(Microbot.getClient(), configManager);
        herbPatches.clear();
        allotmentsByRegion.clear();
        flowerByRegion.clear();

        clientThread.runOnClientThreadOptional(() -> {
            Map<String, HerbPatch> allHerbsByRegion = new HashMap<>();
            for (FarmingPatch patch : farmingWorld.getTabs().get(Tab.HERB)) {
                HerbPatch p = new HerbPatch(patch, cfg, farmingHandler);
                if (!p.isEnabled()) continue;
                allHerbsByRegion.put(p.getRegionName(), p);
                if (p.getPrediction() != CropState.GROWING) {
                    herbPatches.add(p);
                }
            }
            if (cfg.enableAllotments()) {
                for (FarmingPatch patch : farmingWorld.getTabs().get(Tab.ALLOTMENT)) {
                    String region = patch.getRegion().getName();
                    if (!ALLOTMENT_FLOWER_REGIONS.contains(region)) continue;
                    if (farmingHandler.predictPatch(patch) != CropState.GROWING) {
                        allotmentsByRegion.computeIfAbsent(region, k -> new ArrayList<>()).add(patch);
                    }
                }
            }
            if (cfg.enableFlowers()) {
                for (FarmingPatch patch : farmingWorld.getTabs().get(Tab.FLOWER)) {
                    String region = patch.getRegion().getName();
                    if (!ALLOTMENT_FLOWER_REGIONS.contains(region)) continue;
                    if (farmingHandler.predictPatch(patch) != CropState.GROWING) {
                        flowerByRegion.put(region, patch);
                    }
                }
            }
            for (String region : allHerbsByRegion.keySet()) {
                boolean already = herbPatches.stream().anyMatch(p -> p.getRegionName().equals(region));
                if (already) continue;
                if (allotmentsByRegion.containsKey(region) || flowerByRegion.containsKey(region)) {
                    herbPatches.add(allHerbsByRegion.get(region));
                }
            }
            return true;
        });
    }

    private void getNextPatch() {
        if (currentPatch != null || herbPatches.isEmpty()) return;
        currentPatch = herbPatches.stream()
                .filter(p -> p.isEnabled() && Objects.equals(p.getRegionName(), "Weiss"))
                .findFirst()
                .orElseGet(() -> herbPatches.stream().filter(HerbPatch::isEnabled).findFirst().orElse(null));
        if (currentPatch != null) {
            herbPatches.remove(currentPatch);
        }
    }

    private static final int[] ALLOTMENT_PATCH_IDS = {
            ObjectID.FARMING_VEG_PATCH_1, ObjectID.FARMING_VEG_PATCH_2, ObjectID.FARMING_VEG_PATCH_3,
            ObjectID.FARMING_VEG_PATCH_4, ObjectID.FARMING_VEG_PATCH_5, ObjectID.FARMING_VEG_PATCH_6,
            ObjectID.FARMING_VEG_PATCH_7, ObjectID.FARMING_VEG_PATCH_8, ObjectID.FARMING_VEG_PATCH_9,
            ObjectID.FARMING_VEG_PATCH_10, ObjectID.FARMING_VEG_PATCH_11, ObjectID.FARMING_VEG_PATCH_12,
            ObjectID.FARMING_VEG_PATCH_13, ObjectID.FARMING_VEG_PATCH_14, ObjectID.FARMING_VEG_PATCH_15,
            ObjectID.FARMING_VEG_PATCH_16, ObjectID.FARMING_VEG_PATCH_17
    };
    private static final int[] HERB_PATCH_IDS = {
            ObjectID.MYARM_HERBPATCH, ObjectID.FARMING_HERB_PATCH_2, ObjectID.FARMING_HERB_PATCH_4,
            ObjectID.FARMING_HERB_PATCH_8, ObjectID.FARMING_HERB_PATCH_6, ObjectID.FARMING_HERB_PATCH_3,
            ObjectID.FARMING_HERB_PATCH_1, ObjectID.FARMING_HERB_PATCH_7, ObjectID.MY2ARM_HERBPATCH,
            ObjectID.FARMING_HERB_PATCH_5
    };
    private static final int[] FLOWER_PATCH_IDS = {
            ObjectID.FARMING_FLOWER_PATCH_1, ObjectID.FARMING_FLOWER_PATCH_2, ObjectID.FARMING_FLOWER_PATCH_3,
            ObjectID.FARMING_FLOWER_PATCH_4, ObjectID.FARMING_FLOWER_PATCH_5, ObjectID.FARMING_FLOWER_PATCH_6,
            ObjectID.FARMING_FLOWER_PATCH_7, ObjectID.FARMING_FLOWER_PATCH_8, ObjectID.FARMING_FLOWER_PATCH_9
    };

    private boolean handleHerbPatch() {
        if (!ensureInventorySpace()) return false;
        final var obj = Microbot.getRs2TileObjectCache().query().withIds(HERB_PATCH_IDS).nearest();
        if (obj == null) return true;
        String state = getHerbPatchState(obj);

        if (state.equals("Harvestable")) {
            obj.click("Pick");
            Rs2Player.waitForWalking();
            Global.sleepUntil(() -> getHerbPatchState(obj).equals("Empty") || Rs2Inventory.isFull(), 20000);
            return false;
        }
        if (state.equals("Weeds")) {
            obj.click("Rake");
            Rs2Player.waitForWalking();
            Global.sleepUntil(() -> !getHerbPatchState(obj).equals("Weeds"), 15000);
            state = getHerbPatchState(obj);
        }
        if (state.equals("Dead")) {
            obj.click("Clear");
            Rs2Player.waitForWalking();
            Global.sleepUntil(() -> getHerbPatchState(obj).equals("Empty"), 10000);
            state = getHerbPatchState(obj);
        }
        if (state.equals("Empty")) {
            if (Rs2Inventory.hasItem("Weeds")) Rs2Inventory.dropAll("Weeds");
            HerbSeedType seed = getFirstHerbSeedInInventory();
            if (seed == null) {
                Microbot.log("No herb seeds in inventory, skipping patch");
                return true;
            }
            applyCompost(obj); // best-effort — never blocks the plant
            Rs2Inventory.use(seed.getItemId());
            obj.click("Plant");
            Rs2Player.waitForWalking();
            Global.sleepUntil(() -> getHerbPatchState(obj).equals("Growing"), 10000);
            return false;
        }
        return true;
    }

    private boolean handleFlowerPatch(String region) {
        if (!ensureInventorySpace()) return false;
        final var obj = Microbot.getRs2TileObjectCache().query().withIds(FLOWER_PATCH_IDS).nearest();
        if (obj == null) return true;
        String state = getPatchState(obj);
        switch (state) {
            case "Harvestable":
                noteProduceViaLeprechaun();
                obj.click("Pick");
                Rs2Player.waitForWalking();
                Global.sleepUntil(() -> getPatchState(obj).equals("Empty") || Rs2Inventory.isFull(), 10000);
                recoverOwnLimpwurtDrops();
                return false;
            case "Weeds":
                obj.click("Rake");
                Rs2Player.waitForWalking();
                Global.sleepUntil(() -> !getPatchState(obj).equals("Weeds"), 15000);
                return false;
            case "Dead":
                obj.click("Clear");
                Rs2Player.waitForWalking();
                Global.sleepUntil(() -> getPatchState(obj).equals("Empty"), 10000);
                return false;
            case "Empty":
                if (Rs2Inventory.hasItem("Weeds")) Rs2Inventory.dropAll("Weeds");
                FlowerSeedType flowerSeed = cfg.flowerSeed();
                if (!Rs2Inventory.hasItem(flowerSeed.getItemId())) {
                    Microbot.log("No " + flowerSeed.getSeedName() + " in inventory, skipping flowers");
                    return true;
                }
                applyCompost(obj); // best-effort
                Rs2Inventory.use(flowerSeed.getItemId());
                obj.click("Plant");
                Rs2Player.waitForWalking();
                return Global.sleepUntil(() -> getPatchState(obj).equals("Growing"), 10000);
            default:
                return true;
        }
    }

    private boolean handleAllotmentPatches(String region) {
        if (!ensureInventorySpace()) return false;
        var allObjects = Microbot.getRs2TileObjectCache().query().withIds(ALLOTMENT_PATCH_IDS).toList();
        if (allObjects == null || allObjects.isEmpty()) {
            currentAllotmentId = -1;
            return true;
        }
        final WorldPoint playerLoc = Rs2Player.getWorldLocation();
        Rs2TileObjectModel pinned = null;
        if (currentAllotmentId >= 0) {
            pinned = allObjects.stream()
                    .filter(o -> o.getId() == currentAllotmentId && hasStandableNeighbor(o.getWorldLocation()))
                    .min(Comparator.comparingInt(o -> sqDist(o.getWorldLocation(), playerLoc)))
                    .orElse(null);
            if (pinned == null || handledAllotmentIds.contains(currentAllotmentId)) {
                currentAllotmentId = -1;
                pinned = null;
            }
        }
        if (currentAllotmentId < 0) {
            pinned = allObjects.stream()
                    .filter(o -> !handledAllotmentIds.contains(o.getId()) && hasStandableNeighbor(o.getWorldLocation()))
                    .min(Comparator.comparingInt(o -> sqDist(o.getWorldLocation(), playerLoc)))
                    .orElse(null);
            if (pinned == null) return true;
            currentAllotmentId = pinned.getId();
        }
        final var obj = pinned;
        String state = getPatchState(obj);
        switch (state) {
            case "Harvestable":
                obj.click("Pick");
                Rs2Player.waitForWalking();
                Global.sleepUntil(() -> getPatchState(obj).equals("Empty") || Rs2Inventory.isFull(), 20000);
                return false;
            case "Weeds":
                obj.click("Rake");
                Rs2Player.waitForWalking();
                Global.sleepUntil(() -> !getPatchState(obj).equals("Weeds"), 15000);
                return false;
            case "Dead":
                obj.click("Clear");
                Rs2Player.waitForWalking();
                Global.sleepUntil(() -> getPatchState(obj).equals("Empty"), 10000);
                return false;
            case "Empty":
                if (Rs2Inventory.hasItem("Weeds")) Rs2Inventory.dropAll("Weeds");
                AllotmentSeedType allotmentSeed = getFirstAllotmentSeedInInventory();
                if (allotmentSeed == null || Rs2Inventory.itemQuantity(allotmentSeed.getItemId()) < 3) {
                    handledAllotmentIds.add(currentAllotmentId);
                    currentAllotmentId = -1;
                    return false;
                }
                applyCompost(obj); // best-effort
                Rs2Inventory.use(allotmentSeed.getItemId());
                obj.click("Plant");
                Rs2Player.waitForWalking();
                if (Global.sleepUntil(() -> getPatchState(obj).equals("Growing"), 10000)) {
                    handledAllotmentIds.add(currentAllotmentId);
                    currentAllotmentId = -1;
                }
                return false;
            default:
                handledAllotmentIds.add(currentAllotmentId);
                currentAllotmentId = -1;
                return false;
        }
    }

    /**
     * Apply compost — BEST-EFFORT. Returns void: a missing bucket or leprechaun compost is logged and
     * the run plants WITHOUT compost. This is the fix for the harvest→idle stall (legacy blocked here).
     */
    private void applyCompost(Rs2TileObjectModel obj) {
        CompostType compost = cfg.compostType();
        if (compost == CompostType.NONE) return;
        if (!Rs2Inventory.hasItem(compost.getItemId())) {
            if (compost.isReusable()) {
                Microbot.log("Bottomless bucket not in inventory — planting without compost");
                return;
            }
            if (!Rs2Leprechaun.withdrawCompost(compost.getItemId())) {
                Microbot.log("No " + compost.getLabel() + " from leprechaun — planting without compost");
                return;
            }
        }
        int xpBefore = Microbot.getClient().getSkillExperience(Skill.FARMING);
        Rs2Inventory.use(compost.getItemId());
        obj.click("Compost");
        Rs2Player.waitForWalking();
        boolean applied = Global.sleepUntil(
                () -> Microbot.getClient().getSkillExperience(Skill.FARMING) > xpBefore, 5000);
        if (applied && cfg.dropEmptyBuckets() && !compost.isReusable()) {
            Rs2Inventory.drop(ItemID.BUCKET_EMPTY);
        }
    }

    private boolean ensureInventorySpace() {
        if (!Rs2Inventory.isFull()) return true;
        return noteProduceViaLeprechaun();
    }

    private boolean noteProduceViaLeprechaun() {
        Rs2NpcModel leprechaun = Microbot.getRs2NpcCache().query().withName("Tool leprechaun").nearestOnClientThread();
        if (leprechaun == null) return false;
        Set<Integer> seen = new HashSet<>();
        List<Rs2ItemModel> toNote = new ArrayList<>();
        for (Rs2ItemModel item : Rs2Inventory.all()) {
            if (item == null || item.isNoted() || item.getName() == null) continue;
            if (!isNotableProduce(item.getName())) continue;
            if (Rs2Inventory.count(item.getId()) <= 1) continue;
            if (seen.add(item.getId())) toNote.add(item);
        }
        boolean notedAny = false;
        for (Rs2ItemModel item : toNote) {
            Rs2Inventory.use(item);
            leprechaun.click("Talk-to");
            Rs2Inventory.waitForInventoryChanges(10000);
            notedAny = true;
        }
        if (notedAny) return true;
        if (Rs2Inventory.hasItem("Weeds")) {
            Rs2Inventory.dropAll("Weeds");
            return true;
        }
        if (Rs2Inventory.hasItem(ItemID.BUCKET_EMPTY)) {
            Rs2Inventory.drop(ItemID.BUCKET_EMPTY);
            return true;
        }
        return false;
    }

    private void recoverOwnLimpwurtDrops() {
        for (int i = 0; i < 10; i++) {
            Rs2TileItemModel root = Microbot.getRs2TileItemCache().query()
                    .withId(ItemID.LIMPWURT_ROOT)
                    .where(Rs2TileItemModel::isOwned)
                    .within(3)
                    .nearest();
            if (root == null) return;
            if (Rs2Inventory.isFull() && !noteProduceViaLeprechaun()) return;
            if (!root.pickup()) return;
            Rs2Inventory.waitForInventoryChanges(3000);
        }
    }

    private static final String[] ALLOTMENT_PRODUCE = {
            "Potato", "Onion", "Cabbage", "Tomato", "Sweetcorn", "Strawberry", "Watermelon", "Snape grass"};
    private static final String[] FLOWER_PRODUCE = {
            "Marigold", "Rosemary", "Nasturtium", "Woad leaf", "Limpwurt root", "White lily"};

    private static boolean isNotableProduce(String name) {
        if (name.startsWith("Grimy")) return true;
        for (String p : ALLOTMENT_PRODUCE) if (name.equals(p)) return true;
        for (String p : FLOWER_PRODUCE) if (name.equals(p)) return true;
        return false;
    }

    private HerbSeedType getFirstHerbSeedInInventory() {
        for (HerbSeedType herbType : HerbSeedType.values()) {
            if (herbType != HerbSeedType.BEST && Rs2Inventory.hasItem(herbType.getItemId())) {
                return herbType;
            }
        }
        return null;
    }

    private AllotmentSeedType getFirstAllotmentSeedInInventory() {
        if (cfg.allotmentSeed() != AllotmentSeedType.BEST) {
            AllotmentSeedType selected = cfg.allotmentSeed();
            return Rs2Inventory.hasItem(selected.getItemId()) ? selected : null;
        }
        for (AllotmentSeedType seed : AllotmentSeedType.getPlantableSeeds(
                Microbot.getClient().getRealSkillLevel(Skill.FARMING))) {
            if (Rs2Inventory.hasItem(seed.getItemId())) return seed;
        }
        return null;
    }

    private static boolean hasStandableNeighbor(WorldPoint tile) {
        if (tile == null) return false;
        return Rs2Tile.isWalkable(tile.dx(1)) || Rs2Tile.isWalkable(tile.dx(-1))
                || Rs2Tile.isWalkable(tile.dy(1)) || Rs2Tile.isWalkable(tile.dy(-1));
    }

    private static int sqDist(WorldPoint a, WorldPoint b) {
        if (a == null || b == null) return Integer.MAX_VALUE;
        int dx = a.getX() - b.getX();
        int dy = a.getY() - b.getY();
        return dx * dx + dy * dy;
    }

    private static String getHerbPatchState(Rs2TileObjectModel rs2TileObject) {
        var comp = ActionMatch.safeComposition(rs2TileObject);
        if (comp == null) return "Growing"; // transient lookup failure — leave it alone, don't guess
        int v = Microbot.getVarbitValue(comp.getVarbitId());
        if ((v >= 0 && v < 3) || (v >= 60 && v <= 67) || (v >= 173 && v <= 191)
                || (v >= 204 && v <= 219) || (v >= 221 && v <= 255)) return "Weeds";
        if ((v >= 4 && v <= 7) || (v >= 11 && v <= 14) || (v >= 18 && v <= 21) || (v >= 25 && v <= 28)
                || (v >= 32 && v <= 35) || (v >= 39 && v <= 42) || (v >= 46 && v <= 49) || (v >= 53 && v <= 56)
                || (v >= 68 && v <= 71) || (v >= 75 && v <= 78) || (v >= 82 && v <= 85) || (v >= 89 && v <= 92)
                || (v >= 96 && v <= 99) || (v >= 103 && v <= 106) || (v >= 192 && v <= 195)) return "Growing";
        if ((v >= 8 && v <= 10) || (v >= 15 && v <= 17) || (v >= 22 && v <= 24) || (v >= 29 && v <= 31)
                || (v >= 36 && v <= 38) || (v >= 43 && v <= 45) || (v >= 50 && v <= 52) || (v >= 57 && v <= 59)
                || (v >= 72 && v <= 74) || (v >= 79 && v <= 81) || (v >= 86 && v <= 88) || (v >= 93 && v <= 95)
                || (v >= 100 && v <= 102) || (v >= 107 && v <= 109) || (v >= 196 && v <= 197)) return "Harvestable";
        if ((v >= 128 && v <= 169) || (v >= 198 && v <= 200)) return "Diseased";
        if ((v >= 170 && v <= 172) || (v >= 201 && v <= 203)) return "Dead";
        return "Empty";
    }

    private static String getPatchState(Rs2TileObjectModel tileObj) {
        var comp = ActionMatch.safeComposition(tileObj);
        if (comp == null) return "Growing"; // transient lookup failure — leave it alone, don't guess
        if (ActionMatch.exact(comp, "Rake")) return "Weeds";
        if (ActionMatch.exact(comp, "Pick")) return "Harvestable";
        if (ActionMatch.exact(comp, "Harvest")) return "Harvestable";
        if (ActionMatch.exact(comp, "Clear")) return "Dead";
        int varbit = Microbot.getVarbitValue(comp.getVarbitId());
        return varbit <= 5 ? "Empty" : "Growing";
    }

    // ---- banking ----

    private boolean setupAutoInventory() {
        Rs2Walker.walkTo(Rs2Bank.getNearestBank().getWorldPoint(), 20);
        if (!Rs2Bank.openBank() || !Global.sleepUntil(Rs2Bank::isOpen, 10000)) {
            Microbot.log("Failed to open bank");
            return false;
        }
        Rs2Bank.depositAll();
        Rs2Inventory.waitForInventoryChanges(5000);

        int herbPatchCount = (int) herbPatches.stream().filter(HerbPatch::isEnabled).count();
        int allotmentLocationCount = countEnabledAllotmentFlowerLocations();

        boolean toolsOk = Rs2Bank.withdrawX(ItemID.RAKE, 1);
        toolsOk &= Rs2Bank.withdrawX(ItemID.SPADE, 1);
        toolsOk &= Rs2Bank.withdrawX(ItemID.DIBBER, 1);
        if (!toolsOk) {
            Microbot.log("Missing farming tools in bank (rake/spade/dibber)");
            return false;
        }
        EquipmentService.equipFarmingGear(); // farming cape/explorer's ring + secateurs-or-cape yield boost

        boolean missingRunes = !Rs2Bank.withdrawX(ItemID.LAWRUNE, 20);
        missingRunes |= !Rs2Bank.withdrawX(ItemID.AIRRUNE, 50);
        missingRunes |= !Rs2Bank.withdrawX(ItemID.EARTHRUNE, 50);
        missingRunes |= !Rs2Bank.withdrawX(ItemID.FIRERUNE, 50);
        missingRunes |= !Rs2Bank.withdrawX(ItemID.WATERRUNE, 50);
        if (missingRunes && !cfg.allowPartialRuns()) {
            Microbot.log("Missing teleportation runes");
            return false;
        }
        if (cfg.enableMorytania() && Rs2Bank.hasItem(ItemID.ECTOPHIAL)) {
            Rs2Bank.withdrawX(ItemID.ECTOPHIAL, 1);
        }
        withdrawRegionTeleports();

        HerbSeedType seedType = cfg.herbSeed();
        if (seedType == HerbSeedType.BEST) {
            if (!withdrawBestAvailableSeeds(herbPatchCount) && !cfg.allowPartialRuns()) return false;
        } else if (!withdrawSpecificSeeds(seedType.getItemId(), seedType.getSeedName(), herbPatchCount,
                seedType.getLevelRequired())) {
            return false;
        }

        if (cfg.enableAllotments() && allotmentLocationCount > 0) {
            int needed = 3 * 2 * allotmentLocationCount;
            AllotmentSeedType allotmentSeed = cfg.allotmentSeed();
            if (allotmentSeed == AllotmentSeedType.BEST) {
                if (!withdrawBestAvailableAllotmentSeeds(needed) && !cfg.allowPartialRuns()) return false;
            } else if (!withdrawSpecificSeeds(allotmentSeed.getItemId(), allotmentSeed.getSeedName(),
                    needed, allotmentSeed.getLevelRequired()) && !cfg.allowPartialRuns()) {
                return false;
            }
        }
        if (cfg.enableFlowers() && allotmentLocationCount > 0) {
            FlowerSeedType flowerSeed = cfg.flowerSeed();
            if (!withdrawSpecificSeeds(flowerSeed.getItemId(), flowerSeed.getSeedName(),
                    allotmentLocationCount, flowerSeed.getLevelRequired()) && !cfg.allowPartialRuns()) {
                return false;
            }
        }

        // Bottomless compost bucket rides in inventory (fixes the "no bucket" symptom). Non-fatal:
        // if it's not there the run plants without compost (see applyCompost best-effort).
        CompostType compostType = cfg.compostType();
        if (compostType != CompostType.NONE && compostType.isReusable()) {
            if (!Rs2Bank.withdrawX(compostType.getItemId(), 1)) {
                Microbot.log("No bottomless bucket in bank — will plant without compost");
            }
        }

        Rs2Bank.closeBank();
        Global.sleepUntil(() -> !Rs2Bank.isOpen(), 5000);
        return true;
    }

    private boolean withdrawSpecificSeeds(int itemId, String seedName, int count, int levelRequired) {
        int farmingLevel = Microbot.getClient().getRealSkillLevel(Skill.FARMING);
        if (farmingLevel < levelRequired) {
            Microbot.log("Cannot plant " + seedName + " — requires Farming " + levelRequired);
            return false;
        }
        if (!Rs2Bank.withdrawX(itemId, count)) {
            int available = Rs2Bank.count(itemId);
            if (available > 0) {
                Rs2Bank.withdrawX(itemId, Math.min(available, count));
            } else {
                Microbot.log("No " + seedName + " available in bank");
                return false;
            }
        }
        Rs2Inventory.waitForInventoryChanges(2000);
        return true;
    }

    private boolean withdrawBestAvailableSeeds(int patchCount) {
        int farmingLevel = Microbot.getClient().getRealSkillLevel(Skill.FARMING);
        List<HerbSeedType> plantable = HerbSeedType.getPlantableHerbs(farmingLevel);
        if (plantable.isEmpty()) return false;
        int withdrawn = 0;
        for (HerbSeedType herb : plantable) {
            if (withdrawn >= patchCount) break;
            int available = Rs2Bank.count(herb.getItemId());
            if (available > 0) {
                int toWithdraw = Math.min(available, patchCount - withdrawn);
                if (Rs2Bank.withdrawX(herb.getItemId(), toWithdraw)) withdrawn += toWithdraw;
            }
        }
        return withdrawn > 0;
    }

    private boolean withdrawBestAvailableAllotmentSeeds(int totalNeeded) {
        int farmingLevel = Microbot.getClient().getRealSkillLevel(Skill.FARMING);
        List<AllotmentSeedType> plantable = AllotmentSeedType.getPlantableSeeds(farmingLevel);
        if (plantable.isEmpty()) return false;
        int withdrawn = 0;
        for (AllotmentSeedType seed : plantable) {
            if (withdrawn >= totalNeeded) break;
            int available = Rs2Bank.count(seed.getItemId());
            if (available > 0) {
                int toWithdraw = Math.min(available, totalNeeded - withdrawn);
                if (Rs2Bank.withdrawX(seed.getItemId(), toWithdraw)) withdrawn += toWithdraw;
            }
        }
        return withdrawn > 0;
    }

    /**
     * Withdraw the item teleport for each enabled FAR region — the ones standard spellbook runes can't
     * reach. Nearer regions (Falador/Catherby/Ardougne/Lumbridge/Varrock) are covered by the runes +
     * the walker's transport system, so we don't spend inventory slots on them. Non-fatal per item.
     */
    private void withdrawRegionTeleports() {
        if (cfg.enableHosidius()) tryWithdrawByName("Xeric's talisman");
        if (cfg.enableTrollheim()) tryWithdrawByName("Stony basalt");
        if (cfg.enableWeiss()) tryWithdrawByName("Icy basalt");
        if (cfg.enableVarlamore()) tryWithdrawByName("Teleport to Civitas illa Fortis");
        if (cfg.enableGuild()) tryWithdrawByName("Skills necklace");
    }

    private void tryWithdrawByName(String name) {
        if (Rs2Bank.hasItem(name)) {
            Rs2Bank.withdrawOne(name);
            Rs2Inventory.waitForInventoryChanges(1500);
        }
    }

    private int countEnabledAllotmentFlowerLocations() {
        int count = 0;
        if (cfg.enableArdougne()) count++;
        if (cfg.enableCatherby()) count++;
        if (cfg.enableVarlamore()) count++;
        if (cfg.enableFalador()) count++;
        if (cfg.enableHosidius()) count++;
        if (cfg.enableMorytania()) count++;
        return count;
    }
}
