package gg.pivot;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Manages configuration access for Pivot Analytics.
 * <p>
 * Centralizes config retrieval with type-safe getters and sensible defaults.
 * </p>
 */
public class ConfigManager {
    private final PivotPlugin plugin;

    public ConfigManager(PivotPlugin plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration getConfig() {
        return plugin.getConfig();
    }

    public boolean isProfilingEnabled() {
        return getConfig().getBoolean("profiling.enabled", true);
    }

    public String getProfilingMode() {
        return getConfig().getString("profiling.mode", "auto");
    }

    public boolean isAnonymizePluginNames() {
        return getConfig().getBoolean("profiling.privacy.anonymize_plugin_names", false);
    }

    public double getMaxOverheadMs() {
        return getConfig().getDouble("profiling.performance.max_overhead_ms", 0.2);
    }

    public boolean isAutoDisableOnOverhead() {
        return getConfig().getBoolean("profiling.performance.auto_disable_on_overhead", true);
    }
}
