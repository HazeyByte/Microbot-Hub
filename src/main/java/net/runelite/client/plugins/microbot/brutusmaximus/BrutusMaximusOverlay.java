package net.runelite.client.plugins.microbot.brutusmaximus;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

@Singleton
public class BrutusMaximusOverlay extends OverlayPanel {
    private static final int WIDTH = 248;
    private static final int PADDING = 14;
    private static final int SECTION_HEADER_HEIGHT = 24;
    private static final int ROW_HEIGHT = 20;
    private static final int SECTION_GAP = 10;
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

    private static final Font TITLE_FONT = FontManager.getRunescapeBoldFont().deriveFont(16f);
    private static final Font SECTION_FONT = FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD, 15f);
    private static final Font LABEL_FONT = FontManager.getRunescapeSmallFont().deriveFont(14f);
    private static final Font VALUE_FONT = FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD, 14f);
    private static final Font SMALL_FONT = FontManager.getRunescapeSmallFont().deriveFont(13f);

    private final BrutusMaximusScript script;
    private boolean activityCollapsed = false;
    private boolean statsCollapsed = false;
    private Rectangle activityHeaderBounds;
    private Rectangle statsHeaderBounds;

    @Inject
    BrutusMaximusOverlay(BrutusMaximusPlugin plugin, BrutusMaximusScript script) {
        super(plugin);
        this.script = script;
        setPosition(OverlayPosition.TOP_LEFT);
        setNaughty();
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!Microbot.isLoggedIn()) {
            return null;
        }

        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int height = calculateHeight(g);
            Dimension size = new Dimension(WIDTH, height);

            renderBackground(g, size);
            int y = PADDING;
            y = renderHeader(g, size, y);
            y = renderActivitySection(g, size, y);
            renderStatsSection(g, size, y);

            return size;
        } finally {
            g.dispose();
        }
    }

    private int calculateHeight(Graphics2D g) {
        FontMetrics titleMetrics = g.getFontMetrics(TITLE_FONT);
        int titleBlock = titleMetrics.getHeight() + 14;
        int activityBlock = SECTION_HEADER_HEIGHT + (activityCollapsed ? 0 : (2 * ROW_HEIGHT));
        int statsBlock = SECTION_HEADER_HEIGHT + (statsCollapsed ? 0 : (4 * ROW_HEIGHT));
        return PADDING + titleBlock + SECTION_GAP + activityBlock + SECTION_GAP + statsBlock + PADDING;
    }

    private void renderBackground(Graphics2D g, Dimension size) {
        RoundRectangle2D outer = new RoundRectangle2D.Float(0, 0, size.width, size.height, 8, 8);
        g.setColor(BORDER_OUTER);
        g.setStroke(new BasicStroke(2.5f));
        g.draw(outer);

        RoundRectangle2D inner = new RoundRectangle2D.Float(2, 2, size.width - 4, size.height - 4, 6, 6);
        g.setPaint(new GradientPaint(0, 0, BG_PRIMARY, 0, size.height, BG_SECONDARY));
        g.fill(inner);

        g.setColor(BORDER_INNER);
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Float(3, 3, size.width - 6, size.height - 6, 5, 5));
    }

    private int renderHeader(Graphics2D g, Dimension size, int y) {
        g.setFont(TITLE_FONT);
        FontMetrics fm = g.getFontMetrics();

        String title = "BrutusMaximus";
        int titleWidth = fm.stringWidth(title);
        int titleX = (size.width - titleWidth) / 2;
        int titleY = y + fm.getAscent();

        g.setColor(TITLE_SHADOW);
        g.drawString(title, titleX + 1, titleY + 1);
        g.setColor(TITLE_PRIMARY);
        g.drawString(title, titleX, titleY);

        g.setFont(SMALL_FONT);
        String version = "v" + BrutusMaximusPlugin.version;
        int versionWidth = g.getFontMetrics().stringWidth(version);
        int badgeX = size.width - PADDING - versionWidth - 8;
        int badgeY = y + 1;
        g.setColor(new Color(60, 55, 45, 180));
        g.fillRoundRect(badgeX - 3, badgeY, versionWidth + 6, 14, 4, 4);
        g.setColor(new Color(100, 90, 70, 200));
        g.drawRoundRect(badgeX - 3, badgeY, versionWidth + 6, 14, 4, 4);
        g.setColor(ACCENT_STATS);
        g.drawString(version, badgeX, badgeY + 10);

        int dividerY = y + fm.getHeight() + 6;
        g.setColor(DIVIDER);
        g.drawLine(PADDING, dividerY, size.width - PADDING, dividerY);
        return dividerY + 6;
    }

    private int renderActivitySection(Graphics2D g, Dimension size, int y) {
        int headerStartY = y;
        y = drawSectionHeader(g, "Activity", size.width, y, ACCENT_ACTIVITY, !activityCollapsed);
        activityHeaderBounds = new Rectangle(PADDING, headerStartY, size.width - (PADDING * 2), y - headerStartY);
        if (activityCollapsed) {
            return y;
        }
        y = drawDataRow(g, "State", script.getStateName(), size.width, y);
        return drawDataRow(g, "Mechanic", script.getMechanicStatusText(), size.width, y);
    }

    private int renderStatsSection(Graphics2D g, Dimension size, int y) {
        y += SECTION_GAP;
        int headerStartY = y;
        y = drawSectionHeader(g, "Stats", size.width, y, ACCENT_STATS, !statsCollapsed);
        statsHeaderBounds = new Rectangle(PADDING, headerStartY, size.width - (PADDING * 2), y - headerStartY);
        if (statsCollapsed) {
            return y;
        }
        y = drawDataRow(g, "Total Kills", script.getTotalKillsText(), size.width, y);
        y = drawDataRow(g, "Total XP Gained", script.getTotalXpGainedText(), size.width, y);
        y = drawDataRow(g, "XP/Hour", script.getXpPerHourText(), size.width, y);
        return drawDataRow(g, "Run Time", script.getRuntimeText(), size.width, y);
    }

    private int drawSectionHeader(Graphics2D g, String text, int width, int y, Color accent, boolean expanded) {
        g.setFont(SECTION_FONT);
        FontMetrics fm = g.getFontMetrics();
        int baseline = y + fm.getAscent();

        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 80));
        g.fillRect(PADDING - 2, y - 1, 5, fm.getHeight() + 2);
        g.setColor(accent);
        g.fillRect(PADDING - 1, y, 3, fm.getHeight());

        g.setColor(TEXT_BRIGHT);
        g.drawString(text, PADDING + 8, baseline);

        int lineStart = PADDING + 8 + fm.stringWidth(text) + 6;
        int lineY = y + (fm.getHeight() / 2);
        g.setColor(DIVIDER);
        g.drawLine(lineStart, lineY, width - PADDING - (COLLAPSE_ICON_SIZE + 6), lineY);

        int arrowX = width - PADDING - COLLAPSE_ICON_SIZE;
        int arrowY = lineY;
        drawCollapseArrow(g, arrowX, arrowY, expanded);

        return y + SECTION_HEADER_HEIGHT;
    }

    private void drawCollapseArrow(Graphics2D g, int x, int y, boolean expanded) {
        g.setColor(new Color(136, 136, 136));

        int[] xPoints = new int[] {x, x + COLLAPSE_ICON_SIZE, x + (COLLAPSE_ICON_SIZE / 2)};
        int[] yPoints;
        if (expanded) {
            yPoints = new int[] {y - 2, y - 2, y + 3};
        } else {
            yPoints = new int[] {y - 3, y + 2, y + 2};
        }
        g.fillPolygon(xPoints, yPoints, 3);
    }

    private int drawDataRow(Graphics2D g, String label, String value, int width, int y) {
        g.setFont(LABEL_FONT);
        g.setColor(TEXT_DIM);
        int baseline = y + g.getFontMetrics().getAscent();
        g.drawString(label + ":", PADDING, baseline);

        g.setFont(VALUE_FONT);
        g.setColor(TEXT_BRIGHT);
        String rightText = value == null ? "-" : value;
        int valueWidth = g.getFontMetrics().stringWidth(rightText);
        int valueX = Math.max(PADDING + 80, width - PADDING - valueWidth);
        g.drawString(rightText, valueX, baseline);

        return y + ROW_HEIGHT;
    }

    public boolean handleMousePressed(Point canvasPoint) {
        Point localPoint = toLocalPoint(canvasPoint);
        if (localPoint == null) {
            return false;
        }

        if (activityHeaderBounds != null && activityHeaderBounds.contains(localPoint)) {
            activityCollapsed = !activityCollapsed;
            return true;
        }
        if (statsHeaderBounds != null && statsHeaderBounds.contains(localPoint)) {
            statsCollapsed = !statsCollapsed;
            return true;
        }
        return false;
    }

    private Point toLocalPoint(Point canvasPoint) {
        if (canvasPoint == null) {
            return null;
        }
        Rectangle overlayBounds = getBounds();
        if (overlayBounds == null) {
            return canvasPoint;
        }
        return new Point(canvasPoint.x - overlayBounds.x, canvasPoint.y - overlayBounds.y);
    }
}
