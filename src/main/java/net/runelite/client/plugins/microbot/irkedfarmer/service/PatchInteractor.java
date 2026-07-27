package net.runelite.client.plugins.microbot.irkedfarmer.service;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Rs2Leprechaun;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.irkedfarmer.IrkedFarmerConfig;
import net.runelite.client.plugins.microbot.irkedfarmer.model.CompostType;
import net.runelite.client.plugins.microbot.irkedfarmer.model.FarmPatch;
import net.runelite.client.plugins.microbot.irkedfarmer.model.TreeKind;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Interacts with a single patch the player is already standing at: reads the patch's current action
 * and does the right thing (check-health, clear/pay, pick, rake, plant + optional protect). Ported
 * from the legacy tree runner's {@code handlePatch} family, made non-fatal:
 *  - a missing gardener (e.g. Marcellus at Avium) skips the patch instead of shutting the run down;
 *  - protection is best-effort — a missing payment plants UNPROTECTED and continues.
 *
 * {@link #handle} is idempotent and meant to be called repeatedly until it returns DONE or the
 * caller times out.
 */
@Slf4j
public final class PatchInteractor {

    private PatchInteractor() {}

    public enum Outcome {
        DONE,       // patch handled (planted, or nothing to do)
        RETRY,      // made progress / needs another pass (raking, clearing, picking)
        NOT_FOUND,  // patch object or required gardener not found — skip this patch, never fatal
        GROWING     // a tree is growing here; leave it
    }

    private enum PaymentKind { PROTECT, CLEAR }

    private static final String[] POSSIBLE_ACTIONS = {"Check", "Chop", "Pick", "Rake", "Clear", "Inspect"};

    public static Outcome handle(IrkedFarmerConfig cfg, FarmPatch patch) {
        // Fetched once and scanned locally against every candidate action — the object/composition
        // can't change mid-call, so re-querying the cache per action (as findObjectByImposter's
        // loop-replacement originally did) was pure repeated client-thread round-trips.
        Rs2TileObjectModel obj = Microbot.getRs2TileObjectCache().query()
                .withId(patch.getObjectId())
                .nearest();
        var comp = ActionMatch.safeComposition(obj);
        String foundAction = null;
        for (String action : POSSIBLE_ACTIONS) {
            if (ActionMatch.contains(comp, action)) {
                foundAction = action;
                if (!"Inspect".equals(foundAction)) {
                    break;
                }
            }
        }
        if (obj == null || foundAction == null) {
            log.warn("irkedFarmer: patch object {} not found ({})", patch.getObjectId(), patch.name());
            return Outcome.NOT_FOUND;
        }

        // Gielinor names actions inconsistently (Pick-fruit vs Pick-banana, Chop vs Chop-down).
        String exactAction = foundAction;
        if (comp != null && comp.getActions() != null) {
            List<String> actions = Arrays.stream(comp.getActions()).filter(a -> a != null).collect(Collectors.toList());
            for (String a : actions) {
                if (a.startsWith(foundAction)) {
                    exactAction = a;
                    break;
                }
            }
        }

        switch (foundAction) {
            case "Check":
                checkHealth(obj);
                return Outcome.RETRY;
            case "Chop":
                return payment(cfg, patch, PaymentKind.CLEAR) ? Outcome.RETRY : Outcome.NOT_FOUND;
            case "Pick":
                pickFruit(obj, patch, exactAction);
                return Outcome.RETRY;
            case "Rake":
                rake(obj);
                return Outcome.RETRY;
            case "Clear":
                clear(obj);
                return Outcome.RETRY;
            case "Inspect":
                if (plantTree(cfg, obj, patch)) {
                    payment(cfg, patch, PaymentKind.PROTECT); // best-effort; never blocks completion
                    return Outcome.DONE;
                }
                return Outcome.RETRY;
            default:
                return Outcome.RETRY;
        }
    }

    // ---- individual actions ---------------------------------------------------------------

    private static void checkHealth(Rs2TileObjectModel obj) {
        obj.click("Check-health");
        Rs2Player.waitForXpDrop(Skill.FARMING);
        Global.sleep(250, 2500);
    }

    private static void rake(Rs2TileObjectModel obj) {
        obj.click("rake");
        Global.sleepUntil(() -> !ActionMatch.exact(ActionMatch.safeComposition(obj), "Rake"), 30000);
        Global.sleep(400, 1200);
        if (Rs2Inventory.hasItem(ItemID.WEEDS)) {
            Rs2Inventory.dropAll(ItemID.WEEDS);
            Global.sleepUntil(() -> !Rs2Inventory.hasItem(ItemID.WEEDS), 5000);
        }
    }

    private static void clear(Rs2TileObjectModel obj) {
        // Rs2TileObjectModel.click(String) always returns true (verified against the client jar's
        // decompiled bytecode — single return, success and internal-exception paths both hit it), so
        // checking its result can't detect a failed click; the retry loop (caller re-calls handle()
        // until DONE) is what actually recovers from a miss here.
        obj.click("clear");
        Rs2Player.waitForXpDrop(Skill.FARMING, 10000);
    }

    private static void pickFruit(Rs2TileObjectModel obj, FarmPatch patch, String exactAction) {
        obj.click(exactAction);
        Global.sleepUntil(() -> !ActionMatch.exact(ActionMatch.safeComposition(obj), exactAction), 12000);
        Global.sleep(400, 1500);
        noteFruit(patch);
    }

    private static boolean plantTree(IrkedFarmerConfig cfg, Rs2TileObjectModel obj, FarmPatch patch) {
        int saplingId = saplingFor(cfg, patch);
        if (!Rs2Inventory.hasItem(saplingId)) {
            log.info("irkedFarmer: no sapling {} for {} — skipping plant", saplingId, patch.name());
            return false;
        }

        // Reaching here means the caller matched the patch's "Inspect" action, i.e. it is empty and
        // plantable. Compost first — BEST-EFFORT: a missing bucket/compost logs and plants anyway,
        // never blocks (the old isPatchEmpty guard mis-read the base composition and skipped the plant).
        if (shouldCompost(cfg, patch)) {
            int compostId = cfg.compostType().getItemId();
            boolean haveCompost = Rs2Inventory.hasItem(compostId);
            if (!haveCompost && !cfg.compostType().isReusable()) {
                haveCompost = Rs2Leprechaun.withdrawCompost(compostId); // non-reusable comes from the patch leprechaun
            }
            if (haveCompost) {
                Rs2Inventory.useItemOnObject(compostId, obj.getId());
                Rs2Player.waitForXpDrop(Skill.FARMING, 2000);
                Global.sleep(550, 2200);
            } else {
                log.info("irkedFarmer: no compost available at {} — planting without it", patch.name());
            }
        }

        Global.sleep(250, 1000);
        int before = Rs2Inventory.count(saplingId);
        Rs2Inventory.useItemOnObject(saplingId, obj.getId());
        Rs2Inventory.waitForInventoryChanges(3000);
        Global.sleep(750, 2400);
        boolean planted = Rs2Inventory.count(saplingId) < before; // sapling consumed = planted
        if (!planted) {
            Rs2Inventory.deselect();
        }
        return planted;
    }

    private static void noteFruit(FarmPatch patch) {
        int[] fruitIds = {
                ItemID.COOKING_APPLE, ItemID.BANANA, ItemID.ORANGE, ItemID.CURRY_LEAF,
                ItemID.PINEAPPLE, ItemID.PAPAYA, ItemID.COCONUT, ItemID.DRAGONFRUIT
        };
        if (patch.getLeprechaunId() <= 0 || !Rs2Inventory.hasItem(fruitIds)) {
            return; // ponytail: only Kastori carries a mapped leprechaun id; general noting is a later slice
        }
        for (int fruitId : fruitIds) {
            if (Rs2Inventory.hasItem(fruitId)) {
                Rs2Inventory.useItemOnNpc(fruitId, patch.getLeprechaunId());
                Global.sleepUntil(() -> Rs2Inventory.waitForInventoryChanges(5000), 5000);
                return;
            }
        }
    }

    /** Pay a gardener to clear or protect. Non-fatal: missing gardener returns false, never shuts down. */
    private static boolean payment(IrkedFarmerConfig cfg, FarmPatch patch, PaymentKind kind) {
        if (kind == PaymentKind.PROTECT && !protectFor(cfg, patch)) {
            return true; // protection not requested for this kind
        }

        Rs2NpcModel gardener = Microbot.getRs2NpcCache().query()
                .where(n -> hasAction(n, "Pay"))
                .nearestReachable();
        if (gardener == null) {
            // field bug #2 (Marcellus/Avium): don't die — log and skip. Clear can't proceed; protect is optional.
            log.warn("irkedFarmer: no 'Pay' gardener near {} — skipping {}", patch.name(), kind);
            return false;
        }
        gardener.click("Pay");

        Global.sleepUntil(Rs2Dialogue::isInDialogue, 5000);
        Global.sleep(500, 1500);
        if (!Rs2Dialogue.hasSelectAnOption()) {
            return Rs2Dialogue.hasDialogueText("Leave it with me")
                    || Rs2Dialogue.hasDialogueText("already looking after that patch");
        }
        Rs2Dialogue.clickContinue();
        Global.sleep(500, 850);

        if (!Rs2Dialogue.hasSelectAnOption()) {
            log.info("irkedFarmer: unexpected gardener dialogue at {}", patch.name());
            return false;
        }
        if (kind == PaymentKind.PROTECT) {
            if (!Rs2Dialogue.clickOption("don't ask")) {
                Rs2Dialogue.clickOption("Yes");
            }
            Global.sleep(500, 1500);
            Rs2Dialogue.clickContinue();
            Global.sleepUntil(() -> !Rs2Dialogue.isInDialogue(), 6000);
            return true;
        }
        // CLEAR
        Rs2Dialogue.clickOption("Yes");
        Global.sleepUntil(() -> isPatchEmpty(patch), 6000);
        return isPatchEmpty(patch);
    }

    // ---- helpers --------------------------------------------------------------------------

    /**
     * Reads the LIVE (impostor-resolved) composition, same as the deprecated {@code Rs2GameObject
     * .getObjectComposition(int)} it replaces actually did despite its "get" (base-sounding) name —
     * verified against the real source, which resolves {@code getImpostor()} whenever impostor ids
     * are present. Farm patches are the textbook impostor case (name/actions change per growth stage),
     * so skipping the resolve step here would make this permanently read the generic base name.
     */
    private static boolean isPatchEmpty(FarmPatch patch) {
        ObjectComposition comp = resolvedComposition(patch.getObjectId());
        if (comp == null || comp.getName() == null) {
            return false;
        }
        return comp.getName().toLowerCase().endsWith("patch");
    }

    private static ObjectComposition resolvedComposition(int objectId) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            ObjectComposition comp = Microbot.getClient().getObjectDefinition(objectId);
            return comp != null && comp.getImpostorIds() != null ? comp.getImpostor() : comp;
        }).orElse(null);
    }

    /**
     * Reads the BASE npc definition — matching both the deprecated {@code Rs2Npc
     * .getNearestNpcWithAction} selector this replaces and the actual click mechanism
     * ({@code Rs2NpcModel#click} also resolves off the base definition). {@code
     * NPC#getTransformedComposition()} can legitimately return null for a live, payable NPC mid
     * varbit-transform, which would wrongly exclude a valid gardener.
     */
    private static boolean hasAction(Rs2NpcModel npc, String action) {
        NPCComposition comp = Microbot.getClientThread().runOnClientThreadOptional(
                () -> Microbot.getClient().getNpcDefinition(npc.getId())).orElse(null);
        if (comp == null || comp.getActions() == null) return false;
        for (String a : comp.getActions()) {
            if (action.equals(a)) return true;
        }
        return false;
    }

    /**
     * Sapling to plant. Fixes a latent legacy bug: Avium (HARD_TREE, non-fossil) fell through to the
     * fruit sapling; here all HARD_TREE patches use the hardwood sapling.
     */
    private static int saplingFor(IrkedFarmerConfig cfg, FarmPatch patch) {
        if (patch == FarmPatch.PRIFDDINAS_CRYSTAL) {
            return ItemID.PLANTPOT_CRYSTAL_TREE_SAPLING;
        }
        switch (patch.getKind()) {
            case HARD_TREE:  return cfg.selectedHardwood().getSaplingId();
            case FRUIT_TREE: return cfg.selectedFruitTree().getSaplingId();
            case TREE:
            default:         return cfg.selectedTree().getSaplingId();
        }
    }

    private static boolean protectFor(IrkedFarmerConfig cfg, FarmPatch patch) {
        switch (patch.getKind()) {
            case HARD_TREE:  return cfg.protectHardwood();
            case FRUIT_TREE: return cfg.protectFruitTrees();
            case TREE:
            default:         return cfg.protectTrees();
        }
    }

    /** Compost when a compost type is set and we are NOT protecting this kind (protection replaces it). */
    private static boolean shouldCompost(IrkedFarmerConfig cfg, FarmPatch patch) {
        return cfg.compostType() != CompostType.NONE && !protectFor(cfg, patch);
    }
}
