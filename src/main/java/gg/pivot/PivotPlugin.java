// src/main/java/gg/pivot/PivotPlugin.java
package gg.pivot;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Main plugin class for Pivot Analytics.
 * <p>
 * Handles plugin lifecycle (onEnable, onDisable), configuration validation,
 * task scheduling for data collection, and command registration.
 * </p>
 */
public class PivotPlugin extends JavaPlugin {
    private Logger logger;
    private EventCollector eventCollector;
    private BukkitTask tpsTask;
    private BukkitTask flushTask;

    private long lastEventSentTime = 0;

    /**
     * Plugin startup logic.
     * Initializes configuration, TPS detection, event collector, and background tasks.
     */
    @Override
    public void onEnable() {
        logger = getLogger();
        logger.info("=================================");
        logger.info(" Pivot Analytics Starting...    ");
        logger.info("=================================");

        // Save default config if not exists
        saveDefaultConfig();

        // Validate configuration
        if (!validateConfig()) {
            logger.severe("Configuration validation failed! Plugin will be disabled.");
            logger.severe("Please fix config.yml and restart the server.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Log configuration (with API key masking)
        logConfiguration();

        // Security check for config file permissions
        checkConfigPermissions();

        // Initialize TPS detection
        TPSUtil.initialize(this, logger);
        logger.info("TPS Detection: " + TPSUtil.getTPSInfo());

        // Initialize event collector
        eventCollector = new EventCollector(this);

        // Register event listener (only if collection enabled)
        if (getConfig().getBoolean("collection.enabled", true)) {
            getServer().getPluginManager().registerEvents(new EventListener(this), this);
            logger.info("Event listeners registered");
        } else {
            logger.warning("Data collection is DISABLED in config.yml");
        }

        PivotCommand commandHandler = new PivotCommand(this);
        getCommand("pivot").setExecutor(commandHandler);
        getCommand("pivot").setTabCompleter(commandHandler);
        logger.info("Commands registered");

        // Start tasks with dynamic intervals from config
        startTasks();

        // Send server start event
        if (eventCollector != null) {
            String serverVersion = getServer().getVersion();
            int pluginsLoaded = getServer().getPluginManager().getPlugins().length;
            eventCollector.addServerStartEvent(serverVersion, pluginsLoaded);
        }

        logger.info("Pivot Analytics enabled successfully!");
        logger.info("Version: " + getDescription().getVersion());
    }

    /**
     * Plugin shutdown logic.
     * Cancels tasks and flushes remaining events.
     */
    @Override
    public void onDisable() {
        // Send SERVER_STOP event synchronously
        if (eventCollector != null) {
            eventCollector.sendServerStopEvent("manual");
        }

        // Cancel running tasks
        if (tpsTask != null) {
            tpsTask.cancel();
        }
        if (flushTask != null) {
            flushTask.cancel();
        }

        // Flush any remaining events
        if (eventCollector != null) {
            logger.info("Flushing remaining events before shutdown...");
            eventCollector.flush();
        }

        logger.info("Pivot Analytics disabled. Goodbye!");
    }

    /**
     * Validate configuration on startup
     */
    public boolean validateConfig() {
        boolean valid = true;

        // Check API key
        String apiKey = getConfig().getString("api.key", "");
        if (apiKey != null) {
            apiKey = apiKey.trim(); // TRIM WHITESPACE
        } else {
            apiKey = "";
        }

        if (apiKey.isEmpty() || apiKey.equals("paste_your_key_here")) {
            logger.severe("API key not configured! Get your key from https://app.pivotmc.dev");
            valid = false;
        } else if (!apiKey.startsWith("pvt_")) {
            // SECURITY: Enforce API key format to prevent misconfiguration
            logger.severe("API key is invalid! It must start with 'pvt_'");
            valid = false;
        } else if (apiKey.length() < 20) {
            // SECURITY: Enforce minimum length for API key to prevent weak keys
            logger.severe("API key is too short! It must be at least 20 characters.");
            valid = false;
        } else if (!apiKey.matches("^[a-zA-Z0-9_]+$")) {
            // SECURITY: Enforce allowed characters
            logger.severe("API key contains invalid characters! Only alphanumeric and underscores allowed.");
            valid = false;
        }

        // Check API endpoint
        String endpoint = getConfig().getString("api.endpoint", "");
        if (endpoint.isEmpty()) {
            logger.severe("API endpoint not configured!");
            valid = false;
        } else if (!endpoint.startsWith("https://")) {
            logger.severe("API endpoint must use HTTPS! (Security Risk)");
            valid = false;
        }

        // Validate intervals
        int batchInterval = getConfig().getInt("collection.batch-interval", 60);
        int tpsInterval = getConfig().getInt("collection.tps-sample-interval", 30);

        if (batchInterval <= 0) {
            logger.severe("collection.batch-interval must be greater than 0!");
            valid = false;
        } else if (batchInterval < 10) {
            logger.warning("batch-interval is very low (" + batchInterval + "s). Recommended: 30-60s");
        }

        if (tpsInterval <= 0) {
            logger.severe("collection.tps-sample-interval must be greater than 0!");
            valid = false;
        }

        if (tpsInterval >= batchInterval) {
            logger.warning("tps-sample-interval (" + tpsInterval + "s) should be less than batch-interval ("
                    + batchInterval + "s)");
        }

        return valid;
    }

    /**
     * Log configuration (with sensitive data masked)
     */
    private void logConfiguration() {
        String apiKey = getConfig().getString("api.key", "not set");
        if (apiKey != null) apiKey = apiKey.trim(); // Trim whitespace

        String maskedKey;
        if (apiKey == null || apiKey.equals("not set") || apiKey.equals("paste_your_key_here")) {
            maskedKey = "NOT CONFIGURED";
        } else {
            // SECURITY: Mask API key to prevent exposure while allowing verification
            if (apiKey.length() > 8) {
                maskedKey = apiKey.substring(0, 4) + "***" + apiKey.substring(apiKey.length() - 4);
            } else {
                maskedKey = "Configured (Hidden)";
            }
        }

        logger.info("Configuration:");
        logger.info("  API Endpoint: " + getConfig().getString("api.endpoint"));
        logger.info("  API Key: " + maskedKey);
        logger.info("  Collection Enabled: " + getConfig().getBoolean("collection.enabled", true));
        logger.info("  Batch Interval: " + getConfig().getInt("collection.batch-interval", 60) + "s");
        logger.info("  TPS Sample Interval: " + getConfig().getInt("collection.tps-sample-interval", 30) + "s");
        logger.info("  Track Hostnames: " + getConfig().getBoolean("privacy.track-hostnames", true));

        // ADDED: Privacy warning
        boolean anonymize = getConfig().getBoolean("privacy.anonymize-players", false);
        logger.info("  Anonymize Players: " + anonymize);
        if (anonymize) {
            logger.warning("  ⚠ Player anonymization is ENABLED - player-level analytics will be limited");
        }

        logger.info("  Debug Mode: " + getConfig().getBoolean("debug.enabled", false));
    }

    /**
     * Check if config.yml is world-readable (security risk)
     */
    private void checkConfigPermissions() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (configFile.exists()) {
            try {
                Path path = configFile.toPath();
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
                boolean insecure = false;

                if (permissions.contains(PosixFilePermission.OTHERS_READ) || permissions.contains(PosixFilePermission.GROUP_READ)) {
                    logger.warning("SECURITY WARNING: config.yml is readable by other users (Group/Others)!");
                    insecure = true;
                }

                if (permissions.contains(PosixFilePermission.OTHERS_WRITE) || permissions.contains(PosixFilePermission.GROUP_WRITE)) {
                    logger.warning("SECURITY WARNING: config.yml is writable by other users (Group/Others)!");
                    insecure = true;
                }

                if (insecure) {
                    logger.warning("Please restrict file permissions (chmod 600) to protect your API key.");
                }
            } catch (UnsupportedOperationException e) {
                // Not a POSIX system (e.g. Windows), skip check
            } catch (IOException e) {
                logger.warning("Failed to check config.yml permissions: " + e.getMessage());
            }
        }
    }

    /**
     * Start performance monitoring and flush tasks with dynamic intervals
     */
    private void startTasks() {
        if (!getConfig().getBoolean("collection.enabled", true)) {
            logger.info("Skipping task startup (collection disabled)");
            return;
        }

        // TPS sampling interval (convert seconds to ticks: 1 second = 20 ticks)
        int tpsIntervalSeconds = getConfig().getInt("collection.tps-sample-interval", 30);
        long tpsIntervalTicks = tpsIntervalSeconds * 20L;

        // Batch flush interval
        int batchIntervalSeconds = getConfig().getInt("collection.batch-interval", 60);
        long batchIntervalTicks = batchIntervalSeconds * 20L;

        // Start TPS monitoring task
        if (getConfig().getBoolean("collection.track-performance", true)) {
            tpsTask = new BukkitRunnable() {
                @Override
                public void run() {
                    int playerCount = getServer().getOnlinePlayers().size();
                    double tps = TPSUtil.getTPS();
                    eventCollector.addPerformanceEvent(tps, playerCount);

                    if (getConfig().getBoolean("debug.enabled", false)) {
                        logger.info(String.format("Sampled - Players: %d, TPS: %.2f", playerCount, tps));
                    }
                }
            }.runTaskTimerAsynchronously(this, 0L, tpsIntervalTicks);

            logger.info("Started TPS monitoring (every " + tpsIntervalSeconds + "s)");
        }

        // Start batch flush task
        flushTask = new BukkitRunnable() {
            @Override
            public void run() {
                eventCollector.flush();
                lastEventSentTime = System.currentTimeMillis();
            }
        }.runTaskTimerAsynchronously(this, batchIntervalTicks, batchIntervalTicks);

        logger.info("Started event batching (every " + batchIntervalSeconds + "s)");
    }

    /**
     * Restarts the collection tasks.
     * Called when configuration is reloaded via /pivot reload.
     */
    public void restartTasks() {
        // Reload event collector configuration
        if (eventCollector != null) {
            eventCollector.reload();
        }

        // Cancel existing tasks
        if (tpsTask != null) {
            tpsTask.cancel();
            tpsTask = null;
        }
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }

        // Start new tasks with updated config
        startTasks();
    }

    /**
     * Get the event collector instance
     */
    public EventCollector getEventCollector() {
        return eventCollector;
    }

    /**
     * Get the timestamp of the last successful event batch flush.
     * @return Timestamp in milliseconds
     */
    public long getLastEventSentTime() {
        return lastEventSentTime;
    }
}
