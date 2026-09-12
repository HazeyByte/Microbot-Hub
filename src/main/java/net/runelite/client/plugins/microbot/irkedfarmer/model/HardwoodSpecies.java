package net.runelite.client.plugins.microbot.irkedfarmer.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

/**
 * Hardwood tree saplings and their (optional) protection payment. Ported from legacy HardTreeEnums.
 * Note: legacy hardcoded farmingLevel=75 for both (wrong); corrected to real OSRS reqs (Teak 35, Mahogany 55).
 */
@Getter
@RequiredArgsConstructor
public enum HardwoodSpecies {
    TEAK("Teak sapling", ItemID.PLANTPOT_TEAK_SAPLING, ItemID.LIMPWURT_ROOT, 15, 35),
    MAHOGANY("Mahogany sapling", ItemID.PLANTPOT_MAHOGANY_SAPLING, ItemID.YANILLIAN_HOPS, 25, 55);

    private final String label;
    private final int saplingId;
    private final int paymentId;
    private final int paymentAmount;
    private final int farmingLevel;

    @Override
    public String toString() {
        return label;
    }

    public boolean hasRequiredLevel() {
        return Rs2Player.getSkillRequirement(Skill.FARMING, this.farmingLevel);
    }
}
