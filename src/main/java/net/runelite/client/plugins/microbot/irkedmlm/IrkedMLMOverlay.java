package net.runelite.client.plugins.microbot.irkedmlm;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import net.runelite.api.MenuAction;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.irkedmlm.enums.MLMMiningSpot;
import net.runelite.client.plugins.microbot.irkedmlm.enums.MLMStatus;
import net.runelite.client.plugins.microbot.irkedmlm.session.SessionSnapshot;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Motherlode Mine session dashboard.
 *
 * <h3>Data</h3>
 * Everything drawn here comes from the single {@link SessionSnapshot} the script publishes once per
 * tick. The overlay owns no game state, reads no varbits, and performs no client-thread work — it
 * formats numbers and draws. The one thing it derives is XP/hr and GP/hr, which are pure arithmetic
 * over snapshot fields.
 *
 * <h3>Layout</h3>
 * The panel measures its own content every frame and sizes to it, so large ore counts, long area
 * names or a wider UI scale push the panel out instead of colliding with the value column. The
 * returned {@link Dimension} is the real drawn size, which is what RuneLite's overlay renderer uses
 * to stack neighbouring overlays — a fixed preferred size is deliberately *not* set, as that was the
 * cause of the old panel being overlapped by whatever was drawn next to it.
 */
public class IrkedMLMOverlay extends Overlay
{
	// ── Metrics ─────────────────────────────────────────────────────────────
	private static final int MIN_W      = 196;
	private static final int MAX_W      = 300;
	private static final int PAD_X      = 10;
	private static final int PAD_TOP    = 8;
	private static final int PAD_BOT    = 9;
	private static final int COL_GAP    = 14;   // minimum gap between a label and its right-aligned value
	private static final int ARC        = 8;

	private static final int HEAD_ICON  = 18;
	private static final int ROW_ICON   = 14;
	private static final int ICON_GAP   = 7;

	private static final int TITLE_H    = 18;
	private static final int STATUS_H   = 16;
	private static final int ROW_H      = 17;
	private static final int ORE_ROW_H  = 18;
	private static final int SECTION_H  = 16;
	private static final int BAR_H      = 6;
	private static final int BAR_GAP    = 6;
	private static final int SEP_H      = 9;
	private static final int RATE_H     = 32;
	private static final int DOT        = 7;

	// ── Palette ─────────────────────────────────────────────────────────────
	private static final Color BG          = new Color(12, 13, 17, 247);
	private static final Color BORDER      = new Color(196, 152, 52, 120);
	private static final Color GOLD        = new Color(232, 193, 92);
	private static final Color GOLD_DIM    = new Color(205, 172, 96);
	private static final Color TEXT        = new Color(240, 241, 245);
	private static final Color TEXT_MUTED  = new Color(178, 180, 192);
	private static final Color TEXT_OFF    = new Color(124, 126, 138);
	private static final Color SHADOW      = new Color(0, 0, 0, 190);
	private static final Color SEP         = new Color(196, 152, 52, 44);
	private static final Color BAR_BG      = new Color(38, 39, 46);
	private static final Color BAR_FILL    = new Color(226, 183, 74);
	private static final Color BAR_FULL    = new Color(226, 122, 60);
	private static final Color TEAL        = new Color(64, 208, 188);

	private static final Color ST_GO       = new Color(74, 202, 126);
	private static final Color ST_WORK     = new Color(222, 182, 76);
	private static final Color ST_REPAIR   = new Color(232, 146, 48);
	private static final Color ST_BAD      = new Color(216, 84, 74);
	private static final Color ST_IDLE     = new Color(118, 118, 128);

	/** Sack fill at which the bar turns amber — a deposit trip is imminent. */
	private static final float BAR_WARN_PCT = 0.9f;
	/** Mining with no XP drop for this long means something is off; the footer timer turns amber. */
	private static final long  STALL_WARN_MS = 20_000L;

	// ── Ore table ───────────────────────────────────────────────────────────
	private static final int[] ORE_IDS = {
			ItemID.RUNITE_ORE, ItemID.ADAMANTITE_ORE, ItemID.MITHRIL_ORE, ItemID.GOLD_ORE, ItemID.COAL
	};
	private static final String[] ORE_NAMES = { "Runite", "Adamant", "Mithril", "Gold", "Coal" };

	/** Width reserved at the right of the header for the collapse chevron. */
	private static final int CHEVRON_W = 13;

	/** Rendered wherever a figure has no meaningful value yet. */
	static final String NO_VALUE = "—";

	private final IrkedMLMScript script;
	private final IrkedMLMConfig config;
	private final ConfigManager configManager;
	private final ItemManager itemManager;

	/** Last compact state the right-click menu entry was built for, so its label stays correct. */
	private Boolean menuBuiltForCompact;
	private final Map<Integer, BufferedImage> iconCache = new HashMap<>();

	/**
	 * RuneLite's own faces, used at their native 16px. These are bitmap fonts: deriving them to
	 * 10-12px (what the first cut did to the client font) destroys the hinting and is why several
	 * rows were hard to read. Sizes are fixed, so they are plain constants rather than per-frame work.
	 */
	private static final Font F_TITLE   = FontManager.getRunescapeBoldFont();
	private static final Font F_VALUE   = FontManager.getRunescapeBoldFont();
	private static final Font F_LABEL   = FontManager.getRunescapeSmallFont();
	private static final Font F_SECTION = FontManager.getRunescapeSmallFont();
	private static final Font F_SMALL   = FontManager.getRunescapeSmallFont();

	@Inject
	public IrkedMLMOverlay(IrkedMLMPlugin plugin, IrkedMLMScript script, IrkedMLMConfig config,
						   ConfigManager configManager)
	{
		super(plugin);
		this.script = script;
		this.config = config;
		this.configManager = configManager;
		this.itemManager = Microbot.getItemManager();
		setPosition(OverlayPosition.TOP_LEFT);
	}

	/**
	 * Keeps the overlay's right-click entry in step with the current mode. RuneLite overlays get no
	 * click events of their own, so this menu entry — not the drawn chevron — is the thing that
	 * actually collapses the panel; the chevron is its visual affordance.
	 */
	private void syncMenu(boolean compact)
	{
		if (menuBuiltForCompact != null && menuBuiltForCompact == compact)
		{
			return;
		}
		if (menuBuiltForCompact != null)
		{
			removeMenuEntry(MenuAction.RUNELITE_OVERLAY_CONFIG, menuBuiltForCompact ? "Expand" : "Collapse", MENU_TARGET);
		}
		addMenuEntry(MenuAction.RUNELITE_OVERLAY_CONFIG, compact ? "Expand" : "Collapse", MENU_TARGET,
				e -> configManager.setConfiguration(
						IrkedMLMConfig.configGroup, IrkedMLMConfig.overlayCompact, !compact));
		menuBuiltForCompact = compact;
	}

	private static final String MENU_TARGET = "Motherlode Mine";

	@Override
	public Dimension render(Graphics2D g)
	{
		final SessionSnapshot snap = script.getSnapshot();
		if (snap == null)
		{
			return null;
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		boolean compact = config.overlayCompact();
		syncMenu(compact);
		return compact ? renderCompact(g, snap) : renderExpanded(g, snap);
	}

	// ════════════════════════════════════════════════════════════════════════
	// Compact — deliberately its own hierarchy, not a squeezed expanded panel.
	// Status, sack progress, then one chip row of nuggets / XP-hr / GP-hr.
	// ════════════════════════════════════════════════════════════════════════

	private Dimension renderCompact(Graphics2D g, SessionSnapshot snap)
	{
		final String sack   = snap.getSackCount() + " / " + snap.getMaxSackSize();
		final String nug    = String.valueOf(snap.getGainedNuggets());
		final Rates rates   = Rates.of(snap);

		// Width: title + sack on one line, and the three chips below must both fit.
		int headW = PAD_X + HEAD_ICON + ICON_GAP + width(g, F_TITLE, "MLM") + COL_GAP
				+ width(g, F_VALUE, sack) + ICON_GAP + CHEVRON_W + PAD_X;
		int chipW = PAD_X
				+ chipWidth(g, nug) + COL_GAP
				+ chipWidth(g, rates.xpHr) + COL_GAP
				+ chipWidth(g, rates.gpHr) + PAD_X;
		int w = clampWidth(Math.max(headW, chipW));

		int h = PAD_TOP + TITLE_H + STATUS_H + BAR_GAP + BAR_H + BAR_GAP + ROW_ICON + PAD_BOT;

		panel(g, w, h);
		int y = PAD_TOP;

		// Compact puts the sack fraction up on the title line — it is the one number worth the top slot.
		y = renderHeader(g, w, y, "MLM", sack, null, true, snap);
		y += BAR_GAP;

		bar(g, PAD_X, y, w - PAD_X * 2, snap);
		y += BAR_H + BAR_GAP;

		// Chip row, evenly distributed across the panel width.
		int cw = (w - PAD_X * 2) / 3;
		chip(g, ItemID.MOTHERLODE_NUGGET, nug,        PAD_X,          y);
		chip(g, XP_BADGE,                 rates.xpHr, PAD_X + cw,     y);
		chip(g, ItemID.COINS_250,         rates.gpHr, PAD_X + cw * 2, y);

		return new Dimension(w, h);
	}

	// ════════════════════════════════════════════════════════════════════════
	// Expanded
	// ════════════════════════════════════════════════════════════════════════

	private Dimension renderExpanded(Graphics2D g, SessionSnapshot snap)
	{
		final boolean showOre   = config.overlayShowOre();
		final boolean showRates = config.overlayShowRates();
		final boolean showTimer = config.overlayShowTimer();

		final String runtime = formatClock(elapsed(snap.getStartTimeMs()));
		final String sack    = snap.getSackCount() + " / " + snap.getMaxSackSize();
		final String area    = areaLabel(snap.getMiningSpot());
		final String nug     = String.valueOf(snap.getGainedNuggets());
		final Rates  rates   = Rates.of(snap);
		final Timer  timer   = Timer.of(snap);

		final int[] ore = {
				snap.getRuniteCount(), snap.getAdamantiteCount(), snap.getMithrilCount(),
				snap.getGoldCount(), snap.getCoalCount()
		};

		// ── Measure ─────────────────────────────────────────────────────────
		int need = PAD_X + HEAD_ICON + ICON_GAP + width(g, F_TITLE, "MOTHERLODE MINE")
				+ COL_GAP + CHEVRON_W + PAD_X;
		need = Math.max(need, PAD_X + HEAD_ICON + ICON_GAP + DOT + 6
				+ width(g, F_LABEL, statusLabel(snap.getStatus())) + COL_GAP
				+ width(g, F_LABEL, runtime) + PAD_X);
		need = Math.max(need, labelValueWidth(g, ROW_ICON, "SACK", F_VALUE, sack));
		need = Math.max(need, PAD_X + ROW_ICON + ICON_GAP + width(g, F_VALUE, nug)
				+ width(g, F_LABEL, " nuggets") + COL_GAP + width(g, F_LABEL, area) + PAD_X);
		if (showOre)
		{
			need = Math.max(need, PAD_X + ROW_ICON + ICON_GAP + width(g, F_SECTION, "ORE COLLECTED") + PAD_X);
			for (int i = 0; i < ORE_IDS.length; i++)
			{
				need = Math.max(need, labelValueWidth(g, ROW_ICON, ORE_NAMES[i], F_VALUE, String.valueOf(ore[i])));
			}
		}
		if (showRates)
		{
			// Two equal halves, each holding icon + the wider of label/value.
			int half = ROW_ICON + ICON_GAP + Math.max(
					Math.max(width(g, F_SMALL, "XP/hr"), width(g, F_SMALL, "GP/hr")),
					Math.max(width(g, F_VALUE, rates.xpHr), width(g, F_VALUE, rates.gpHr)));
			need = Math.max(need, PAD_X + half * 2 + COL_GAP + PAD_X);
		}
		if (showTimer)
		{
			need = Math.max(need, labelValueWidth(g, ROW_ICON, timer.label, F_VALUE, timer.value));
		}
		final int w = clampWidth(need);

		int h = PAD_TOP + TITLE_H + STATUS_H + SEP_H
				+ ROW_H + BAR_GAP + BAR_H + BAR_GAP
				+ ROW_H;
		if (showOre)
		{
			h += SEP_H + SECTION_H + ORE_IDS.length * ORE_ROW_H;
		}
		if (showRates)
		{
			h += SEP_H + RATE_H;
		}
		if (showTimer)
		{
			h += SEP_H + ROW_H;
		}
		h += PAD_BOT;

		// ── Draw ────────────────────────────────────────────────────────────
		panel(g, w, h);
		int y = PAD_TOP;

		y = renderHeader(g, w, y, "MOTHERLODE MINE", null, runtime, false, snap);
		y = separator(g, w, y);
		y = renderSack(g, w, y, sack, nug, area, snap);
		if (showOre)
		{
			y = separator(g, w, y);
			y = renderOre(g, w, y, ore);
		}
		if (showRates)
		{
			y = separator(g, w, y);
			y = renderRates(g, w, y, rates);
		}
		if (showTimer)
		{
			y = separator(g, w, y);
			renderTimer(g, w, y, timer);
		}

		return new Dimension(w, h);
	}

	/**
	 * Pickaxe, title and collapse chevron on the first line; status dot and label on the second.
	 * Shared by both modes so the two can't drift apart — they differ only in which line carries a
	 * right-aligned figure ({@code titleRight} for compact's sack, {@code statusRight} for the
	 * expanded panel's runtime). Either may be null.
	 */
	private int renderHeader(Graphics2D g, int w, int y, String title,
							 String titleRight, String statusRight, boolean chevronDown,
							 SessionSnapshot snap)
	{
		int textX = PAD_X + HEAD_ICON + ICON_GAP;
		icon(g, ItemID.RUNE_PICKAXE, PAD_X, y + 1, HEAD_ICON);
		text(g, F_TITLE, GOLD, title, textX, y, TITLE_H);
		if (titleRight != null)
		{
			textRight(g, F_VALUE, TEXT, titleRight, w - PAD_X - CHEVRON_W, y, TITLE_H);
		}
		chevron(g, w - PAD_X, y + (TITLE_H - 5) / 2, chevronDown);
		y += TITLE_H;

		statusLine(g, snap, textX, y);
		if (statusRight != null)
		{
			textRight(g, F_LABEL, TEXT_MUTED, statusRight, w - PAD_X, y, STATUS_H);
		}
		return y + STATUS_H;
	}

	private int renderSack(Graphics2D g, int w, int y, String sack, String nug, String area, SessionSnapshot snap)
	{
		icon(g, ItemID.PAYDIRT, PAD_X, y + 1, ROW_ICON);
		text(g, F_SECTION, GOLD_DIM, "SACK", PAD_X + ROW_ICON + ICON_GAP, y, ROW_H);
		textRight(g, F_VALUE, TEXT, sack, w - PAD_X, y, ROW_H);
		y += ROW_H + BAR_GAP;

		bar(g, PAD_X, y, w - PAD_X * 2, snap);
		y += BAR_H + BAR_GAP;

		// Nuggets (gold, the MLM progression number) left; current area muted right.
		icon(g, ItemID.MOTHERLODE_NUGGET, PAD_X, y + 1, ROW_ICON);
		int nx = PAD_X + ROW_ICON + ICON_GAP;
		nx += text(g, F_VALUE, GOLD, nug, nx, y, ROW_H);
		text(g, F_LABEL, TEXT_MUTED, " nuggets", nx, y, ROW_H);
		int areaSpace = (w - PAD_X) - (nx + width(g, F_LABEL, " nuggets") + COL_GAP);
		textRight(g, F_LABEL, TEXT_MUTED, fit(g, F_LABEL, area, areaSpace), w - PAD_X, y, ROW_H);
		return y + ROW_H;
	}

	private int renderOre(Graphics2D g, int w, int y, int[] ore)
	{
		icon(g, ItemID.PAYDIRT, PAD_X, y, ROW_ICON);
		text(g, F_SECTION, GOLD, "ORE COLLECTED", PAD_X + ROW_ICON + ICON_GAP, y, SECTION_H);
		y += SECTION_H;

		for (int i = 0; i < ORE_IDS.length; i++)
		{
			boolean any = ore[i] > 0;
			icon(g, ORE_IDS[i], PAD_X, y + 1, ROW_ICON);
			text(g, F_LABEL, any ? TEXT : TEXT_OFF, ORE_NAMES[i], PAD_X + ROW_ICON + ICON_GAP, y, ORE_ROW_H);
			textRight(g, F_VALUE, any ? TEXT : TEXT_OFF, String.valueOf(ore[i]), w - PAD_X, y, ORE_ROW_H);
			y += ORE_ROW_H;
		}
		return y;
	}

	private int renderRates(Graphics2D g, int w, int y, Rates rates)
	{
		int half = (w - PAD_X * 2) / 2;

		xpBadge(g, PAD_X, y + (RATE_H - ROW_ICON) / 2, ROW_ICON);
		rateText(g, PAD_X + ROW_ICON + ICON_GAP, y, "XP/hr", rates.xpHr);

		int gx = PAD_X + half + COL_GAP / 2;
		icon(g, ItemID.COINS_250, gx, y + (RATE_H - ROW_ICON) / 2, ROW_ICON);
		rateText(g, gx + ROW_ICON + ICON_GAP, y, "GP/hr", rates.gpHr);

		// Hairline divider between the two halves.
		g.setColor(SEP);
		g.setStroke(new BasicStroke(1f));
		g.drawLine(PAD_X + half, y + 4, PAD_X + half, y + RATE_H - 4);

		return y + RATE_H;
	}

	private void renderTimer(Graphics2D g, int w, int y, Timer timer)
	{
		clockIcon(g, PAD_X, y + 1, ROW_ICON);
		int labelX = PAD_X + ROW_ICON + ICON_GAP;
		int labelSpace = (w - PAD_X) - labelX - COL_GAP - width(g, F_VALUE, timer.value);
		text(g, F_LABEL, TEXT_MUTED, fit(g, F_LABEL, timer.label, labelSpace), labelX, y, ROW_H);
		textRight(g, F_VALUE, timer.warn ? ST_REPAIR : TEAL, timer.value, w - PAD_X, y, ROW_H);
	}

	// ── Drawing primitives ──────────────────────────────────────────────────

	private void panel(Graphics2D g, int w, int h)
	{
		g.setColor(BG);
		g.fillRoundRect(0, 0, w, h, ARC, ARC);
		g.setColor(BORDER);
		g.setStroke(new BasicStroke(1f));
		g.drawRoundRect(0, 0, w - 1, h - 1, ARC, ARC);
	}

	private int separator(Graphics2D g, int w, int y)
	{
		int mid = y + SEP_H / 2;
		g.setColor(SEP);
		g.setStroke(new BasicStroke(1f));
		g.drawLine(PAD_X, mid, w - PAD_X, mid);
		return y + SEP_H;
	}

	private void statusLine(Graphics2D g, SessionSnapshot snap, int x, int y)
	{
		MLMStatus st = snap.getStatus();
		g.setColor(statusColor(st));
		g.fillOval(x, y + (STATUS_H - DOT) / 2, DOT, DOT);
		text(g, F_LABEL, TEXT, statusLabel(st), x + DOT + 6, y, STATUS_H);
	}

	private void bar(Graphics2D g, int x, int y, int w, SessionSnapshot snap)
	{
		int max = snap.getMaxSackSize();
		float pct = max > 0 ? Math.min(1f, Math.max(0f, (float) snap.getSackCount() / max)) : 0f;

		g.setColor(BAR_BG);
		g.fillRoundRect(x, y, w, BAR_H, BAR_H, BAR_H);

		int fill = Math.round(w * pct);
		if (fill > 0)
		{
			g.setColor(pct >= BAR_WARN_PCT ? BAR_FULL : BAR_FILL);
			// Never narrower than the cap radius, or the rounded ends render as a notch.
			g.fillRoundRect(x, y, Math.max(fill, BAR_H), BAR_H, BAR_H, BAR_H);
		}
	}

	/** Gap between a compact chip's icon and its value. */
	private static final int CHIP_GAP = 5;
	/** Sentinel icon id meaning "draw the XP lozenge" — RuneLite ships no XP item sprite. */
	private static final int XP_BADGE = -1;

	/** One compact chip: small icon + value. Used only by the compact panel. */
	private void chip(Graphics2D g, int itemId, String value, int x, int y)
	{
		if (itemId == XP_BADGE)
		{
			xpBadge(g, x, y, ROW_ICON);
		}
		else
		{
			icon(g, itemId, x, y, ROW_ICON);
		}
		text(g, F_VALUE, TEXT, value, x + ROW_ICON + CHIP_GAP, y, ROW_ICON);
	}

	private int chipWidth(Graphics2D g, String value)
	{
		return ROW_ICON + CHIP_GAP + width(g, F_VALUE, value);
	}

	private void rateText(Graphics2D g, int x, int y, String label, String value)
	{
		text(g, F_SMALL, TEXT_MUTED, label, x, y + 1, 13);
		text(g, F_VALUE, TEXT, value, x, y + 13, 17);
	}

	/** Draws text with its box top at {@code y}, vertically centred in {@code rowH}. Returns its width. */
	private int text(Graphics2D g, Font f, Color c, String s, int x, int y, int rowH)
	{
		g.setFont(f);
		FontMetrics fm = g.getFontMetrics();
		int baseline = y + (rowH + fm.getAscent() - fm.getDescent()) / 2;
		// Drop shadow first: the panel is translucent, so without it thin glyphs disappear against a
		// bright cave wall behind the overlay.
		g.setColor(SHADOW);
		g.drawString(s, x + 1, baseline + 1);
		g.setColor(c);
		g.drawString(s, x, baseline);
		return fm.stringWidth(s);
	}

	private void textRight(Graphics2D g, Font f, Color c, String s, int rightX, int y, int rowH)
	{
		g.setFont(f);
		text(g, f, c, s, rightX - g.getFontMetrics().stringWidth(s), y, rowH);
	}

	private int width(Graphics2D g, Font f, String s)
	{
		return g.getFontMetrics(f).stringWidth(s);
	}

	/** Width a row needs for: pad + icon + gap + label + column gap + value + pad. */
	private int labelValueWidth(Graphics2D g, int iconW, String label, Font valueFont, String value)
	{
		return PAD_X + iconW + ICON_GAP + width(g, F_LABEL, label) + COL_GAP + width(g, valueFont, value) + PAD_X;
	}

	/**
	 * Truncates {@code s} with an ellipsis so it fits {@code available} px. Only the two
	 * variable-length strings need it — the area name and the phase label — and only once the panel
	 * has already been clamped at {@link #MAX_W}. Everything else sizes the panel instead of clipping.
	 */
	private String fit(Graphics2D g, Font f, String s, int available)
	{
		FontMetrics fm = g.getFontMetrics(f);
		if (available <= 0 || fm.stringWidth(s) <= available)
		{
			return s;
		}
		int ell = fm.stringWidth("…");
		int end = s.length();
		while (end > 0 && fm.stringWidth(s.substring(0, end)) + ell > available)
		{
			end--;
		}
		return end <= 0 ? "…" : s.substring(0, end) + "…";
	}

	private static int clampWidth(int w)
	{
		return Math.max(MIN_W, Math.min(MAX_W, w));
	}

	private void icon(Graphics2D g, int itemId, int x, int y, int size)
	{
		BufferedImage img = itemImage(itemId);
		if (img != null)
		{
			g.drawImage(img, x, y, size, size, null);
		}
	}

	/** Small gold XP lozenge — RuneLite ships no XP item icon, so it is drawn rather than faked with text. */
	private void xpBadge(Graphics2D g, int x, int y, int size)
	{
		g.setColor(GOLD_DIM);
		g.setStroke(new BasicStroke(1f));
		g.drawRoundRect(x, y, size - 1, size - 1, 4, 4);
		g.setFont(F_SECTION);
		FontMetrics fm = g.getFontMetrics();
		g.setColor(GOLD);
		g.drawString("XP", x + (size - fm.stringWidth("XP")) / 2,
				y + (size + fm.getAscent() - fm.getDescent()) / 2);
	}

	/**
	 * Collapse/expand chevron in the header's top-right. Purely an affordance for the right-click
	 * entry {@link #syncMenu} installs — points up when expanded (click to fold away), down when
	 * compact (click to open out).
	 */
	private void chevron(Graphics2D g, int rightX, int y, boolean pointDown)
	{
		int w = 9;
		int h = 5;
		int x = rightX - w;
		Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(GOLD_DIM);
		if (pointDown)
		{
			g.drawLine(x, y, x + w / 2, y + h);
			g.drawLine(x + w / 2, y + h, x + w, y);
		}
		else
		{
			g.drawLine(x, y + h, x + w / 2, y);
			g.drawLine(x + w / 2, y, x + w, y + h);
		}
		g.setStroke(old);
	}

	private void clockIcon(Graphics2D g, int x, int y, int size)
	{
		Stroke old = g.getStroke();
		g.setColor(TEXT_MUTED);
		g.setStroke(new BasicStroke(1.2f));
		g.drawOval(x + 1, y + 1, size - 3, size - 3);
		int cx = x + size / 2;
		int cy = y + size / 2;
		g.drawLine(cx, cy, cx, cy - size / 4);
		g.drawLine(cx, cy, cx + size / 5, cy);
		g.setStroke(old);
	}

	private BufferedImage itemImage(int itemId)
	{
		BufferedImage cached = iconCache.get(itemId);
		if (cached != null)
		{
			return cached;
		}
		try
		{
			// Only successes are cached: before login ItemManager has no sprites yet, and a miss cached
			// here would leave the panel permanently iconless.
			BufferedImage img = itemManager.getImage(itemId, 1, false);
			if (img != null)
			{
				iconCache.put(itemId, img);
			}
			return img;
		}
		catch (Exception ignored)
		{
			return null;
		}
	}

	// ── Formatting ──────────────────────────────────────────────────────────

	/** Test seam: the XP/hr string the panel would draw for this snapshot. */
	static String xpPerHour(SessionSnapshot snap)
	{
		return Rates.of(snap).xpHr;
	}

	/** XP/hr and GP/hr, derived purely from snapshot fields. */
	private static final class Rates
	{
		final String xpHr;
		final String gpHr;

		private Rates(String xpHr, String gpHr)
		{
			this.xpHr = xpHr;
			this.gpHr = gpHr;
		}

		static Rates of(SessionSnapshot snap)
		{
			long runMs = elapsed(snap.getStartTimeMs());
			if (runMs <= 0)
			{
				return new Rates(NO_VALUE, NO_VALUE);
			}
			double perMs = 3_600_000.0 / runMs;
			String gp = abbreviate(Math.round(snap.getTotalValueGained() * perMs));

			// A zero startXp is not a baseline of zero — it means the script had not read Mining XP
			// yet (plugin started at the login screen). Subtracting it would report the account's
			// whole Mining XP as this session's gain, which then visibly counts *down* as runtime
			// grows. Show nothing until a real baseline exists.
			if (snap.getStartXp() <= 0)
			{
				return new Rates(NO_VALUE, gp);
			}
			int gainedXp = Math.max(0, snap.getCurrentXp() - snap.getStartXp());
			return new Rates(abbreviate(Math.round(gainedXp * perMs)), gp);
		}
	}

	/**
	 * The footer timer. MLM veins collapse on a random per-ore roll rather than a respawn timer, so
	 * there is no honest "time to next vein" to count down. While mining we show time since the last
	 * ore instead (the same signal the script's own stall watchdog uses); otherwise how long the
	 * current phase has been running.
	 */
	private static final class Timer
	{
		final String label;
		final String value;
		final boolean warn;

		private Timer(String label, String value, boolean warn)
		{
			this.label = label;
			this.value = value;
			this.warn = warn;
		}

		static Timer of(SessionSnapshot snap)
		{
			if (snap.getStatus() == MLMStatus.MINING)
			{
				if (snap.getLastOreMs() <= 0)
				{
					return new Timer("Last ore", NO_VALUE, false);
				}
				long since = elapsed(snap.getLastOreMs());
				return new Timer("Last ore", formatShort(since), since >= STALL_WARN_MS);
			}
			String phase = snap.getSubStateLabel();
			return new Timer(
					phase == null || phase.isEmpty() ? "Phase" : phase,
					formatShort(elapsed(snap.getStatusEnteredMs())),
					false);
		}
	}

	private static long elapsed(long sinceMs)
	{
		return sinceMs > 0 ? Math.max(0L, System.currentTimeMillis() - sinceMs) : 0L;
	}

	static String areaLabel(MLMMiningSpot spot)
	{
		if (spot == null)
		{
			return NO_VALUE;
		}
		String[] parts = spot.name().split("_");
		StringBuilder sb = new StringBuilder(spot.name().length());
		for (String part : parts)
		{
			if (part.isEmpty())
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(' ');
			}
			sb.append(part.charAt(0)).append(part.substring(1).toLowerCase());
		}
		return sb.toString();
	}

	static String formatClock(long ms)
	{
		long h = TimeUnit.MILLISECONDS.toHours(ms);
		long m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60;
		long s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60;
		return h > 0
				? String.format("%d:%02d:%02d", h, m, s)
				: String.format("%02d:%02d", m, s);
	}

	static String formatShort(long ms)
	{
		long total = TimeUnit.MILLISECONDS.toSeconds(ms);
		long m = total / 60;
		long s = total % 60;
		return m > 0 ? String.format("%dm %02ds", m, s) : s + "s";
	}

	static String abbreviate(long n)
	{
		if (n >= 1_000_000L)
		{
			return scaled(n, 1_000_000.0, "M");
		}
		if (n >= 1_000L)
		{
			return scaled(n, 1_000.0, "K");
		}
		return String.valueOf(n);
	}

	/**
	 * One decimal below ten units, none at or above, so the rate column never grows past five
	 * characters. The rounding is checked <em>after</em> scaling on purpose: 9,950 rounds up to ten
	 * thousand, and must render "10K" rather than the wider, off-pattern "10.0K".
	 */
	private static String scaled(long n, double unit, String suffix)
	{
		double v = n / unit;
		return Math.round(v * 10) >= 100
				? Math.round(v) + suffix
				: String.format("%.1f%s", v, suffix);
	}

	private static Color statusColor(MLMStatus s)
	{
		if (s == null)
		{
			return ST_IDLE;
		}
		switch (s)
		{
			case MINING:
				return ST_GO;
			case DEPOSIT_HOPPER:
			case EMPTY_SACK:
			case DROP_GEMS:
				return ST_WORK;
			case FIXING_WATERWHEEL:
			case WAITING_FOR_REPAIR:
				return ST_REPAIR;
			case RECOVERY:
				return ST_BAD;
			default:
				return ST_IDLE;
		}
	}

	private static String statusLabel(MLMStatus s)
	{
		if (s == null)
		{
			return "Idle";
		}
		switch (s)
		{
			case MINING:
				return "Mining";
			case DEPOSIT_HOPPER:
				return "Depositing";
			case EMPTY_SACK:
				return "Emptying sack";
			case FIXING_WATERWHEEL:
				return "Fixing wheel";
			case WAITING_FOR_REPAIR:
				return "Waiting";
			case DROP_GEMS:
				return "Dropping gems";
			case RECOVERY:
				return "Recovering";
			default:
				return "Idle";
		}
	}
}
