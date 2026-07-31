package net.runelite.client.plugins.microbot.irkedfarmer.model;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.irkedfarmer.IrkedFarmerConfig;

/**
 * Common shape shared by patch categories that carry an individual config toggle. Only {@link FarmPatch}
 * implements this today — see
 * docs/superpowers/specs/2026-07-30-irkedfarmer-granular-config-design.md §3 for why {@code HerbPatch}
 * is excluded.
 */
public interface Patch {
    WorldPoint getLocation();
    String getConfigKey();
    int getFarmingLevel();
    boolean hasRequiredLevel();

    /**
     * Per-patch enable toggle, read directly from {@link ConfigManager} by string key rather than
     * through the {@link IrkedFarmerConfig} interface proxy — {@code ConfigManager.getConfiguration(group,
     * key, type)} reads the raw persisted value and returns {@code null} when unset; it does not fall
     * back to the {@code @ConfigItem}'s annotated default (verified against ConfigManager source). Unset
     * therefore means "never explicitly disabled" here, matching every per-patch {@code @ConfigItem}
     * declared with {@code default boolean x() { return true; }}.
     *
     * {@code Microbot} has no static {@code getConfigManager()} accessor (verified: its
     * {@code ConfigManager} field is private with no getter) — {@code Microbot.getInjector()
     * .getInstance(ConfigManager.class)} is the pattern already used elsewhere in the client for this
     * exact lookup (see {@code Rs2Camera.java}).
     */
    default boolean isEnabled() {
        Boolean value = Microbot.getInjector().getInstance(ConfigManager.class)
                .getConfiguration(IrkedFarmerConfig.GROUP, getConfigKey(), boolean.class);
        return value == null || value;
    }
}
