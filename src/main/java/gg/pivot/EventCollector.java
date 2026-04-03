// src/main/java/gg/pivot/EventCollector.java
package gg.pivot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Collects and batches events for sending to the Pivot API.
 * <p>
 * This class uses {@link java.util.concurrent.ConcurrentLinkedQueue} to store events
 * efficiently without blocking the main server thread. Events are flushed periodically
 * by an asynchronous task in {@link PivotPlugin}.
 * </p>
 * <p>
 * <b>Bolt Optimizations:</b>
 * <ul>
 *   <li>Uses non-blocking queues to avoid main thread contention.</li>
 *   <li>Defers heavy operations (like UUID hashing) to the async flush task.</li>
 *   <li>Drains queues directly to JSON arrays to minimize allocations.</li>
 * </ul>
 * </p>
 */
public class EventCollector {
    private final PivotPlugin plugin;
    private final Logger logger;
    private final OkHttpClient httpClient;
    private volatile String apiKey;

    // Added for Phase 3A
    // volatile ensures cross-thread visibility: setTickProfiler() may be called from the main thread
    // while flush() runs on an async task thread.
    private volatile TickProfiler tickProfiler;

    // ⚡ Bolt Optimization: Use ConcurrentLinkedQueue to avoid blocking main thread with locks
    private final Queue<PlayerEventData> playerEvents = new ConcurrentLinkedQueue<>();
    private final Queue<PerformanceEventData> performanceEvents = new ConcurrentLinkedQueue<>();
    private final Queue<ServerEventData> serverEvents = new ConcurrentLinkedQueue<>();
    private final Queue<JsonObject> profilingEvents = new ConcurrentLinkedQueue<>();

    private ApiClient apiClient;

    // Prevents multiple concurrent retry chains from stacking when the API is unreachable.
    // A top-level send (attempt == 1) is skipped when a retry chain is already active,
    // avoiding log spam and amplified load during outages.
    private final AtomicBoolean retryPending = new AtomicBoolean(false);

    // ⚡ Bolt Optimization: Reuse MessageDigest to prevent object instantiation overhead during async flush
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
     * Sets up the OkHttpClient with strict timeouts (15s) to prevent resource exhaustion.
     * </p>
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
     * Package-private constructor for testing, allowing injection of a custom {@link OkHttpClient}.
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
        // This ensures the collector doesn't run with an invalid key even if PivotPlugin validation was bypassed
        if (!PivotPlugin.isValidApiKeyFormat(this.apiKey)) {
            logger.warning("EventCollector initialized with invalid API key (must start with 'pvt_', be >= 20 chars, and alphanumeric/hyphens). Events will NOT be sent.");
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
            // We could keep old key, or disable. Disabling is safer to avoid confusion if config is broken.
            this.apiKey = null;
        } else {
            this.apiKey = trimmedKey;
        }
        if (this.apiClient != null) this.apiClient.reload();
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
    public void addPlayerEvent(String eventType, String playerUuid, String playerName, String hostname, String quitReason, Boolean sessionClean, String connectionType) {
        // Only add hostname if tracking enabled and not null
        boolean trackHostnames = plugin.getConfig().getBoolean("privacy.track-hostnames", true);
        String finalHostname = (trackHostnames && hostname != null && !hostname.isEmpty()) ? hostname : null;

        // ⚡ Bolt Optimization: Use POJO to avoid JsonObject creation on main thread
        playerEvents.add(new PlayerEventData(eventType, playerUuid, playerName, finalHostname, quitReason, sessionClean, connectionType));
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
     * @param serverVersion The version string of the server (e.g., "git-Paper-123").
     * @param pluginsLoaded The number of plugins currently loaded.
     */
    public void addServerStartEvent(String serverVersion, int pluginsLoaded) {
        // ⚡ Bolt Optimization: Use POJO to avoid JsonObject creation on main thread
        serverEvents.add(new ServerEventData(serverVersion, pluginsLoaded));
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
     * Drains event queues, anonymizes player data (if enabled), builds a JSON payload,
     * and sends it to the Pivot API. By collecting events in queues and flushing
     * them periodically, we batch network requests and minimize API overhead.
     * </p>
     * <p>
     * <b>Threading:</b> Normally invoked by a periodic async background task, so
     * anonymization (SHA-256 hashing) and JSON construction run off the main thread.
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
         * 1. This flush() method is called periodically by an async background task during normal
         *    operation, but it may also be invoked synchronously on the main thread during
         *    PivotPlugin.onDisable() for a final drain on shutdown.
         * 2. It drains events from concurrent queues directly into Gson JsonArrays.
         * 3. Costly operations such as UUID hashing are performed here; during the normal
         *    async flush path these run off the main thread, but during the onDisable() path
         *    they may run on the main thread.
         * 4. The arrays are consolidated into a single JSON payload to minimize API calls and network overhead.
         */

        // Collect Tick Profile
        JsonObject tickProfileEvent = null;
        if (tickProfiler != null) {
            tickProfileEvent = tickProfiler.collectSample();
        }

        // ⚡ Bolt Optimization: Early return if queues empty to avoid allocations
        if (playerEvents.isEmpty() && performanceEvents.isEmpty() && serverEvents.isEmpty() && tickProfileEvent == null && profilingEvents.isEmpty()) {
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
                    // RuntimeException is the only exception hashUuid() can throw: the SHA256_DIGEST
                    // ThreadLocal initializer wraps NoSuchAlgorithmException in a plain RuntimeException.
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

            if (polledEvent.hostname != null) event.addProperty("hostname", polledEvent.hostname);
            if (polledEvent.quitReason != null) event.addProperty("quit_reason", polledEvent.quitReason);
            if (polledEvent.sessionClean != null) event.addProperty("session_clean", polledEvent.sessionClean);
            if (polledEvent.connectionType != null) event.addProperty("connection_type", polledEvent.connectionType);

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

        JsonArray profilingArray = new JsonArray();
        JsonObject pe;
        while ((pe = profilingEvents.poll()) != null) {
            profilingArray.add(pe);
        }

        if (debugEnabled) {
            logger.info("Events to send - Player: " + playerArray.size() + ", Performance: " + perfArray.size() + ", Server: " + serverArray.size());
        }

        // Nothing to send (double check)
        if (playerArray.size() == 0 && perfArray.size() == 0 && serverArray.size() == 0 && tickProfileEvent == null && profilingArray.size() == 0) {
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
     * Builds the HTTP request for the API.
     * <p>
     * Validates configuration, enforces HTTPS, and sets the {@code X-API-Key} header.
     * </p>
     *
     * @param json The JSON payload to send.
     * @return The built {@link Request} object, or {@code null} if validation fails.
     */
    private Request buildRequest(String json) {
        String apiEndpoint = plugin.getApiEndpoint();

        if (apiEndpoint == null || apiKey == null) {
            logger.warning("API endpoint or key not configured. Skipping event send.");
            return null;
        }

        // SECURITY: Final check for HTTPS before sending
        if (!apiEndpoint.startsWith("https://")) {
            logger.severe("Security check failed: API endpoint must use HTTPS. Event dropped.");
            return null;
        }

        RequestBody body = RequestBody.create(
                json,
                MediaType.parse("application/json"));

        return new Request.Builder()
                .url(apiEndpoint)
                .addHeader("X-API-Key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();
    }

    /**
     * Send JSON payload to API endpoint asynchronously.
     *
     * @param json The JSON payload.
     */
    private void sendToAPI(String json) {
        sendToAPI(json, 1);
    }

    /**
     * Send JSON payload to API endpoint asynchronously with retry logic.
     */
    private void sendToAPI(String json, int attempt) {
        // Skip top-level sends while a retry chain is already active to prevent multiple
        // overlapping retry chains from piling up during an outage. Acquire the flag atomically
        // before enqueuing the HTTP call.
        if (attempt == 1) {
            if (!retryPending.compareAndSet(false, true)) {
                logger.warning("Skipping batch send: a retry chain is already pending.");
                return;
            }
        } else {
            // Ensure the flag stays raised for in-progress retry chains.
            retryPending.set(true);
        }

        Request request;
        try {
            request = buildRequest(json);
        } catch (Exception e) {
            logger.warning("Failed to build request for events: " + e.getMessage());
            retryPending.set(false);
            return;
        }

        if (request == null) {
            // If we cannot build a request (e.g., bad config or insecure endpoint),
            // ensure any pending retry chain is cleared so future sends are not suppressed.
            retryPending.set(false);
            return;
        }

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // SECURITY: Retrieve API key to ensure redaction (using request header)
                String usedApiKey = call.request().header("X-API-Key");
                String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";

                // SECURITY: Redact sensitive info (API key and regex pattern) from exception message
                errorMsg = redactSensitiveInfo(errorMsg, usedApiKey);

                logger.warning("Failed to send events: " + errorMsg);

                if (plugin.getConfig().getBoolean("debug.enabled", false)) {
                    logger.warning("Network error details: " + e.getClass().getSimpleName());
                }

                if (attempt <= 3) {
                    long delayTicks = attempt == 1 ? 100L : attempt == 2 ? 300L : 900L;
                    if (plugin.isEnabled()) {
                        logger.info("Retrying batch send in " + (delayTicks / 20) + "s (Attempt " + (attempt + 1) + "/4)");
                        retryPending.set(true);
                        org.bukkit.Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> sendToAPI(json, attempt + 1), delayTicks);
                    } else {
                        retryPending.set(false);
                    }
                } else {
                    retryPending.set(false);
                }
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    String usedApiKey = call.request().header("X-API-Key");
                    if (response.isSuccessful()) {
                        retryPending.set(false);
                        String apiVersion = response.header("X-API-Version");
                        logger.info("Connected to Pivot API version: " + apiVersion);
                        String responseBody = response.body() != null ? response.body().string() : "no body";
                        logger.info("Successfully sent events: " + redactSensitiveInfo(responseBody, usedApiKey));
                    } else {
                        String errorBody = response.body() != null ? response.body().string() : "no error details";

                        // SECURITY: Redact API key from error logs if it appears in the response
                        errorBody = redactSensitiveInfo(errorBody, usedApiKey);

                        logger.warning("Failed to send events: " + response.code() + " - " + errorBody);

                        // Specific error handling
                        if (response.code() == 401) {
                            logger.severe("Authentication failed! Check your API key in config.yml");
                            logger.severe("Make sure your API key starts with 'pvt_'");
                        } else if (response.code() == 429) {
                            logger.warning("Rate limit exceeded.");
                        } else if (response.code() == 400) {
                            logger.severe("Invalid request data. Enable debug mode for details.");
                        }

                        if (response.code() != 401 && response.code() != 400 && attempt <= 3) {
                            long delayTicks = attempt == 1 ? 100L : attempt == 2 ? 300L : 900L;
                            if (plugin.isEnabled()) {
                                logger.info("Retrying batch send in " + (delayTicks / 20) + "s (Attempt " + (attempt + 1) + "/4)");
                                retryPending.set(true);
                                org.bukkit.Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> sendToAPI(json, attempt + 1), delayTicks);
                            } else {
                                retryPending.set(false);
                            }
                        } else {
                            retryPending.set(false);
                        }
                    }
                } catch (IOException e) {
                    logger.warning("Failed to read response: " + e.getMessage());
                } finally {
                    response.close();
                }
            }
        });
    }

    /**
     * Send JSON payload to API endpoint synchronously.
     * <p>
     * Used only for critical events (like SERVER_STOP) where we cannot rely on async execution.
     * </p>
     *
     * @param json The JSON payload.
     * @throws IOException If the network request fails.
     */
    private void sendToAPISync(String json) throws IOException {
        Request request = buildRequest(json);
        if (request == null) return;

        String usedApiKey = request.header("X-API-Key");

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String apiVersion = response.header("X-API-Version");
                logger.info("Connected to Pivot API version: " + apiVersion);
                String responseBody = response.body() != null ? response.body().string() : "no body";
                logger.info("Successfully sent events: " + redactSensitiveInfo(responseBody, usedApiKey));
            } else {
                String errorBody = response.body() != null ? response.body().string() : "no error details";
                logger.warning("Failed to send events: " + response.code() + " - " + redactSensitiveInfo(errorBody, usedApiKey));
            }
        }
    }

    /**
     * Redact sensitive information (API keys) from logs.
     * <p>
     * Scrubs both the specific API key used and any pattern resembling an API key
     * to prevent leaks in stack traces or error messages.
     * </p>
     *
     * @param text   The text to sanitize.
     * @param apiKey The specific API key known to be in use (optional).
     * @return The sanitized text with keys replaced by {@code [REDACTED]} or {@code pvt_***}.
     */
    private String redactSensitiveInfo(String text, String apiKey) {
        if (text == null) return "null";
        String redacted = text;

        // Redact specific key if known
        if (apiKey != null && !apiKey.isEmpty()) {
            redacted = redacted.replace(apiKey, "[REDACTED]");
        }

        // SECURITY: Defense in Depth - Redact any pattern resembling an API key
        // Matches "pvt_" followed by at least 10 alphanumeric/underscore characters
        redacted = redacted.replaceAll("pvt_[a-zA-Z0-9_]{10,}", "pvt_***");

        return redacted;
    }

    /**
     * Redact PII (UUIDs, names, hostnames) from JSON payload for debug logging.
     * <p>
     * Ensures user privacy when {@code debug.log-batches} is enabled.
     * </p>
     *
     * @param json The raw JSON payload string.
     * @return A string representation of the JSON with PII fields replaced by {@code [REDACTED]}.
     */
    private String redactPii(String json) {
        try {
            JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            if (obj.has("player_events")) {
                JsonArray players = obj.getAsJsonArray("player_events");
                // Need to clone or rebuild to avoid modifying the original array if we were modifying objects in place
                // But parseString creates a NEW structure, so we are safe to modify 'obj'
                for (com.google.gson.JsonElement e : players) {
                    if (e.isJsonObject()) {
                        JsonObject p = e.getAsJsonObject();
                        if (p.has("player_uuid")) p.addProperty("player_uuid", "[REDACTED]");
                        if (p.has("player_name")) p.addProperty("player_name", "[REDACTED]");
                        if (p.has("hostname")) p.addProperty("hostname", "[REDACTED]");
                        if (p.has("quit_reason")) p.addProperty("quit_reason", "[REDACTED]");
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

        PlayerEventData(String eventType, String playerUuid, String playerName, String hostname, String quitReason, Boolean sessionClean, String connectionType) {
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
}
