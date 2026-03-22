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

    /**
     * Initializes the ConfigManager.
     *
     * @param plugin The main plugin instance.
     */
    public ConfigManager(PivotPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Helper to get the file configuration from the main plugin.
     *
     * @return The Bukkit FileConfiguration object.
     */
    private FileConfiguration getConfig() {
        return plugin.getConfig();
    }

    /**
     * Checks if profiling is globally enabled.
     *
     * @return {@code true} if profiling is enabled, {@code false} otherwise.
     */
    public boolean isProfilingEnabled() {
        return getConfig().getBoolean("profiling.enabled", true);
    }

    /**
     * Gets the configured profiling mode.
     * <p>
     * Options:
     * <ul>
     *   <li>{@code auto} - On Paper, use TickProfiler's Paper-compatible timings mode (currently backed by the custom listener-wrapping backend, not native Paper Timings v2); on non-Paper, use the standard custom profiling.</li>
     *   <li>{@code paper_only} - Only enable the profiler on Paper servers using the Paper-compatible timings mode (still backed by the custom listener-wrapping backend); disable profiling on non-Paper servers.</li>
     *   <li>{@code custom_only} - Always use the standard custom profiling backend, even when running on Paper.</li>
     * </ul>
     * To disable profiling entirely, set {@code profiling.enabled: false} in config.

     *
     * @return The profiling mode string (default: "auto").
     */
    public String getProfilingMode() {
        return getConfig().getString("profiling.mode", "auto");
    }

    /**
     * Checks if plugin names should be anonymized in reports.
     *
     * @return {@code true} if plugin names should be hashed, {@code false} otherwise.
     */
    public boolean isAnonymizePluginNames() {
        return getConfig().getBoolean("profiling.privacy.anonymize_plugin_names", false);
    }

    /**
     * Gets the maximum allowed overhead for the profiler per tick.
     *
     * @return The max overhead in milliseconds (default: 0.2ms).
     */
    public double getMaxOverheadMs() {
        return getConfig().getDouble("profiling.performance.max_overhead_ms", 0.2);
    }

    /**
     * Checks if profiling should be automatically disabled if overhead exceeds the limit.
     *
     * @return {@code true} if auto-disable is enabled.
     */
    public boolean isAutoDisableOnOverhead() {
        return getConfig().getBoolean("profiling.performance.auto_disable_on_overhead", true);
    }
}
