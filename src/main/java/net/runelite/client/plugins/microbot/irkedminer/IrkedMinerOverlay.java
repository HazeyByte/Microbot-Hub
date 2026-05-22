/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  javax.inject.Inject
 *  net.runelite.api.Client
 *  net.runelite.api.Experience
 *  net.runelite.api.Skill
 *  net.runelite.client.plugins.microbot.Microbot
 *  net.runelite.client.ui.FontManager
 *  net.runelite.client.ui.overlay.OverlayPanel
 *  net.runelite.client.ui.overlay.OverlayPosition
 */
package net.runelite.client.plugins.microbot.irkedminer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.irkedminer.IrkedMinerConfig;
import net.runelite.client.plugins.microbot.irkedminer.IrkedMinerScript;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;

public class IrkedMinerOverlay
extends OverlayPanel {
    private static final String FONT_RESOURCE = "/net/runelite/client/plugins/microbot/irkedminer/assets/Cinzel-Regular.ttf";
    private static final int OVERLAY_WIDTH = 248;
    private static final int PADDING = 14;
    private static final int ROW_HEIGHT = 19;
    private static final int SECTION_GAP = 10;
    private static final int INNER_PADDING = 8;
    private static final int COLLAPSE_ICON_SIZE = 8;
    private static final Color BG_PRIMARY = new Color(27, 27, 23, 235);
    private static final Color BG_SECONDARY = new Color(35, 35, 30, 235);
    private static final Color BORDER_OUTER = new Color(85, 75, 55, 200);
    private static final Color BORDER_INNER = new Color(50, 45, 35, 180);
    private static final Color DIVIDER = new Color(60, 55, 45, 120);
    private static final Color TITLE_PRIMARY = new Color(255, 200, 100);
    private static final Color TITLE_SHADOW = new Color(0, 0, 0, 150);
    private static final Color TEXT_BRIGHT = new Color(255, 255, 200);
    private static final Color TEXT_DIM = new Color(180, 170, 150);
    private static final Color ACCENT_ACTIVITY = new Color(99, 206, 141);
    private static final Color ACCENT_STATS = new Color(255, 196, 91);
    private static final Color ACCENT_SETTINGS = new Color(118, 182, 240);
    private static final Color XP_BAR_BG = new Color(40, 40, 35, 200);
    private static final Color XP_BAR_FILL_START = new Color(99, 206, 141);
    private static final Color XP_BAR_FILL_END = new Color(125, 232, 168);
    private static final Color XP_BAR_BORDER = new Color(100, 90, 70);
    private static final Color TOGGLE_ON_BG = new Color(99, 206, 141, 76);
    private static final Color TOGGLE_ON_BORDER = new Color(99, 206, 141);
    private static final Color TOGGLE_OFF_BG = new Color(80, 60, 60, 180);
    private static final Color TOGGLE_OFF_BORDER = new Color(120, 80, 80);
    private static final Color BATCH_MODE_BG = new Color(255, 196, 91, 38);
    private static final Color BATCH_MODE_TEXT = new Color(255, 196, 91);
    private static final float TITLE_FONT_SIZE = 16.0f;
    private static final Font TITLE_FONT = IrkedMinerOverlay.loadTitleFont(16.0f).deriveFont(1);
    private static final Font SECTION_FONT = FontManager.getRunescapeSmallFont().deriveFont(1, 13.0f);
    private static final Font LABEL_FONT = FontManager.getRunescapeSmallFont().deriveFont(12.0f);
    private static final Font VALUE_FONT = FontManager.getRunescapeSmallFont().deriveFont(1, 12.0f);
    private static final Font SMALL_FONT = FontManager.getRunescapeSmallFont().deriveFont(11.0f);
    private final IrkedMinerScript script;
    private final Client client;
    private final IrkedMinerConfig config;
    private Instant startTime;
    private int startXp;
    private int startLevel;
    private boolean firstRun = false;
    private boolean activityCollapsed = false;
    private boolean statsCollapsed = false;
    private boolean settingsCollapsed = false;
    private Rectangle activityHeaderBounds;
    private Rectangle statsHeaderBounds;
    private Rectangle settingsHeaderBounds;

    @Inject
    public IrkedMinerOverlay(IrkedMinerScript script, Client client, IrkedMinerConfig config) {
        this.script = script;
        this.client = client;
        this.config = config;
        this.setPosition(OverlayPosition.TOP_LEFT);
    }

    public void resetStats() {
        this.startTime = Instant.now();
        this.startXp = this.client.getSkillExperience(Skill.MINING);
        this.startLevel = this.client.getRealSkillLevel(Skill.MINING);
    }

    public boolean handleMousePressed(Point point) {
        Point localPoint = this.toLocalPoint(point);
        if (localPoint == null) {
            return false;
        }
        if (this.activityHeaderBounds != null && this.activityHeaderBounds.contains(localPoint)) {
            this.activityCollapsed = !this.activityCollapsed;
            return true;
        }
        if (this.statsHeaderBounds != null && this.statsHeaderBounds.contains(localPoint)) {
            this.statsCollapsed = !this.statsCollapsed;
            return true;
        }
        if (this.settingsHeaderBounds != null && this.settingsHeaderBounds.contains(localPoint)) {
            this.settingsCollapsed = !this.settingsCollapsed;
            return true;
        }
        return false;
    }

    private Point toLocalPoint(Point canvasPoint) {
        if (canvasPoint == null) {
            return null;
        }
        Rectangle overlayBounds = this.getBounds();
        if (overlayBounds == null) {
            return canvasPoint;
        }
        return new Point(canvasPoint.x - overlayBounds.x, canvasPoint.y - overlayBounds.y);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public Dimension render(Graphics2D graphics) {
        if (!Microbot.isLoggedIn()) {
            this.firstRun = false;
            return null;
        }
        if (!this.firstRun) {
            this.resetStats();
            this.firstRun = true;
        }
        Graphics2D g = (Graphics2D)graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int totalHeight = this.calculateTotalHeight(g);
            Dimension size = new Dimension(248, totalHeight);
            this.renderBackground(g, size);
            int y = 14;
            y = this.renderHeader(g, size, y);
            y = this.renderActivitySection(g, size, y);
            y = this.renderStatsSection(g, size, y);
            this.renderSettingsSection(g, size, y);
            Dimension dimension = size;
            return dimension;
        }
        finally {
            g.dispose();
        }
    }

    private int calculateTotalHeight(Graphics2D g) {
        int height = 28;
        FontMetrics titleMetrics = g.getFontMetrics(TITLE_FONT);
        height += titleMetrics.getHeight() + 10;
        height += 28;
        if (!this.activityCollapsed) {
            height += 57;
        }
        height += 28;
        if (!this.statsCollapsed) {
            height += 134;
        }
        height += 28;
        if (!this.settingsCollapsed) {
            height += 76;
        }
        return height;
    }

    private void renderBackground(Graphics2D g, Dimension size) {
        RoundRectangle2D.Float outerBorder = new RoundRectangle2D.Float(0.0f, 0.0f, size.width, size.height, 8.0f, 8.0f);
        g.setColor(BORDER_OUTER);
        g.setStroke(new BasicStroke(2.5f));
        g.draw(outerBorder);
        RoundRectangle2D.Float background = new RoundRectangle2D.Float(2.0f, 2.0f, size.width - 4, size.height - 4, 6.0f, 6.0f);
        GradientPaint bgGradient = new GradientPaint(0.0f, 0.0f, BG_PRIMARY, 0.0f, size.height, BG_SECONDARY);
        g.setPaint(bgGradient);
        g.fill(background);
        g.setColor(BORDER_INNER);
        g.setStroke(new BasicStroke(1.0f));
        g.draw(new RoundRectangle2D.Float(3.0f, 3.0f, size.width - 6, size.height - 6, 5.0f, 5.0f));
    }

    private int renderHeader(Graphics2D g, Dimension size, int startY) {
        g.setFont(TITLE_FONT);
        FontMetrics metrics = g.getFontMetrics();
        String title = "irkedMiner";
        int titleWidth = metrics.stringWidth(title);
        int titleX = (size.width - titleWidth) / 2;
        int titleY = startY + metrics.getAscent();
        g.setColor(TITLE_SHADOW);
        g.drawString(title, titleX + 1, titleY + 1);
        g.setColor(TITLE_PRIMARY);
        g.drawString(title, titleX, titleY);
        g.setFont(SMALL_FONT);
        String version = "v1.1.2";
        int versionWidth = g.getFontMetrics().stringWidth(version);
        int badgeX = size.width - 14 - versionWidth - 8;
        int badgeY = startY + 2;
        g.setColor(new Color(60, 55, 45, 180));
        g.fillRoundRect(badgeX - 3, badgeY, versionWidth + 6, 14, 4, 4);
        g.setColor(new Color(100, 90, 70, 200));
        g.drawRoundRect(badgeX - 3, badgeY, versionWidth + 6, 14, 4, 4);
        g.setColor(ACCENT_STATS);
        g.drawString(version, badgeX, badgeY + 10);
        int lineY = startY + metrics.getHeight() + 4;
        this.drawDecorativeLine(g, lineY, size.width - 28);
        return lineY + 8;
    }

    private int renderActivitySection(Graphics2D g, Dimension size, int startY) {
        int y;
        String modeDisplay;
        String stateName = this.script != null && this.script.getState() != null ? this.script.getState().name() : "IDLE";
        String displayStatus = Microbot.status != null && !Microbot.status.isBlank() ? Microbot.status : stateName;
        String oreName = this.config != null ? this.config.targetOre().getName() : "Unknown";
        Object currentTier = "";
        if (this.config != null && this.config.enablePriorityMining()) {
            modeDisplay = "BATCH";
            if (this.script != null) {
                try {
                    Method method = ((Object)((Object)this.script)).getClass().getMethod("getCurrentBatchTier", new Class[0]);
                    Object tier = method.invoke((Object)this.script, new Object[0]);
                    if (tier != null) {
                        currentTier = " P" + String.valueOf(tier);
                    }
                }
                catch (Exception method) {}
            }
        } else {
            modeDisplay = this.config != null && this.config.useBank() ? "Banking" : "Power Mining";
        }
        int headerStartY = y = startY + 10;
        y = this.drawSectionHeader(g, "Activity", y, size.width, ACCENT_ACTIVITY, !this.activityCollapsed);
        this.activityHeaderBounds = new Rectangle(14, headerStartY, size.width - 28, y - headerStartY);
        if (!this.activityCollapsed) {
            y = this.drawDataRow(g, "Activity", displayStatus, y, size.width, null);
            y = this.drawDataRow(g, "Target", oreName, y, size.width, null);
            y = this.config != null && this.config.enablePriorityMining() ? this.drawBatchModeRow(g, modeDisplay + (String)currentTier, y, size.width) : this.drawDataRow(g, "Mode", modeDisplay, y, size.width, null);
        }
        return y;
    }

    private int renderStatsSection(Graphics2D g, Dimension size, int startY) {
        int y;
        int currentLevel = this.client.getRealSkillLevel(Skill.MINING);
        int currentXp = this.client.getSkillExperience(Skill.MINING);
        int xpGained = currentXp - this.startXp;
        int oresMined = this.script != null ? Math.max(0, this.script.getOresMined()) : 0;
        int worldHops = this.script != null ? this.script.getWorldHops() : 0;
        long secondsElapsed = Math.max(1L, Duration.between(this.startTime, Instant.now()).getSeconds());
        double xpPerHour = (double)xpGained / (double)secondsElapsed * 3600.0;
        Object levelText = String.valueOf(currentLevel);
        if (currentLevel > this.startLevel) {
            levelText = (String)levelText + " (+" + (currentLevel - this.startLevel) + ")";
        }
        int headerStartY = y = startY + 10;
        y = this.drawSectionHeader(g, "Stats", y, size.width, ACCENT_STATS, !this.statsCollapsed);
        this.statsHeaderBounds = new Rectangle(14, headerStartY, size.width - 28, y - headerStartY);
        if (!this.statsCollapsed) {
            y = this.drawDataRow(g, "Level", (String)levelText, y, size.width, null);
            y += 2;
            y = this.drawXpProgressBar(g, currentXp, currentLevel, y, size.width);
            y += 2;
            y = this.drawDataRow(g, "XP Gained", this.formatNumber(xpGained), y, size.width, null);
            y = this.drawDataRow(g, "XP/Hour", this.formatNumber((long)xpPerHour), y, size.width, null);
            y = this.drawDataRow(g, "Ores Mined", String.valueOf(oresMined), y, size.width, null);
            y = this.drawDataRow(g, "World Hops", String.valueOf(worldHops), y, size.width, null);
            y = this.drawDataRow(g, "Runtime", this.formatDuration(Duration.between(this.startTime, Instant.now())), y, size.width, null);
        }
        return y;
    }

    private int renderSettingsSection(Graphics2D g, Dimension size, int startY) {
        int y;
        int headerStartY = y = startY + 10;
        y = this.drawSectionHeader(g, "Settings", y, size.width, ACCENT_SETTINGS, !this.settingsCollapsed);
        this.settingsHeaderBounds = new Rectangle(14, headerStartY, size.width - 28, y - headerStartY);
        if (!this.settingsCollapsed) {
            y = this.drawToggleRow(g, "Batch Mode", this.config != null && this.config.enablePriorityMining(), y, size.width);
            y = this.drawToggleRow(g, "World Hop", this.config != null && this.config.enableWorldHopping(), y, size.width);
            y = this.drawToggleRow(g, "Banking", this.config != null && this.config.useBank(), y, size.width);
            y = this.drawToggleRow(g, "Drop Gems", this.config != null && this.config.dropUncutGems(), y, size.width);
        }
        return y;
    }

    private int drawSectionHeader(Graphics2D g, String text, int y, int width, Color accentColor, boolean expanded) {
        g.setFont(SECTION_FONT);
        FontMetrics metrics = g.getFontMetrics();
        g.setColor(new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 80));
        g.fillRect(13, y - 1, 5, metrics.getHeight() + 2);
        g.setColor(accentColor);
        g.fillRect(14, y, 3, metrics.getHeight());
        g.setColor(TEXT_BRIGHT);
        g.drawString(text, 22, y + metrics.getAscent());
        int lineX = 22 + metrics.stringWidth(text) + 6;
        g.setColor(DIVIDER);
        g.drawLine(lineX, y + metrics.getHeight() / 2, width - 14 - 12, y + metrics.getHeight() / 2);
        int arrowX = width - 14 - 8;
        int arrowY = y + metrics.getHeight() / 2;
        this.drawCollapseArrow(g, arrowX, arrowY, expanded);
        return y + metrics.getHeight() + 6;
    }

    private void drawCollapseArrow(Graphics2D g, int x, int y, boolean expanded) {
        int[] yPoints;
        int[] xPoints;
        g.setColor(new Color(136, 136, 136));
        if (expanded) {
            xPoints = new int[]{x, x + 8, x + 4};
            yPoints = new int[]{y - 2, y - 2, y + 3};
        } else {
            xPoints = new int[]{x, x + 8, x + 4};
            yPoints = new int[]{y - 3, y + 2, y + 2};
        }
        g.fillPolygon(xPoints, yPoints, 3);
    }

    private int drawDataRow(Graphics2D g, String label, String value, int y, int width, Color valueColor) {
        g.setFont(LABEL_FONT);
        g.setColor(TEXT_DIM);
        int labelBaseline = y + g.getFontMetrics().getAscent() + 1;
        g.drawString(label + ":", 22, labelBaseline);
        g.setFont(VALUE_FONT);
        FontMetrics valueMetrics = g.getFontMetrics();
        int valueWidth = valueMetrics.stringWidth(value);
        int valueBaseline = y + valueMetrics.getAscent() + 1;
        g.setColor(valueColor != null ? valueColor : TEXT_BRIGHT);
        g.drawString(value, width - 14 - 8 - valueWidth, valueBaseline);
        return y + 19;
    }

    private int drawBatchModeRow(Graphics2D g, String value, int y, int width) {
        g.setFont(LABEL_FONT);
        g.setColor(TEXT_DIM);
        int labelBaseline = y + g.getFontMetrics().getAscent() + 1;
        g.drawString("Mode:", 22, labelBaseline);
        g.setFont(VALUE_FONT);
        FontMetrics valueMetrics = g.getFontMetrics();
        int valueWidth = valueMetrics.stringWidth(value);
        int badgeX = width - 14 - 8 - valueWidth - 8;
        int badgeY = y + 2;
        g.setColor(BATCH_MODE_BG);
        g.fillRoundRect(badgeX, badgeY, valueWidth + 8, 14, 3, 3);
        g.setColor(new Color(255, 196, 91, 100));
        g.drawRoundRect(badgeX, badgeY, valueWidth + 8, 14, 3, 3);
        g.setFont(SMALL_FONT);
        g.setColor(BATCH_MODE_TEXT);
        int badgeBaseline = y + g.getFontMetrics().getAscent() + 1;
        g.drawString(value, badgeX + 4, badgeBaseline);
        return y + 19;
    }

    private int drawToggleRow(Graphics2D g, String label, boolean enabled, int y, int width) {
        g.setFont(LABEL_FONT);
        g.setColor(TEXT_DIM);
        int labelBaseline = y + g.getFontMetrics().getAscent() + 1;
        g.drawString(label + ":", 22, labelBaseline);
        int toggleX = width - 14 - 8 - 45;
        int toggleY = y + 3;
        int toggleW = 45;
        int toggleH = 13;
        g.setColor(enabled ? TOGGLE_ON_BG : TOGGLE_OFF_BG);
        g.fillRoundRect(toggleX, toggleY, toggleW, toggleH, 5, 5);
        g.setColor(enabled ? TOGGLE_ON_BORDER : TOGGLE_OFF_BORDER);
        g.drawRoundRect(toggleX, toggleY, toggleW, toggleH, 5, 5);
        if (enabled) {
            g.setColor(new Color(99, 206, 141, 76));
            for (int i = 0; i < 3; ++i) {
                g.drawRoundRect(toggleX - i, toggleY - i, toggleW + i * 2, toggleH + i * 2, 5 + i, 5 + i);
            }
        }
        g.setFont(SMALL_FONT);
        g.setColor(TEXT_BRIGHT);
        String status = enabled ? "ON" : "OFF";
        int statusWidth = g.getFontMetrics().stringWidth(status);
        g.drawString(status, toggleX + (toggleW - statusWidth) / 2, toggleY + g.getFontMetrics().getAscent() + 1);
        return y + 19;
    }

    private int drawXpProgressBar(Graphics2D g, int currentXp, int currentLevel, int y, int width) {
        int barX = 22;
        int barWidth = width - 28 - 16;
        int barHeight = 13;
        int xpForCurrentLevel = Experience.getXpForLevel((int)currentLevel);
        int xpForNextLevel = Experience.getXpForLevel((int)(currentLevel + 1));
        int xpIntoLevel = currentXp - xpForCurrentLevel;
        int xpNeededForLevel = xpForNextLevel - xpForCurrentLevel;
        double progress = Math.min(1.0, (double)xpIntoLevel / (double)xpNeededForLevel);
        g.setColor(XP_BAR_BG);
        g.fillRoundRect(barX, y, barWidth, barHeight, 4, 4);
        int fillWidth = (int)((double)barWidth * progress);
        if (fillWidth > 0) {
            GradientPaint xpGradient = new GradientPaint(barX, y, XP_BAR_FILL_START, barX + fillWidth, y, XP_BAR_FILL_END);
            g.setPaint(xpGradient);
            g.fillRoundRect(barX, y, fillWidth, barHeight, 4, 4);
        }
        g.setColor(XP_BAR_BORDER);
        g.drawRoundRect(barX, y, barWidth, barHeight, 4, 4);
        g.setFont(SMALL_FONT);
        String progressText = String.format("%.1f%%", progress * 100.0);
        int textWidth = g.getFontMetrics().stringWidth(progressText);
        g.setColor(TEXT_BRIGHT);
        g.drawString(progressText, barX + (barWidth - textWidth) / 2, y + 10);
        return y + barHeight + 4;
    }

    private void drawDecorativeLine(Graphics2D g, int y, int width) {
        int x = 14;
        GradientPaint lineGradient = new GradientPaint(x, y, new Color(100, 90, 70, 0), (float)x + (float)width / 2.0f, y, new Color(150, 130, 90, 200));
        g.setPaint(lineGradient);
        g.fillRect(x, y, width / 2, 2);
        lineGradient = new GradientPaint((float)x + (float)width / 2.0f, y, new Color(150, 130, 90, 200), x + width, y, new Color(100, 90, 70, 0));
        g.setPaint(lineGradient);
        g.fillRect(x + width / 2, y, width / 2, 2);
    }

    private String formatDuration(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    private String formatNumber(long number) {
        return NumberFormat.getInstance().format(number);
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    private static Font loadTitleFont(float size) {
        try (InputStream stream = IrkedMinerOverlay.class.getResourceAsStream(FONT_RESOURCE);){
            if (stream == null) {
                Font font2 = FontManager.getRunescapeFont().deriveFont(size);
                return font2;
            }
            Font base = Font.createFont(0, stream);
            Font font = base.deriveFont(size);
            return font;
        }
        catch (FontFormatException | IOException ex) {
            return FontManager.getRunescapeFont().deriveFont(size);
        }
    }
}

