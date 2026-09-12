package net.runelite.client.plugins.microbot.blastoisefurnace.enums;

public enum State {
    WALK_TO_FURNACE,   // not at the furnace yet — travel / descend the stairs
    BANKING,           // at the bank: deposit bars, sort tools + ore
    LOADING,           // putting ore onto the conveyor belt
    WAITING,           // ore is smelting, bars not dispensed yet
    COLLECTING         // taking the finished bars from the dispenser
}
