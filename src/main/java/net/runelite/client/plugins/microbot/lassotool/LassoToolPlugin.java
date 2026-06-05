package net.runelite.client.plugins.microbot.lassotool;

import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;

import javax.inject.Inject;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@PluginDescriptor(
        name = PluginConstants.MOCROSOFT + "Lasso Tool",
        description = "Draw freehand lasso areas on screen like Photoshop. Capture tiles, objects, NPCs inside the area for debugging, custom spots (e.g. MLM), or data export.",
        tags = {"dev", "debug", "area", "lasso", "capture", "mlm", "motherload"},
        authors = {"Microbot Community"},
        version = LassoToolPlugin.version,
        minClientVersion = "2.1.0",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
@Slf4j
public class LassoToolPlugin extends Plugin
{
    public static final String version = "1.0.4";

    @Inject
    private Client client;

    @Inject
    private LassoToolConfig config;

    @Inject
    private KeyManager keyManager;

    @Inject
    private MouseManager mouseManager;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private LassoToolOverlay overlay;

    private LassoToolMouseListener mouseListener;

    private HotkeyListener toggleListener;

    @Getter
    private volatile boolean drawing = false;

    // Use CopyOnWriteArrayList so AWT mouse thread (adds) and client render thread (reads/clears) are safe
    private final CopyOnWriteArrayList<Point> currentLassoPath = new CopyOnWriteArrayList<>();

    @Getter
    private volatile Set<WorldPoint> currentPreviewTiles = Collections.emptySet();

    @Getter
    private volatile LassoArea currentArea = null;

    private String lastCaptureReport = "";

    private long lastCaptureTime = 0;

    @Provides
    LassoToolConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(LassoToolConfig.class);
    }

    @Override
    protected void startUp()
    {
        mouseListener = new LassoToolMouseListener(this);

        toggleListener = new HotkeyListener(config::toggleLassoHotkey)
        {
            @Override
            public void hotkeyPressed()
            {
                toggleLassoMode();
            }
        };

        keyManager.registerKeyListener(toggleListener);
        overlayManager.add(overlay);

        log.info("Lasso Tool started - version {}", version);
    }

    @Override
    protected void shutDown()
    {
        keyManager.unregisterKeyListener(toggleListener);
        mouseManager.unregisterMouseListener(mouseListener);
        overlayManager.remove(overlay);

        drawing = false;
        currentLassoPath.clear();
        currentPreviewTiles = Collections.emptySet();
        currentArea = null;

        log.info("Lasso Tool stopped");
    }

    public void toggleLassoMode()
    {
        drawing = !drawing;
        if (drawing)
        {
            currentLassoPath.clear();
            currentPreviewTiles = Collections.emptySet();
            // Defensive unregister in case of prior state, then register (mouse listener only while drawing)
            mouseManager.unregisterMouseListener(mouseListener);
            mouseManager.registerMouseListener(mouseListener);
            log.info("[LassoTool] Lasso drawing mode ENABLED - left drag for freehand OR left-click for points; right/double-click or toggle hotkey to finish. Game clicks blocked.");
            // Chat on client thread (hotkey may arrive on AWT/EDT)
            Microbot.getClientThread().invoke(() -> {
                if (client.getLocalPlayer() != null)
                {
                    client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "Lasso ON: drag freehand or click points (clicks captured - won't walk/interact). Right-click/double-click/toggle to finish. See red banner on screen.", null);
                }
            });
        }
        else
        {
            mouseManager.unregisterMouseListener(mouseListener);
            if (!currentLassoPath.isEmpty())
            {
                finishLasso(); // finalize if was drawing
            }
            log.info("[LassoTool] Lasso drawing mode DISABLED");
        }
    }

    public void startNewLasso()
    {
        currentLassoPath.clear();
        currentPreviewTiles = Collections.emptySet();
    }

    public void addLassoPoint(Point p)
    {
        if (p == null) return;
        // Dedup close points. Raw screen points only (no client API here - AWT thread).
        if (currentLassoPath.isEmpty() || currentLassoPath.get(currentLassoPath.size() - 1).distance(p) > 3)
        {
            currentLassoPath.add(new Point(p));
            // No live preview update here; overlay render (client thread) computes live tile preview when path present.
        }
    }

    public void finishLasso()
    {
        if (currentLassoPath.size() < 3)
        {
            currentLassoPath.clear();
            drawing = false;
            mouseManager.unregisterMouseListener(mouseListener);
            return;
        }

        // Snapshot the path for the client-thread compute (prevents mutation during work)
        final List<Point> pathSnapshot = new ArrayList<>(currentLassoPath);

        // Stop drawing and stop receiving mouse events immediately (AWT context)
        drawing = false;
        mouseManager.unregisterMouseListener(mouseListener);
        // Do NOT clear path yet - this lets the overlay continue showing the stroke + live tile preview
        // until the client thread work completes and clears it.

        // Perform all WorldPoint/Perspective/client state work on the client thread to avoid
        // "must be called on client thread" during mouseDragged/mouseReleased (AWT-EventQueue).
        Microbot.getClientThread().invoke(() -> {
            try
            {
                Set<WorldPoint> tiles = computeTilesInLasso(pathSnapshot);

                currentPreviewTiles = tiles;
                currentArea = new LassoArea("Lasso-" + System.currentTimeMillis(), tiles, new ArrayList<>(pathSnapshot));

                log.info("[LassoTool] Lasso finished - {} tiles captured", tiles.size());

                if (client.getLocalPlayer() != null)
                {
                    client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "",
                            "Lasso captured " + tiles.size() + " tiles. Mesh code " + (config.autoCopy() ? "auto-copied" : "ready for capture"), null);
                }

                // Always do a data grab report on finish (logs + chat summary). Safe here.
                captureData();

                // Auto export mesh if configured
                if (config.autoCopy())
                {
                    copyToClipboard();
                }

                // Auto save the full selection (mesh + report) to file if configured.
                // This uses the hotkey flow: hotkey to activate drawing, draw, hotkey (or right/double) to finish+save.
                if (config.autoSaveToFile())
                {
                    saveToFile();
                }

                // Now clear the drawing path; overlay will fall back to rendering currentArea tiles.
                currentLassoPath.clear();
            }
            catch (Exception e)
            {
                log.error("[LassoTool] Error finalizing lasso on client thread", e);
                currentPreviewTiles = Collections.emptySet();
                currentArea = null;
                currentLassoPath.clear();
            }
        });
    }

    /**
     * Snap a screen point (from lasso drag) to the canvas center of the tile it is over.
     * Used when snapToTiles is enabled (research from standard lasso "snap to grid/edges" behavior).
     * Must be called on client thread.
     */
    private java.awt.Point snapScreenPointToNearestTileCenter(java.awt.Point screenPoint)
    {
        if (screenPoint == null || client.getLocalPlayer() == null) return screenPoint;

        WorldPoint player = client.getLocalPlayer().getWorldLocation();
        if (player == null) return screenPoint;
        int plane = player.getPlane();

        java.awt.Point best = screenPoint;
        double bestDist = Double.MAX_VALUE;

        // Scan a reasonable radius; test which tile poly contains the raw screen point
        for (int dx = -12; dx <= 12; dx++)
        {
            for (int dy = -12; dy <= 12; dy++)
            {
                WorldPoint wp = new WorldPoint(player.getX() + dx, player.getY() + dy, plane);
                LocalPoint lp = LocalPoint.fromWorld(client, wp);
                if (lp == null) continue;

                java.awt.Polygon tilePoly = Perspective.getCanvasTilePoly(client, lp);
                if (tilePoly != null && tilePoly.contains(screenPoint.x, screenPoint.y))
                {
                    net.runelite.api.Point canvas = Perspective.localToCanvas(client, lp, plane);
                    if (canvas != null)
                    {
                        java.awt.Point cand = new java.awt.Point(canvas.getX(), canvas.getY());
                        double d = screenPoint.distance(cand);
                        if (d < bestDist)
                        {
                            bestDist = d;
                            best = cand;
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * Compute which tiles are inside the lasso polygon.
     * MUST be called on the client thread (uses Perspective, LocalPoint.fromWorld, client.getLocalPlayer).
     * Package-visible so LassoToolOverlay can call it during render() for live preview while drawing.
     */
    Set<WorldPoint> computeTilesInLasso(List<Point> path)
    {
        if (path == null || path.size() < 3) return Collections.emptySet();

        // Build screen polygon from path (the user's drawn lasso)
        // If snapToTiles, use snapped points for the poly (standard lasso "quantize to grid" for selection)
        // while the visual stroke (in overlay) remains raw freehand for natural feel.
        List<Point> pointsToUse = path;
        if (config.snapToTiles())
        {
            pointsToUse = new ArrayList<>();
            for (Point p : path)
            {
                pointsToUse.add(snapScreenPointToNearestTileCenter(p));
            }
        }

        Polygon screenPoly = new Polygon();
        for (Point p : pointsToUse)
        {
            screenPoly.addPoint(p.x, p.y);
        }

        Set<WorldPoint> inside = new HashSet<>();

        if (client.getLocalPlayer() == null) return inside;

        WorldPoint player = client.getLocalPlayer().getWorldLocation();
        if (player == null) return inside;
        int plane = player.getPlane();

        // Scan a larger area around player. Selection was reported "off" in some cases (large areas,
        // camera movement during draw, or edge tiles). Larger scan + center test helps pull correct tiles.
        // (Note: path pixels are from draw-time view; large player movement during draw can affect accuracy.)
        int scan = 50;
        for (int dx = -scan; dx <= scan; dx++)
        {
            for (int dy = -scan; dy <= scan; dy++)
            {
                WorldPoint wp = new WorldPoint(player.getX() + dx, player.getY() + dy, plane);
                LocalPoint lp = LocalPoint.fromWorld(client, wp);
                if (lp == null) continue;

                java.awt.Polygon tilePoly = Perspective.getCanvasTilePoly(client, lp);
                if (tilePoly == null) continue;

                // Test center of tile - if the center projects inside the drawn lasso shape, include it.
                // This is the primary "selection" logic for which tiles are "inside" the user's lasso.
                double cx = tilePoly.getBounds().getCenterX();
                double cy = tilePoly.getBounds().getCenterY();

                if (screenPoly.contains(cx, cy))
                {
                    inside.add(wp);
                }
            }
        }
        return inside;
    }

    public void captureData()
    {
        if (currentArea == null || currentArea.getTiles().isEmpty())
        {
            log.warn("[LassoTool] No area to capture");
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastCaptureTime < 500) return; // debounce
        lastCaptureTime = now;

        Set<WorldPoint> tiles = currentArea.getTiles();

        // Grab data using Microbot caches where possible
        StringBuilder report = new StringBuilder();
        report.append("=== Lasso Capture: ").append(currentArea.getName()).append(" ===\n");
        report.append("Tiles: ").append(tiles.size()).append("\n");

        // Tile objects
        var tileObjects = net.runelite.client.plugins.microbot.Microbot.getRs2TileObjectCache().query()
                .toList().stream()
                .filter(o -> tiles.contains(o.getWorldLocation()))
                .collect(java.util.stream.Collectors.toList());

        report.append("Tile Objects (").append(tileObjects.size()).append("):\n");
        for (var obj : tileObjects)
        {
            report.append("  ").append(obj.getName()).append(" (").append(obj.getId()).append(") @ ").append(obj.getWorldLocation()).append("\n");
        }

        // Players
        var players = net.runelite.client.plugins.microbot.Microbot.getRs2PlayerCache().query()
                .toList().stream()
                .filter(p -> tiles.contains(p.getWorldLocation()))
                .collect(java.util.stream.Collectors.toList());
        report.append("Players nearby: ").append(players.size()).append("\n");

        // NPCs (more data pull)
        var npcs = net.runelite.client.plugins.microbot.Microbot.getRs2NpcCache().query()
                .toList().stream()
                .filter(n -> tiles.contains(n.getWorldLocation()))
                .collect(java.util.stream.Collectors.toList());
        report.append("NPCs (").append(npcs.size()).append("):\n");
        for (var npc : npcs)
        {
            report.append("  ").append(npc.getName()).append(" (id=").append(npc.getId()).append(") @ ").append(npc.getWorldLocation()).append("\n");
        }

        // Ground items / tile items (more data pull - "another" cache)
        var groundItems = net.runelite.client.plugins.microbot.Microbot.getRs2TileItemCache().query()
                .toList().stream()
                .filter(i -> tiles.contains(i.getWorldLocation()))
                .collect(java.util.stream.Collectors.toList());
        report.append("Ground Items (").append(groundItems.size()).append("):\n");
        for (var item : groundItems)
        {
            String iname = item.getName() != null ? item.getName() : ("Item#" + item.getId());
            report.append("  ").append(iname).append(" x").append(item.getQuantity()).append(" (id=").append(item.getId()).append(") @ ").append(item.getWorldLocation()).append("\n");
        }

        // Store the report for file save etc.
        lastCaptureReport = report.toString();

        // For full data grab, user can extend. Print to chat + clipboard option
        log.info("[LassoTool] Capture report:\n{}", lastCaptureReport);

        if (client.getLocalPlayer() != null)
        {
            // Send to game chat (split if too long)
            String summary = "Lasso: " + tiles.size() + " tiles, " + tileObjects.size() + " objects, " + npcs.size() + " npcs, " + groundItems.size() + " items";
            client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", summary, null);
        }

        // NOTE: clipboard copy of mesh is handled by caller (finishLasso) based on autoCopy config.
        // We intentionally do not auto-copy here to avoid double-copy and to respect the config.
    }

    public void copyToClipboard()
    {
        if (currentArea == null) return;

        String code = currentArea.toMeshCode();
        try
        {
            StringSelection selection = new StringSelection(code);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
            log.info("[LassoTool] Copied mesh code to clipboard");
            if (client.getLocalPlayer() != null)
            {
                client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "Lasso mesh code copied to clipboard", null);
            }
        }
        catch (Exception e)
        {
            log.error("Failed to copy to clipboard", e);
        }
    }

    /**
     * Save the current lasso selection (tiles JSON + mesh code + full data report) to a file.
     * File is written to ~/.microbot/lasso-captures/lasso-YYYYMMDD-HHmmss.txt
     * This can be triggered automatically on finish (see config.autoSaveToFile) or called manually.
     * Uses the hotkey activation flow: press hotkey to start lasso, draw your area (clicks won't affect game),
     * then press hotkey (or right/double-click) to finish and save the selection to file.
     */
    public void saveToFile()
    {
        if (currentArea == null || currentArea.getTiles().isEmpty())
        {
            log.warn("[LassoTool] No selection to save to file");
            return;
        }

        try
        {
            String home = System.getProperty("user.home");
            java.nio.file.Path dir = java.nio.file.Paths.get(home, ".microbot", "lasso-captures");
            java.nio.file.Files.createDirectories(dir);

            String ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            java.nio.file.Path file = dir.resolve("lasso-" + ts + ".txt");

            StringBuilder content = new StringBuilder();
            content.append("Lasso Selection Capture\n");
            content.append("Name: ").append(currentArea.getName()).append("\n");
            content.append("Saved: ").append(ts).append("\n\n");

            content.append("=== Tiles as JSON (for custom areas / MLM etc) ===\n");
            content.append(currentArea.toJsonList()).append("\n\n");

            content.append("=== Java Mesh Code (copy-paste ready Set<WorldPoint>) ===\n");
            content.append(currentArea.toMeshCode()).append("\n\n");

            if (lastCaptureReport != null && !lastCaptureReport.isEmpty())
            {
                content.append("=== Full Data Report (tiles + objects + npcs + ground items) ===\n");
                content.append(lastCaptureReport).append("\n");
            }

            java.nio.file.Files.writeString(file, content.toString());

            log.info("[LassoTool] Saved selection to file: {}", file);
            if (client.getLocalPlayer() != null)
            {
                client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "Lasso selection saved to file: " + file.getFileName(), null);
            }
        }
        catch (Exception e)
        {
            log.error("Failed to save lasso selection to file", e);
            if (client.getLocalPlayer() != null)
            {
                client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "Failed to save lasso to file (see log)", null);
            }
        }
    }

    public LassoToolConfig getConfig()
    {
        return config;
    }

    public List<Point> getCurrentLassoPath()
    {
        // Return a snapshot copy; callers (overlay render) must not hold reference across frames
        return new ArrayList<>(currentLassoPath);
    }

    // Called from mouse listener
    public void setDrawing(boolean drawing)
    {
        this.drawing = drawing;
    }

    /**
     * Clears drawing state without finalizing/capturing a lasso (used for cancel via right-click on empty etc.).
     */
    public void clearLassoState()
    {
        currentLassoPath.clear();
        currentPreviewTiles = Collections.emptySet();
        currentArea = null;
    }

    /**
     * Force-stop drawing mode and unregister listener (for cancel paths).
     */
    public void stopLassoDrawing()
    {
        drawing = false;
        mouseManager.unregisterMouseListener(mouseListener);
        clearLassoState();
    }
}
