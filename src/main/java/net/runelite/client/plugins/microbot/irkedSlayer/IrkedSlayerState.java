package net.runelite.client.plugins.microbot.irkedSlayer;

public enum IrkedSlayerState {
    IDLE("Idle"),
    GETTING_TASK("Getting Task"),
    SKIPPING_TASK("Skipping Task"),
    BLOCKING_TASK("Blocking Task"),
    DETECTING_TASK("Detecting Task"),
    RESTORING_AT_POH("Restoring at POH"),
    BANKING("Banking"),
    SWAPPING_SPELLBOOK("Swapping Spellbook"),
    TRAVELING("Traveling"),
    AT_LOCATION("At Location"),
    FIGHTING("Fighting"),
    ERROR("Error");

    private final String displayName;

    IrkedSlayerState(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
