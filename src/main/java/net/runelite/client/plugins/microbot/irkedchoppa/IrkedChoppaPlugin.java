package net.runelite.client.plugins.microbot.irkedchoppa;

import com.google.inject.Provides;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.inventory.InteractOrder;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.irkedchoppa.Forestry.*;
import net.runelite.client.plugins.microbot.irkedchoppa.enums.ForestryEvents;
import net.runelite.client.plugins.microbot.irkedchoppa.enums.IrkedChoppaTree;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * IrkedChoppa woodcutting plugin with smart banking system
 */
@PluginDescriptor(
        name = PluginConstants.MOCROSOFT + "IrkedChoppa",
        description = "Advanced woodcutting plugin with smart banking and forestry support",
        tags = {"woodcutting", "skilling", "banking", "forestry"},
        authors = {"Mocrosoft"},
        version = IrkedChoppaPlugin.version,
        minClientVersion = "2.0.7",
        cardUrl = "https://chsami.github.io/Microbot-Hub/irkedChoppa/assets/card.jpg",
        iconUrl = "https://chsami.github.io/Microbot-Hub/irkedChoppa/assets/icon.jpg",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class IrkedChoppaPlugin extends Plugin {
    public static final String version = "1.4.1";
    @Inject
    @Getter(AccessLevel.MODULE)
    public IrkedChoppaScript irkedChoppaScript;
    @Inject
    IrkedChoppaConfig config; 
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private IrkedChoppaOverlay irkedChoppaOverlay;

    @Inject
    @Getter(AccessLevel.PUBLIC)
    Rs2TileObjectCache rs2TileObjectCache;

    // Forestry event handlers
    private EggEvent eggEvent;
    private EntlingsEvent entlingsEvent;
    private FlowersEvent flowersEvent;
    private FoxEvent foxEvent;
    private HivesEvent hivesEvent;
    private LeprechaunEvent leprechaunEvent;
    private RitualEvent ritualEvent;
    private RootEvent rootEvent;
    private StrugglingSaplingEvent saplingEvent;

    // Forestry event state
    public final List<Rs2NpcModel> ritualCircles = new ArrayList<>();
    public ForestryEvents currentForestryEvent = ForestryEvents.NONE;
    public final GameObject[] saplingOrder = new GameObject[3];
    public final List<GameObject> saplingIngredients = new ArrayList<>(5);
    
    // Thread-safe counters
    private final AtomicInteger completedForestryEvents = new AtomicInteger(0);

    // Patterns
    private static final Pattern WOOD_CUT_PATTERN = Pattern.compile("You get (?:some|an)[\\w ]+(?:logs?|mushrooms)\\.");

    @Provides
    IrkedChoppaConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(IrkedChoppaConfig.class);
    }

    @Override
    protected void startUp() {
        log.info("Starting IrkedChoppa plugin v{}", version);
        
        if (overlayManager != null) {
            overlayManager.add(irkedChoppaOverlay);
        }
        
        registerMicrobotEventHandlers();
        
        if (config.enableForestry()) {
            this.addEvents();
        }
        
        if (irkedChoppaScript != null) {
            irkedChoppaScript.run(config);
            log.info("IrkedChoppa script started successfully");
        } else {
            log.error("Failed to start IrkedChoppa script - script is null");
        }
    }

    @Override
    protected void shutDown() {
        log.info("Shutting down IrkedChoppa plugin");
        
        // Unregister Microbot event handlers
        unregisterMicrobotEventHandlers();
        
        // Shutdown script first
        if (irkedChoppaScript != null) {
            irkedChoppaScript.shutdown();
            log.info("IrkedChoppa script shutdown completed");
        }
        
        // Remove forestry events
        this.removeEvents();
        
        // Clear plugin state
        ritualCircles.clear();
        currentForestryEvent = ForestryEvents.NONE;
        completedForestryEvents.set(0);
        
        // Remove overlay
        if (overlayManager != null) {
            overlayManager.remove(irkedChoppaOverlay);
        }
        
        log.info("IrkedChoppa plugin shutdown completed");
    }

    // Microbot event handler registration
    private void registerMicrobotEventHandlers() {
        try {
            // Register chat message handler
            Microbot.getEventBus().register(this);
            log.debug("Microbot event handlers registered successfully");
        } catch (Exception e) {
            log.warn("Failed to register Microbot event handlers: {}", e.getMessage());
        }
    }
    
    private void unregisterMicrobotEventHandlers() {
        try {
            // Unregister chat message handler
            Microbot.getEventBus().unregister(this);
            log.debug("Microbot event handlers unregistered successfully");
        } catch (Exception e) {
            log.warn("Failed to unregister Microbot event handlers: {}", e.getMessage());
        }
    }
    
    // Event handlers using Microbot's event system
    public void onChatMessage(ChatMessage event) {
        if (event.getType() != ChatMessageType.SPAM
                && event.getType() != ChatMessageType.GAMEMESSAGE
                && event.getType() != ChatMessageType.MESBOX) {
            return;
        }

        final var msg = event.getMessage();
        if (WOOD_CUT_PATTERN.matcher(msg).matches()) {
            irkedChoppaOverlay.incrementLogsChopped();
        }

        if (msg.equals("you can't light a fire here.")) {
            irkedChoppaScript.cannotLightFire = true;
        }

        if (msg.startsWith("The sapling seems to love")) {
            handleSaplingMessage(msg);
        }
    }
    
    private void handleSaplingMessage(String msg) {
        int ingredientNum = msg.contains("first") ? 1 : (msg.contains("second") ? 2 : (msg.contains("third") ? 3 : -1));
        if (ingredientNum == -1) {
            log.debug("unable to find ingredient index from message: {}", msg);
            return;
        }

        GameObject ingredientObj = null;
        for (GameObject obj : this.saplingIngredients) {
            // Use Queryable API to get the object name properly
            Rs2TileObjectModel tileObj = rs2TileObjectCache.query()
                    .withId(obj.getId())
                    .where(o -> o.getWorldLocation().equals(obj.getWorldLocation()))
                    .nearest();
            
            if (tileObj != null) {
                String compositionName = tileObj.getName();
                if (compositionName != null && msg.contains(compositionName.toLowerCase())) {
                    ingredientObj = obj;
                    break;
                }
            }
        }
        if (ingredientObj == null) {
            log.debug("unable to find ingredient from message: {}", msg);
            return;
        }

        this.saplingOrder[ingredientNum - 1] = ingredientObj;
    }
    
    public void onNpcSpawned(NpcSpawned event) {
        NPC npc = event.getNpc();
        int id = npc.getId();
        if (id >= NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_A_1 && id <= NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_D_4) {
            // Convert legacy NPC to modern Rs2NpcModel
            Rs2NpcModel model = new Rs2NpcModel(npc);
            this.ritualCircles.add(model);
        }
    }
    
    public void onNpcDespawned(NpcDespawned event) {
        NPC npc = event.getNpc();
        int id = npc.getId();
        if (id >= NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_A_1 && id <= NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_D_4) {
            this.ritualCircles.removeIf(n -> n.getIndex() == npc.getIndex());
        }
    }
    
    public void onGameObjectSpawned(GameObjectSpawned event) {
        GameObject gameObject = event.getGameObject();
        switch (gameObject.getId()) {
            case ObjectID.GATHERING_EVENT_SAPLING_INGREDIENT_1:
            case ObjectID.GATHERING_EVENT_SAPLING_INGREDIENT_2:
            case ObjectID.GATHERING_EVENT_SAPLING_INGREDIENT_3:
            case ObjectID.GATHERING_EVENT_SAPLING_INGREDIENT_4A:
            case ObjectID.GATHERING_EVENT_SAPLING_INGREDIENT_4B:
            case ObjectID.GATHERING_EVENT_SAPLING_INGREDIENT_4C:
            case ObjectID.GATHERING_EVENT_SAPLING_INGREDIENT_5:
                this.saplingIngredients.add(gameObject);
                break;
        }
    }
    
    public void onConfigChanged(ConfigChanged ev) {
        if (ev.getGroup().equals(IrkedChoppaConfig.configGroup)) {
            log.debug("Config changed: {} = {}", ev.getKey(), ev.getNewValue());
            
            if (ev.getKey().equals("enableForestry")) {
                if (config.enableForestry()) {
                    log.info("Forestry enabled, adding events");
                    this.addEvents();
                } else {
                    log.info("Forestry disabled, removing events");
                    this.removeEvents();
                }
            } else {
                var key = ev.getKey();
                var value = ev.getNewValue();
                if (value != null && value.equals("true")) {
                    log.debug("Enabling event: {}", key);
                    this.addEvent(key);
                }
                else if (value != null && value.equals("false")) {
                    log.debug("Disabling event: {}", key);
                    this.removeEvent(key);
                }
            }
        }
    }

    private void addEvents() {
        var eventManager = Microbot.getBlockingEventManager();

        if (config.eggEvent()) {
            eggEvent = new EggEvent(this);
            eventManager.add(eggEvent);
        }

        if (config.entlingsEvent()) {
            entlingsEvent = new EntlingsEvent(this);
            eventManager.add(entlingsEvent);
        }

        if (config.flowersEvent()) {
            flowersEvent = new FlowersEvent(this);
            eventManager.add(flowersEvent);
        }

        if (config.foxEvent()) {
            foxEvent = new FoxEvent(this);
            eventManager.add(foxEvent);
        }

        if (config.hivesEvent()) {
            hivesEvent = new HivesEvent(this);
            eventManager.add(hivesEvent);
        }

        if (config.leprechaunEvent()) {
            leprechaunEvent = new LeprechaunEvent(this);
            eventManager.add(leprechaunEvent);
        }

        if (config.ritualEvent()) {
            ritualEvent = new RitualEvent(this);
            eventManager.add(ritualEvent);
        }

        if (config.rootEvent()) {
            rootEvent = new RootEvent(this);
            eventManager.add(rootEvent);
        }

        if (config.saplingEvent()) {
            saplingEvent = new StrugglingSaplingEvent(this);
            eventManager.add(saplingEvent);
        }
    }

    private void removeEvents() {
        var eventManager = Microbot.getBlockingEventManager();

        if (eggEvent != null) {
            eventManager.remove(eggEvent);
            eggEvent = null;
        }

        if (entlingsEvent != null) {
            eventManager.remove(entlingsEvent);
            entlingsEvent = null;
        }

        if (flowersEvent != null) {
            eventManager.remove(flowersEvent);
            flowersEvent = null;
        }

        if (foxEvent != null) {
            eventManager.remove(foxEvent);
            foxEvent = null;
        }

        if (hivesEvent != null) {
            eventManager.remove(hivesEvent);
            hivesEvent = null;
        }

        if (leprechaunEvent != null) {
            eventManager.remove(leprechaunEvent);
            leprechaunEvent = null;
        }

        if (ritualEvent != null) {
            eventManager.remove(ritualEvent);
            ritualEvent = null;
        }

        if (rootEvent != null) {
            eventManager.remove(rootEvent);
            rootEvent = null;
        }

        if (saplingEvent != null) {
            eventManager.remove(saplingEvent);
            saplingEvent = null;
        }
    }


    private void addEvent(String key){
        var eventManager = Microbot.getBlockingEventManager();
        switch (key) {
            case "eggEvent":
                eggEvent = new EggEvent(this);
                eventManager.add(eggEvent);
                break;
            case "entlingsEvent":
                entlingsEvent = new EntlingsEvent(this);
                eventManager.add(entlingsEvent);
                break;
            case "flowersEvent":
                flowersEvent = new FlowersEvent(this);
                eventManager.add(flowersEvent);
                break;
            case "foxEvent":
                foxEvent = new FoxEvent(this);
                eventManager.add(foxEvent);
                break;
            case "hivesEvent":
                hivesEvent = new HivesEvent(this);
                eventManager.add(hivesEvent);
                break;
            case "leprechaunEvent":
                leprechaunEvent = new LeprechaunEvent(this);
                eventManager.add(leprechaunEvent);
                break;
            case "ritualEvent":
                ritualEvent = new RitualEvent(this);
                eventManager.add(ritualEvent);
                break;
            case "rootEvent":
                rootEvent = new RootEvent(this);
                eventManager.add(rootEvent);
                break;
            case "saplingEvent":
                saplingEvent = new StrugglingSaplingEvent(this);
                eventManager.add(saplingEvent);
                break;
        }
    }

    private void removeEvent(String key) {
        var eventManager = Microbot.getBlockingEventManager();
        switch (key) {
            case "eggEvent":
                if (eggEvent != null) {
                    eventManager.remove(eggEvent);
                    eggEvent = null;
                }
                break;
            case "entlingsEvent":
                if (entlingsEvent != null) {
                    eventManager.remove(entlingsEvent);
                    entlingsEvent = null;
                }
                break;
            case "flowersEvent":
                if (flowersEvent != null) {
                    eventManager.remove(flowersEvent);
                    flowersEvent = null;
                }
                break;
            case "foxEvent":
                if (foxEvent != null) {
                    eventManager.remove(foxEvent);
                    foxEvent = null;
                }
                break;
            case "hivesEvent":
                if (hivesEvent != null) {
                    eventManager.remove(hivesEvent);
                    hivesEvent = null;
                }
                break;
            case "leprechaunEvent":
                if (leprechaunEvent != null) {
                    eventManager.remove(leprechaunEvent);
                    leprechaunEvent = null;
                }
                break;
            case "ritualEvent":
                if (ritualEvent != null) {
                    eventManager.remove(ritualEvent);
                    ritualEvent = null;
                }
                break;
            case "rootEvent":
                if (rootEvent != null) {
                    eventManager.remove(rootEvent);
                    rootEvent = null;
                }
                break;
            case "saplingEvent":
                if (saplingEvent != null) {
                    eventManager.remove(saplingEvent);
                    saplingEvent = null;
                }
                break;
        }
    }
    
    public void incrementForestryEventCompleted() {
        completedForestryEvents.incrementAndGet();
    }
    
    public int getCompletedForestryEventCount() {
        return completedForestryEvents.get();
    }

    public IrkedChoppaTree getSelectedTree() {
        if (irkedChoppaScript != null) {
            return irkedChoppaScript.getActiveTree();
        }
        return config.TREE();
    }
    
    /**
     * Ensures inventory has space for forestry event rewards by dropping logs if needed
     * @param requiredSlots minimum number of free slots needed
     * @return true if enough space was made available
     */
    public boolean ensureInventorySpace(int requiredSlots) {
        int currentFreeSlots = 28 - Rs2Inventory.count();
        if (currentFreeSlots >= requiredSlots) {
            return true;
        }
        
        IrkedChoppaTree tree = getSelectedTree();
        String logName = tree.getLog();
        int slotsNeeded = requiredSlots - currentFreeSlots;
        int logsToDelete = Math.min(slotsNeeded, Rs2Inventory.count(logName));
        
        if (logsToDelete <= 0) {
            log.warn("Cannot make inventory space - no logs to drop");
            return false;
        }
        
        log.info("Making space for forestry rewards: dropping {} logs of {}", logsToDelete, tree.getName());
        
        int actualDropped = Rs2Inventory.dropAmount(logName, logsToDelete, InteractOrder.EFFICIENT_ROW);
        
        sleepUntil(() -> (28 - Rs2Inventory.count()) >= requiredSlots, 2000);
        
        boolean success = (28 - Rs2Inventory.count()) >= requiredSlots;
        if (!success) {
            log.warn("Failed to create enough inventory space: dropped {} logs but still need {} slots", 
                actualDropped, requiredSlots);
        }
        
        return success;
    }
}
