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
import java.util.logging.Logger;

public class EventCollector {
    private final PivotPlugin plugin;
    private final Logger logger;
    private final OkHttpClient httpClient;

    private final List<JsonObject> playerEvents = new ArrayList<>();
    private final List<JsonObject> performanceEvents = new ArrayList<>();

    public EventCollector(PivotPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.httpClient = new OkHttpClient();
    }

    /**
     * Get current player event queue size (for /pivot status)
     */
    public int getPlayerEventCount() {
        synchronized (playerEvents) {
            return playerEvents.size();
        }
    }

    /**
     * Get current performance event queue size (for /pivot status)
     */
    public int getPerformanceEventCount() {
        synchronized (performanceEvents) {
            return performanceEvents.size();
        }
    }

    /**
     * Add a player event (JOIN/QUIT)
     */
    public void addPlayerEvent(String eventType, String playerUuid, String playerName, String hostname) {
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

        synchronized (playerEvents) {
            playerEvents.add(event);
        }
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

        synchronized (performanceEvents) {
            performanceEvents.add(event);
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

        List<JsonObject> playerBatch;
        List<JsonObject> perfBatch;

        // Copy and clear lists atomically
        synchronized (playerEvents) {
            playerBatch = new ArrayList<>(playerEvents);
            playerEvents.clear();
        }

        synchronized (performanceEvents) {
            perfBatch = new ArrayList<>(performanceEvents);
            performanceEvents.clear();
        }

        if (debugEnabled) {
            logger.info("Events to send - Player: " + playerBatch.size() + ", Performance: " + perfBatch.size());
        }

        // Nothing to send
        if (playerBatch.isEmpty() && perfBatch.isEmpty()) {
            if (debugEnabled) {
                logger.info("No events to send");
            }
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

        String json = payload.toString();

        // Log full payload if debug enabled
        if (logBatches) {
            logger.info("Sending batch payload: " + json);
        }

        // Send to API
        sendToAPI(json);
    }

    /**
     * Send JSON payload to API endpoint
     */
    private void sendToAPI(String json) {
        String apiEndpoint = plugin.getConfig().getString("api.endpoint");
        String apiKey = plugin.getConfig().getString("api.key");

        if (apiEndpoint == null || apiKey == null) {
            logger.warning("API endpoint or key not configured. Skipping event send.");
            return;
        }

        RequestBody body = RequestBody.create(
                json,
                MediaType.parse("application/json"));

        Request request = new Request.Builder()
                .url(apiEndpoint)
                .addHeader("X-API-Key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

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
                    if (response.isSuccessful()) {
                        String responseBody = response.body() != null ? response.body().string() : "no body";
                        logger.info("Successfully sent events: " + responseBody);
                    } else {
                        String errorBody = response.body() != null ? response.body().string() : "no error details";
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
}
