package net.runelite.client.plugins.microbot.irkedfarmer.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.ItemID;

/** Compost options. Ported from legacy CompostType. */
@Getter
@RequiredArgsConstructor
public enum CompostType {
    NONE("None", -1, -1, false),
    COMPOST("Compost", ItemID.BUCKET_COMPOST, -1, false),
    SUPERCOMPOST("Supercompost", ItemID.BUCKET_SUPERCOMPOST, -1, false),
    ULTRACOMPOST("Ultracompost", ItemID.BUCKET_ULTRACOMPOST, -1, false),
    // The bucket sits in the bank empty until it's been dipped in a compost bin at least once —
    // the bank withdrawal needs to accept either state (see BankService.withdrawBottomlessBucket).
    BOTTOMLESS_BUCKET("Bottomless compost bucket", ItemID.BOTTOMLESS_COMPOST_BUCKET_FILLED,
            ItemID.BOTTOMLESS_COMPOST_BUCKET, true);

    private final String label;
    private final int itemId;
    private final int emptyItemId; // -1 when not applicable
    private final boolean reusable;

    @Override
    public String toString() {
        return label;
    }
}
