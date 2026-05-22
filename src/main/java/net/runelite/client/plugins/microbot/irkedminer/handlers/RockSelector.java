/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.runelite.api.Actor
 *  net.runelite.api.Player
 *  net.runelite.api.coords.WorldPoint
 *  net.runelite.client.plugins.microbot.Microbot
 *  net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache
 *  net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectQueryable
 *  net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel
 *  net.runelite.client.plugins.microbot.util.player.Rs2Player
 *  net.runelite.client.plugins.mining.MiningAnimation
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 */
package net.runelite.client.plugins.microbot.irkedminer.handlers;

import java.util.Comparator;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.runelite.api.Actor;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectQueryable;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.irkedminer.IrkedMinerConfig;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.mining.MiningAnimation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RockSelector {
    private static final Logger log = LoggerFactory.getLogger(RockSelector.class);
    private static final int OCCUPIED_ROCK_DISTANCE = 1;
    private static final long OCCUPIED_ROCK_FALLBACK_MS = 8000L;
    private static final boolean AVOID_OCCUPIED_ROCKS = true;
    private final IrkedMinerConfig config;
    private final Rs2TileObjectCache tileObjectCache;
    private final Supplier<WorldPoint> targetLocationSupplier;
    private final IntSupplier maxDistanceSupplier;
    private final Supplier<String> selectedOreKeySupplier;
    private final Supplier<String> selectedOreNameSupplier;
    private long allRocksOccupiedSinceMs = 0L;

    public RockSelector(IrkedMinerConfig config, Rs2TileObjectCache tileObjectCache, Supplier<WorldPoint> targetLocationSupplier, IntSupplier maxDistanceSupplier, Supplier<String> selectedOreKeySupplier, Supplier<String> selectedOreNameSupplier) {
        this.config = config;
        this.tileObjectCache = tileObjectCache;
        this.targetLocationSupplier = targetLocationSupplier;
        this.maxDistanceSupplier = maxDistanceSupplier;
        this.selectedOreKeySupplier = selectedOreKeySupplier;
        this.selectedOreNameSupplier = selectedOreNameSupplier;
    }

    public Rs2TileObjectModel findNearestRockFast() {
        return this.findNearestRockFastInternal(true);
    }

    public Rs2TileObjectModel findNearestRockFastForOreKey(String oreKey) {
        return this.findNearestRockFastInternal(oreKey, true);
    }

    public Rs2TileObjectModel findNearestRockFastNoOccupancy() {
        return this.findNearestRockFastInternal(false);
    }

    public Rs2TileObjectModel findNearestRockFastNoOccupancyForOreKey(String oreKey) {
        return this.findNearestRockFastInternal(oreKey, false);
    }

    private Rs2TileObjectModel findNearestRockFastInternal(boolean applyOccupiedFilter) {
        return this.findNearestRockFastInternal(this.selectedOreKeySupplier.get(), applyOccupiedFilter);
    }

    private Rs2TileObjectModel findNearestRockFastInternal(String oreKey, boolean applyOccupiedFilter) {
        if (oreKey == null || oreKey.isBlank()) {
            return null;
        }
        WorldPoint anchor = this.getAnchorLocation();
        if (anchor == null) {
            return null;
        }
        int searchRadius = Math.min(15, this.maxDistanceSupplier.getAsInt());
        String normalizedKey = oreKey.toLowerCase();
        try {
            List<Rs2TileObjectModel> rocks = ((Rs2TileObjectQueryable)((Rs2TileObjectQueryable)this.tileObjectCache.query().within(anchor, searchRadius)).where(obj -> {
                try {
                    String objName = obj.getName();
                    String normalizedName = this.normalizeRockName(objName);
                    return objName != null && normalizedName.contains(normalizedKey) && this.isValidRock((Rs2TileObjectModel)obj, normalizedKey);
                }
                catch (Exception e) {
                    return false;
                }
            })).toList();
            if (applyOccupiedFilter) {
                rocks = this.filterOccupiedRocks(rocks, anchor, searchRadius);
            }
            return this.pickBestRock(rocks);
        }
        catch (Exception e) {
            if (this.config.enableDebugLogging()) {
                log.debug("Fast rock check failed: {}", (Object)e.getMessage());
            }
            return null;
        }
    }

    public Rs2TileObjectModel findNearestRockWithRetry(TickWaiter waiter) {
        Rs2TileObjectModel rock = this.findNearestRock();
        if (rock != null) {
            return rock;
        }
        for (int attempt = 1; attempt <= 2; ++attempt) {
            if (this.config.enableDebugLogging()) {
                log.debug("Rock detection retry attempt {}", (Object)attempt);
            }
            waiter.waitForNextTick(800);
            rock = this.findNearestRock();
            if (rock == null) continue;
            if (this.config.enableDebugLogging()) {
                log.debug("Found rock on retry attempt {}", (Object)attempt);
            }
            return rock;
        }
        if (this.config.enableDebugLogging()) {
            log.debug("No rocks found after retry attempts");
        }
        return null;
    }

    public Rs2TileObjectModel findNearestRock() {
        String oreKey = this.selectedOreKeySupplier.get();
        String oreName = this.selectedOreNameSupplier.get();
        if (oreKey == null || oreKey.isBlank()) {
            log.warn("Cannot find rock: selectedOreKey is empty");
            return null;
        }
        WorldPoint anchor = this.getAnchorLocation();
        if (anchor == null) {
            return null;
        }
        int searchRadius = Math.min(this.maxDistanceSupplier.getAsInt(), 25);
        if (this.config.enableDebugLogging()) {
            log.debug("Searching for {} within {} tiles from anchor {}", new Object[]{oreName, searchRadius, anchor});
        }
        String normalizedKey = oreKey.toLowerCase();
        List<Rs2TileObjectModel> rocks = ((Rs2TileObjectQueryable)((Rs2TileObjectQueryable)this.tileObjectCache.query().within(anchor, searchRadius)).where(obj -> {
            try {
                String objName = obj.getName();
                String normalizedName = this.normalizeRockName(objName);
                return objName != null && normalizedName.contains(normalizedKey) && this.isValidRock((Rs2TileObjectModel)obj, normalizedKey);
            }
            catch (Exception e) {
                return false;
            }
        })).toList();
        if (!(rocks = this.filterOccupiedRocks(rocks, anchor, searchRadius)).isEmpty()) {
            Rs2TileObjectModel rock = this.pickBestRock(rocks);
            if (this.config.enableDebugLogging()) {
                log.debug("Found {} by name search: {} (keyword: {})", new Object[]{oreName, rock.getName(), normalizedKey});
            }
            return rock;
        }
        if (this.config.enableDebugLogging()) {
            log.debug("No {} rocks found within {} tiles of player", (Object)oreName, (Object)searchRadius);
            List<Rs2TileObjectModel> allObjects = ((Rs2TileObjectQueryable)this.tileObjectCache.query().within(searchRadius)).toList();
            log.debug("Total objects in cache: {}", (Object)allObjects.size());
            List<Rs2TileObjectModel> rockObjects = allObjects.stream().filter(obj -> {
                try {
                    String name = obj.getName();
                    return name != null && name.toLowerCase().contains("rock");
                }
                catch (Exception e) {
                    return false;
                }
            }).collect(Collectors.toList());
            log.debug("Found {} rock objects nearby", (Object)rockObjects.size());
            rockObjects.forEach(obj -> {
                try {
                    log.debug("Rock object: {} at {}", (Object)obj.getName(), (Object)obj.getWorldLocation());
                }
                catch (Exception e) {
                    log.debug("Rock object with error: {}", (Object)e.getMessage());
                }
            });
        }
        return null;
    }

    public Rs2TileObjectModel findNearestRockForOreKey(String oreKey) {
        if (oreKey == null || oreKey.isBlank()) {
            return null;
        }
        WorldPoint anchor = this.getAnchorLocation();
        if (anchor == null) {
            return null;
        }
        int searchRadius = Math.min(this.maxDistanceSupplier.getAsInt(), 25);
        String normalizedKey = oreKey.toLowerCase();
        List<Rs2TileObjectModel> rocks = ((Rs2TileObjectQueryable)((Rs2TileObjectQueryable)this.tileObjectCache.query().within(anchor, searchRadius)).where(obj -> {
            try {
                String objName = obj.getName();
                String normalizedName = this.normalizeRockName(objName);
                return objName != null && normalizedName.contains(normalizedKey) && this.isValidRock((Rs2TileObjectModel)obj, normalizedKey);
            }
            catch (Exception e) {
                return false;
            }
        })).toList();
        rocks = this.filterOccupiedRocks(rocks, anchor, searchRadius);
        return this.pickBestRock(rocks);
    }

    public Rs2TileObjectModel findNearestRockNoOccupancyForOreKey(String oreKey) {
        if (oreKey == null || oreKey.isBlank()) {
            return null;
        }
        WorldPoint anchor = this.getAnchorLocation();
        if (anchor == null) {
            return null;
        }
        int searchRadius = Math.min(this.maxDistanceSupplier.getAsInt(), 25);
        String normalizedKey = oreKey.toLowerCase();
        List rocks = ((Rs2TileObjectQueryable)((Rs2TileObjectQueryable)this.tileObjectCache.query().within(anchor, searchRadius)).where(obj -> {
            try {
                String objName = obj.getName();
                String normalizedName = this.normalizeRockName(objName);
                return objName != null && normalizedName.contains(normalizedKey) && this.isValidRock((Rs2TileObjectModel)obj, normalizedKey);
            }
            catch (Exception e) {
                return false;
            }
        })).toList();
        return this.pickBestRock(rocks);
    }

    public RockAvailability getRockAvailabilitySnapshot() {
        String oreKey = this.selectedOreKeySupplier.get();
        if (oreKey == null || oreKey.isBlank()) {
            return new RockAvailability(0, 0);
        }
        WorldPoint anchor = this.getAnchorLocation();
        if (anchor == null) {
            return new RockAvailability(0, 0);
        }
        int searchRadius = Math.min(this.maxDistanceSupplier.getAsInt(), 25);
        String normalizedKey = oreKey.toLowerCase();
        try {
            List rocks = ((Rs2TileObjectQueryable)((Rs2TileObjectQueryable)this.tileObjectCache.query().within(anchor, searchRadius)).where(obj -> {
                try {
                    String objName = obj.getName();
                    String normalizedName = this.normalizeRockName(objName);
                    return objName != null && normalizedName.contains(normalizedKey) && this.isValidRock((Rs2TileObjectModel)obj, normalizedKey);
                }
                catch (Exception e) {
                    return false;
                }
            })).toList();
            if (rocks.isEmpty()) {
                return new RockAvailability(0, 0);
            }
            List<WorldPoint> nearbyMinerLocations = this.getNearbyMiningPlayerLocations(anchor, searchRadius + 2);
            if (nearbyMinerLocations.isEmpty()) {
                return new RockAvailability(rocks.size(), rocks.size());
            }
            int availableRocks = (int)rocks.stream().filter(rock -> !this.isRockOccupiedByNearbyMiner((Rs2TileObjectModel)rock, nearbyMinerLocations)).count();
            return new RockAvailability(rocks.size(), availableRocks);
        }
        catch (Exception e) {
            if (this.config.enableDebugLogging()) {
                log.debug("Failed rock availability snapshot: {}", (Object)e.getMessage());
            }
            return new RockAvailability(0, 0);
        }
    }

    private List<Rs2TileObjectModel> filterOccupiedRocks(List<Rs2TileObjectModel> rocks, WorldPoint anchor, int searchRadius) {
        if (rocks.isEmpty() || anchor == null) {
            this.allRocksOccupiedSinceMs = 0L;
            return rocks;
        }
        List<WorldPoint> nearbyMinerLocations = this.getNearbyMiningPlayerLocations(anchor, searchRadius + 2);
        if (nearbyMinerLocations.isEmpty()) {
            this.allRocksOccupiedSinceMs = 0L;
            return rocks;
        }
        List<Rs2TileObjectModel> availableRocks = rocks.stream().filter(rock -> !this.isRockOccupiedByNearbyMiner((Rs2TileObjectModel)rock, nearbyMinerLocations)).collect(Collectors.toList());
        if (this.config.enableDebugLogging() && availableRocks.size() != rocks.size()) {
            log.debug("Filtered {} occupied rocks ({} nearby miners detected)", (Object)(rocks.size() - availableRocks.size()), (Object)nearbyMinerLocations.size());
        }
        if (!availableRocks.isEmpty()) {
            this.allRocksOccupiedSinceMs = 0L;
            return availableRocks;
        }
        long now = System.currentTimeMillis();
        if (this.allRocksOccupiedSinceMs == 0L) {
            this.allRocksOccupiedSinceMs = now;
            return availableRocks;
        }
        long occupiedDurationMs = now - this.allRocksOccupiedSinceMs;
        if (occupiedDurationMs >= 8000L) {
            this.allRocksOccupiedSinceMs = 0L;
            if (this.config.enableDebugLogging()) {
                log.debug("All candidate rocks occupied for {}ms, allowing fallback selection", (Object)occupiedDurationMs);
            }
            return rocks;
        }
        return availableRocks;
    }

    private List<WorldPoint> getNearbyMiningPlayerLocations(WorldPoint anchor, int searchRadius) {
        if (anchor == null) {
            return List.of();
        }
        try {
            List<WorldPoint> locations = (List<WorldPoint>)Microbot.getClientThread().invoke(() -> {
                if (Microbot.getClient() == null || Microbot.getClient().getTopLevelWorldView() == null || Microbot.getClient().getLocalPlayer() == null) {
                    return List.of();
                }
                Player localPlayer = Microbot.getClient().getLocalPlayer();
                return Microbot.getClient().getTopLevelWorldView().players().stream().filter(player -> player != null && player != localPlayer).filter(player -> MiningAnimation.MINING_ANIMATIONS.contains(player.getAnimation())).map(Actor::getWorldLocation).filter(playerLocation -> playerLocation != null && playerLocation.distanceTo(anchor) <= searchRadius).collect(Collectors.toList());
            });
            return locations != null ? locations : List.of();
        }
        catch (Exception e) {
            if (this.config.enableDebugLogging()) {
                log.debug("Failed to snapshot nearby mining players: {}", (Object)e.getMessage());
            }
            return List.of();
        }
    }

    private boolean isRockOccupiedByNearbyMiner(Rs2TileObjectModel rock, List<WorldPoint> nearbyMinerLocations) {
        if (rock == null || nearbyMinerLocations == null || nearbyMinerLocations.isEmpty()) {
            return false;
        }
        WorldPoint rockLocation = rock.getWorldLocation();
        if (rockLocation == null) {
            return false;
        }
        return nearbyMinerLocations.stream().filter(playerLocation -> playerLocation != null && playerLocation.getPlane() == rockLocation.getPlane()).anyMatch(playerLocation -> playerLocation.distanceTo(rockLocation) <= 1);
    }

    public int getOreRespawnTime(String oreName) {
        if (oreName == null || oreName.isBlank()) {
            return 30000;
        }
        if (oreName.equalsIgnoreCase("runite")) {
            return 30000;
        }
        if (oreName.equalsIgnoreCase("adamantite") || oreName.equalsIgnoreCase("adamant")) {
            return 15000;
        }
        if (oreName.equalsIgnoreCase("mithril")) {
            return 12000;
        }
        if (oreName.equalsIgnoreCase("coal")) {
            return 10000;
        }
        if (oreName.equalsIgnoreCase("gold") || oreName.equalsIgnoreCase("silver")) {
            return 8000;
        }
        if (oreName.equalsIgnoreCase("iron")) {
            return 3000;
        }
        if (oreName.equalsIgnoreCase("copper") || oreName.equalsIgnoreCase("tin")) {
            return 5000;
        }
        if (oreName.equalsIgnoreCase("clay") || oreName.equalsIgnoreCase("limestone") || oreName.equalsIgnoreCase("sandstone") || oreName.equalsIgnoreCase("granite")) {
            return 5000;
        }
        if (oreName.equalsIgnoreCase("lead") || oreName.equalsIgnoreCase("nickel") || oreName.equalsIgnoreCase("elemental") || oreName.equalsIgnoreCase("daeyalt essence")) {
            return 8000;
        }
        return 10000;
    }

    private WorldPoint getAnchorLocation() {
        WorldPoint target = this.targetLocationSupplier.get();
        if (target != null) {
            return target;
        }
        return Rs2Player.getWorldLocation();
    }

    private boolean isValidRock(Rs2TileObjectModel rock, String expectedOreKey) {
        boolean isGenericRock;
        String rockName;
        if (rock == null) {
            return false;
        }
        try {
            rockName = rock.getName();
        }
        catch (Exception ex) {
            return false;
        }
        if (rockName == null) {
            return false;
        }
        String lowerName = rockName.toLowerCase();
        String normalizedRockName = this.normalizeRockName(rockName);
        String oreKey = expectedOreKey != null ? expectedOreKey.trim().toLowerCase() : "";
        boolean isCorrectOreType = !oreKey.isEmpty() && normalizedRockName.contains(oreKey);
        boolean bl = isGenericRock = lowerName.equals("rocks") || lowerName.equals("rock");
        if (!isCorrectOreType) {
            if (this.config.enableDebugLogging()) {
                log.debug("Rejecting rock '{}' - doesn't contain selected ore '{}'", (Object)rock.getName(), (Object)oreKey);
            }
            return false;
        }
        if (isGenericRock) {
            if (this.config.enableDebugLogging()) {
                log.debug("Rejecting depleted rock '{}' (no ore type)", (Object)rock.getName());
            }
            return false;
        }
        if (!this.isMineableRock(rock)) {
            if (this.config.enableDebugLogging()) {
                log.debug("Rejecting rock '{}' - not currently mineable", (Object)rock.getName());
            }
            return false;
        }
        return this.isWithinDistance(rock);
    }

    private boolean isMineableRock(Rs2TileObjectModel rock) {
        try {
            if (rock.getObjectComposition() == null || rock.getObjectComposition().getActions() == null) {
                return false;
            }
            for (String action : rock.getObjectComposition().getActions()) {
                if (action == null || !action.equalsIgnoreCase("Mine")) continue;
                return true;
            }
        }
        catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private boolean isWithinDistance(Rs2TileObjectModel rock) {
        WorldPoint anchor = this.getAnchorLocation();
        if (anchor == null) {
            return false;
        }
        return rock.getWorldLocation().distanceTo(anchor) <= this.maxDistanceSupplier.getAsInt();
    }

    private Rs2TileObjectModel pickBestRock(List<Rs2TileObjectModel> rocks) {
        WorldPoint playerLocation = Rs2Player.getWorldLocation();
        WorldPoint anchor = this.getAnchorLocation();
        return rocks.stream().min(Comparator.comparingInt(rock -> this.distanceTo((Rs2TileObjectModel)rock, playerLocation)).thenComparingInt(rock -> this.distanceTo((Rs2TileObjectModel)rock, anchor))).orElse(null);
    }

    private int distanceTo(Rs2TileObjectModel rock, WorldPoint point) {
        if (rock == null || point == null || rock.getWorldLocation() == null) {
            return Integer.MAX_VALUE;
        }
        return rock.getWorldLocation().distanceTo(point);
    }

    private String normalizeRockName(String rockName) {
        if (rockName == null) {
            return "";
        }
        String normalized = rockName.toLowerCase().trim();
        if (normalized.endsWith(" rocks")) {
            normalized = normalized.substring(0, normalized.length() - " rocks".length());
        } else if (normalized.endsWith(" rock")) {
            normalized = normalized.substring(0, normalized.length() - " rock".length());
        }
        return normalized.trim();
    }

    public static interface TickWaiter {
        public void waitForNextTick(int var1);
    }

    public static final class RockAvailability {
        private final int totalRocks;
        private final int availableRocks;

        public RockAvailability(int totalRocks, int availableRocks) {
            this.totalRocks = Math.max(0, totalRocks);
            this.availableRocks = Math.max(0, availableRocks);
        }

        public int getTotalRocks() {
            return this.totalRocks;
        }

        public int getAvailableRocks() {
            return this.availableRocks;
        }

        public boolean allRocksOccupied() {
            return this.totalRocks > 0 && this.availableRocks <= 0;
        }
    }
}
