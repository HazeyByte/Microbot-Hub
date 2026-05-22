/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.runelite.client.config.Config
 *  net.runelite.client.config.ConfigGroup
 *  net.runelite.client.config.ConfigInformation
 *  net.runelite.client.config.ConfigItem
 *  net.runelite.client.config.ConfigSection
 *  net.runelite.client.plugins.microbot.util.inventory.InteractOrder
 */
package net.runelite.client.plugins.microbot.irkedminer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigInformation;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.plugins.microbot.irkedminer.data.Ores;
import net.runelite.client.plugins.microbot.irkedminer.priority.BatchTierOption;
import net.runelite.client.plugins.microbot.util.inventory.InteractOrder;

@ConfigGroup(value="IrkedMiner")
@ConfigInformation(value="<div style='margin:0;padding:0;font-family:sans-serif;line-height:1.35;'><div style='color:#ffc864;font-size:16px;font-weight:bold;'>IrkedMiner</div><div style='color:#cdbb95;margin:1px 0 6px 0;'>v1.1.2</div><div style='border-top:1px solid #4f4636;margin:0 0 6px 0;'></div><div style='color:#ffb347;font-weight:bold;margin:0 0 2px 0;'>Quick Setup</div><div style='color:#ddd2be;'>&bull; Start near your rocks.<br/>&bull; Set <b>Target Ore</b> + <b>Distance to Stray</b> (normal/powermine).<br/>&bull; Enable <b>Batch Mining</b> to use Priority order instead of Target Ore.<br/>&bull; Choose inventory behavior (Bank / Deposit Box / Drop).</div><div style='margin-top:6px;color:#c9bd9e;'><b style='color:#9ef2be;'>Batch Mining Rules:(Currently only tested on Runite and Adamantite)</b><br/>1) Priority 1 &amp; Priority 2 are required and should be different.<br/>2) Priority 3+ can be blank.<br/>3) The script locks to the first available Priority and mines it until exhausted, then advances downward.<br/>4) After a successful bank, it restarts from Priority 1.</div></div>")
public interface IrkedMinerConfig
extends Config {
    @ConfigSection(name="Mining", description="Mining target and range", position=0)
    public static final String miningSection = "miningSection";
    @ConfigSection(name="Batch Mining", description="Priority-based ore selection (overrides Target Ore)", position=1)
    public static final String batchSection = "batchSection";
    @ConfigSection(name="Inventory", description="Drop and bank behavior", position=2)
    public static final String inventorySection = "inventorySection";
    @ConfigSection(name="World", description="World hop behavior", position=3)
    public static final String worldSection = "worldSection";
    @ConfigSection(name="Debug", description="Debug options", position=4)
    public static final String debugSection = "debugSection";

    @ConfigItem(keyName="targetOre", name="Target Ore", description="Used for normal mining / powermining. Ignored when Batch Mining is enabled.", position=0, section="miningSection")
    default public Ores targetOre() {
        return Ores.IRON;
    }

    @ConfigItem(keyName="distanceToStray", name="Distance to Stray", description="Max distance from your start tile to search for rocks (applies to all modes).", position=1, section="miningSection")
    default public int distanceToStray() {
        return 20;
    }

    @ConfigItem(keyName="enablePriorityMining", name="Enable Batch Mining", description="Overrides Target Ore. Locks to the first available Priority and mines it until exhausted, then advances downward. After banking, it restarts from Priority 1. Priority 1 and Priority 2 are required and should be different.", position=0, section="batchSection")
    default public boolean enablePriorityMining() {
        return false;
    }

    @ConfigItem(keyName="batchTier1", name="Priority 1 Ore (Required)", description="Highest priority ore. Must be set and should differ from Priority 2.", position=1, section="batchSection")
    default public BatchTierOption batchTier1() {
        return BatchTierOption.RUNITE;
    }

    @ConfigItem(keyName="batchTier2", name="Priority 2 Ore (Required)", description="Second priority ore. Must be set and should differ from Priority 1.", position=2, section="batchSection")
    default public BatchTierOption batchTier2() {
        return BatchTierOption.ADAMANTITE;
    }

    @ConfigItem(keyName="batchTier3", name="Priority 3 Ore (Optional)", description="Optional third priority. Can be None. Duplicates are ignored.", position=3, section="batchSection")
    default public BatchTierOption batchTier3() {
        return BatchTierOption.NONE;
    }

    @ConfigItem(keyName="batchTier4", name="Priority 4 Ore (Optional)", description="Optional final priority. Can be None. Duplicates are ignored.", position=4, section="batchSection")
    default public BatchTierOption batchTier4() {
        return BatchTierOption.NONE;
    }

    @ConfigItem(keyName="useBank", name="Use Bank", description="Bank when inventory is full. If enabled, Deposit Box should be off.", position=0, section="inventorySection")
    default public boolean useBank() {
        return false;
    }

    @ConfigItem(keyName="useDepositBox", name="Use Deposit Box", description="Use a deposit box only. If enabled, Bank should be off.", position=1, section="inventorySection")
    default public boolean useDepositBox() {
        return false;
    }

    @ConfigItem(keyName="dropUncutGems", name="Drop Uncut Gems", description="When dropping, also drop uncut gems if inventory is full.", position=2, section="inventorySection")
    default public boolean dropUncutGems() {
        return true;
    }

    @ConfigItem(keyName="itemsToKeep", name="Items to Keep", description="Comma-separated item names to never drop.", position=3, section="inventorySection")
    default public String itemsToKeep() {
        return "pickaxe";
    }

    @ConfigItem(keyName="interactOrder", name="Drop Order", description="Order used when dropping items.", position=4, section="inventorySection")
    default public InteractOrder interactOrder() {
        return InteractOrder.STANDARD;
    }

    @ConfigItem(keyName="enableWorldHopping", name="Enable World Hopping", description="Hop when rocks are depleted or occupied (behavior depends on script logic).", position=0, section="worldSection")
    default public boolean enableWorldHopping() {
        return true;
    }

    @ConfigItem(keyName="enableDebugLogging", name="Enable Debug Logging", description="Enable detailed logs for troubleshooting.", position=0, section="debugSection")
    default public boolean enableDebugLogging() {
        return false;
    }
}

