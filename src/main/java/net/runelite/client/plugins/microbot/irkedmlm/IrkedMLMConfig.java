package net.runelite.client.plugins.microbot.irkedmlm;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigInformation;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.plugins.microbot.irkedmlm.enums.MLMMiningSpot;
import net.runelite.client.plugins.microbot.irkedmlm.enums.MLMSackSize;

@ConfigGroup(IrkedMLMConfig.configGroup)
@ConfigInformation(
		"Motherload Mine. Use the Core Settings below to configure (mining area, sack size, etc.). " +
		"Full priority rules, repair/sack logic, and humanization."
)
public interface IrkedMLMConfig extends Config
{
	String configGroup = "micro-motherloadmine";

	String useDepositAll = "useDepositAll";
	String antiCrash = "antiCrash";
	String dropGems = "dropGems";
	String useUpstairsHopper = "useUpstairsHopper";
	String miningArea = "miningArea";
	String sackSize = "sackSize";
	String debugMode = "debugMode";
	String showMiningAreas = "showMiningAreas";
	String useGemBag = "useGemBag";
	String enableHumanLikeBehavior = "enableHumanLikeBehavior";

	@ConfigSection(
			name = "Core Settings",
			description = "Essential choices that change how the bot behaves (mining location, sack capacity, debug visibility)",
			position = 0
	)
	String coreSection = "core";

	@ConfigSection(
			name = "General",
			description = "Quality-of-life and safety toggles",
			position = 1
	)
	String generalSection = "general";

	@ConfigSection(
			name = "Features",
			description = "Optional unlocks and advanced behavior",
			position = 2
	)
	String featureSection = "features";

	@ConfigSection(
			name = "Humanization",
			description = "Master toggle for MLM-specific human-like behavior (our custom pauses, jitter, attention variation, imperfection). " +
					"Separate from the global Rs2Antiban plugin (you can disable that globally if desired). " +
					"When enabled: 'sometimes fast (full-attention quick reaction) sometimes not (relaxed full random)' is baked naturally; urgent situations (sack full at start, will-fill post-deposit, repair needed after deposit) bias toward quicker responses. " +
					"When disabled: no script pauses, faster tick rate, minimal session throttles, no antiban action-cooldown blocking + optimal choices (best vein, repair even 1 strut, exact 5 min spot refresh with no jitter, no extra glances/admire/pre-deposit, etc.). " +
					"Full list of gated behaviors and exact rules in the plugin's docs/README.md.",
			position = 3

	)
	String humanSection = "humanization";

	// Core Settings (prominent — these have the biggest impact on behavior)
	@ConfigItem(
			keyName = miningArea,
			name = "Mining Area",
			description = "Primary mining location.<br/>" +
					"Upper spots (WEST_UPPER, EAST_UPPER) use strict hand-defined tile meshes for wall veins in the small chambers. " +
					"Lower spots use larger WorldAreas. Anti-crash logic only applies on the lower level.",
			position = 0,
			section = coreSection
	)
	default MLMMiningSpot miningArea()
	{
		return MLMMiningSpot.WEST_LOWER;
	}

	@ConfigItem(
			keyName = sackSize,
			name = "Sack Size",
			description = "Your current pay-dirt sack capacity.<br/>" +
					"STANDARD = 108, UPGRADED = 189 (after spending 200 nuggets on the upgrade).<br/>" +
					"The plugin auto-detects increases during a run, but this sets the initial assumption and is used for projections.",
			position = 1,
			section = coreSection
	)
	default MLMSackSize sackSize()
	{
		return MLMSackSize.STANDARD;
	}

	@ConfigItem(
			keyName = debugMode,
			name = "Debug Mode",
			description = "Enables rich console logging of status transitions, post-deposit routing decisions, human pauses/jitter, " +
					"vein selection, sack projections, repair gating, anti-crash, and session sub-states. " +
					"Essential for understanding why the bot did (or didn't) do something.",
			position = 2,
			section = coreSection
	)
	default boolean debugMode()
	{
		return false;
	}

	@ConfigItem(
			keyName = showMiningAreas,
			name = "Show Mining Areas",
			description = "Draw selectable zone tiles (green) and rockfall-blocked tiles (red = static map, orange = session memory) on the game map for the configured/active mining spot.",
			position = 3,
			section = coreSection
	)
	default boolean showMiningAreas()
	{
		return false;
	}

	@ConfigItem(
			keyName = useUpstairsHopper,
			name = "Use Upstairs Hopper",
			description = "Use the hopper on the upper floor (saves a lot of running when unlocked).<br/>" +
					"Requires 57 Mining + 100 nuggets to unlock the ladder, plus another ~50 nuggets for the upstairs hopper access itself.<br/>" +
					"<b>Important:</b> even with this on, the bot will still climb down to empty the sack and to repair water wheels (repairs are downstairs only).",
			position = 4,
			section = coreSection
	)
	default boolean upstairsHopperUnlocked()
	{
		return false;
	}

	// General (QoL / safety)
	@ConfigItem(
			keyName = useDepositAll,
			name = "Use Deposit All",
			description = "Use the 'Deposit All' button at the deposit box after emptying the sack (instead of depositing individual ores).<br/>" +
					"<b>Lock the inventory slots</b> for anything you want to keep (e.g. your pickaxe, gems you don't want to drop, etc.). " +
					"Pay-dirt is never sent to the deposit box.",
			position = 0,
			section = generalSection
	)
	default boolean useDepositAll()
	{
		return false;
	}

	@ConfigItem(
			keyName = antiCrash,
			name = "Anti Crash",
			description = "On the lower level only: if another player is standing on/very near the chosen vein, reselect a different nearby vein or shuffle to the spot anchor. " +
					"Helps avoid reports and looks more human. Has no effect on upper floor (space is too tight).",
			position = 1,
			section = generalSection
	)
	default boolean useAntiCrash()
	{
		return false;
	}

	@ConfigItem(
			keyName = dropGems,
			name = "Drop Gems",
			description = "Drop uncut gems (sapphire/emerald/ruby/diamond) as soon as they are mined while still at the vein.<br/>" +
					"Does not affect pay-dirt or ores. Use together with 'Use Gem Bag' if you want to keep gems via the bag instead.",
			position = 2,
			section = generalSection
	)
	default boolean dropGems()
	{
		return false;
	}

	@ConfigItem(
			keyName = useGemBag,
			name = "Use Gem Bag",
			description = "If you have a gem bag, empty it at the deposit box when it is full (~60 gems total).<br/>" +
					"Only happens during the EMPTY_SACK phase (once per sack cycle). Only uncut gems are considered for the bag.",
			position = 3,
			section = generalSection
	)
	default boolean useGemBag()
	{
		return false;
	}

	// Humanization (master toggle for our custom likeness layer)
	@ConfigItem(
			keyName = enableHumanLikeBehavior,
			name = "Human-like behavior",
			description = "Enable our MLM-specific human likeness (random waits, occasional imperfection/hesitation/glances, spot jitter, 1-strut 90% skip chance, post-repair admire, pre-deposit extra, etc.). " +
					"The variation ('sometimes fast sometimes not') and urgent bias are natural to the implementation — no separate % slider. " +
					"Turn off for small consistent delays + optimal choices (still has life, not robotic). Distinct from global Rs2Antiban toggle.",
			position = 0,
			section = humanSection
	)
	default boolean enableHumanLikeBehavior()
	{
		return true;
	}

}
