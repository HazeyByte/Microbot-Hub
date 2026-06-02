package net.runelite.client.plugins.microbot.motherloadmine;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigInformation;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.plugins.microbot.motherloadmine.enums.MLMMiningSpot;
import net.runelite.client.plugins.microbot.motherloadmine.enums.MLMSackSize;

@ConfigGroup(MotherloadMineConfig.configGroup)
@ConfigInformation(
		"• This plugin will automate mining in motherload mine <br />" +
				"• If using deposit all feature, <b>ensure you lock the slots you wish to keep in inventory</b> <br />" +
				"• Start near the bank chest in motherload mine <br />"
)
public interface MotherloadMineConfig extends Config
{
	String configGroup = "micro-motherloadmine";

	String useDepositAll = "useDepositAll";
	String antiCrash = "antiCrash";
	String dropGems = "dropGems";
	String useUpstairsHopper = "useUpstairsHopper";
	String miningArea = "miningArea";
	String debugMode = "debugMode";
	String useGemBag = "useGemBag";

	@ConfigSection(
			name = "General",
			description = "General Plugin Settings",
			position = 0
	)
	String generalSection = "general";

	@ConfigSection(
			name = "Features",
			description = "Feature Settings",
			position = 1
	)
	String featureSection = "features";

	@ConfigItem(
			keyName = useDepositAll,
			name = "Use Deposit All",
			description = "Uses deposit all button in the deposit box<br>" +
					"Note: ensure you enable locked slots enabled for the items you want to keep in your inventory",
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
			description = "Avoids other players when mining in the lower level",
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
			description = "Automatically drop gems while mining",
			position = 2,
			section = generalSection
	)
	default boolean dropGems()
	{
		return false;
	}

	@ConfigItem(
			keyName = debugMode,
			name = "Debug Mode",
			description = "Enables verbose debug logging to the RuneLite console",
			position = 3,
			section = generalSection
	)
	default boolean debugMode()
	{
		return false;
	}

	// Upstairs hopper unlocked
	@ConfigItem(
			keyName = useUpstairsHopper,
			name = "Use Upstairs Hopper",
			description = "Should the plugin use the upstairs hopper",
			position = 0,
			section = featureSection
	)
	default boolean upstairsHopperUnlocked()
	{
		return false;
	}

	// Mining Area Selection
	@ConfigItem(
			keyName = miningArea,
			name = "Mining Area",
			description = "Choose the specific area to mine in Motherload Mine",
			position = 1,
			section = featureSection
	)
	default MLMMiningSpot miningArea()
	{
		return MLMMiningSpot.WEST_LOWER;
	}

	@ConfigItem(
			keyName = "sackSize",
			name = "Sack Size",
			description = "Select your sack capacity.",
			position = 2,
			section = featureSection
	)
	default MLMSackSize sackSize()
	{
		return MLMSackSize.STANDARD;
	}

	@ConfigItem(
			keyName = useGemBag,
			name = "Use Gem Bag",
			description = "Empty gem bag at deposit box when full (60 gems of any type)",
			position = 3,
			section = featureSection
	)
	default boolean useGemBag()
	{
		return false;
	}
}