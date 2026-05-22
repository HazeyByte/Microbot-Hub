/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.runelite.client.plugins.microbot.Microbot
 *  net.runelite.client.plugins.microbot.util.Global
 *  net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban
 *  net.runelite.client.plugins.microbot.util.bank.Rs2Bank
 *  net.runelite.client.plugins.microbot.util.depositbox.Rs2DepositBox
 *  net.runelite.client.plugins.microbot.util.inventory.InteractOrder
 *  net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
 *  net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel
 *  net.runelite.client.plugins.microbot.util.player.Rs2Player
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 */
package net.runelite.client.plugins.microbot.irkedminer.handlers;

import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.irkedminer.IrkedMinerConfig;
import net.runelite.client.plugins.microbot.irkedminer.IrkedMinerState;
import net.runelite.client.plugins.microbot.irkedminer.data.Ores;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.depositbox.Rs2DepositBox;
import net.runelite.client.plugins.microbot.util.inventory.InteractOrder;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InventoryHandler {
    private static final Logger log = LoggerFactory.getLogger(InventoryHandler.class);
    private static final String COAL_BAG = "coal bag";
    private static final String GEM_BAG_OPEN = "Open gem bag";
    private static final String GEM_BAG_CLOSED = "gem bag";
    private final IrkedMinerConfig config;
    private final Supplier<Ores> selectedOreSupplier;
    private final Supplier<String> selectedOreKeySupplier;
    private final Consumer<IrkedMinerState> changeState;
    private final BooleanSupplier walkBackIfNeeded;
    private int bankingRetryCount = 0;
    private int droppingRetryCount = 0;

    public InventoryHandler(IrkedMinerConfig config, Supplier<Ores> selectedOreSupplier, Supplier<String> selectedOreKeySupplier, Consumer<IrkedMinerState> changeState, BooleanSupplier walkBackIfNeeded) {
        this.config = config;
        this.selectedOreSupplier = selectedOreSupplier;
        this.selectedOreKeySupplier = selectedOreKeySupplier;
        this.changeState = changeState;
        this.walkBackIfNeeded = walkBackIfNeeded;
    }

    public void reset() {
        this.bankingRetryCount = 0;
        this.droppingRetryCount = 0;
    }

    public void resetForState(IrkedMinerState newState) {
        if (newState == IrkedMinerState.BANK) {
            this.bankingRetryCount = 0;
        } else if (newState == IrkedMinerState.DROP) {
            this.droppingRetryCount = 0;
        }
    }

    public boolean tryFillBagsIfPossible() {
        boolean changed = false;
        if (!this.config.dropUncutGems() && this.hasGemBag() && this.hasUncutGems()) {
            changed = this.fillGemBag();
        }
        if (this.isCoalOreSelected() && this.hasCoalBag() && this.hasCoalInInventory()) {
            changed = this.fillCoalBag() || changed;
        }
        return changed && !Rs2Inventory.isFull();
    }

    public void executeBanking() {
        boolean opened;
        if (this.config.useDepositBox()) {
            this.executeDepositBoxBanking();
            return;
        }
        Microbot.status = "Banking...";
        if (this.config.dropUncutGems() && this.hasUncutGems()) {
            log.info("Dropping uncut gems before banking (dropUncutGems enabled)");
            this.dropUncutGems();
            if (!Rs2Inventory.isFull()) {
                this.changeState.accept(this.nextPostInventoryState());
                this.bankingRetryCount = 0;
                if (this.config.useBank()) {
                    Rs2Antiban.takeMicroBreakByChance();
                }
                return;
            }
        }
        if (!(Rs2Bank.isOpen() || (opened = Rs2Bank.openBank()) || Rs2Bank.walkToBankAndUseBank())) {
            ++this.bankingRetryCount;
            if (this.bankingRetryCount > 3) {
                this.changeState.accept(IrkedMinerState.RECOVER);
            }
            return;
        }
        this.handleBankingDeposits();
    }

    public void executeDropping() {
        Microbot.status = "Dropping items...";
        String[] itemsToKeep = this.parseItemsToKeep();
        if (this.config.dropUncutGems() && this.hasUncutGems()) {
            log.info("Dropping uncut gems before dropping items (user enabled dropUncutGems: {})", (Object)this.config.dropUncutGems());
            this.dropUncutGems();
        } else if (this.config.enableDebugLogging()) {
            log.debug("Not dropping gems in dropping state - dropUncutGems: {}, hasUncutGems: {}", (Object)this.config.dropUncutGems(), (Object)this.hasUncutGems());
        }
        if (this.isCoalPowerMine()) {
            this.dropCoalInInventory();
            if (this.emptyCoalBagToInventory()) {
                this.dropCoalInInventory();
            }
        }
        Rs2Inventory.dropAllExcept((boolean)false, (InteractOrder)this.config.interactOrder(), (String[])itemsToKeep);
        if (Global.sleepUntil(() -> !Rs2Inventory.isFull(), (int)3500)) {
            this.changeState.accept(this.nextPostInventoryState());
            this.droppingRetryCount = 0;
            if (this.config.useBank()) {
                Rs2Antiban.takeMicroBreakByChance();
            }
        } else {
            ++this.droppingRetryCount;
            if (this.droppingRetryCount > 3) {
                this.changeState.accept(IrkedMinerState.RECOVER);
            }
        }
    }

    private void executeDepositBoxBanking() {
        Microbot.status = "Depositing...";
        if (!Rs2DepositBox.isOpen() && !Rs2DepositBox.openDepositBox()) {
            ++this.bankingRetryCount;
            if (this.bankingRetryCount > 3) {
                this.changeState.accept(IrkedMinerState.RECOVER);
            }
            return;
        }
        this.handleDepositBoxDeposits();
    }

    private void handleBankingDeposits() {
        this.handleInventoryBeforeDeposit();
        String[] itemsToKeep = this.parseItemsToKeep();
        Rs2Bank.depositAllExcept((String[])itemsToKeep);
        if (Global.sleepUntil(() -> !Rs2Inventory.isFull(), (int)2500)) {
            Rs2Bank.closeBank();
            Global.sleepUntil(() -> !Rs2Bank.isOpen(), (int)2000);
            if (!this.walkBackIfNeeded.getAsBoolean()) {
                log.warn("Banking finished but walk-back to mining area could not be completed");
                this.changeState.accept(IrkedMinerState.WAIT_RESPAWN);
                return;
            }
            this.changeState.accept(this.nextPostInventoryState());
            this.bankingRetryCount = 0;
            if (this.config.useBank()) {
                Rs2Antiban.takeMicroBreakByChance();
            }
        } else {
            ++this.bankingRetryCount;
            if (this.bankingRetryCount > 3) {
                this.changeState.accept(IrkedMinerState.RECOVER);
            }
        }
    }

    private void handleDepositBoxDeposits() {
        this.handleInventoryBeforeDeposit();
        String[] itemsToKeep = this.parseItemsToKeep();
        Rs2DepositBox.depositAllExcept((String[])itemsToKeep);
        if (Global.sleepUntil(() -> !Rs2Inventory.isFull(), (int)2500)) {
            Rs2DepositBox.closeDepositBox();
            if (!this.walkBackIfNeeded.getAsBoolean()) {
                log.warn("Deposit finished but walk-back to mining area could not be completed");
                this.changeState.accept(IrkedMinerState.WAIT_RESPAWN);
                return;
            }
            this.changeState.accept(this.nextPostInventoryState());
            this.bankingRetryCount = 0;
            if (this.config.useBank()) {
                Rs2Antiban.takeMicroBreakByChance();
            }
        } else {
            ++this.bankingRetryCount;
            if (this.bankingRetryCount > 3) {
                this.changeState.accept(IrkedMinerState.RECOVER);
            }
        }
    }

    private void handleInventoryBeforeDeposit() {
        if (!this.config.dropUncutGems()) {
            this.emptyGemBagToBank();
        }
        this.emptyCoalBagToBank();
    }

    private void dropUncutGems() {
        List<Rs2ItemModel> allItems = Rs2Inventory.all();
        List<Rs2ItemModel> gemsToDrop = allItems.stream().filter(item -> item != null && item.getName() != null && this.isUncutGem(item.getName())).collect(Collectors.toList());
        if (gemsToDrop.isEmpty()) {
            if (this.config.enableDebugLogging()) {
                log.debug("No uncut gems found to drop");
            }
            return;
        }
        if (this.config.enableDebugLogging()) {
            log.debug("Found {} uncut gems to drop: {}", (Object)gemsToDrop.size(), gemsToDrop.stream().map(Rs2ItemModel::getName).collect(Collectors.toList()));
        }
        int droppedCount = 0;
        for (Rs2ItemModel gem : gemsToDrop) {
            if (!Rs2Inventory.interact((int)gem.getId(), (String)"Drop")) continue;
            ++droppedCount;
            Global.sleepUntil(() -> !Rs2Inventory.contains((int[])new int[]{gem.getId()}), (int)800);
        }
        log.info("Dropped {}/{} uncut gems", (Object)droppedCount, (Object)gemsToDrop.size());
        Global.sleepUntil(() -> !Rs2Player.isAnimating(), (int)600);
    }

    private boolean hasUncutGems() {
        List<Rs2ItemModel> allItems = Rs2Inventory.all();
        if (this.config.enableDebugLogging()) {
            log.debug("Checking inventory for uncut gems. Total items: {}", (Object)allItems.size());
            allItems.forEach(item -> {
                if (item != null && item.getName() != null) {
                    log.debug("Inventory item: '{}' (ID: {})", (Object)item.getName(), (Object)item.getId());
                }
            });
        }
        boolean hasGems = allItems.stream().anyMatch(item -> item != null && item.getName() != null && this.isUncutGem(item.getName()));
        if (this.config.enableDebugLogging()) {
            log.debug("Gem check - dropUncutGems enabled: {}, has gems: {}", (Object)this.config.dropUncutGems(), (Object)hasGems);
        }
        return hasGems;
    }

    private boolean isUncutGem(String itemName) {
        if (itemName == null) {
            return false;
        }
        String lowerName = itemName.toLowerCase().trim();
        return lowerName.equals("uncut sapphire") || lowerName.equals("uncut emerald") || lowerName.equals("uncut ruby") || lowerName.equals("uncut diamond") || lowerName.equals("uncut dragonstone") || lowerName.equals("uncut onyx") || lowerName.equals("uncut opal") || lowerName.equals("uncut jade") || lowerName.equals("uncut red topaz");
    }

    private String[] parseItemsToKeep() {
        List<String> items = Arrays.stream(this.config.itemsToKeep().split(",")).map(String::trim).filter(item -> !item.isEmpty()).collect(Collectors.toList());
        items.add(GEM_BAG_CLOSED);
        items.add(GEM_BAG_OPEN);
        items.add(COAL_BAG);
        return items.toArray(new String[0]);
    }

    private boolean hasGemBag() {
        return Rs2Inventory.contains((String[])new String[]{GEM_BAG_CLOSED}) || Rs2Inventory.contains((String[])new String[]{GEM_BAG_OPEN});
    }

    private boolean openGemBag() {
        if (Rs2Inventory.contains((String[])new String[]{GEM_BAG_OPEN})) {
            return true;
        }
        if (Rs2Inventory.contains((String[])new String[]{GEM_BAG_CLOSED}) && Rs2Inventory.interact((String)GEM_BAG_CLOSED, (String)"Open")) {
            return Global.sleepUntil(() -> Rs2Inventory.contains((String[])new String[]{GEM_BAG_OPEN}), (int)1500);
        }
        return false;
    }

    private boolean fillGemBag() {
        if (!this.openGemBag()) {
            return false;
        }
        boolean filled = Rs2Inventory.interact((String)GEM_BAG_OPEN, (String)"Fill");
        if (filled) {
            Rs2Inventory.waitForInventoryChanges((int)2500);
        }
        return filled;
    }

    private void emptyGemBagToBank() {
        if (!this.hasGemBag() || !Rs2Bank.isOpen() && !Rs2DepositBox.isOpen()) {
            return;
        }
        Rs2Bank.emptyGemBag();
        Rs2Inventory.waitForInventoryChanges((int)2500);
    }

    private boolean hasCoalBag() {
        return Rs2Inventory.contains((String[])new String[]{COAL_BAG});
    }

    private boolean hasCoalInInventory() {
        return Rs2Inventory.all().stream().anyMatch(item -> item != null && item.getName() != null && item.getName().equalsIgnoreCase("coal"));
    }

    private boolean fillCoalBag() {
        if (!this.hasCoalBag()) {
            return false;
        }
        boolean filled = Rs2Inventory.interact((String)COAL_BAG, (String)"Fill");
        if (filled) {
            Rs2Inventory.waitForInventoryChanges((int)2500);
        }
        return filled;
    }

    private boolean emptyCoalBagToInventory() {
        if (!this.hasCoalBag()) {
            return false;
        }
        boolean emptied = Rs2Inventory.interact((String)COAL_BAG, (String)"Empty");
        if (emptied) {
            Rs2Inventory.waitForInventoryChanges((int)2500);
        }
        return emptied;
    }

    private void emptyCoalBagToBank() {
        if (Rs2Bank.isOpen() || Rs2DepositBox.isOpen()) {
            this.emptyCoalBagToInventory();
        }
    }

    private void dropCoalInInventory() {
        Rs2Inventory.all().stream().filter(item -> item != null && item.getName() != null && item.getName().equalsIgnoreCase("coal")).forEach(item -> {
            if (Rs2Inventory.interact((int)item.getId(), (String)"Drop")) {
                Global.sleepUntil(() -> !Rs2Inventory.contains((int[])new int[]{item.getId()}), (int)800);
            }
        });
    }

    private boolean isCoalOreSelected() {
        Ores selectedOre = this.selectedOreSupplier.get();
        String selectedKey = this.selectedOreKeySupplier.get();
        return selectedOre == Ores.COAL || "coal".equalsIgnoreCase(selectedKey);
    }

    private boolean isCoalPowerMine() {
        return !this.config.useBank() && this.isCoalOreSelected();
    }

    private IrkedMinerState nextPostInventoryState() {
        return this.config.enablePriorityMining() ? IrkedMinerState.FIND_TARGET : IrkedMinerState.MINE;
    }
}
