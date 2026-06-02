package net.runelite.client.plugins.microbot.motherloadmine.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MLMSackSize {
    STANDARD("108 (Standard)"),
    UPGRADED("189 (Upgraded)");

    private final String name;

    @Override
    public String toString() {
        return name;
    }
}
