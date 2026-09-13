package net.runelite.client.plugins.microbot.irkedmlm;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigInformation;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.plugins.microbot.irkedmlm.enums.AfkParkSide;
import net.runelite.client.plugins.microbot.irkedmlm.enums.DepositMethod;
import net.runelite.client.plugins.microbot.irkedmlm.enums.MLMMiningSpot;
import net.runelite.client.plugins.microbot.irkedmlm.enums.MLMSackSize;
import net.runelite.client.plugins.microbot.irkedmlm.enums.MouseActivity;

@ConfigGroup(IrkedMLMConfig.configGroup)
@ConfigInformation(
		"Motherlode Mine. Sections are grouped by activity: General, Mining, Gem Bag, Hopper &amp; Sack, " +
		"Repair, Deposit, Humanization, and Advanced. Each option's description says exactly when it applies."
)
public interface IrkedMLMConfig extends Config
{
	String configGroup = "micro-irkedmlm";

	String depositMethod = "depositMethod";
	String antiCrash = "antiCrash";
	String dropGems = "dropGems";
	String useUpstairsHopper = "useUpstairsHopper";
	String miningArea = "miningArea";
	String sackSize = "sackSize";
	String debugMode = "debugMode";
	String showMiningAreas = "showMiningAreas";
	String useGemBag = "useGemBag";
	String enableHumanLikeBehavior = "enableHumanLikeBehavior";
	String afkParkSide = "afkParkSide";
	String mouseActivity = "mouseActivity";
	String useImcandoHammer = "useImcandoHammer";
	String waitForOthersToRepair = "waitForOthersToRepair";
	String hopWhenFrozen = "hopWhenFrozen";
	String frozenHopGraceSeconds = "frozenHopGraceSeconds";
	String repairStruts = "repairStruts";
	String overlayCompact = "overlayCompact";
	String overlayShowOre = "overlayShowOre";
	String overlayShowRates = "overlayShowRates";
	String overlayShowTimer = "overlayShowTimer";

	@ConfigSection(name = "General",       description = "Mining location, floor, and core behaviour", position = 0)
	String generalSection = "general";

	@ConfigSection(name = "Mining",        description = "Vein selection and anti-crash behaviour",     position = 1)
	String miningSection = "mining";

	@ConfigSection(name = "Gem Bag",       description = "Gem bag handling",                            position = 2)
	String gemBagSection = "gembag";

	@ConfigSection(name = "Hopper & Sack", description = "Pay-dirt sack capacity and hopper choice",    position = 3)
	String hopperSackSection = "hoppersack";

	@ConfigSection(name = "Repair",        description = "Water-wheel repair and hammer preference",    position = 4)
	String repairSection = "repair";

	@ConfigSection(name = "Deposit",       description = "How ores are banked",                         position = 5)
	String depositSection = "deposit";

	@ConfigSection(
			name = "Humanization",
			description = "Master toggle for the MLM-specific human layer: randomized pauses, varied mouse behaviour " +
					"(occasional off-screen AFK, hovering the next vein as one nears depletion, non-centred click points), " +
					"spot jitter, hesitation, and a delayed/randomized pickaxe special. Separate from the global Rs2Antiban plugin. " +
					"Turn OFF for maximum speed. Full details in the plugin's docs/README.md.",
			position = 6
	)
	String humanSection = "humanization";

	@ConfigSection(name = "Overlay",       description = "What the session panel shows",                position = 7)
	String overlaySection = "overlay";

	// Debug Mode and Show Mining Areas are developer tooling and stay hidden from the config panel.
	// The code paths remain live — set them in the config file, or drop `hidden = true`, to use the
	// on-map area/reachability view when checking spot geometry against the real mine.

	// General
	@ConfigItem(
			keyName = miningArea,
			name = "Mining Area",
			description = "Primary mining location.<br/>" +
					"Upper spots (WEST_UPPER, EAST_UPPER) use strict hand-defined tile meshes for wall veins in the small chambers. " +
					"Lower spots use larger WorldAreas. Anti-crash logic only applies on the lower level.",
			position = 0,
			section = generalSection
	)
	default MLMMiningSpot miningArea()
	{
		return MLMMiningSpot.WEST_LOWER;
	}

	// Mining
	@ConfigItem(
			keyName = antiCrash,
			name = "Anti Crash",
			description = "On the lower level only: if another player is standing on/very near the chosen vein, reselect a different nearby vein or shuffle to the spot anchor. " +
					"Helps avoid reports and looks more human. Has no effect on upper floor (space is too tight).",
			position = 0,
			section = miningSection
	)
	default boolean useAntiCrash()
	{
		return false;
	}

	// Gem Bag
	@ConfigItem(
			keyName = useGemBag,
			name = "Use Gem Bag",
			description = "If you have a gem bag, the bot withdraws it (if needed), keeps it locked in inventory slot 1 " +
					"so Deposit All can never bank it, opens it, and empties it at the deposit box when full (~60 gems).<br/>" +
					"Emptying only happens during the EMPTY_SACK phase (once per sack cycle). Uses the game's " +
					"'Lock inventory slots' feature (enabled automatically).",
			position = 0,
			section = gemBagSection
	)
	default boolean useGemBag()
	{
		return false;
	}

	@ConfigItem(
			keyName = dropGems,
			name = "Drop Gems",
			description = "Drop uncut gems (sapphire/emerald/ruby/diamond) as soon as they are mined while still at the vein.<br/>" +
					"Does not affect pay-dirt or ores. Use together with 'Use Gem Bag' if you want to keep gems via the bag instead.",
			position = 1,
			section = gemBagSection
	)
	default boolean dropGems()
	{
		return false;
	}

	// Hopper & Sack
	@ConfigItem(
			keyName = sackSize,
			name = "Sack Size",
			description = "Your current pay-dirt sack capacity.<br/>" +
					"STANDARD = 108, UPGRADED = 189 (after spending 200 nuggets on the upgrade).<br/>" +
					"The plugin auto-detects increases during a run, but this sets the initial assumption and is used for projections.",
			position = 0,
			section = hopperSackSection
	)
	default MLMSackSize sackSize()
	{
		return MLMSackSize.STANDARD;
	}

	@ConfigItem(
			keyName = useUpstairsHopper,
			name = "Use Upstairs Hopper",
			description = "Use the hopper on the upper floor (saves a lot of running when unlocked).<br/>" +
					"Requires 57 Mining + 100 nuggets to unlock the ladder, plus another ~50 nuggets for the upstairs hopper access itself.<br/>" +
					"<b>Important:</b> even with this on, the bot will still climb down to empty the sack and to repair water wheels (repairs are downstairs only).",
			position = 1,
			section = hopperSackSection
	)
	default boolean upstairsHopperUnlocked()
	{
		return false;
	}

	// Repair
	@ConfigItem(
			keyName = repairStruts,
			name = "Repair Struts",
			description = "ON: repair the broken water-wheel struts yourself, but only on a deposit trip — right after " +
					"emptying pay-dirt into the hopper, when the wheel is stopped and no other player is already fixing it. " +
					"The bot won't break off mining, banking, or recovery just to run to the wheel.<br/>" +
					"OFF: the bot never repairs. When both wheels break, pay-dirt stops processing — see 'Wait for Others to " +
					"Repair' below to park and wait for another player (and optionally hop worlds) instead of stalling.",
			position = 0,
			section = repairSection
	)
	default boolean repairStruts()
	{
		return true;
	}

	@ConfigItem(
			keyName = useImcandoHammer,
			name = "Use Imcando Hammer",
			description = "Only applies when 'Repair Struts' is ON. You are supplying your own Imcando hammer (main-hand or off-hand variant), equipped or in your inventory.<br/>" +
					"ON: the bot repairs struts with your Imcando hammer — it never walks to the supply crate for a hammer, and never drops it.<br/>" +
					"OFF: the bot fetches a regular hammer from the supply crate for each repair and drops it afterwards.",
			position = 1,
			section = repairSection
	)
	default boolean useImcandoHammer()
	{
		return false;
	}

	@ConfigItem(
			keyName = waitForOthersToRepair,
			name = "Wait for Others to Repair",
			description = "Don't repair over another player already stood at the water wheel — wait and let them finish, rather than both of you clicking the same struts. If they turn out to be idle the job is reclaimed after a short randomised patience window, so a bystander can never leave the bot stuck.",
			position = 2,
			section = repairSection
	)
	default boolean waitForOthersToRepair()
	{
		return true;
	}

	@ConfigItem(
			keyName = hopWhenFrozen,
			name = "Hop World When Frozen",
			description = "Only applies when 'Wait for Others to Repair' is ON. If the wheel stays frozen past the grace period below, hop to " +
					"the most-populated accessible world (your pay-dirt progress is saved across hops, and busy worlds get struts " +
					"fixed fastest).<br/>" +
					"OFF: wait indefinitely on the current world until someone repairs it.",
			position = 3,
			section = repairSection
	)
	default boolean hopWhenFrozen()
	{
		return false;
	}

	@ConfigItem(
			keyName = frozenHopGraceSeconds,
			name = "Frozen Grace (seconds)",
			description = "Only applies when 'Hop World When Frozen' is ON. How long to wait for another player to repair before hopping worlds. " +
					"Give busy worlds a short wait; raise it if you prefer to give players more time before hopping.",
			position = 4,
			section = repairSection
	)
	default int frozenHopGraceSeconds()
	{
		return 120;
	}

	// Deposit
	@ConfigItem(
			keyName = depositMethod,
			name = "Deposit Method",
			description = "How ores are banked at the deposit box after emptying the sack.<br/>" +
					"DEPOSIT ITEMS: deposits each ore type individually — always safe, never touches the gem bag. " +
					"Recommended if you don't lock inventory slots.<br/>" +
					"DEPOSIT ALL: uses the deposit box 'Deposit All' button. With 'Use Gem Bag' on, the bot locks the bag " +
					"in slot 1 so it is protected and excluded from Deposit All. Pay-dirt is never sent.",
			position = 0,
			section = depositSection
	)
	default DepositMethod depositMethod()
	{
		return DepositMethod.ITEMS;
	}

	// Humanization
	@ConfigItem(
			keyName = enableHumanLikeBehavior,
			name = "Human-like behavior",
			description = "ON: randomized waits, mixed mouse behaviour (off-screen AFK, hover the next vein near depletion, " +
					"non-centred click points), spot jitter, hesitation, 1-strut 90% skip, and a delayed/randomized special attack. " +
					"OFF: FAST — minimal delays and optimal choices, but click points stay randomized so it isn't pixel-perfect. " +
					"Distinct from the global Rs2Antiban toggle.",
			position = 0,
			section = humanSection
	)
	default boolean enableHumanLikeBehavior()
	{
		return true;
	}

	@ConfigItem(
			keyName = afkParkSide,
			name = "AFK Park Side",
			description = "Only applies when Human-like behavior is ON. Which edge the mouse parks off (and returns from) during off-screen AFK — " +
					"where your attention actually goes while the skill ticks over.<br/>" +
					"None: never leave the client. The cursor rests on-screen where the last click left it.<br/>" +
					"Left / Right: a second monitor beside you — the cursor only ever crosses that edge.<br/>" +
					"Top / Bottom: a monitor stacked above, or a browser/chat/taskbar below the client.<br/>" +
					"Random: pick one edge per login and keep it consistent for the whole session.",
			position = 1,
			section = humanSection
	)
	default AfkParkSide afkParkSide()
	{
		return AfkParkSide.RANDOM;
	}

	@ConfigItem(
			keyName = mouseActivity,
			name = "Mouse Activity",
			description = "Only applies when Human-like behavior is ON. What the cursor does during a mining bout.<br/>" +
					"AFK: click the vein, park off-screen, nothing else — no hovering, no inventory checks. Best for MLM.<br/>" +
					"Balanced: parks most bouts; the ones spent on-screen pre-hover the next vein and occasionally check the inventory.<br/>" +
					"Active: seldom parks, and is busy while watching — hovers the next vein and checks the inventory far more often.<br/>" +
					"With AFK Park Side set to None, the \"park\" outcome keeps the cursor in the window instead.",
			position = 2,
			section = humanSection
	)
	default MouseActivity mouseActivity()
	{
		return MouseActivity.AFK;
	}

	// Overlay
	@ConfigItem(
			keyName = overlayCompact,
			name = "Compact Mode",
			description = "Shrinks the session panel to the at-a-glance essentials: status, sack progress, nuggets and the XP/hr + GP/hr rates. "
					+ "Ore totals, area and the phase timer are hidden regardless of the toggles below.",
			position = 0,
			section = overlaySection
	)
	default boolean overlayCompact()
	{
		return false;
	}

	@ConfigItem(
			keyName = overlayShowOre,
			name = "Show Ore Totals",
			description = "Show the per-ore session totals (runite, adamantite, mithril, gold, coal) with item icons.",
			position = 1,
			section = overlaySection
	)
	default boolean overlayShowOre()
	{
		return true;
	}

	@ConfigItem(
			keyName = overlayShowRates,
			name = "Show Performance",
			description = "Show the XP/hr and GP/hr session rates.",
			position = 2,
			section = overlaySection
	)
	default boolean overlayShowRates()
	{
		return true;
	}

	@ConfigItem(
			keyName = overlayShowTimer,
			name = "Show Phase Timer",
			description = "Show the footer timer: while mining it counts up since the last ore (a stalled bot is obvious at a glance); "
					+ "otherwise it counts how long the current phase has been running.",
			position = 3,
			section = overlaySection
	)
	default boolean overlayShowTimer()
	{
		return true;
	}

	// Advanced
	@ConfigItem(
			keyName = debugMode,
			name = "Debug Mode",
			description = "Enables rich console logging of status transitions, post-deposit routing decisions, human pauses/jitter, " +
					"vein selection, sack projections, repair gating, anti-crash, and session sub-states. " +
					"Essential for understanding why the bot did (or didn't) do something.",
			position = 0,
			hidden = true
	)
	default boolean debugMode()
	{
		return false;
	}

	@ConfigItem(
			keyName = showMiningAreas,
			name = "Show Mining Areas",
			description = "Draw the configured spot on the game map: green = selectable zone, blue = tiles the bot believes it can walk to right now, red = rockfall-blocked (static), orange = rockfalls learned this session.",
			position = 1,
			hidden = true
	)
	default boolean showMiningAreas()
	{
		return false;
	}

}
