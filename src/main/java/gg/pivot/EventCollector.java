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
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Collects and batches events for sending to the Pivot API.
 * <p>
 * This class uses {@link java.util.concurrent.ConcurrentLinkedQueue} to store events
 * efficiently without blocking the main server thread. Events are flushed periodically
 * by an asynchronous task in {@link PivotPlugin}.
 * </p>
 */
public class EventCollector {
    private final PivotPlugin plugin;
    private final Logger logger;
    private final OkHttpClient httpClient;
    private volatile String apiKey;

    // ⚡ Bolt Optimization: Use ConcurrentLinkedQueue to avoid blocking main thread with locks
    private final Queue<JsonObject> playerEvents = new ConcurrentLinkedQueue<>();
    private final Queue<JsonObject> performanceEvents = new ConcurrentLinkedQueue<>();
    private final Queue<JsonObject> serverEvents = new ConcurrentLinkedQueue<>();

    /**
     * Initializes the EventCollector.
     * <p>
     * Sets up the OkHttpClient with strict timeouts (15s) to prevent resource exhaustion.
     * </p>
     * @param plugin The main plugin instance
     */
    public EventCollector(PivotPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();

        String key = plugin.getConfig().getString("api.key");
        this.apiKey = key != null ? key.trim() : null; // SECURITY: Trim whitespace to prevent leakage

        // SECURITY: Set explicit timeouts to prevent resource exhaustion
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    /**
     * Reloads configuration values (API key)
     */
    public void reload() {
        String key = plugin.getConfig().getString("api.key");
        this.apiKey = key != null ? key.trim() : null; // SECURITY: Trim whitespace
    }

    /**
     * Get current player event queue size (for /pivot status)
     */
    public int getPlayerEventCount() {
        return playerEvents.size();
    }

    /**
     * Get current performance event queue size (for /pivot status)
     */
    public int getPerformanceEventCount() {
        return performanceEvents.size();
    }

    /**
     * Add a player event (JOIN/QUIT)
     */
    public void addPlayerEvent(String eventType, String playerUuid, String playerName, String hostname, String quitReason, Boolean sessionClean, String connectionType) {
        JsonObject event = new JsonObject();
        event.addProperty("timestamp", System.currentTimeMillis());
        event.addProperty("event_type", eventType);

        // FEATURE: UUID anonymization
        boolean anonymize = plugin.getConfig().getBoolean("privacy.anonymize-players", false);
        if (anonymize) {
            String hashedUuid = hashUuid(playerUuid);
            event.addProperty("player_uuid", hashedUuid);
            event.addProperty("player_name", "Player"); // Generic name for privacy
        } else {
            event.addProperty("player_uuid", playerUuid);
            event.addProperty("player_name", playerName);
        }

        // Only add hostname if tracking enabled and not null
        boolean trackHostnames = plugin.getConfig().getBoolean("privacy.track-hostnames", true);
        if (trackHostnames && hostname != null && !hostname.isEmpty()) {
            event.addProperty("hostname", hostname);
        }

        if (quitReason != null) {
            event.addProperty("quit_reason", quitReason);
        }
        if (sessionClean != null) {
            event.addProperty("session_clean", sessionClean);
        }
        if (connectionType != null) {
            event.addProperty("connection_type", connectionType);
        }

        playerEvents.add(event);
    }

    /**
     * Hash a UUID using SHA-256 for anonymization
     *
     * @param uuid Player UUID string
     * @return Hashed UUID (64 hex characters)
     */
    private String hashUuid(String uuid) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
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
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 should always be available
            logger.warning("SHA-256 not available! Using plain UUID.");
            return uuid;
        }
    }

    /**
     * Add a performance event (TPS sample)
     */
    public void addPerformanceEvent(double tps, int playerCount) {
        JsonObject event = new JsonObject();
        event.addProperty("timestamp", System.currentTimeMillis());
        event.addProperty("tps", tps);
        event.addProperty("player_count", playerCount);

        performanceEvents.add(event);
    }

    /**
     * Add a server start event
     */
    public void addServerStartEvent(String serverVersion, int pluginsLoaded) {
        JsonObject event = new JsonObject();
        event.addProperty("timestamp", System.currentTimeMillis());
        event.addProperty("event_type", "SERVER_START");
        event.addProperty("server_version", serverVersion);
        event.addProperty("plugins_loaded", pluginsLoaded);

        serverEvents.add(event);
    }

    /**
     * Send a server stop event synchronously
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
            sendToAPISync(payload.toString());
        } catch (IOException e) {
            logger.warning("Failed to send SERVER_STOP event: " + e.getMessage());
        }
    }

    /**
     * Flush all collected events to API
     */
    public void flush() {
        boolean debugEnabled = plugin.getConfig().getBoolean("debug.enabled", false);
        boolean logBatches = plugin.getConfig().getBoolean("debug.log-batches", false);

        if (debugEnabled) {
            logger.info("Flush called - checking for events to send");
        }

        // ⚡ Bolt Optimization: Early return if queues empty to avoid ArrayList allocations
        if (playerEvents.isEmpty() && performanceEvents.isEmpty() && serverEvents.isEmpty()) {
            if (debugEnabled) {
                logger.info("No events to send");
            }
            return;
        }

        // Drain queues into local batches
        List<JsonObject> playerBatch = new ArrayList<>();
        JsonObject polledEvent;
        while ((polledEvent = playerEvents.poll()) != null) {
            playerBatch.add(polledEvent);
        }

        List<JsonObject> perfBatch = new ArrayList<>();
        while ((polledEvent = performanceEvents.poll()) != null) {
            perfBatch.add(polledEvent);
        }

        List<JsonObject> serverBatch = new ArrayList<>();
        while ((polledEvent = serverEvents.poll()) != null) {
            serverBatch.add(polledEvent);
        }

        if (debugEnabled) {
            logger.info("Events to send - Player: " + playerBatch.size() + ", Performance: " + perfBatch.size() + ", Server: " + serverBatch.size());
        }

        // Nothing to send (double check after drain)
        if (playerBatch.isEmpty() && perfBatch.isEmpty() && serverBatch.isEmpty()) {
            return;
        }

        // Build JSON payload
        JsonObject payload = new JsonObject();
        payload.addProperty("batch_timestamp", System.currentTimeMillis());

        JsonArray playerArray = new JsonArray();
        for (JsonObject event : playerBatch) {
            playerArray.add(event);
        }
        payload.add("player_events", playerArray);

        JsonArray perfArray = new JsonArray();
        for (JsonObject event : perfBatch) {
            perfArray.add(event);
        }
        payload.add("performance_events", perfArray);

        JsonArray serverArray = new JsonArray();
        for (JsonObject event : serverBatch) {
            serverArray.add(event);
        }
        payload.add("server_events", serverArray);

        String json = payload.toString();

        // Log full payload if debug enabled
        if (logBatches) {
            // SECURITY: Redact PII from debug logs
            logger.info("Sending batch payload: " + redactPii(json));
        }

        // Send to API
        sendToAPI(json);
    }

    /**
     * Builds the HTTP request for the API
     * @param json The JSON payload
     * @return The built Request object or null if validation fails
     */
    private Request buildRequest(String json) {
        String apiEndpoint = plugin.getConfig().getString("api.endpoint");

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
     * Send JSON payload to API endpoint
     */
    private void sendToAPI(String json) {
        Request request = buildRequest(json);
        if (request == null) return;

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                logger.warning("Failed to send events: " + e.getMessage());

                if (plugin.getConfig().getBoolean("debug.enabled", false)) {
                    logger.warning("Network error details: " + e.getClass().getSimpleName());
                }
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    String usedApiKey = call.request().header("X-API-Key");
                    if (response.isSuccessful()) {
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
                            logger.warning("Rate limit exceeded. Events will be sent in next batch.");
                        } else if (response.code() == 400) {
                            logger.severe("Invalid request data. Enable debug mode for details.");
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
     * Redact sensitive information (API key) from logs
     */
    private String redactSensitiveInfo(String text, String apiKey) {
        if (apiKey != null && !apiKey.isEmpty() && text.contains(apiKey)) {
            return text.replace(apiKey, "[REDACTED]");
        }
        return text;
    }

    /**
     * Redact PII (UUIDs, names, hostnames) from JSON payload for logging
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
                    }
                }
            }
            return obj.toString();
        } catch (Exception e) {
            // Fallback if parsing fails
            return "[Unable to redact PII - Payload Hidden]";
        }
    }
}
