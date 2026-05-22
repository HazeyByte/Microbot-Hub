package net.runelite.client.plugins.microbot.irkedchoppa.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum IrkedChoppaAction {
    BANK("Bank logs"),
    DROP("Drop logs"),
    BURN("Burn logs"),
    BURN_CAMPFIRE("Burn logs at campfire");

    private final String description;

    @Override
    public String toString() {
        return description;
    }
}
