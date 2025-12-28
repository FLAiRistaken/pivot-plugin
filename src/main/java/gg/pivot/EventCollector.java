package gg.pivot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.*;

import java.io.IOException;
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

    // UPDATED: Added hostname parameter
    public void addPlayerEvent(String eventType, String playerUuid, String playerName, String hostname) {
        JsonObject event = new JsonObject();
        event.addProperty("timestamp", System.currentTimeMillis());
        event.addProperty("event_type", eventType);
        event.addProperty("player_uuid", playerUuid);
        event.addProperty("player_name", playerName);

        // Only add hostname if not null (for marketing attribution)
        if (hostname != null && !hostname.isEmpty()) {
            event.addProperty("hostname", hostname);
        }

        synchronized (playerEvents) {
            playerEvents.add(event);
        }
    }

    public void addPerformanceEvent(double tps, int playerCount) {
        JsonObject event = new JsonObject();
        event.addProperty("timestamp", System.currentTimeMillis());
        event.addProperty("tps", tps);
        event.addProperty("player_count", playerCount);

        synchronized (performanceEvents) {
            performanceEvents.add(event);
        }
    }

    public void flush() {
        logger.info("Flush called - checking for events to send");

        List<JsonObject> playerBatch;
        List<JsonObject> perfBatch;

        // Copy and clear lists
        synchronized (playerEvents) {
            playerBatch = new ArrayList<>(playerEvents);
            playerEvents.clear();
        }

        synchronized (performanceEvents) {
            perfBatch = new ArrayList<>(performanceEvents);
            performanceEvents.clear();
        }

        logger.info("Events to send - Player: " + playerBatch.size() + ", Performance: " + perfBatch.size());

        if (playerBatch.isEmpty() && perfBatch.isEmpty()) {
            logger.info("No events to send");
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

        // Send to API
        sendToAPI(payload.toString());
    }

    private void sendToAPI(String json) {
        String apiEndpoint = plugin.getConfig().getString("pivot.api-endpoint");
        String apiKey = plugin.getConfig().getString("pivot.api-key");

        RequestBody body = RequestBody.create(
            json,
            MediaType.parse("application/json"));

        Request request = new Request.Builder()
            .url(apiEndpoint)
            .addHeader("Authorization", "Bearer " + apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                logger.warning("Failed to send events: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    logger.info("Successfully sent events: " + response.body().string());
                } else {
                    logger.warning("Failed to send events: " + response.code() + " - " + response.body().string());
                }
                response.close();
            }
        });
    }
}
