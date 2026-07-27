package net.runelite.client.plugins.microbot.irkedfarmer.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

/** Fruit tree saplings and their (optional) protection payment. Ported from legacy FruitTreeEnum. */
@Getter
@RequiredArgsConstructor
public enum FruitTreeSpecies {
    APPLE("Apple sapling", ItemID.PLANTPOT_APPLE_SAPLING, ItemID.SWEETCORN, 9, 27),
    BANANA("Banana sapling", ItemID.PLANTPOT_BANANA_SAPLING, ItemID.BASKET_APPLE_5, 4, 33),
    ORANGE("Orange sapling", ItemID.PLANTPOT_ORANGE_SAPLING, ItemID.BASKET_STRAWBERRY_5, 3, 39),
    CURRY("Curry sapling", ItemID.PLANTPOT_CURRY_SAPLING, ItemID.BASKET_BANANA_5, 5, 42),
    PINEAPPLE("Pineapple sapling", ItemID.PLANTPOT_PINEAPPLE_SAPLING, ItemID.WATERMELON, 10, 51),
    PAPAYA("Papaya sapling", ItemID.PLANTPOT_PAPAYA_SAPLING, ItemID.PINEAPPLE, 10, 57),
    PALM("Palm sapling", ItemID.PLANTPOT_PALM_SAPLING, ItemID.PAPAYA, 15, 68),
    DRAGONFRUIT("Dragonfruit sapling", ItemID.PLANTPOT_DRAGONFRUIT_SAPLING, ItemID.COCONUT, 15, 81);

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
