package net.runelite.client.plugins.microbot.blastoisefurnace;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.blastoisefurnace.enums.Bars;
import net.runelite.client.plugins.microbot.blastoisefurnace.enums.State;
import net.runelite.client.plugins.microbot.breakhandler.BreakHandlerScript;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.misc.Rs2Potion;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import javax.inject.Inject;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.runelite.api.gameval.ItemID.*;
import static net.runelite.api.gameval.ObjectID.*;
import static net.runelite.api.gameval.VarbitID.*;

@Slf4j
public class BlastoiseFurnaceScript extends Script {
    private static final Pattern ITEM_NAME_SUFFIX_PATTERN = Pattern.compile("^(.*?)(?:\\s*\\((\\d+)\\))?$");
    static final int coalBag = 12019;
    private static final int MAX_ORE_PER_INTERACTION = 27;
    private static final int MAX_ORE_PER_HYBRID_INTERACTION = 26;
    private boolean cameraConfigured = false;
    // Overlay session stats (G2)
    long sessionStartMs;
    long sessionStartSmithXp;
    int barsMade;
    // Instance state (was static — static leaked stale flags across plugin restarts).
    public State state = State.BANKING;
    boolean coalBagEmpty;
    private boolean timerStarted = false;
    private volatile boolean timeIsUp;
    private boolean init = false;

    private final BlastoiseFurnacePlugin plugin;
    private final BlastoiseFurnaceConfig config;

    private boolean hasRequiredOresForSmithing() {
        int primaryOre = config.getBars().getPrimaryOre();
        Integer secondaryOre = config.getBars().getSecondaryOre();
        // Count bank AND inventory so we don't log out while still holding a smithable load.
        // ponytail: coal sealed inside the coal bag isn't visible here — full coal-bag
        // accounting is Phase 3, tracked via coalBagEmpty.
        boolean hasPrimaryOre = Rs2Bank.hasItem(primaryOre) || Rs2Inventory.hasItem(primaryOre);
        boolean hasSecondaryOre = secondaryOre == null
                || Rs2Bank.hasItem(secondaryOre)
                || Rs2Inventory.hasItem(secondaryOre)
                || (secondaryOre == COAL && !coalBagEmpty);
        return hasPrimaryOre && hasSecondaryOre;
    }

    @Inject
    public BlastoiseFurnaceScript(BlastoiseFurnacePlugin plugin, BlastoiseFurnaceConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public boolean run() {
        Microbot.enableAutoRunOn = false;
        state = State.BANKING;
        sessionStartMs = System.currentTimeMillis();
        sessionStartSmithXp = Microbot.getClient().getSkillExperience(Skill.SMITHING);
        barsMade = 0;
        Rs2Antiban.resetAntibanSettings();
        applyAntiBanSettings();

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) {
                    return;
                }

                if (!super.run()) {
                    return;
                }

                if (!Rs2GameObject.exists(BLAST_FURNACE_DISPENSER)) {
                    state = State.WALK_TO_FURNACE;
                    Microbot.status = "Travelling to furnace";
                    if (Rs2Player.isAnimating()) {
                        return;
                    }
                    Rs2Walker.walkTo(new WorldPoint(2931, 10197, 0));
                    Microbot.getRs2TileObjectCache().query().interact(DWARF_KELDAGRIM_FACTORY_STAIRS);
                    return;
                }

                if (state == State.WALK_TO_FURNACE) {
                    state = State.BANKING;
                }

                setupCameraOnce();

                if (!init) {
                    // Only fund the coffer on startup if it is actually empty — never top a
                    // partially-funded coffer up to the target.
                    if (!fullCoffer()) {
                        checkAndTopOffCoffer();
                    }
                    init = true;
                    return;
                }

                if (!fullCoffer()) {
                    checkAndTopOffCoffer();
                }

                boolean hasGauntlets;
                switch (state) {
                    case BANKING:
                        Microbot.status = "Banking";
                        if (!Rs2Bank.isOpen()) {
                            Rs2Bank.openBank();
                            sleepUntil(Rs2Bank::isOpen, 20000);
                        }

                        if (config.getBars().isRequiresCoalBag() && !Rs2Inventory.contains(coalBag)) {
                            if (!Rs2Bank.hasItem(coalBag)) {
                                Microbot.showMessage("No coal bag found in inventory and bank.");
                                Microbot.stopPlugin(plugin);
                                return;
                            }

                            Rs2Bank.withdrawItem(coalBag);
                        }

                        if (config.getBars().isRequiresGoldsmithGloves()) {
                            hasGauntlets = Rs2Inventory.contains(GAUNTLETS_OF_GOLDSMITHING) || Rs2Equipment.isWearing(GAUNTLETS_OF_GOLDSMITHING);
                            if (!hasGauntlets) {
                                if (!Rs2Bank.hasItem(GAUNTLETS_OF_GOLDSMITHING)) {
                                    Microbot.showMessage("No goldsmith gauntlets found.");
                                    Microbot.stopPlugin(plugin);
                                    return;
                                }

                                Rs2Bank.withdrawItem(GAUNTLETS_OF_GOLDSMITHING);
                            }
                        } else Rs2Bank.depositAll(GAUNTLETS_OF_GOLDSMITHING);

                        if (Rs2Inventory.hasItem("bar")) {
                            Rs2Bank.depositAllExcept(coalBag, GAUNTLETS_OF_GOLDSMITHING, ICE_GLOVES, SMITHING_UNIFORM_GLOVES_ICE);
                            if (Rs2Inventory.isFull()) {
                                // Wait so our player avoids drinking potions with a full inventory
                                if (!Rs2Inventory.waitForInventoryChanges(1800)) {
                                    sleepUntil(() -> !Rs2Inventory.isFull(), 1200);
                                }
                            }
                        }

                        if (!hasRequiredOresForSmithing()) {
                            log.warn("Out of ores. Walking you out for coffer safety");
                            Rs2Walker.walkTo(new WorldPoint(2930, 10196, 0));
                            Rs2Player.logout();
                            Microbot.stopPlugin(plugin);
                            return;
                        }

                        if (Microbot.getClient().getEnergy() < 8100) {
                            useStaminaPotions();
                        }

                        // Bars already waiting (e.g. after a restart) → go collect them; otherwise load.
                        if (dispenserContainsBars()) {
                            state = State.COLLECTING;
                        } else {
                            state = State.LOADING;
                        }
                        break;

                    case LOADING:
                        Microbot.status = "Loading furnace";
                        // G4: wear the goldsmith gauntlets BEFORE the ore hits the belt so the
                        // very first gold batch gets the bonus (no-op when not doing gold).
                        equipGoldSmithGauntlets();
                        retrieveItemsForCurrentFurnaceInteraction();
                        state = State.WAITING;
                        break;

                    case WAITING:
                        Microbot.status = "Waiting for bars";
                        if (barsInDispenser(config.getBars()) > 0 || dispenserContainsBars()) {
                            state = State.COLLECTING;
                        } else if (!oreCookingInFurnace()) {
                            // nothing dispensing and nothing left cooking — reload rather than hang here.
                            state = State.BANKING;
                        }
                        break;

                    case COLLECTING:
                        Microbot.status = "Collecting bars";
                        if (handleDispenserLooting()) {
                            state = State.BANKING;
                        }
                        break;
                }
            } catch (Exception ex) {
                log.warn("Error in main loop: {} - ", ex.getMessage(), ex);
            }

        }, 0, 200, TimeUnit.MILLISECONDS);
        return true;
    }

    private void handleTax() {
        log.info("Paying noob smithing tax");
        if (!Rs2Bank.isOpen()) {
            Rs2Bank.openBank();
            sleepUntil(Rs2Bank::isOpen, 20000);
        }
        Rs2Bank.depositOne(config.getBars().getPrimaryOre());
        sleep(500, 1200);
        Rs2Bank.depositOne(COAL);
        sleep(500, 1200);
        Rs2Bank.withdrawX(COINS, 2500);
        sleep(500, 1200);
        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen());
        var blastie = Microbot.getRs2NpcCache().query().withName("Blast Furnace Foreman").nearestOnClientThread();
        if (blastie != null) blastie.click("Pay");
        sleepUntil(Rs2Dialogue::isInDialogue, 10000);
        if (Rs2Dialogue.hasSelectAnOption()) {
            Rs2Dialogue.clickOption("Yes");
            sleep(1000, 1850);
            Rs2Dialogue.clickContinue();
            sleepUntil(()-> !Rs2Dialogue.isInDialogue(), Rs2Random.between(750,1500));
            if(!Rs2Dialogue.isInDialogue()){
                setTenMinuteTimer();
            }
        }
    }

    /** @return true when collection is finished (or nothing to do); false to retry (bars still molten). */
    private boolean handleDispenserLooting() {
        if (Rs2Inventory.isFull()) {
            return true; // no room — let BANKING deposit first
        }
        if (!dispenserContainsBars()) {
            sleepUntil(this::dispenserContainsBars, Rs2Random.between(3000, 5000));
        }

        if (!Rs2Equipment.isWearing(ICE_GLOVES) && !Rs2Equipment.isWearing(SMITHING_UNIFORM_GLOVES_ICE)) {
            boolean equipped = Rs2Inventory.interact(ICE_GLOVES, "Wear")
                    || Rs2Inventory.interact(SMITHING_UNIFORM_GLOVES_ICE, "Wear");
            if (!equipped) {
                Microbot.showMessage("Ice gloves or smith gloves required to loot the hot bars.");
                Rs2Player.logout();
                Microbot.stopPlugin(plugin);
                return true;
            }
        }

        final int pendingBars = barsInDispenser(config.getBars());

        humanReactionDelay();
        Microbot.getRs2TileObjectCache().query().interact(BLAST_FURNACE_DISPENSER, "Take");

        sleepUntil(() ->
                Rs2Widget.hasWidget("What would you like to take?") ||
                        Rs2Widget.hasWidget("How many would you like") ||
                        Rs2Widget.hasWidget("The bars are still molten!"), 5000);

        // G6: if the bars haven't cooled (ice gloves not on / just formed), give them a
        // moment and retry rather than banking on a failed take.
        if (Rs2Widget.hasWidget("The bars are still molten!")) {
            log.info("Bars still molten - waiting to cool before retrying");
            sleep(Rs2Random.between(1200, 2400));
            return false;
        }

        sleepUntil(() ->
                Rs2Widget.hasWidget("What would you like to take?") ||
                        Rs2Widget.hasWidget("How many would you like"), 3000);

        boolean multipleBarTypes = Rs2Widget.hasWidget("What would you like to take?");
        boolean canLootBar = Rs2Widget.hasWidget("How many would you like");

        if (super.run()) {
            if (canLootBar || multipleBarTypes) {
                Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
            }
            Rs2Inventory.waitForInventoryChanges(5000);
            if (pendingBars > 0) barsMade += pendingBars;
            Rs2Bank.openBank();
            equipGoldSmithGauntlets();
        }
        return true;
    }

    /** Ore still smelting in the furnace for the current bar (primary ore or coal remaining). */
    private boolean oreCookingInFurnace() {
        return Microbot.getVarbitValue(config.getBars().getBFPrimaryOreID()) > 0
                || Microbot.getVarbitValue(BLAST_FURNACE_COAL) > 0;
    }

    private void retrievePrimary() {
        int ore = config.getBars().getPrimaryOre();
        if (!Rs2Inventory.hasItem(ore)) {
            Rs2Bank.withdrawAll(ore);
            return;
        }
        doOreRun(false, false);
    }

    private void retrieveDoubleCoal() {
        if (!Rs2Inventory.hasItem(COAL)) {
            Rs2Bank.withdrawAll(COAL);
            return;
        }
        if (!Rs2Inventory.interact(coalBag, "Fill"))
            return;
        depositOre();
    }

    private void retrieveCoalAndPrimary() {
        int ore = config.getBars().getPrimaryOre();
        if (!Rs2Inventory.hasItem(ore)) {
            Rs2Bank.withdrawAll(ore);
            sleep(500, 1200);
            return;
        }
        if (!Rs2Inventory.interact(coalBag, "Fill"))
            return;

        sleep(500, 1200);
        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen());
        depositOre();
        doOreRun(false, false);
    }

    private void retrieveCoalAndGold() {
        if (!Rs2Inventory.hasItem(GOLD_ORE)) {
            Rs2Bank.withdrawAll(GOLD_ORE);
            return;
        }
        if (!Rs2Inventory.interact(coalBag, "Fill"))
            return;

        sleep(500, 1200);
        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen());
        depositOre();
        doOreRun(true, true);
    }

    private void retrieveGold() {
        if (!Rs2Inventory.hasItem(GOLD_ORE)) {
            Rs2Bank.withdrawAll(GOLD_ORE);
            return;
        }
        depositOre();
        doOreRun(true, true);
    }

    private void doOreRun(boolean useIceGloves, boolean waitInventoryChange) {
        // No explicit walk to a tile: putting ore on the belt leaves us a few tiles from the
        // dispenser and the "Take" interaction auto-paths the rest — like a player just clicking
        // the dispenser. Here we only wait for the bars to be produced.
        sleep(3400);
        sleepUntil(() -> barsInDispenser(config.getBars()) > 0, 10000);

        if (useIceGloves) {
            if (!Rs2Equipment.isWearing(ICE_GLOVES) && !Rs2Equipment.isWearing(SMITHING_UNIFORM_GLOVES_ICE)) {
                boolean equipped = Rs2Inventory.interact(ICE_GLOVES, "Wear")
                        || Rs2Inventory.interact(SMITHING_UNIFORM_GLOVES_ICE, "Wear");
                if (!equipped) {
                    Microbot.showMessage("Ice gloves or smith gloves required to loot the hot bars.");
                    Rs2Player.logout();
                    Microbot.stopPlugin(plugin);
                    return;
                }
            }
            if (waitInventoryChange)
                Rs2Inventory.waitForInventoryChanges(2000);
        } else {
            sleep(400, 700);
        }
    }

    private void retrieveItemsForCurrentFurnaceInteraction() {
        final Bars bar = config.getBars();
        final int coal = Microbot.getVarbitValue(
                bar == Bars.GOLD_BAR ? BLAST_FURNACE_GOLD_ORE : BLAST_FURNACE_COAL
        );
        final int divisor = isHybrid(bar) ? MAX_ORE_PER_HYBRID_INTERACTION : MAX_ORE_PER_INTERACTION;
        final int batch = coal / divisor;

        switch (bar) {
            case GOLD_BAR:
                retrieveGold();
                break;

            case STEEL_BAR:
            case MITHRIL_BAR:
                dispatchStandard(batch, 0, retrieveDoubleCoal, retrieveCoalAndPrimary, retrievePrimary);
                break;

            case ADAMANTITE_BAR:
            case RUNITE_BAR:
                dispatchStandard(batch, 2, retrieveDoubleCoal, retrieveCoalAndPrimary, retrievePrimary);
                break;

            case HYBRID_MITHRIL_BAR:
                dispatchHybrid(batch, 0, retrieveCoalAndGold, retrieveCoalAndPrimary);
                break;

            case HYBRID_ADAMANTITE_BAR:
                dispatchHybrid(batch, 2, retrieveCoalAndGold, retrieveCoalAndPrimary);
                break;

            case HYBRID_RUNITE_BAR:
                dispatchHybrid(batch, 3, retrieveCoalAndGold, retrieveCoalAndPrimary);
                break;

            default:
                assert false : "unhandled bar type: " + bar;
        }
    }

    private boolean isHybrid(Bars bar) {
        return bar.name().startsWith("HYBRID");
    }

    private final Runnable retrievePrimary = this::retrievePrimary;
    private final Runnable retrieveCoalAndPrimary = this::retrieveCoalAndPrimary;
    private final Runnable retrieveCoalAndGold = this::retrieveCoalAndGold;
    private final Runnable retrieveDoubleCoal = this::retrieveDoubleCoal;

    // Retrieval action codes (pure decision, split out so the feeding math is unit-testable).
    public static final int ACTION_DOUBLE_COAL = 0;
    public static final int ACTION_COAL_AND_PRIMARY = 1;
    public static final int ACTION_PRIMARY = 2;
    public static final int ACTION_COAL_AND_GOLD = 0;

    /** Standard bars: pick the retrieval action from how many coal-bag loads are already in the furnace. */
    public static int standardAction(int batch, int doubleCoalMax) {
        if (batch <= doubleCoalMax) return ACTION_DOUBLE_COAL;
        if (batch <= 6) return ACTION_COAL_AND_PRIMARY;
        return ACTION_PRIMARY;
    }

    /** Hybrid (gold-included) bars: coal+gold while the furnace is still coal-light, else coal+primary. */
    public static int hybridAction(int batch, int goldThreshold) {
        return batch <= goldThreshold ? ACTION_COAL_AND_GOLD : ACTION_COAL_AND_PRIMARY;
    }

    private void dispatchStandard(int batch, int doubleCoalMax, Runnable doubleCoal, Runnable coalAndPrimary, Runnable primary) {
        switch (standardAction(batch, doubleCoalMax)) {
            case ACTION_DOUBLE_COAL: doubleCoal.run(); break;
            case ACTION_COAL_AND_PRIMARY: coalAndPrimary.run(); break;
            default: primary.run();
        }
    }

    private void dispatchHybrid(int batch, int goldThreshold, Runnable coalAndGold, Runnable coalAndPrimary) {
        if (hybridAction(batch, goldThreshold) == ACTION_COAL_AND_GOLD) coalAndGold.run();
        else coalAndPrimary.run();
    }

    private void useStaminaPotions() {
        if (!Rs2Bank.isOpen()) return;

        boolean hasStaminaPotion = Rs2Bank.hasItem(Rs2Potion.getStaminaPotion());
        boolean hasEnergyPotion = Rs2Bank.hasItem(Rs2Potion.getRestoreEnergyPotionsVariants());


        if (!Rs2Player.hasStaminaBuffActive() && hasStaminaPotion) {
            String potionName = getLowestDosePotionName(List.of(Rs2Potion.getStaminaPotion()));
            if (potionName != null) {
                withdrawAndDrink(potionName);
                return;
            }
        }
        if (hasEnergyPotion) {
            String potionName = getLowestDosePotionName(Rs2Potion.getRestoreEnergyPotionsVariants());
            if (potionName != null) {
                withdrawAndDrink(potionName);
            }
        }
    }

    private String getLowestDosePotionName(List<String> variants) {
        return Rs2Bank.getAll(item -> variants.stream().anyMatch(variant -> item.getName().toLowerCase().contains(variant.toLowerCase())))
                .min(Comparator.comparingInt(item -> getDoseFromName(item.getName())))
                .map(Rs2ItemModel::getName)
                .orElse(null);
    }

    public static int getDoseFromName(String potionItemName) {
        Matcher matcher = ITEM_NAME_SUFFIX_PATTERN.matcher(potionItemName);
        if (matcher.find() && matcher.group(2) != null) {
            return Integer.parseInt(matcher.group(2));
        }
        return 0;
    }

    public static String getBaseName(String itemName) {
        Matcher matcher = ITEM_NAME_SUFFIX_PATTERN.matcher(itemName);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return itemName;
    }

    private void withdrawAndDrink(String potionItemName) {
        String baseName = getBaseName(potionItemName);
        boolean withdrewPotion = Rs2Bank.withdrawOne(potionItemName);
        if (!withdrewPotion) {
            if (Rs2Inventory.isFull()) {
                // Wait a full tick for safety
                if (!Rs2Inventory.waitForInventoryChanges(600) && Rs2Inventory.isFull()) {
                    log.debug("Inventory remained full while attempting to withdraw {}", potionItemName);
                    return;
                }
                withdrewPotion = Rs2Bank.withdrawOne(potionItemName);
            }
            if (!withdrewPotion) {
                log.debug("Failed to withdraw potion {} from the bank", potionItemName);
                return;
            }
        }
        Rs2Inventory.waitForInventoryChanges(1800);
        Rs2Inventory.interact(potionItemName, "drink");
        Rs2Inventory.waitForInventoryChanges(1800);
        if (Rs2Inventory.hasItem(baseName)) {
            Rs2Bank.depositOne(baseName);
            Rs2Inventory.waitForInventoryChanges(1800);
        }
        if (Rs2Inventory.hasItem(ItemID.VIAL_EMPTY)) {
            Rs2Bank.depositOne(ItemID.VIAL_EMPTY);
            Rs2Inventory.waitForInventoryChanges(1800);
        }
    }

    private int getInventoryOreCount() {
        return Rs2Inventory.count("ore") + Rs2Inventory.count("coal");
    }

    private boolean putOreOnConveyorBelt() {
        // Bounded retry loop (was unbounded recursion — a persistent foreman widget
        // could StackOverflow). Each pass re-clicks the belt; pay the tax only when
        // we're outside a paid 10-minute window.
        for (int attempt = 0; attempt < 5; attempt++) {
            final int oreCount = getInventoryOreCount();
            if (oreCount <= 0) {
                log.error("No ore in Inventory");
                return false;
            }
            if (!Microbot.getRs2TileObjectCache().query().interact(BLAST_FURNACE_CONVEYER_BELT_CLICKABLE, "Put-ore-on")) {
                log.error("Failed to interact with conveyor belt");
                return false;
            }
            sleepUntil(() -> Rs2Dialogue.isInDialogue() || getInventoryOreCount() < oreCount, 10_000);
            if (!Rs2Widget.hasWidget("You must ask the foreman's")) {
                return true;
            }
            log.info("Need to pay the noob tax");
            if (!(timerStarted && !timeIsUp)) {
                handleTax();
            }
        }
        log.warn("Gave up putting ore on the conveyor belt after retries");
        return false;
    }

    public void setTenMinuteTimer(){
        if(timerStarted) return;
        // Must reset here: once the first timer fires timeIsUp stays true forever
        // otherwise, which made the tax re-pay on every subsequent foreman prompt.
        timeIsUp = false;

        Timer timer = new Timer();

        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                Microbot.log("Time's up! 10 minutes have passed.");
                timeIsUp = true;
                timerStarted = false;
                timer.cancel();
            }
        };

        long delay = 10 * 60 * 1000; // 10 minutes in ms
        Microbot.log("Timer started for 10 minutes...");

        timer.schedule(task, delay);

        timerStarted = true;
    }

    private void depositOre() {
        putOreOnConveyorBelt();
        if (config.getBars().isRequiresCoalBag()) {
            if (Rs2Inventory.interact(coalBag, "Empty")) Rs2Inventory.waitForInventoryChanges(3_000);
            else log.error("Failed to empty coal bag 1");
            putOreOnConveyorBelt();
        }
        if (config.getBars().isRequiresCoalBag() &&
                (Rs2Inventory.hasItem(SMITHING_UNIFORM_GLOVES_ICE)
                        || Rs2Inventory.hasItem(GAUNTLETS_OF_GOLDSMITHING)
                        || Rs2Inventory.hasItem(ICE_GLOVES))) {
            if (Rs2Inventory.interact(coalBag, "Empty")) Rs2Inventory.waitForInventoryChanges(3_000);
            else log.error("Failed to empty coal bag 2");
            putOreOnConveyorBelt();
        }
    }


    public int barsInDispenser(Bars bar) {
        switch (bar) {
            case GOLD_BAR:
                return getBars(BLAST_FURNACE_GOLD_BARS);
            case STEEL_BAR:
                return getBars(BLAST_FURNACE_STEEL_BARS);
            case MITHRIL_BAR:
                return getBars(BLAST_FURNACE_MITHRIL_BARS);
            case ADAMANTITE_BAR:
                return getBars(BLAST_FURNACE_ADAMANTITE_BARS);
            case RUNITE_BAR:
                return getBars(BLAST_FURNACE_RUNITE_BARS);
            case HYBRID_MITHRIL_BAR:
                return fallbackBar(
                        BLAST_FURNACE_MITHRIL_BARS
                );
            case HYBRID_ADAMANTITE_BAR:
                return fallbackBar(
                        BLAST_FURNACE_ADAMANTITE_BARS
                );
            case HYBRID_RUNITE_BAR:
                return fallbackBar(
                        BLAST_FURNACE_RUNITE_BARS
                );
            default:
                return -1;
        }
    }

    private int fallbackBar(int primary) {
        int value = Microbot.getVarbitValue(primary);
        return value > 0 ? value : Microbot.getVarbitValue(net.runelite.api.gameval.VarbitID.BLAST_FURNACE_GOLD_BARS);
    }

    private int getBars(int varbitId) {
        return Microbot.getVarbitValue(varbitId);
    }

    public boolean dispenserContainsBars() {
        return Arrays.stream(new int[]{
                BLAST_FURNACE_IRON_BARS,
                BLAST_FURNACE_STEEL_BARS,
                BLAST_FURNACE_GOLD_BARS,
                BLAST_FURNACE_MITHRIL_BARS,
                BLAST_FURNACE_ADAMANTITE_BARS,
                BLAST_FURNACE_RUNITE_BARS
        }).anyMatch(id -> Microbot.getVarbitValue(id) > 0);
    }

    private void equipGoldSmithGauntlets() {
        if (config.getBars().isRequiresGoldsmithGloves()) {
            Rs2Inventory.interact(GAUNTLETS_OF_GOLDSMITHING, "Wear");
        }
    }

    private void applyAntiBanSettings() {
        // Simple, non-blocking antiban — this is what worked originally. Deliberately NOT the
        // smithing template: that enables playSchedule and forces per-action cooldowns, which
        // pause this tight loop. Natural mouse gives human movement without gating the cycle.
        Rs2AntibanSettings.antibanEnabled = true;
        Rs2AntibanSettings.naturalMouse = true;
        Rs2AntibanSettings.devDebug = false;
    }

    // One-time camera setup, like a player angling the view once so the bank, conveyor and
    // dispenser are all visible. After this we never auto-rotate — object interactions already
    // nudge the camera only when a target is genuinely off-screen. Pitch only; the player's
    // yaw/zoom are left as they set them.
    private void setupCameraOnce() {
        if (cameraConfigured) return;
        cameraConfigured = true;
        Rs2Camera.adjustPitch(0.9f);
    }

    // H2: small inline reaction beat before the loot click. Cycle-level pacing/fatigue/
    // breaks are handled by the Rs2Antiban framework (actionCooldown + the smithing
    // template), not here — this is just the pre-click latency the framework doesn't cover.
    private void humanReactionDelay() {
        if (!config.humanisation()) return;
        sleep(Rs2Random.between(0, 99) < 85 ? Rs2Random.between(200, 900) : Rs2Random.between(900, 2100));
    }

    public void shutdown() {
        init = false;
        cameraConfigured = false;
        state = State.BANKING;
        coalBagEmpty = false;
        super.shutdown();
    }

    public int evaluateCofferDeposit() {
        if (BreakHandlerScript.breakIn > 0) {
            return BreakHandlerScript.breakIn * 20;
        }

        return config.cofferTarget();
    }

    public void checkAndTopOffCoffer() {
        int lvl = Microbot.getClient().getRealSkillLevel(Skill.SMITHING);
        if (lvl < 60) return;

        int inCoffer = Microbot.getVarbitValue(BLAST_FURNACE_COFFER);
        int required = evaluateCofferDeposit();
        int delta = required - inCoffer;

        if (delta == 0) return;

        boolean underfilled = delta > 0;
        int amount = Math.abs(delta);

        if (Rs2Inventory.isFull()) {
            if (!Rs2Bank.isOpen()) {
                Rs2Bank.openBank();
                sleepUntil(Rs2Bank::isOpen, 5000);
                Rs2Bank.depositAllExcept(coalBag, GAUNTLETS_OF_GOLDSMITHING, ICE_GLOVES, SMITHING_UNIFORM_GLOVES_ICE);
            }
        }

        if (underfilled) {
            if (!Rs2Bank.isOpen()) {
                Rs2Bank.openBank();
                sleepUntil(Rs2Bank::isOpen, 5000);
            }

            if (!Rs2Bank.hasBankItem(COINS, amount)) {
                Microbot.stopPlugin(plugin);
                return;
            }

            Rs2Bank.withdrawX(COINS, amount);
            sleep(600, 900);
            Rs2Bank.closeBank();
            sleepUntil(() -> !Rs2Bank.isOpen());
        }

        Microbot.getRs2TileObjectCache().query().interact(BLAST_FURNACE_AUTOMATA_COFFER, "use");
        Rs2Player.waitForWalking(2400);

        if (underfilled && Rs2Dialogue.hasDialogueOption("deposit", false)) {
            Rs2Widget.clickWidget("deposit");
            sleepUntil(() -> Rs2Dialogue.hasQuestion("Deposit how much?"), 2400);
            Rs2Keyboard.typeString(String.valueOf(amount));
            Rs2Keyboard.enter();
            Rs2Inventory.waitForInventoryChanges(1200);
        } else if (!underfilled && Rs2Dialogue.hasDialogueOption("withdraw", false)) {
            Rs2Widget.clickWidget("withdraw");
            sleepUntil(() -> Rs2Dialogue.hasQuestion("Withdraw how much?"), 2400);
            Rs2Keyboard.typeString(String.valueOf(amount));
            Rs2Keyboard.enter();
            Rs2Inventory.waitForInventoryChanges(1200);
            if (!Rs2Bank.isOpen()) {
                Rs2Bank.openBank();
                sleepUntil(Rs2Bank::isOpen, 5000);
                Rs2Bank.depositAllExcept(coalBag, GAUNTLETS_OF_GOLDSMITHING, ICE_GLOVES, SMITHING_UNIFORM_GLOVES_ICE);
            }
        } else {
            log.warn("Unexpected coffer dialogue state. delta = {}", delta);
            Rs2Dialogue.clickContinue();
        }
    }

    public boolean fullCoffer() {
        int coffer = Microbot.getVarbitValue(BLAST_FURNACE_COINSINCOFFER);
        return coffer == 1;
    }
}
