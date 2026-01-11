// src/main/java/gg/pivot/PivotPlugin.java
package gg.pivot;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

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

        logger.info("Pivot Analytics enabled successfully!");
        logger.info("Version: " + getDescription().getVersion());
    }

    @Override
    public void onDisable() {
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
    private boolean validateConfig() {
        boolean valid = true;

        // Check API key
        String apiKey = getConfig().getString("api.key", "");
        if (apiKey.isEmpty() || apiKey.equals("paste_your_key_here")) {
            logger.severe("API key not configured! Get your key from https://app.pivotmc.dev");
            valid = false;
        } else if (!apiKey.startsWith("pvt_")) {
            logger.warning("API key format looks invalid (should start with 'pvt_')");
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

        if (batchInterval < 10) {
            logger.warning("batch-interval is very low (" + batchInterval + "s). Recommended: 30-60s");
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
        String maskedKey;
        if (apiKey.equals("not set") || apiKey.equals("paste_your_key_here")) {
            maskedKey = "NOT CONFIGURED";
        } else {
            maskedKey = "***" + apiKey.substring(Math.max(0, apiKey.length() - 4));
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

    public void restartTasks() {
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

    public long getLastEventSentTime() {
        return lastEventSentTime;
    }
}
