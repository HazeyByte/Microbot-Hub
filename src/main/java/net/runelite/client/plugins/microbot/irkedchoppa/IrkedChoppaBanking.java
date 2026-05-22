package net.runelite.client.plugins.microbot.irkedchoppa;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ObjectComposition;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.depositbox.Rs2DepositBox;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2BankID;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Smart banking system for IrkedChoppa plugin that caches optimal bank methods
 * using existing Microbot API
 */
@Slf4j
public class IrkedChoppaBanking {
    
    // Cache for storing IrkedChoppa's bank method preferences
    private static final ConcurrentHashMap<String, BankPreference> bankCache = new ConcurrentHashMap<>();
    
    // Cache timeout (30 minutes)
    private static final long CACHE_TIMEOUT_MS = 30 * 60 * 1000;
    
    public static class BankPreference {
        public final BankLocation preferredLocation;
        public final String preferredType; // "chest", "booth", "npc", "ge", "deposit"
        public final WorldPoint bankLocation;
        public final Integer bankId; // null for NPCs
        public final long timestamp;
        public final int successCount;
        public final int failureCount;
        
        public BankPreference(BankLocation location, String type, WorldPoint bankLocation, Integer bankId,
                            int successCount, int failureCount) {
            this.preferredLocation = location;
            this.preferredType = type;
            this.bankLocation = bankLocation;
            this.bankId = bankId;
            this.timestamp = System.currentTimeMillis();
            this.successCount = successCount;
            this.failureCount = failureCount;
        }
        
        public boolean isCacheValid() {
            return System.currentTimeMillis() - timestamp <= CACHE_TIMEOUT_MS;
        }
        
        public double getSuccessRate() {
            int total = successCount + failureCount;
            return total == 0 ? 0.0 : (double) successCount / total;
        }
        
        public boolean isReliable() {
            return getSuccessRate() >= 0.8 && (successCount + failureCount) >= 3;
        }
    }
    
    /**
     * Main banking method for IrkedChoppa - uses cached method if available and reliable
     */
    public static boolean openBankForChoppa() {
        String pluginName = "IrkedChoppa";
        
        // Try cached method first
        BankPreference preference = bankCache.get(pluginName);
        
        if (preference != null && preference.isCacheValid() && preference.isReliable()) {
            log.info("Using cached bank method: {} at {} (success rate: {:.2f}%)", 
                    preference.preferredType, preference.bankLocation, preference.getSuccessRate() * 100.0);
            
            if (tryCachedBankMethod(preference)) {
                recordSuccess(preference);
                return true;
            } else {
                log.warn("Cached bank method failed, falling back to discovery");
                recordFailure(preference);
            }
        }
        
        // Find new optimal method using existing Microbot API
        BankPreference newMethod = findOptimalBankMethod();
        if (newMethod != null) {
            log.info("Found new bank method: {} at {}", newMethod.preferredType, newMethod.bankLocation);
            cacheBankMethod(pluginName, newMethod);
            
            if (tryCachedBankMethod(newMethod)) {
                recordSuccess(newMethod);
                return true;
            } else {
                recordFailure(newMethod);
            }
        }
        
        log.warn("No bank method found");
        return false;
    }
    
    /**
     * Find optimal bank method using existing Microbot API
     */
    private static BankPreference findOptimalBankMethod() {
        WorldPoint playerPos = Microbot.getClient().getLocalPlayer().getWorldLocation();
        log.debug("Searching for optimal bank method from player position: {}", playerPos);
        
        // Priority 1: Use existing Rs2Bank.openBank() - it already does smart finding
        log.debug("Trying Rs2Bank.openBank()...");
        if (Rs2Bank.openBank()) {
            log.info("Rs2Bank.openBank() succeeded, identifying what we opened");
            return identifyCurrentBankMethod();
        }
        log.debug("Rs2Bank.openBank() failed, trying alternative methods");
        
        // Priority 2: Try to find any bank objects using Queryable API
        Rs2TileObjectModel anyBank = Microbot.getRs2TileObjectCache().query()
            .where(obj -> Arrays.stream(Rs2BankID.bankIds).anyMatch(id -> obj.getId() == id))
            .nearest(20); // Search within 20 tiles
        
        if (anyBank != null) {
            int distance = playerPos.distanceTo(anyBank.getWorldLocation());
            log.info("Found bank object: {} at {} (distance: {} tiles)", anyBank.getName(), anyBank.getWorldLocation(), distance);
            if (anyBank.isReachable() && anyBank.click("Bank")) {
                if (sleepUntil(Rs2Bank::isOpen, 3000)) {
                    String bankType = determineBankType(anyBank.getId());
                    return new BankPreference(
                        null,
                        bankType,
                        anyBank.getWorldLocation(),
                        anyBank.getId(),
                        0, 0
                    );
                }
            }
        } else {
            log.debug("No bank objects found within 20 tiles of {}", playerPos);
        }
        
        // Priority 3: Try GE booths using existing API
        Rs2TileObjectModel geBooth = Microbot.getRs2TileObjectCache().query()
            .where(obj -> obj.getId() == 10060 || obj.getId() == 30389)
            .nearest(20);
        
        if (geBooth != null) {
            int distance = playerPos.distanceTo(geBooth.getWorldLocation());
            log.info("Trying GE booth at {} (distance: {} tiles)", geBooth.getWorldLocation(), distance);
            if (geBooth.isReachable() && geBooth.click("Bank")) {
                if (sleepUntil(Rs2Bank::isOpen, 3000)) {
                    return new BankPreference(
                        null,
                        "ge",
                        geBooth.getWorldLocation(),
                        geBooth.getId(),
                        0, 0
                    );
                }
            }
        } else {
            log.debug("No GE booths found within 20 tiles of {}", playerPos);
        }
        
        // Priority 4: Try deposit boxes using existing API
        Rs2TileObjectModel depositBox = Microbot.getRs2TileObjectCache().query()
            .where(obj -> Arrays.stream(Rs2BankID.bankIds).anyMatch(id -> obj.getId() == id))
            .where(obj -> {
                ObjectComposition comp = Microbot.getClient().getObjectDefinition(obj.getId());
                return comp != null && comp.getActions() != null && 
                       Arrays.asList(comp.getActions()).contains("Deposit");
            })
            .nearest(20);
        
        if (depositBox != null) {
            int distance = playerPos.distanceTo(depositBox.getWorldLocation());
            log.info("Trying deposit box at {} (distance: {} tiles)", depositBox.getWorldLocation(), distance);
            if (depositBox.isReachable() && depositBox.click("Deposit")) {
                if (sleepUntil(Rs2DepositBox::isOpen, 3000)) {
                    return new BankPreference(
                        null,
                        "deposit",
                        depositBox.getWorldLocation(),
                        depositBox.getId(),
                        0, 0
                    );
                }
            }
        } else {
            log.debug("No deposit boxes found within 20 tiles of {}", playerPos);
        }
        
        // Priority 5: Try banker NPCs
        Rs2NpcModel banker = Microbot.getRs2NpcCache().query()
            .withName("Banker")
            .nearest(20);
        
        if (banker != null) {
            int distance = playerPos.distanceTo(banker.getWorldLocation());
            log.info("Trying banker NPC at {} (distance: {} tiles)", banker.getWorldLocation(), distance);
            if (banker.isReachable() && banker.click("Bank")) {
                if (sleepUntil(Rs2Bank::isOpen, 3000)) {
                    return new BankPreference(
                        null,
                        "npc",
                        banker.getWorldLocation(),
                        banker.getId(),
                        0, 0
                    );
                }
            }
        } else {
            log.debug("No banker NPCs found within 20 tiles of {}", playerPos);
        }
        
        log.warn("No bank objects or NPCs found within 20 tiles of player position {}", playerPos);
        return null;
    }
    
    /**
     * Identify what bank method we're currently using after successful Rs2Bank.openBank()
     */
    private static BankPreference identifyCurrentBankMethod() {
        WorldPoint playerPos = Microbot.getClient().getLocalPlayer().getWorldLocation();
        
        // Check for nearby bank objects using existing Queryable API
        Rs2TileObjectModel bank = Microbot.getRs2TileObjectCache().query()
            .where(obj -> Arrays.stream(Rs2BankID.bankIds).anyMatch(id -> obj.getId() == id))
            .nearest(5);
        
        if (bank != null) {
            String type = determineBankType(bank.getId());
            return new BankPreference(
                null,
                type,
                bank.getWorldLocation(),
                bank.getId(),
                0, 0
            );
        }
        
        // Check for NPCs
        Rs2NpcModel banker = Microbot.getRs2NpcCache().query()
            .withName("Banker")
            .nearest(5);
        
        if (banker != null) {
            return new BankPreference(
                null,
                "npc",
                banker.getWorldLocation(),
                banker.getId(),
                0, 0
            );
        }
        
        // Default fallback
        return new BankPreference(
            null,
            "booth",
            playerPos,
            null,
            0, 0
        );
    }
    
    /**
     * Try to use a cached bank method using existing Microbot API
     */
    private static boolean tryCachedBankMethod(BankPreference method) {
        try {
            switch (method.preferredType) {
                case "chest":
                case "booth":
                    // Use existing Queryable API to find specific bank
                    Rs2TileObjectModel bank = Microbot.getRs2TileObjectCache().query()
                        .where(obj -> obj.getId() == method.bankId)
                        .where(obj -> obj.getWorldLocation().equals(method.bankLocation))
                        .nearest();
                    
                    if (bank != null && bank.isReachable()) {
                        return bank.click("Bank") && sleepUntil(Rs2Bank::isOpen, 3000);
                    }
                    // Fallback to general bank finding
                    return Rs2Bank.openBank();
                    
                case "npc":
                    // Use existing NPC cache
                    Rs2NpcModel banker = Microbot.getRs2NpcCache().query()
                        .withName("Banker")
                        .nearest();
                    
                    if (banker != null && banker.isReachable()) {
                        return banker.click("Bank") && sleepUntil(Rs2Bank::isOpen, 3000);
                    }
                    break;
                    
                case "ge":
                    // Use existing Queryable API to find GE booth
                    Rs2TileObjectModel geBooth = Microbot.getRs2TileObjectCache().query()
                        .where(obj -> obj.getId() == 10060 || obj.getId() == 30389)
                        .nearest();
                    if (geBooth != null) {
                        return geBooth.click("Bank") && sleepUntil(Rs2Bank::isOpen, 3000);
                    }
                    break;
                    
                case "deposit":
                    // Use existing Queryable API to find deposit box
                    Rs2TileObjectModel depositBox = Microbot.getRs2TileObjectCache().query()
                        .where(obj -> Arrays.stream(Rs2BankID.bankIds).anyMatch(id -> obj.getId() == id))
                        .where(obj -> {
                            ObjectComposition comp = Microbot.getClient().getObjectDefinition(obj.getId());
                            return comp != null && comp.getActions() != null && 
                                   Arrays.stream(comp.getActions()).anyMatch(action -> "Deposit".equals(action));
                        })
                        .nearest();
                    if (depositBox != null) {
                        return depositBox.click("Deposit") && sleepUntil(Rs2DepositBox::isOpen, 3000);
                    }
                    break;
            }
        } catch (Exception e) {
            Microbot.logStackTrace("IrkedChoppaBanking", e);
        }
        
        return false;
    }
    
    /**
     * Determine bank type from ID using existing Rs2BankID
     */
    private static String determineBankType(int bankId) {
        // Check if it's a common chest ID
        Integer[] commonChestIds = {75, 76, 27663, 27664, 27665, 27666};
        if (Arrays.asList(commonChestIds).contains(bankId)) {
            return "chest";
        }
        
        // Check if it's a common booth ID
        Integer[] commonBoothIds = {2213, 2214, 375, 376, 377, 378};
        return Arrays.asList(commonBoothIds).contains(bankId) ? "booth" : "booth";
    }
    
    /**
     * Cache the bank method
     */
    private static void cacheBankMethod(String pluginName, BankPreference method) {
        bankCache.put(pluginName, method);
    }
    
    /**
     * Record successful bank usage
     */
    private static void recordSuccess(BankPreference method) {
        BankPreference updated = new BankPreference(
            method.preferredLocation,
            method.preferredType,
            method.bankLocation,
            method.bankId,
            method.successCount + 1,
            method.failureCount
        );
        bankCache.put("IrkedChoppa", updated);
        log.debug("Bank method success recorded: {} (success rate: {:.2f}%)", 
                 method.preferredType, updated.getSuccessRate() * 100.0);
    }
    
    /**
     * Record failed bank usage
     */
    private static void recordFailure(BankPreference method) {
        BankPreference updated = new BankPreference(
            method.preferredLocation,
            method.preferredType,
            method.bankLocation,
            method.bankId,
            method.successCount,
            method.failureCount + 1
        );
        bankCache.put("IrkedChoppa", updated);
        log.debug("Bank method failure recorded: {} (success rate: {:.2f}%)", 
                 method.preferredType, updated.getSuccessRate() * 100.0);
    }
    
    /**
     * Get current bank method info for debugging
     */
    public static BankPreference getCurrentBankMethod() {
        return bankCache.get("IrkedChoppa");
    }
    
    /**
     * Clear IrkedChoppa's bank cache
     */
    public static void clearCache() {
        bankCache.remove("IrkedChoppa");
        log.info("IrkedChoppa bank cache cleared");
    }
    
    /**
     * Check if we have a reliable cached bank method
     */
    public static boolean hasReliableCache() {
        BankPreference preference = bankCache.get("IrkedChoppa");
        return preference != null && preference.isCacheValid() && preference.isReliable();
    }
}
