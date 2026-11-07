/*
 * Copyright (c) 2017, Tyler <https://github.com/tylerthardy>
 * Copyright (c) 2020, dekvall <https://github.com/dekvall>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.microbot.herbihunter;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Provides;
import lombok.Getter;
import lombok.Setter;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry;
import org.slf4j.event.Level;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.ui.overlay.OverlayManager;
import org.apache.commons.lang3.ArrayUtils;

import javax.inject.Inject;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@PluginDescriptor(
        name = PluginConstants.IRKED + "Herbi Hunter",
        description = "Hunts herbiboars on Fossil Island with Relaxed and Focused play styles, banking, restocking and session history.",
        tags = {"herbiboar", "herbi hunter", "herbi", "hunter", "herblore", "fossil island", "skilling", "irked"},
        authors = {"Igor", "chsami", "Kavraka91", "irkedMATT"},
        version = HerbiHunterPlugin.version,
        minClientVersion = "2.6.54",
        iconUrl = "https://chsami.github.io/Microbot-Hub/HerbiHunterPlugin/assets/icon.png",
        cardUrl = "https://chsami.github.io/Microbot-Hub/HerbiHunterPlugin/assets/card.png",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)

public class HerbiHunterPlugin extends Plugin {
    static final String version = "1.0.0";

    @Getter
    @Setter
    private Instant startTime;

    @Getter
    @Setter
    private long runningTime;

    @Getter
    @Setter
    private Integer startXp = 0;

    @Getter
    @Setter
    private Integer xpGained = 0;


    @Getter
    @Setter
    private Integer xpPerHour = 0;

    private static final List<WorldPoint> END_LOCATIONS = ImmutableList.of(
            new WorldPoint(3693, 3798, 0),
            new WorldPoint(3702, 3808, 0),
            new WorldPoint(3703, 3826, 0),
            new WorldPoint(3710, 3881, 0),
            new WorldPoint(3700, 3877, 0),
            new WorldPoint(3715, 3840, 0),
            new WorldPoint(3751, 3849, 0),
            new WorldPoint(3685, 3869, 0),
            new WorldPoint(3681, 3863, 0)
    );

    static final List<WorldPoint> START_LOCATIONS = ImmutableList.of(
            new WorldPoint(3686, 3870, 0),  // MIDDLE
            new WorldPoint(3705, 3830, 0),  // LEPRECHAUN
            new WorldPoint(3704, 3810, 0),  // CAMP_ENTRANCE
            new WorldPoint(3695, 3800, 0),  // GHOST_MUSHROOM
            new WorldPoint(3751, 3850, 0)); // DRIFTWOOD

    private static final Set<Integer> START_OBJECT_IDS = ImmutableSet.of(
            ObjectID.HUNTING_TRAIL_SPAWN_FOSSIL1,
            ObjectID.HUNTING_TRAIL_SPAWN_FOSSIL2,
            ObjectID.HUNTING_TRAIL_SPAWN_FOSSIL3,
            ObjectID.HUNTING_TRAIL_SPAWN_FOSSIL4,
            ObjectID.HUNTING_TRAIL_SPAWN_FOSSIL5
    );

    private static final List<Integer> HERBIBOAR_REGIONS = ImmutableList.of(
            14652,
            14651,
            14908,
            14907
    );

    @Inject
    private Client client;

    @Provides
    HerbiboarConfig provideConfig(ConfigManager configManager) { return configManager.getConfig(HerbiboarConfig.class); }

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private HerbiboarOverlay overlay;

    @Inject
    private HerbiboarTileOverlay tileOverlay;

    @Inject
    private HerbiboarMinimapOverlay minimapOverlay;

    @Inject
    private HerbiboarScript script;

    @Inject
    private EventBus eventBus;

    /** Registered by hand: @Subscribe on a sideloaded jar logs a LambdaConversionException per handler. */
    private final List<EventBus.Subscriber> subscribers = new ArrayList<>();

    // Written on the client thread, read by the script thread: plain HashMap/ArrayList throw ConcurrentModificationException.
    @Getter
    private final Map<WorldPoint, TileObject> starts = new ConcurrentHashMap<>();
    @Getter
    private final Map<WorldPoint, TileObject> trailObjects = new ConcurrentHashMap<>();
    @Getter
    private final Map<WorldPoint, TileObject> tunnels = new ConcurrentHashMap<>();
    @Getter
    private final Set<Integer> shownTrails = ConcurrentHashMap.newKeySet();
    private final List<HerbiboarSearchSpot> currentPath = new CopyOnWriteArrayList<>();

    /** A snapshot: a size() then get() on the live list could straddle a clear() on the client thread. */
    public List<HerbiboarSearchSpot> getCurrentPath()
    {
        return new ArrayList<>(currentPath);
    }

    @Getter
    private boolean inHerbiboarArea;

    @Getter
    private TrailToSpot nextTrail;


    @Getter
    @Setter
    private volatile List<WorldPoint> walkRoute = Collections.emptyList();

    @Getter
    private HerbiboarSearchSpot.Group currentGroup;

    @Getter
    private int finishId;

    private boolean started;

    private static final String CORE_GROUP = "herbiboar";
    private static final String CORE_SHOW_TRAIL = "showTrail";
    private String coreShowTrail;

    @Inject
    private com.google.gson.Gson gson;

    @Getter
    private volatile HerbiboarSession session;
    @Getter
    private volatile List<HerbiboarSession> history = Collections.emptyList();
    @Setter
    private volatile String stopReason;
    private long lastCheckpointMs;
    private static final long CHECKPOINT_MS = 5 * 60_000L;

    @Override
    protected void startUp() {
        Microbot.log(Level.INFO, "Herbi Hunter " + version + " started");
        ClientThread clientThread = Microbot.getClientThread();
        HerbiboarConfig config = provideConfig(Microbot.getConfigManager());

        java.io.File dir = HerbiboarSession.dir();
        HerbiboarSession.recoverCheckpoint(dir, gson);
        history = HerbiboarSession.loadHistory(dir, gson);
        session = new HerbiboarSession(System.currentTimeMillis());
        session.playStyle = String.valueOf(config.playStyle());
        session.startLevel = client.getRealSkillLevel(Skill.HUNTER);
        stopReason = null;
        lastCheckpointMs = System.currentTimeMillis();

        setStartTime(Instant.now());
        setStartXp(Microbot.getClient().getSkillExperience(Skill.HUNTER));

        // Core RuneLite's Herbiboar draws every finished trail, never the current one: off while we run, restored after.
        coreShowTrail = Microbot.getConfigManager().getConfiguration(CORE_GROUP, CORE_SHOW_TRAIL);
        Microbot.getConfigManager().setConfiguration(CORE_GROUP, CORE_SHOW_TRAIL, false);

        overlayManager.add(overlay);
        overlayManager.add(tileOverlay);
        overlayManager.add(minimapOverlay);

        subscribe(HitsplatApplied.class, this::onHitsplatApplied);
        subscribe(ChatMessage.class, this::onChatMessage);
        subscribe(BeforeRender.class, this::onBeforeRender);
        subscribe(MenuOptionClicked.class, this::onMenuOptionClicked);
        subscribe(GameStateChanged.class, this::onGameStateChanged);
        subscribe(VarbitChanged.class, this::onVarbitChanged);
        subscribe(GameObjectSpawned.class, this::onGameObjectSpawned);
        subscribe(GameObjectDespawned.class, this::onGameObjectDespawned);
        subscribe(GroundObjectSpawned.class, this::onGroundObjectSpawned);
        subscribe(GroundObjectDespawned.class, this::onGroundObjectDespawned);
        subscribe(GameTick.class, this::onGameTick);

        script.run(config, this);

        if (client.getGameState() == GameState.LOGGED_IN)
        {
            clientThread.invokeLater(() ->
            {
                inHerbiboarArea = checkArea();
                updateTrailData();
            });
        }
    }
    @Override
    protected void shutDown() {
        subscribers.forEach(eventBus::unregister);
        subscribers.clear();
        finishSession();
        overlayManager.remove(overlay);
        overlayManager.remove(minimapOverlay);
        overlayManager.remove(tileOverlay);
        resetTrailData();
        clearCache();
        setRunningTime(0);
        setStartXp(0);
        setStartTime(null);
        setXpPerHour(0);
        inHerbiboarArea = false;
        if (coreShowTrail == null) Microbot.getConfigManager().unsetConfiguration(CORE_GROUP, CORE_SHOW_TRAIL);
        else Microbot.getConfigManager().setConfiguration(CORE_GROUP, CORE_SHOW_TRAIL, coreShowTrail);
        script.shutdown();
    }

    private <T> void subscribe(Class<T> type, java.util.function.Consumer<T> handler) {
        subscribers.add(eventBus.register(type, handler, 0f));
    }

    private volatile boolean trailConfused;

    /** An Ammonite Crab's hit interrupts a search with no message. */
    @Getter
    private volatile int lastHitTick = -1;

    public void onHitsplatApplied(HitsplatApplied event) {
        if (event.getActor() == client.getLocalPlayer()) lastHitTick = client.getTickCount();
    }

    public void onChatMessage(ChatMessage chatMessage) {
        if (chatMessage.getType() != ChatMessageType.GAMEMESSAGE) return;
        String msg = chatMessage.getMessage();
        if (inHerbiboarArea) Microbot.log(Level.INFO, "[HB-CHAT] %s", net.runelite.client.util.Text.removeTags(msg));
        if (inHerbiboarArea && msg.contains("Nothing seems to be out of place")) Microbot.log(Level.INFO, "[HB-NOTHING] trail: %s", trailDump());
        // contains: the real line is longer ("The creature has successfully confused you with its tracks...").
        if (msg.contains("confused you with its tracks") || msg.contains("need to start again")) {
            trailConfused = true;
        }
    }

    String trailDump()
    {
        StringBuilder sb = new StringBuilder();
        for (HerbiboarSearchSpot spot : HerbiboarSearchSpot.values())
        {
            for (TrailToSpot trail : spot.getTrails())
            {
                int v = client.getVarbitValue(trail.getVarbitId());
                if (v > 0) sb.append(spot.name()).append('=').append(v).append(v == trail.getValue() ? "* " : " ");
            }
        }
        sb.append("| finish=").append(client.getVarbitValue(VarbitID.HUNTING_TRAIL_ENDS_FOSSIL)).append(" | path=");
        for (HerbiboarSearchSpot spot : currentPath) sb.append(spot.name()).append(' ');
        return sb.toString().trim();
    }

    public boolean consumeTrailConfused()
    {
        if (!trailConfused) return false;
        trailConfused = false;
        return true;
    }

    private void updateTrailData()
    {
        if (!isInHerbiboarArea())
        {
            return;
        }

        boolean pathActive = false;
        for (HerbiboarSearchSpot spot : HerbiboarSearchSpot.values())
        {
            for (TrailToSpot trail : spot.getTrails())
            {
                int value = client.getVarbitValue(trail.getVarbitId());

                if (value == trail.getValue())
                {
                    currentGroup = spot.getGroup();
                    nextTrail = trail;

                    // You never visit the same spot twice
                    if (!currentPath.contains(spot))
                    {
                        currentPath.add(spot);
                    }
                }
                else if (value > 0)
                {
                    shownTrails.addAll(trail.getFootprintIds());
                    pathActive = true;
                }
            }
        }

        finishId = client.getVarbitValue(VarbitID.HUNTING_TRAIL_ENDS_FOSSIL);

        // The started varbit is only set after the first spot is searched, so a current group also counts as started.
        started = client.getVarbitValue(VarbitID.HUNTING_TRAILS_USED_FOSSIL) > 0 || currentGroup != null;
        if (!pathActive && started)
        {
            resetTrailData();
        }
    }

    @Getter
    private volatile String lastClickedOption = "";
    @Getter
    private volatile int lastClickedId = -1;
    @Getter
    private volatile int clickCount;

    /** As of the last finished frame: mid-rebuild reads show "Examine". */
    @Getter
    private volatile MenuEntry topEntry;

    public void onBeforeRender(BeforeRender event)
    {
        MenuEntry[] entries = client.getMenu().getMenuEntries();
        MenuEntry e = entries.length == 0 ? null : entries[entries.length - 1];
        // A copy: the client reuses entry objects.
        topEntry = e == null ? null : new NewMenuEntry(e.getOption(), e.getParam0(), e.getParam1(), e.getType().getId(),
                e.getIdentifier(), e.getItemId(), e.getTarget());
    }

    public void onMenuOptionClicked(MenuOptionClicked menuOpt)
    {
        lastClickedOption = menuOpt.getMenuOption();
        lastClickedId = menuOpt.getId();
        clickCount++;
    }

    static int routeAheadFrom(List<WorldPoint> route, WorldPoint me)
    {
        if (route == null || route.size() < 2 || me == null) return -1;
        if (me.distanceTo(route.get(route.size() - 1)) <= 1) return -1;
        int best = -1, bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < route.size(); i++)
        {
            WorldPoint p = route.get(i);
            int d = p.getPlane() == me.getPlane() ? p.distanceTo(me) : Integer.MAX_VALUE;
            if (d < bestDist)
            {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    private void resetTrailData()
    {
        Microbot.log(Level.DEBUG, "Reset trail data");
        shownTrails.clear();
        currentPath.clear();
        nextTrail = null;
        currentGroup = null;
        finishId = 0;
        started = false;
    }

    private void clearCache()
    {
        starts.clear();
        trailObjects.clear();
        tunnels.clear();
    }

    static boolean reenteredArea(boolean wasIn, boolean nowIn)
    {
        return !wasIn && nowIn;
    }

    public void onGameStateChanged(GameStateChanged event)
    {
        switch (event.getGameState())
        {
            case HOPPING:
            case LOGGING_IN:
                resetTrailData();
                break;
            case LOADING:
                clearCache();
                boolean was = inHerbiboarArea;
                inHerbiboarArea = checkArea();
                // Back from a G.E. trip: the trail varbits changed while we were away, so read them again.
                if (reenteredArea(was, inHerbiboarArea))
                {
                    updateTrailData();
                    Microbot.log(Level.INFO, "Back in the hunting area: trail %s, finish %d",
                        currentGroup == null ? "none" : currentGroup.name(), finishId);
                }
                break;
            default:
                break;
        }
    }

    public void onVarbitChanged(VarbitChanged event)
    {
        updateTrailData();
    }

    public void onGameObjectSpawned(GameObjectSpawned event)
    {
        onTileObject(null, event.getGameObject());
    }

    public void onGameObjectDespawned(GameObjectDespawned event)
    {
        onTileObject(event.getGameObject(), null);
    }

    public void onGroundObjectSpawned(GroundObjectSpawned event)
    {
        onTileObject(null, event.getGroundObject());
    }

    public void onGroundObjectDespawned(GroundObjectDespawned event)
    {
        onTileObject(event.getGroundObject(), null);
    }

    private void onTileObject(TileObject oldObject, TileObject newObject)
    {
        if (oldObject != null)
        {
            WorldPoint oldLocation = oldObject.getWorldLocation();
            starts.remove(oldLocation);
            trailObjects.remove(oldLocation);
            tunnels.remove(oldLocation);
        }

        if (newObject == null)
        {
            return;
        }

        if (START_OBJECT_IDS.contains(newObject.getId()))
        {
            starts.put(newObject.getWorldLocation(), newObject);
            return;
        }

        if (HerbiboarSearchSpot.isTrail(newObject.getId()))
        {
            return;
        }

        if (HerbiboarSearchSpot.isSearchSpot(newObject.getWorldLocation()))
        {
            trailObjects.put(newObject.getWorldLocation(), newObject);
            return;
        }

        if (END_LOCATIONS.contains(newObject.getWorldLocation()))
        {
            tunnels.put(newObject.getWorldLocation(), newObject);
        }
    }

    public int calculateXpPerHour() {
        int currentXP = client.getSkillExperience(Skill.HUNTER);
        if (getStartXp() <= 0) {
            // At the login screen Hunter reads 0: the session begins with the first real reading.
            if (currentXP <= 0) return 0;
            setStartXp(currentXP);
            setStartTime(Instant.now());
            HerbiboarSession s = session;
            if (s != null && s.caught == 0) s.startMs = System.currentTimeMillis();
        }
        setXpGained(currentXP - getStartXp());
        if (getRunningTime() <= 1) return 0;
        return (int) (getXpGained() * 3600L / getRunningTime());
    }

    public void onGameTick(GameTick event) {
        setRunningTime(Instant.now().getEpochSecond() - getStartTime().getEpochSecond());
        setXpPerHour(calculateXpPerHour());
        HerbiboarSession s = session;
        long now = System.currentTimeMillis();
        if (s != null && now - lastCheckpointMs >= CHECKPOINT_MS) {
            lastCheckpointMs = now;
            fillTotals(s);
            HerbiboarSession.checkpoint(HerbiboarSession.dir(), gson, s);
        }
    }

    private void fillTotals(HerbiboarSession s) {
        Integer xp = getXpGained();
        s.xpGained = xp == null ? 0 : xp;
        s.endLevel = client.getRealSkillLevel(Skill.HUNTER);
    }

    private void finishSession() {
        HerbiboarSession s = session;
        session = null;
        if (s == null) return;
        s.endMs = System.currentTimeMillis();
        fillTotals(s);
        s.stopReason = stopReason == null ? "stopped" : stopReason;
        if (s.caught == 0 && s.durationMs(s.endMs) < 60_000) return;
        java.io.File dir = HerbiboarSession.dir();
        HerbiboarSession.saveFinished(dir, gson, s);
        double rate = s.catchesPerHour(s.endMs);
        long ms = Math.max(1, s.durationMs(s.endMs));
        Microbot.log(Level.INFO, "Herbiboar session saved: %s, %d caught (%s/hr), %,d Hunter XP (%,d/hr), %d bank trips; %s play style; %s -> %s",
                HerbiboarOverlay.hoursMinutes(s.durationMs(s.endMs)), s.caught, rate < 0 ? "-" : String.valueOf(Math.round(rate)),
                s.xpGained, s.xpGained * 3_600_000L / ms, s.bankTrips, s.playStyle, s.stopReason, HerbiboarSession.historyFile(dir));
    }

    private boolean checkArea()
    {
        final WorldView wv = client.getTopLevelWorldView();
        if (wv == null) {
            return false;
        }
        final int[] mapRegions = wv.getMapRegions();
        for (int region : HERBIBOAR_REGIONS)
        {
            if (ArrayUtils.contains(mapRegions, region))
            {
                return true;
            }
        }
        return false;
    }

    public List<WorldPoint> getEndLocations()
    {
        return END_LOCATIONS;
    }
}
