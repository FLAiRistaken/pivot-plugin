// src/main/java/gg/pivot/EventCollector.java
package gg.pivot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;

/**
 * Collects and batches events for sending to the Pivot API.
 * <p>
 * This class uses {@link java.util.concurrent.ConcurrentLinkedQueue} to store
 * events
 * efficiently without blocking the main server thread. Events are flushed
 * periodically
 * by an asynchronous task in {@link PivotPlugin}.
 * </p>
 * <p>
 * <b>Bolt Optimizations:</b>
 * <ul>
 * <li>Uses non-blocking queues to avoid main thread contention.</li>
 * <li>Defers heavy operations (like UUID hashing) to the async flush task.</li>
 * <li>Drains queues directly to JSON arrays to minimize allocations.</li>
 * </ul>
 * </p>
 */
public class EventCollector {
    private final PivotPlugin plugin;
    private final Logger logger;
    private final OkHttpClient httpClient;
    private volatile String apiKey;

    // Added for Phase 3A
    // volatile ensures cross-thread visibility: setTickProfiler() may be called
    // from the main thread
    // while flush() runs on an async task thread.
    private volatile TickProfiler tickProfiler;

    // ⚡ Bolt Optimization: Use ConcurrentLinkedQueue to avoid blocking main thread
    // with locks
    private final Queue<PlayerEventData> playerEvents = new ConcurrentLinkedQueue<>();
    private final Queue<PerformanceEventData> performanceEvents = new ConcurrentLinkedQueue<>();
    private final Queue<ServerEventData> serverEvents = new ConcurrentLinkedQueue<>();
    private final Queue<ServerInfoEventData> serverInfoEvents = new ConcurrentLinkedQueue<>();
    private final Queue<JsonObject> profilingEvents = new ConcurrentLinkedQueue<>();

    private ApiClient apiClient;

    // ⚡ Bolt Optimization: Reuse MessageDigest to prevent object instantiation
    // overhead during async flush
    private static final ThreadLocal<MessageDigest> SHA256_DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available!", e);
        }
    });

    /**
     * Initializes the EventCollector.
     * <p>
     * Sets up the OkHttpClient with strict timeouts (15s) to prevent resource
     * exhaustion.
     * </p>
     * 
     * @param plugin The main plugin instance
     */
    public EventCollector(PivotPlugin plugin) {
        this(plugin, new OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build());
    }

    /**
     * Package-private constructor for testing, allowing injection of a custom
     * {@link OkHttpClient}.
     *
     * @param plugin     The main plugin instance
     * @param httpClient The HTTP client to use for outgoing requests
     */
    EventCollector(PivotPlugin plugin, OkHttpClient httpClient) {
        Objects.requireNonNull(plugin, "plugin must not be null");
        Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.plugin = plugin;
        this.logger = plugin.getLogger();

        this.apiKey = plugin.getApiKey();

        // SECURITY: Validate API key format (High Priority)
        // This ensures the collector doesn't run with an invalid key even if
        // PivotPlugin validation was bypassed
        if (!PivotPlugin.isValidApiKeyFormat(this.apiKey)) {
            logger.warning(
                    "EventCollector initialized with invalid API key (must start with 'pvt_', be >= 20 chars, and alphanumeric/hyphens). Events will NOT be sent.");
            this.apiKey = null; // Disable sending
        }

        this.httpClient = httpClient;
        this.apiClient = new ApiClient(plugin, httpClient);
    }

    /**
     * Sets the tick profiler instance used to collect plugin performance data.
     *
     * @param tickProfiler The {@link TickProfiler} instance
     */
    public void setTickProfiler(TickProfiler tickProfiler) {
        this.tickProfiler = tickProfiler;
    }

    /**
     * Enqueues a profiling event JSON object to the profiling event queue
     * for inclusion in the next batch flush.
     *
     * @param event The profiling event as a {@link JsonObject}
     */
    public void addProfilingEvent(JsonObject event) {
        profilingEvents.add(event);
    }

    public int getProfilingEventCount() {
        return profilingEvents.size();
    }

    /**
     * Reloads configuration values (API key).
     * <p>
     * Updates the API key from the config file. Validates the key format
     * (must start with 'pvt_' and be >= 20 chars). If invalid, the collector
     * is disabled to prevent authentication errors.
     * </p>
     */
    public void reload() {
        String trimmedKey = plugin.getApiKey();

        // SECURITY: Validate API key format on reload
        if (!PivotPlugin.isValidApiKeyFormat(trimmedKey)) {
            logger.warning("EventCollector reload: Invalid API key. Keeping previous key (if valid) or disabling.");
            // We could keep old key, or disable. Disabling is safer to avoid confusion if
            // config is broken.
            this.apiKey = null;
        } else {
            this.apiKey = trimmedKey;
        }
        if (this.apiClient != null)
            this.apiClient.reload();
    }

    /**
     * Get current player event queue size.
     *
     * @return Number of queued player events.
     */
    public int getPlayerEventCount() {
        return playerEvents.size();
    }

    /**
     * Get current performance event queue size.
     *
     * @return Number of queued performance events.
     */
    public int getPerformanceEventCount() {
        return performanceEvents.size();
    }

    /**
     * Add a player event (JOIN/QUIT) to the queue.
     *
     * @param eventType      The type of event (e.g., "PLAYER_JOIN", "PLAYER_QUIT").
     * @param playerUuid     The UUID of the player.
     * @param playerName     The name of the player.
     * @param hostname       The hostname the player joined with (optional).
     * @param quitReason     The reason for quitting (optional, for QUIT events).
     * @param sessionClean   Whether the session ended cleanly (optional).
     * @param connectionType The type of connection ("initial" or "reconnect").
     */
    public void addPlayerEvent(String eventType, String playerUuid, String playerName, String hostname,
            String quitReason, Boolean sessionClean, String connectionType) {
        // Only add hostname if tracking enabled and not null
        boolean trackHostnames = plugin.getConfig().getBoolean("privacy.track-hostnames", true);
        String finalHostname = (trackHostnames && hostname != null && !hostname.isEmpty()) ? hostname : null;

        // ⚡ Bolt Optimization: Use POJO to avoid JsonObject creation on main thread
        playerEvents.add(new PlayerEventData(eventType, playerUuid, playerName, finalHostname, quitReason, sessionClean,
                connectionType));
    }

    /**
     * Hash a UUID using SHA-256 for anonymization
     *
     * @param uuid Player UUID string
     * @return Hashed UUID (64 hex characters)
     */
    private String hashUuid(String uuid) {
        MessageDigest digest = SHA256_DIGEST.get();
        byte[] hash = digest.digest(uuid.getBytes(StandardCharsets.UTF_8));

        // Convert bytes to hex string
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * Add a performance event (TPS sample) to the queue.
     *
     * @param tps         The current server TPS (Ticks Per Second).
     * @param playerCount The current number of online players.
     */
    public void addPerformanceEvent(double tps, int playerCount) {
        // ⚡ Bolt Optimization: Use POJO to avoid JsonObject creation on main thread
        performanceEvents.add(new PerformanceEventData(tps, playerCount));
    }

    /**
     * Add a server start event to the queue.
     *
     * @param serverVersion The version string of the server (e.g.,
     *                      "git-Paper-123").
     * @param pluginsLoaded The number of plugins currently loaded.
     */
    public void addServerStartEvent(String serverVersion, int pluginsLoaded) {
        // ⚡ Bolt Optimization: Use POJO to avoid JsonObject creation on main thread
        serverEvents.add(new ServerEventData(serverVersion, pluginsLoaded));
    }

    /**
     * Add a SERVER_INFO event to the queue.
     *
     * @param plugin The plugin instance used to collect server metadata.
     */
    public void addServerInfoEvent(PivotPlugin plugin) {
        org.bukkit.Server server = org.bukkit.Bukkit.getServer();
        String minecraftVersion = server.getBukkitVersion();
        String serverFork = server.getName();
        String javaVersion = System.getProperty("java.version");
        String osName = System.getProperty("os.name");
        long allocatedRamMb = Runtime.getRuntime().maxMemory() / 1048576L;
        int cpuCores = Runtime.getRuntime().availableProcessors();
        String pivotVersion = plugin.getDescription().getVersion();

        org.bukkit.plugin.Plugin[] allPlugins = server.getPluginManager().getPlugins();
        java.util.List<ServerInfoEventData.PluginInfo> installedPlugins = new java.util.ArrayList<>();
        for (org.bukkit.plugin.Plugin p : allPlugins) {
            if (!p.getName().equals(plugin.getName())) {
                installedPlugins.add(new ServerInfoEventData.PluginInfo(p.getName(), p.getDescription().getVersion()));
            }
        }

        serverInfoEvents.add(new ServerInfoEventData(
                minecraftVersion, serverFork, javaVersion, osName,
                allocatedRamMb, cpuCores, pivotVersion, installedPlugins));
    }

    /**
     * Send a server stop event synchronously.
     * <p>
     * This is called during plugin disable. It bypasses the async queue to ensure
     * the event is sent before the JVM shuts down.
     * </p>
     *
     * @param reason The reason for the stop (usually "manual").
     */
    public void sendServerStopEvent(String reason) {
        JsonObject event = new JsonObject();
        event.addProperty("timestamp", System.currentTimeMillis());
        event.addProperty("event_type", "SERVER_STOP");
        event.addProperty("reason", reason);

        // Build payload
        JsonObject payload = new JsonObject();
        payload.addProperty("batch_timestamp", System.currentTimeMillis());

        JsonArray serverArray = new JsonArray();
        serverArray.add(event);
        payload.add("server_events", serverArray);

        // Send synchronously
        try {
            apiClient.sendToAPISync(payload.toString());
        } catch (IOException e) {
            // SECURITY: Redact sensitive info (API key) from exception message
            String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            logger.warning("Failed to send SERVER_STOP event: " + ApiClient.redactSensitiveInfo(errorMsg, this.apiKey));
        }
    }

    /**
     * Flush all collected events to the API.
     * <p>
     * Drains event queues, anonymizes player data (if enabled), builds a JSON
     * payload,
     * and sends it to the Pivot API. By collecting events in queues and flushing
     * them periodically, we batch network requests and minimize API overhead.
     * </p>
     * <p>
     * <b>Threading:</b> Normally invoked by a periodic async background task, so
     * anonymization (SHA-256 hashing) and JSON construction run off the main
     * thread.
     * However, this method is also called synchronously on the main thread from
     * {@code PivotPlugin.onDisable()} for a final drain on shutdown, so heavy work
     * may occasionally run on the main thread during that path.
     * </p>
     */
    public void flush() {
        boolean debugEnabled = plugin.getConfig().getBoolean("debug.enabled", false);
        boolean logBatches = plugin.getConfig().getBoolean("debug.log-batches", false);

        if (debugEnabled) {
            logger.info("Flush called - checking for events to send");
        }

        /*
         * Batching Pattern Logic:
         * 1. This flush() method is called periodically by an async background task
         * during normal
         * operation, but it may also be invoked synchronously on the main thread during
         * PivotPlugin.onDisable() for a final drain on shutdown.
         * 2. It drains events from concurrent queues directly into Gson JsonArrays.
         * 3. Costly operations such as UUID hashing are performed here; during the
         * normal
         * async flush path these run off the main thread, but during the onDisable()
         * path
         * they may run on the main thread.
         * 4. The arrays are consolidated into a single JSON payload to minimize API
         * calls and network overhead.
         */

        // Collect Tick Profile
        JsonObject tickProfileEvent = null;
        if (tickProfiler != null) {
            tickProfileEvent = tickProfiler.collectSample();
        }

        // ⚡ Bolt Optimization: Early return if queues empty to avoid allocations
        if (playerEvents.isEmpty() && performanceEvents.isEmpty() && serverEvents.isEmpty()
                && serverInfoEvents.isEmpty() && tickProfileEvent == null
                && profilingEvents.isEmpty()) {
            if (debugEnabled) {
                logger.info("No events to send");
            }
            return;
        }

        // ⚡ Bolt Optimization: Drain directly to JsonArray and anonymize async
        boolean anonymize = plugin.getConfig().getBoolean("privacy.anonymize-players", false);

        JsonArray playerArray = new JsonArray();
        PlayerEventData polledEvent;
        while ((polledEvent = playerEvents.poll()) != null) {
            JsonObject event = new JsonObject();
            event.addProperty("timestamp", polledEvent.timestamp);
            event.addProperty("event_type", polledEvent.eventType);

            // Anonymization logic
            if (anonymize) {
                String hashedUuid;
                try {
                    hashedUuid = hashUuid(polledEvent.playerUuid);
                } catch (RuntimeException e) {
                    // RuntimeException is the only exception hashUuid() can throw: the
                    // SHA256_DIGEST
                    // ThreadLocal initializer wraps NoSuchAlgorithmException in a plain
                    // RuntimeException.
                    // SECURITY: Fail secure - if hashing fails, do not send raw UUID
                    logger.severe("SHA-256 hashing failed during anonymization: " + e.getMessage());
                    hashedUuid = "ANONYMIZATION_FAILED";
                }
                event.addProperty("player_uuid", hashedUuid);
                event.addProperty("player_name", "Player");
            } else {
                event.addProperty("player_uuid", polledEvent.playerUuid);
                event.addProperty("player_name", polledEvent.playerName);
            }

            if (polledEvent.hostname != null)
                event.addProperty("hostname", polledEvent.hostname);
            if (polledEvent.quitReason != null)
                event.addProperty("quit_reason", polledEvent.quitReason);
            if (polledEvent.sessionClean != null)
                event.addProperty("session_clean", polledEvent.sessionClean);
            if (polledEvent.connectionType != null)
                event.addProperty("connection_type", polledEvent.connectionType);

            playerArray.add(event);
        }

        JsonArray perfArray = new JsonArray();
        PerformanceEventData perfEvent;
        while ((perfEvent = performanceEvents.poll()) != null) {
            JsonObject event = new JsonObject();
            event.addProperty("timestamp", perfEvent.timestamp);
            event.addProperty("tps", perfEvent.tps);
            event.addProperty("player_count", perfEvent.playerCount);
            perfArray.add(event);
        }

        JsonArray serverArray = new JsonArray();
        ServerEventData serverEvent;
        while ((serverEvent = serverEvents.poll()) != null) {
            JsonObject event = new JsonObject();
            event.addProperty("timestamp", serverEvent.timestamp);
            event.addProperty("event_type", "SERVER_START");
            event.addProperty("server_version", serverEvent.serverVersion);
            event.addProperty("plugins_loaded", serverEvent.pluginsLoaded);
            serverArray.add(event);
        }
        ServerInfoEventData serverInfoEvent;
        while ((serverInfoEvent = serverInfoEvents.poll()) != null) {
            JsonObject event = new JsonObject();
            event.addProperty("timestamp", serverInfoEvent.timestamp);
            event.addProperty("event_type", "SERVER_INFO");
            event.addProperty("minecraft_version", serverInfoEvent.minecraftVersion);
            event.addProperty("server_fork", serverInfoEvent.serverFork);
            event.addProperty("java_version", serverInfoEvent.javaVersion);
            event.addProperty("os_name", serverInfoEvent.osName);
            event.addProperty("allocated_ram_mb", serverInfoEvent.allocatedRamMb);
            event.addProperty("cpu_cores", serverInfoEvent.cpuCores);
            event.addProperty("pivot_plugin_version", serverInfoEvent.pivotPluginVersion);
            JsonArray pluginsArray = new JsonArray();
            for (ServerInfoEventData.PluginInfo pi : serverInfoEvent.installedPlugins) {
                JsonObject pluginObj = new JsonObject();
                pluginObj.addProperty("name", pi.name);
                pluginObj.addProperty("version", pi.version);
                pluginsArray.add(pluginObj);
            }
            event.add("installed_plugins", pluginsArray);
            serverArray.add(event);
        }

        JsonArray profilingArray = new JsonArray();
        JsonObject pe;
        while ((pe = profilingEvents.poll()) != null) {
            profilingArray.add(pe);
        }

        if (debugEnabled) {
            logger.info("Events to send - Player: " + playerArray.size() + ", Performance: " + perfArray.size()
                    + ", Server: " + serverArray.size());
        }

        // Nothing to send (double check)
        if (playerArray.size() == 0 && perfArray.size() == 0 && serverArray.size() == 0 && tickProfileEvent == null
                && profilingArray.size() == 0 && serverInfoEvents.isEmpty()) {
            return;
        }

        // Build JSON payload
        JsonObject payload = new JsonObject();
        payload.addProperty("batch_timestamp", System.currentTimeMillis());
        payload.add("player_events", playerArray);
        payload.add("performance_events", perfArray);
        payload.add("server_events", serverArray);

        if (profilingArray.size() > 0) {
            payload.add("profiling_events", profilingArray);
        }

        if (tickProfileEvent != null) {
            JsonArray tpArray = new JsonArray();
            tpArray.add(tickProfileEvent);
            payload.add("tick_profile_events", tpArray);
        }

        String json = payload.toString();

        // Log full payload if debug enabled
        if (logBatches) {
            // SECURITY: Redact PII from debug logs
            logger.info("Sending batch payload: " + redactPii(json));
        }

        // Send to API
        apiClient.sendToAPI(json);
    }

    public ApiClient getApiClient() {
        return apiClient;
    }

    /**
     * Redact PII (UUIDs, names, hostnames) from JSON payload for debug logging.
     * <p>
     * Ensures user privacy when {@code debug.log-batches} is enabled.
     * </p>
     *
     * @param json The raw JSON payload string.
     * @return A string representation of the JSON with PII fields replaced by
     *         {@code [REDACTED]}.
     */
    private String redactPii(String json) {
        try {
            JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            if (obj.has("player_events")) {
                JsonArray players = obj.getAsJsonArray("player_events");
                // Need to clone or rebuild to avoid modifying the original array if we were
                // modifying objects in place
                // But parseString creates a NEW structure, so we are safe to modify 'obj'
                for (com.google.gson.JsonElement e : players) {
                    if (e.isJsonObject()) {
                        JsonObject p = e.getAsJsonObject();
                        if (p.has("player_uuid"))
                            p.addProperty("player_uuid", "[REDACTED]");
                        if (p.has("player_name"))
                            p.addProperty("player_name", "[REDACTED]");
                        if (p.has("hostname"))
                            p.addProperty("hostname", "[REDACTED]");
                        if (p.has("quit_reason"))
                            p.addProperty("quit_reason", "[REDACTED]");
                    }
                }
            }
            return obj.toString();
        } catch (Exception e) {
            // Fallback if parsing fails
            return "[Unable to redact PII - Payload Hidden]";
        }
    }

    /**
     * Simple POJO to hold player event data until flush.
     * Avoids main-thread overhead of creating JsonObjects.
     */
    private static class PlayerEventData {
        final long timestamp;
        final String eventType;
        final String playerUuid;
        final String playerName;
        final String hostname;
        final String quitReason;
        final Boolean sessionClean;
        final String connectionType;

        PlayerEventData(String eventType, String playerUuid, String playerName, String hostname, String quitReason,
                Boolean sessionClean, String connectionType) {
            this.timestamp = System.currentTimeMillis();
            this.eventType = eventType;
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.hostname = hostname;
            this.quitReason = quitReason;
            this.sessionClean = sessionClean;
            this.connectionType = connectionType;
        }
    }

    private static class PerformanceEventData {
        final long timestamp;
        final double tps;
        final int playerCount;

        PerformanceEventData(double tps, int playerCount) {
            this.timestamp = System.currentTimeMillis();
            this.tps = tps;
            this.playerCount = playerCount;
        }
    }

    private static class ServerEventData {
        final long timestamp;
        final String serverVersion;
        final int pluginsLoaded;

        ServerEventData(String serverVersion, int pluginsLoaded) {
            this.timestamp = System.currentTimeMillis();
            this.serverVersion = serverVersion;
            this.pluginsLoaded = pluginsLoaded;
        }
    }

    private static class ServerInfoEventData {
        final long timestamp;
        final String minecraftVersion;
        final String serverFork;
        final String javaVersion;
        final String osName;
        final long allocatedRamMb;
        final int cpuCores;
        final String pivotPluginVersion;
        final java.util.List<PluginInfo> installedPlugins;

        ServerInfoEventData(String minecraftVersion, String serverFork, String javaVersion,
                String osName, long allocatedRamMb, int cpuCores, String pivotPluginVersion,
                java.util.List<PluginInfo> installedPlugins) {
            this.timestamp = System.currentTimeMillis();
            this.minecraftVersion = minecraftVersion;
            this.serverFork = serverFork;
            this.javaVersion = javaVersion;
            this.osName = osName;
            this.allocatedRamMb = allocatedRamMb;
            this.cpuCores = cpuCores;
            this.pivotPluginVersion = pivotPluginVersion;
            this.installedPlugins = installedPlugins;
        }

        static class PluginInfo {
            final String name;
            final String version;

            PluginInfo(String name, String version) {
                this.name = name;
                this.version = version;
            }
        }
    }
}
