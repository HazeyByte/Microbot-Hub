package net.runelite.client.plugins.microbot.irkedchoppa.util;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

@Slf4j
public class IrkedChopperBasketUtil {
    
    private static final String LOG_BASKET_NAME = "Log basket";
    private static final String FORESTRY_BASKET_NAME = "Forestry basket";
    
    public static boolean hasLogBasket() {
        boolean inInventory = Rs2Inventory.hasItem(LOG_BASKET_NAME);
        boolean equipped = Rs2Equipment.isWearing(LOG_BASKET_NAME);
        log.debug("Log Basket check - Inventory: {}, Equipped: {}", inInventory, equipped);
        return inInventory || equipped;
    }
    
    public static boolean hasForestryBasket() {
        boolean inInventory = Rs2Inventory.hasItem(FORESTRY_BASKET_NAME);
        boolean equipped = Rs2Equipment.isWearing(FORESTRY_BASKET_NAME);
        log.debug("Forestry Basket check - Inventory: {}, Equipped: {}", inInventory, equipped);
        return inInventory || equipped;
    }
    
    private static boolean emptyBasket(String basketName) {
        log.info("Emptying {}...", basketName);
        int logsInInventoryBefore = Rs2Inventory.count(log -> log.getName().toLowerCase().contains("logs"));
        
        boolean success = Rs2Inventory.interact(basketName, "Empty");
        if (success) {
            sleepUntil(() -> {
                int logsInInventoryAfter = Rs2Inventory.count(log -> log.getName().toLowerCase().contains("logs"));
                return logsInInventoryAfter > logsInInventoryBefore;
            }, 2000);
            log.info("{} emptied successfully", basketName);
        } else {
            log.warn("Failed to empty {}", basketName);
        }
        
        return success;
    }
    
    public static boolean emptyLogBasket() {
        if (!hasLogBasket()) {
            log.debug("No log basket found to empty");
            return false;
        }
        return emptyBasket(LOG_BASKET_NAME);
    }
    
    public static boolean emptyForestryBasket() {
        if (!hasForestryBasket()) {
            log.debug("No forestry basket found to empty");
            return false;
        }
        return emptyBasket(FORESTRY_BASKET_NAME);
    }
    
    public static void emptyAllBaskets() {
        log.info("Starting basket emptying process");
        
        if (hasLogBasket()) {
            log.info("Emptying Log Basket");
            emptyLogBasket();
            sleep(400, 800);
        }
        
        if (hasForestryBasket()) {
            log.info("Emptying Forestry Basket");
            emptyForestryBasket();
            sleep(400, 800);
        }
        
        log.info("Basket emptying process completed");
    }
    
    private static boolean emptyBasketAtBank(String basketName) {
        if (!Rs2Bank.isOpen()) {
            return false;
        }
        
        boolean success = Rs2Inventory.interact(basketName, "Empty");
        if (success) {
            sleep(600, 1000);
        }
        
        return success;
    }
    
    public static boolean emptyLogBasketAtBank() {
        if (!Rs2Bank.isOpen()) {
            return false;
        }
        
        if (!hasLogBasket()) {
            return true;
        }
        
        return emptyBasketAtBank(LOG_BASKET_NAME);
    }
    
    public static boolean emptyForestryBasketAtBank() {
        if (!Rs2Bank.isOpen()) {
            return false;
        }
        
        if (!hasForestryBasket()) {
            return true;
        }
        
        return emptyBasketAtBank(FORESTRY_BASKET_NAME);
    }
    
    public static boolean emptyAllBasketsAtBank() {
        if (!Rs2Bank.isOpen()) {
            return false;
        }
        
        boolean success = true;
        
        if (hasLogBasket()) {
            success &= emptyLogBasketAtBank();
        }
        
        if (hasForestryBasket()) {
            success &= emptyForestryBasketAtBank();
        }
        
        return success;
    }
}
