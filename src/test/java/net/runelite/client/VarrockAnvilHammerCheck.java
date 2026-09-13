package net.runelite.client;

import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.varrockanvil.VarrockAnvilScript;
import net.runelite.client.plugins.microbot.varrockanvil.enums.Bars;

import java.util.Arrays;
import java.util.List;

/**
 * Self-check for the Varrock Anvil hammer set.
 *
 * The bug this guards: the plugin used to hardcode ItemID.HAMMER, so Deposit-All banked an
 * inventory Imcando hammer and an equipped one was never seen — either way the script stopped
 * with "Could not find hammer in bank" on an account perfectly able to smith.
 *
 * OSRS wiki, Imcando hammer: once repaired it works "both while equipped and unequipped, in any
 * way that a normal hammer can be used". Two variants exist — main-hand (weapon slot, 25644) and
 * off-hand (shield slot, 29775) — swapped via the "Swap" option. The broken hammer (25633) does
 * NOT work and must stay out of the set.
 *
 * Also covers the Sailing keel metal-name mapping, since a wrong name there is the other way this
 * plugin used to lock up.
 *
 * Run as a plain main() (repo has no JUnit). Fails loudly if a variant is dropped from the
 * deposit-exclusion list, which is the silent way this regresses.
 */
public class VarrockAnvilHammerCheck {
    public static void main(String[] args) {
        hammerSet();
        depositKeepsEveryVariant();
        depositKeepsMaterial();
        brokenHammerExcluded();
        isHammerPredicate();
        keelMetalNames();
        metalsWithoutKeels();
        wornHammerBanksTheSpare();
        keelTiers();
        bluriteRemoved();
        System.out.println("VarrockAnvilHammerCheck: OK");
    }

    /** The predicate every deposit filter routes through. */
    private static void isHammerPredicate() {
        check(VarrockAnvilScript.isHammer(ItemID.HAMMER), "plain hammer");
        check(VarrockAnvilScript.isHammer(ItemID.IMCANDO_HAMMER), "Imcando main-hand");
        check(VarrockAnvilScript.isHammer(ItemID.IMCANDO_HAMMER_OFFHAND), "Imcando off-hand");
        check(!VarrockAnvilScript.isHammer(ItemID.IMCANDO_HAMMER_BROKEN), "broken hammer is not a hammer");
        check(!VarrockAnvilScript.isHammer(ItemID.MITHRIL_BAR), "a bar is not a hammer");
    }

    /**
     * The keel metal word is NOT the bar name minus " bar". Verified against the OSRS Wiki:
     * "Adamant keel parts" are made from adamantite bars, "Rune keel parts" from runite bars.
     * Getting this wrong makes the item unfindable in the anvil interface, which used to send
     * the script into an endless DETERMINE -> SMITHING -> RECOVERY loop.
     */
    private static void keelMetalNames() {
        check("adamant".equals(Bars.ADAMANTITE.getKeelMetal()), "Adamantite bar -> 'adamant', not 'adamantite'");
        check("rune".equals(Bars.RUNITE.getKeelMetal()), "Runite bar -> 'rune', not 'runite'");
        check("bronze".equals(Bars.BRONZE.getKeelMetal()), "Bronze bar -> 'bronze'");
        check("mithril".equals(Bars.MITHRIL.getKeelMetal()), "Mithril bar -> 'mithril'");
        check("iron".equals(Bars.IRON.getKeelMetal()), "Iron bar -> 'iron'");
        check("steel".equals(Bars.STEEL.getKeelMetal()), "Steel bar -> 'steel'");
    }

    /** Metals with no keel parts must be rejected up front, not discovered by failing at the anvil. */
    private static void metalsWithoutKeels() {
        check(!Bars.SILVER.hasKeelParts(), "silver has no keel parts");
        check(!Bars.GOLD.hasKeelParts(), "gold has no keel parts");
        check(Bars.ADAMANTITE.hasKeelParts() && Bars.RUNITE.hasKeelParts(), "adamant/rune do have keel parts");
    }

    /** All three usable hammers must be recognised — a plain hammer alone is the old bug. */
    private static void hammerSet() {
        List<Integer> ids = boxed(VarrockAnvilScript.HAMMER_IDS);
        check(ids.contains(ItemID.HAMMER), "plain hammer must be usable");
        check(ids.contains(ItemID.IMCANDO_HAMMER), "Imcando main-hand must be usable");
        check(ids.contains(ItemID.IMCANDO_HAMMER_OFFHAND), "Imcando off-hand must be usable");
        check(ids.size() == 3, "expected exactly 3 hammer variants, got " + ids.size());
    }

    /** With nothing worn, Deposit-All must exclude every variant or banking throws the hammer away. */
    private static void depositKeepsEveryVariant() {
        List<Integer> keep = Arrays.asList(VarrockAnvilScript.keepOnDeposit(false));
        for (int id : VarrockAnvilScript.HAMMER_IDS) {
            check(keep.contains(id), "deposit-exclusion list must keep hammer id " + id);
        }
    }

    /** The material must survive Deposit-All alongside the hammers. */
    private static void depositKeepsMaterial() {
        List<Integer> keep = Arrays.asList(VarrockAnvilScript.keepOnDeposit(false, ItemID.MITHRIL_BAR));
        check(keep.contains(ItemID.MITHRIL_BAR), "deposit-exclusion list must keep the material");
        check(keep.size() == VarrockAnvilScript.HAMMER_IDS.length + 1,
                "material must be added to the hammers, not replace them");
    }

    /**
     * The live bug: a plain hammer sat in the inventory while an Imcando off-hand was equipped,
     * wasting a slot forever because the exclusion list kept every hammer unconditionally.
     * With one worn, no hammer should be kept in the inventory.
     */
    private static void wornHammerBanksTheSpare() {
        List<Integer> keep = Arrays.asList(VarrockAnvilScript.keepOnDeposit(true, ItemID.MITHRIL_BAR));
        check(keep.contains(ItemID.MITHRIL_BAR), "material is still kept when a hammer is worn");
        for (int id : VarrockAnvilScript.HAMMER_IDS) {
            check(!keep.contains(id), "a worn hammer means carried hammer " + id + " must be banked");
        }
        check(keep.size() == 1, "only the material should be kept when a hammer is worn");
    }

    /**
     * Keel tiers, from the OSRS Wiki keel tables. Dragon is the odd one out twice over: it takes 2
     * materials where every other tier takes 5, and its REGULAR keel parts come from dragon metal
     * sheets at the Dragon Forge — only Large dragon keel parts (2 Dragon keel parts) work at an anvil.
     */
    private static void keelTiers() {
        check(Bars.BRONZE.getKeelLevel() == 10 && Bars.BRONZE.getKeelQuantity() == 5, "bronze keel 10 / 5");
        check(Bars.IRON.getKeelLevel() == 22, "iron keel 22");
        check(Bars.STEEL.getKeelLevel() == 38, "steel keel 38");
        check(Bars.MITHRIL.getKeelLevel() == 56, "mithril keel 56");
        check(Bars.ADAMANTITE.getKeelLevel() == 74, "adamant keel 74");
        check(Bars.RUNITE.getKeelLevel() == 86, "rune keel 86");
        check(Bars.DRAGON.getKeelLevel() == 94, "dragon keel 94");
        check(Bars.DRAGON.getKeelQuantity() == 2, "dragon takes 2 materials, not 5");
        check(Bars.DRAGON.regularKeelNeedsDragonForge(), "regular dragon keel parts need the Dragon Forge");
        for (Bars bar : Bars.values()) {
            if (bar == Bars.DRAGON || !bar.hasKeelParts()) continue;
            check(!bar.regularKeelNeedsDragonForge(), bar + " keel parts are anvil-made");
            check(bar.getKeelQuantity() == 5, bar + " takes 5 materials per keel part");
        }
    }

    /** Blurite was removed from the bar list at the user's request. */
    private static void bluriteRemoved() {
        for (Bars bar : Bars.values()) {
            check(!bar.toString().toLowerCase().contains("blurite"), "blurite must not be offered");
        }
    }

    /** A broken Imcando hammer cannot smith — keeping it would strand the script at the anvil. */
    private static void brokenHammerExcluded() {
        check(!boxed(VarrockAnvilScript.HAMMER_IDS).contains(ItemID.IMCANDO_HAMMER_BROKEN),
                "broken Imcando hammer must NOT count as a usable hammer");
    }

    private static List<Integer> boxed(int[] ids) {
        return Arrays.stream(ids).boxed().collect(java.util.stream.Collectors.toList());
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("VarrockAnvilHammerCheck FAILED: " + message);
        }
    }
}
