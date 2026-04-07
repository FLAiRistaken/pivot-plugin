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
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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

    // Added for Phase 3A
    private ConfigManager configManager;
    private TickProfiler tickProfiler;
    private ChunkProfiler chunkProfiler;
    private CommandProfiler commandProfiler;
    private ConfigSnapshotReporter configSnapshotReporter;

    // ⚡ Bolt Optimization: Cache player count to avoid main thread blocking
    private final AtomicInteger onlinePlayerCount = new AtomicInteger(0);

    private long lastEventSentTime = 0;

    /**
     * Plugin startup logic.
     * Initializes configuration, TPS detection, event collector, and background
     * tasks.
     */
    @Override
    public void onEnable() {
        logger = getLogger();
        logger.info("=================================");
        logger.info(" Pivot Analytics Starting...    ");
        logger.info("=================================");

        // Save default config if not exists
        saveDefaultConfig();

        // Initialize ConfigManager
        this.configManager = new ConfigManager(this);

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

        // Initialize player count
        onlinePlayerCount.set(getServer().getOnlinePlayers().size());

        // Initialize event collector
        eventCollector = new EventCollector(this);


        // Initialize TickProfiler
        tickProfiler = new TickProfiler(this, configManager);
        eventCollector.setTickProfiler(tickProfiler);

        // Initialize Phase 3B Profilers
        chunkProfiler = new ChunkProfiler(this, eventCollector);
        commandProfiler = new CommandProfiler(this, eventCollector);
        getServer().getPluginManager().registerEvents(chunkProfiler, this);
        getServer().getPluginManager().registerEvents(commandProfiler, this);

        this.configSnapshotReporter = new ConfigSnapshotReporter(this, eventCollector, tickProfiler, chunkProfiler);


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
        // Shutdown TickProfiler first to restore the original scheduler (Spigot mode)
        if (tickProfiler != null) {
            tickProfiler.shutdown();
        }
        if (commandProfiler != null) {
            commandProfiler.disable();
        }

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
            if (chunkProfiler != null) {
                chunkProfiler.flushAndReset();
                chunkProfiler.disable();
            }
            eventCollector.flush();
        }

        logger.info("Pivot Analytics disabled. Goodbye!");
    }

    /**
     * Validate configuration on startup.
     * <p>
     * Checks for:
     * <ul>
     * <li>Valid API key format (starts with 'pvt_', length >= 20,
     * alphanumeric).</li>
     * <li>Valid API endpoint (HTTPS required).</li>
     * <li>Sane collection intervals (batch interval > TPS interval).</li>
     * </ul>
     * </p>
     *
     * @return {@code true} if configuration is valid, {@code false} otherwise.
     */
    public boolean validateConfig() {
        boolean valid = true;

        // Check API key
        String apiKey = getApiKey();
        if (apiKey == null) apiKey = "";

        if (!isValidApiKeyFormat(apiKey)) {
            logger.severe(
                    "API key is invalid! It must start with 'pvt_', be at least 20 chars, and contain only alphanumeric characters, underscores, or hyphens.");
            valid = false;
        }

        // Check API endpoint
        String endpoint = getApiEndpoint();
        if (endpoint == null) endpoint = "";

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
     * Log configuration to console with sensitive data masked.
     * <p>
     * API keys are partially masked (e.g., "pvt_***1234") or fully hidden
     * to prevent leakage in server logs.
     * </p>
     */
    private void logConfiguration() {
        String apiKey = getApiKey();

        String maskedKey;
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("paste_your_key_here")) {
            maskedKey = "NOT CONFIGURED";
        } else {
            // SECURITY: Mask API key to prevent exposure while allowing verification
            if (apiKey.length() > 8) {
                maskedKey = apiKey.substring(0, 4) + "***" + apiKey.substring(apiKey.length() - 4);
            } else {
                maskedKey = "Configured (Hidden)";
            }
        }

        String endpoint = getApiEndpoint();

        logger.info("Configuration:");
        logger.info("  API Endpoint: " + (endpoint != null ? endpoint : "NOT CONFIGURED"));
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
     * Check if config.yml is world-readable (security risk).
     * <p>
     * If the file is readable or writable by Group/Others, this method attempts
     * to lock permissions to {@code 600} (Owner Read/Write only) using POSIX APIs.
     * Logs a warning if the file is insecure and cannot be fixed.
     * </p>
     */
    public void checkConfigPermissions() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (configFile.exists()) {
            try {
                Path path = configFile.toPath();
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
                boolean insecure = false;

                if (permissions.contains(PosixFilePermission.OTHERS_READ)
                        || permissions.contains(PosixFilePermission.GROUP_READ)) {
                    logger.warning("SECURITY WARNING: config.yml is readable by other users (Group/Others)!");
                    insecure = true;
                }

                if (permissions.contains(PosixFilePermission.OTHERS_WRITE)
                        || permissions.contains(PosixFilePermission.GROUP_WRITE)) {
                    logger.warning("SECURITY WARNING: config.yml is writable by other users (Group/Others)!");
                    insecure = true;
                }

                if (insecure) {
                    logger.warning("Config.yml is insecure! Attempting to lock permissions (chmod 600)...");
                    try {
                        Set<PosixFilePermission> securePerms = new HashSet<>();
                        securePerms.add(PosixFilePermission.OWNER_READ);
                        securePerms.add(PosixFilePermission.OWNER_WRITE);
                        Files.setPosixFilePermissions(path, securePerms);
                        logger.info("Success! Config.yml is now secure (600).");
                    } catch (IOException e) {
                        logger.severe("Failed to lock permissions: " + e.getMessage());
                        logger.warning("Please manually run: chmod 600 " + configFile.getAbsolutePath());
                    }
                }
            } catch (UnsupportedOperationException e) {
                // Not a POSIX system (e.g. Windows), skip check
            } catch (IOException e) {
                logger.warning("Failed to check config.yml permissions: " + e.getMessage());
            }
        }
    }

    /**
     * Start performance monitoring and flush tasks with dynamic intervals.
     * <p>
     * Schedules asynchronous tasks for:
     * <ul>
     * <li>TPS Sampling: Captures server performance metrics.</li>
     * <li>Event Flushing: Batches and sends collected events to the API.</li>
     * </ul>
     * Intervals are configured in {@code config.yml}.
     * </p>
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
            scheduleNextTpsSample(0L);
            logger.info("Started TPS monitoring (every " + tpsIntervalSeconds + "s)");
        }

        // Start batch flush task
        flushTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (configSnapshotReporter != null) configSnapshotReporter.maybeEmitSnapshot();
                if (chunkProfiler != null) chunkProfiler.flushAndReset();
                eventCollector.flush();
                lastEventSentTime = System.currentTimeMillis();
            }
        }.runTaskTimerAsynchronously(this, batchIntervalTicks, batchIntervalTicks);

        logger.info("Started event batching (every " + batchIntervalSeconds + "s)");
    }

    /**
     * Schedules the next TPS sample with dynamic interval based on player count.
     * ⚡ Bolt Optimization: Reduces sampling frequency when server is empty.
     */
    private void scheduleNextTpsSample(long delayTicks) {
        if (!isEnabled())
            return;

        tpsTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!isEnabled())
                    return;

                // Capture data
                // ⚡ Bolt: Use cached player count (thread-safe, no main thread blocking)
                int playerCount = getOnlinePlayerCount();
                double tps = TPSUtil.getTPS();
                eventCollector.addPerformanceEvent(tps, playerCount);

                if (getConfig().getBoolean("debug.enabled", false)) {
                    logger.info(String.format("Sampled - Players: %d, TPS: %.2f", playerCount, tps));
                }

                // Schedule next run
                // ⚡ Bolt: Increase interval if 0 players to save resources
                int baseInterval = getConfig().getInt("collection.tps-sample-interval", 30);
                long nextDelay = baseInterval * 20L;

                boolean idleThrottling = getConfig().getBoolean("collection.idle-throttling", true);
                if (idleThrottling && playerCount == 0) {
                    nextDelay *= 4; // Increase delay if idle
                }

                scheduleNextTpsSample(nextDelay);
            }
        }.runTaskLaterAsynchronously(PivotPlugin.this, delayTicks);
    }

    /**
     * Restarts the collection tasks.
     * <p>
     * Called when configuration is reloaded via {@code /pivot reload}.
     * Cancels existing tasks and starts new ones with updated intervals.
     * </p>
     */
    public void restartTasks() {
        // Reload event collector configuration
        if (eventCollector != null) {
            eventCollector.reload();
        }

        // Shutdown and reinitialise TickProfiler to apply updated config and restore scheduler
        if (tickProfiler != null) {
            tickProfiler.shutdown();
        }
        if (chunkProfiler != null) {
            chunkProfiler.reload();
        }
        if (commandProfiler != null) {
            commandProfiler.reload();
        }
        tickProfiler = new TickProfiler(this, configManager);
        if (eventCollector != null) {
            eventCollector.setTickProfiler(tickProfiler);
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
     * Get the event collector instance.
     *
     * @return The active {@link EventCollector}.
     */
    public EventCollector getEventCollector() {
        return eventCollector;
    }

    /**
     * Get the timestamp of the last successful event batch flush.
     *
     * @return Timestamp in milliseconds.
     */
    public long getLastEventSentTime() {
        return lastEventSentTime;
    }

    /**
     * Get the cached online player count.
     * Thread-safe and non-blocking.
     */
    public int getOnlinePlayerCount() {
        return onlinePlayerCount.get();
    }

    /**
     * Update the cached player count.
     *
     * @param delta The change in player count (e.g., +1 or -1).
     */
    public void updatePlayerCount(int delta) {
        onlinePlayerCount.addAndGet(delta);
    }

    /**
     * Validate API key format.
     * <p>
     * Rules:
     * <ul>
     * <li>Must not be null or empty</li>
     * <li>Must not be the default placeholder</li>
     * <li>Must start with "pvt_"</li>
     * <li>Must be at least 20 characters long</li>
     * <li>Must contain only alphanumeric characters, underscores, or hyphens</li>
     * </ul>
     * </p>
     *
     * @param apiKey The API key string to validate.
     * @return {@code true} if valid, {@code false} otherwise.
     */
    public static boolean isValidApiKeyFormat(String apiKey) {
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("paste_your_key_here")) {
            return false;
        }
        if (!apiKey.startsWith("pvt_")) {
            return false;
        }
        if (apiKey.length() < 20) {
            return false;
        }
        // Allow alphanumeric, underscores, AND hyphens
        return apiKey.matches("^[a-zA-Z0-9_-]+$");
    }

    /**
     * Retrieves the API key, checking both nested (api.key) and flat (api-key) configurations.
     * @return The trimmed API key, or null if not set.
     */
    public String getApiKey() {
        String key = getConfig().getString("api.key");
        if (key == null || key.isEmpty()) {
            key = getConfig().getString("api-key");
        }
        return key != null ? key.trim() : null;
    }

    /**
     * Retrieves the API endpoint, checking both nested (api.endpoint) and flat (api-endpoint) configurations.
     * @return The trimmed API endpoint, or null if not set.
     */
    public String getApiEndpoint() {
        String endpoint = getConfig().getString("api.endpoint");
        if (endpoint == null || endpoint.isEmpty()) {
            endpoint = getConfig().getString("api-endpoint");
        }
        return endpoint != null ? endpoint.trim() : null;
    }
}
