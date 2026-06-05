package net.runelite.client.plugins.microbot.irkedmlm;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.motherloadmine.session.SessionSnapshot;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.TimeUnit;

@Slf4j
public class IrkedMLMOverlay extends Overlay {

    // ── Layout ────────────────────────────────────────────────────────────────
    private static final int W         = 210;
    private static final int PAD_X     = 10;
    private static final int PAD_Y     = 7;
    private static final int ROW_H     = 19;
    private static final int ICON_SIZE = 14;
    private static final int HEADER_H  = 26;
    private static final int ARC       = 6;

    // ── Palette ───────────────────────────────────────────────────────────────
    private static final Color COL_BG          = new Color(16, 16, 16, 235);
    private static final Color COL_HEADER_BG   = new Color(35, 27,  8, 245);
    private static final Color COL_BORDER      = new Color(218, 165, 32, 190);
    private static final Color COL_TITLE       = new Color(255, 215, 0);
    private static final Color COL_SECTION     = new Color(200, 160, 50);
    private static final Color COL_KEY         = new Color(155, 155, 155);
    private static final Color COL_VAL         = new Color(235, 235, 235);
    private static final Color COL_VAL_DIM     = new Color(110, 110, 110);
    private static final Color COL_DIVIDER     = new Color(218, 165, 32, 55);
    private static final Color COL_BAR_BG      = new Color(40, 40, 40);
    private static final Color COL_BAR_FILL    = new Color(218, 165, 32, 200);

    // ── Ore metadata ──────────────────────────────────────────────────────────
    private static final int[] ORE_IDS = {
            net.runelite.api.gameval.ItemID.RUNITE_ORE,
            net.runelite.api.gameval.ItemID.ADAMANTITE_ORE,
            net.runelite.api.gameval.ItemID.MITHRIL_ORE,
            net.runelite.api.gameval.ItemID.GOLD_ORE,
            net.runelite.api.gameval.ItemID.COAL
    };
    private static final String[] ORE_NAMES = {
            "Runite", "Adamantite", "Mithril", "Gold", "Coal"
    };

    private final IrkedMLMScript script;
    private final ItemManager itemManager;

    @Inject
    public IrkedMLMOverlay(IrkedMLMPlugin plugin, IrkedMLMScript script) {
        super(plugin);
        this.script = script;
        this.itemManager = Microbot.getItemManager();
        setPosition(OverlayPosition.TOP_LEFT);
        setPreferredSize(new Dimension(W, 0));
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public Dimension render(Graphics2D g) {
        SessionSnapshot snap = script.getSnapshot();
        if (snap == null) snap = SessionSnapshot.empty();

        long runMs = snap.getStartTimeMs() > 0
                ? System.currentTimeMillis() - snap.getStartTimeMs()
                : 0L;

        String subState = snap.getSubStateLabel();
        boolean hasPhase = subState != null && !subState.isEmpty();

        // Pre-calculate ore counts so we can build the sack progress bar
        int[] oreCounts = {
                snap.getRuniteCount(),
                snap.getAdamantiteCount(),
                snap.getMithrilCount(),
                snap.getGoldCount(),
                snap.getCoalCount()
        };

        // ── Total height calculation ──────────────────────────────────────────
        int totalH = PAD_Y                          // top gap
                + HEADER_H                          // title bar
                + PAD_Y                             // below header
                + ROW_H                             // Runtime
                + ROW_H + 2                         // State (+ extra space after runtime for better spacing)
                + ROW_H                             // Area
                + ROW_H + 5                         // Sack + progress bar
                + ROW_H + 4                         // Nuggets (moved down slightly)
                + PAD_Y + 1 + PAD_Y                 // divider
                + 12 + 4                            // "ORE COLLECTED" label
                + ORE_IDS.length * (ICON_SIZE + 5)  // ore rows
                + PAD_Y + 1 + PAD_Y                 // divider
                + ROW_H                             // XP/hr
                + ROW_H                             // GP/hr
                + (hasPhase ? ROW_H : 0)            // Phase (optional)
                + PAD_Y;                            // bottom gap

        // ── Anti-aliasing ─────────────────────────────────────────────────────
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // ── Background ────────────────────────────────────────────────────────
        g.setColor(COL_BG);
        g.fillRoundRect(0, 0, W, totalH, ARC, ARC);

        // Header tint
        g.setColor(COL_HEADER_BG);
        g.fillRoundRect(0, 0, W, HEADER_H + PAD_Y * 2, ARC, ARC);
        g.fillRect(0, HEADER_H, W, PAD_Y);  // square off the bottom of the header block

        // Border
        g.setColor(COL_BORDER);
        g.setStroke(new BasicStroke(1.2f));
        g.drawRoundRect(0, 0, W - 1, totalH - 1, ARC, ARC);

        // ── Title ─────────────────────────────────────────────────────────────
        Font titleFont = g.getFont().deriveFont(Font.BOLD, 13f);
        g.setFont(titleFont);
        FontMetrics tfm = g.getFontMetrics();
        String title = "Motherload Mine";
        g.setColor(COL_TITLE);
        g.drawString(title, (W - tfm.stringWidth(title)) / 2, PAD_Y + tfm.getAscent());

        Font labelFont = g.getFont().deriveFont(Font.PLAIN, 11f);
        Font boldFont  = g.getFont().deriveFont(Font.BOLD,  11f);

        int curY = PAD_Y + HEADER_H + PAD_Y;

        // ── Info rows ─────────────────────────────────────────────────────────
        curY = drawRow(g, curY, labelFont, boldFont,
                "Runtime", formatDuration(runMs));
        curY += 2;  // extra spacing after runtime row
        curY = drawRow(g, curY, labelFont, boldFont,
                "State", snap.getStatus() != null ? snap.getStatus().name() : "Idle");
        curY = drawRow(g, curY, labelFont, boldFont,
                "Area", snap.getMiningSpot() != null ? snap.getMiningSpot().name() : "—");

        // Sack row + thin progress bar underneath
        int sack    = snap.getSackCount();
        int sackMax = snap.getMaxSackSize();
        curY = drawRow(g, curY, labelFont, boldFont,
                "Sack", sack + " / " + sackMax);
        if (sackMax > 0) {
            int barW  = W - PAD_X * 2;
            int barH  = 3;
            float pct = Math.min(1f, (float) sack / sackMax);
            g.setColor(COL_BAR_BG);
            g.fillRoundRect(PAD_X, curY, barW, barH, 2, 2);
            g.setColor(COL_BAR_FILL);
            g.fillRoundRect(PAD_X, curY, (int)(barW * pct), barH, 2, 2);
            curY += 5;
        }

        curY += 4;  // move nuggets row down slightly for better spacing
        curY = drawRow(g, curY, labelFont, boldFont,
                "Nuggets", String.valueOf(snap.getGainedNuggets()));

        // ── Divider ───────────────────────────────────────────────────────────
        curY = drawDivider(g, curY);

        // ── Ore section ───────────────────────────────────────────────────────
        Font sectionFont = g.getFont().deriveFont(Font.BOLD, 10f);
        g.setFont(sectionFont);
        g.setColor(COL_SECTION);
        g.drawString("ORE COLLECTED", PAD_X, curY + 10);
        curY += 14;

        for (int i = 0; i < ORE_IDS.length; i++) {
            int count = oreCounts[i];
            BufferedImage icon = getItemImage(ORE_IDS[i]);
            if (icon != null) {
                g.drawImage(icon, PAD_X, curY, ICON_SIZE, ICON_SIZE, null);
            }
            g.setFont(labelFont);
            g.setColor(count > 0 ? COL_VAL : COL_VAL_DIM);
            g.drawString(ORE_NAMES[i], PAD_X + ICON_SIZE + 6, curY + ICON_SIZE - 2);

            // Right-aligned count
            g.setFont(boldFont);
            FontMetrics bfm = g.getFontMetrics();
            String countStr = String.valueOf(count);
            g.setColor(count > 0 ? COL_VAL : COL_VAL_DIM);
            g.drawString(countStr, W - PAD_X - bfm.stringWidth(countStr), curY + ICON_SIZE - 2);

            curY += ICON_SIZE + 5;
        }

        // ── Divider ───────────────────────────────────────────────────────────
        curY = drawDivider(g, curY);

        // ── Stats ─────────────────────────────────────────────────────────────
        int    gainedXp   = snap.getCurrentXp() - snap.getStartXp();
        double xpPerHour  = runMs > 0 ? gainedXp * 3_600_000.0 / runMs : 0.0;
        double gpPerHour  = runMs > 0 ? snap.getTotalValueGained() * 3_600_000.0 / runMs : 0.0;

        curY = drawRow(g, curY, labelFont, boldFont, "XP/hr",  formatNumber((long) xpPerHour));
        curY = drawRow(g, curY, labelFont, boldFont, "GP/hr",  formatNumber((long) gpPerHour));
        if (hasPhase) {
            curY = drawRow(g, curY, labelFont, boldFont, "Phase", subState);
        }

        return new Dimension(W, totalH);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Draws a key/value row; returns the next Y position. */
    private int drawRow(Graphics2D g, int y, Font labelFont, Font valueFont,
                        String key, String value) {
        g.setFont(labelFont);
        FontMetrics lfm = g.getFontMetrics();
        g.setColor(COL_KEY);
        g.drawString(key, PAD_X, y + lfm.getAscent());

        g.setFont(valueFont);
        FontMetrics vfm = g.getFontMetrics();
        g.setColor(COL_VAL);
        g.drawString(value, W - PAD_X - vfm.stringWidth(value), y + vfm.getAscent());
        return y + ROW_H;
    }

    /** Draws a horizontal gold divider; returns the next Y position. */
    private int drawDivider(Graphics2D g, int y) {
        y += PAD_Y;
        g.setColor(COL_DIVIDER);
        g.setStroke(new BasicStroke(1f));
        g.drawLine(PAD_X, y, W - PAD_X, y);
        return y + PAD_Y;
    }

    private BufferedImage getItemImage(int itemId) {
        try {
            return itemManager.getImage(itemId, 1, false);
        } catch (Exception e) {
            return null;
        }
    }

    private String formatDuration(long ms) {
        long h = TimeUnit.MILLISECONDS.toHours(ms);
        long m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60;
        long s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    private String formatNumber(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }
}
