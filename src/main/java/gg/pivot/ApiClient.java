package gg.pivot;

import okhttp3.*;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Handles API communication with exponential backoff.
 */
public class ApiClient {
    private final PivotPlugin plugin;
    private final Logger logger;
    private final OkHttpClient httpClient;
    private volatile String apiKey;
    private final AtomicLong lastSuccessfulSendAt = new AtomicLong(0);

    private static final int[] BACKOFF_SECONDS = {5, 10, 20, 40};

    public ApiClient(PivotPlugin plugin, OkHttpClient httpClient) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.httpClient = httpClient;
        this.apiKey = plugin.getApiKey();
    }

    public void reload() {
        this.apiKey = plugin.getApiKey();
    }

    public long getLastSuccessfulSendAt() {
        return lastSuccessfulSendAt.get();
    }

    private Request buildRequest(String json) {
        String apiEndpoint = plugin.getApiEndpoint();

        if (apiEndpoint == null || apiKey == null) {
            logger.warning("API endpoint or key not configured. Skipping event send.");
            return null;
        }

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

    public void sendToAPI(String json) {
        sendToAPI(json, 0);
    }

    private void sendToAPI(String json, int retryCount) {
        Request request = buildRequest(json);
        if (request == null) return;

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                String usedApiKey = call.request().header("X-API-Key");
                String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                errorMsg = redactSensitiveInfo(errorMsg, usedApiKey);

                logger.warning("Failed to send events: " + errorMsg);

                if (plugin.getConfig().getBoolean("debug.enabled", false)) {
                    logger.warning("Network error details: " + e.getClass().getSimpleName());
                }

                scheduleRetry(json, retryCount);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    String usedApiKey = call.request().header("X-API-Key");
                    if (response.isSuccessful()) {
                        String apiVersion = response.header("X-API-Version");
                        logger.info("Connected to Pivot API version: " + apiVersion);
                        String responseBody = "no body";
                        try {
                            if (response.body() != null) responseBody = response.body().string();
                        } catch (IOException e) {
                            logger.warning("Failed to read response body: " + e.getMessage());
                        }
                        logger.info("Successfully sent events: " + redactSensitiveInfo(responseBody, usedApiKey));
                        lastSuccessfulSendAt.set(System.currentTimeMillis());
                    } else {
                        String errorBody = "no error details";
                        try {
                            if (response.body() != null) errorBody = response.body().string();
                        } catch (IOException e) {
                            logger.warning("Failed to read error body: " + e.getMessage());
                        }
                        errorBody = redactSensitiveInfo(errorBody, usedApiKey);

                        logger.warning("Failed to send events: " + response.code() + " - " + errorBody);

                        if (response.code() == 401) {
                            logger.severe("Invalid API key - check config.yml");
                            // 401: DO NOT RETRY
                        } else if (response.code() == 429) {
                            String retryAfterHeader = response.header("Retry-After");
                            int delaySeconds = 60;
                            if (retryAfterHeader != null) {
                                try {
                                    delaySeconds = Integer.parseInt(retryAfterHeader);
                                } catch (NumberFormatException ignored) {}
                            }
                            logger.warning("Rate limit exceeded. Retrying after " + delaySeconds + "s.");
                            scheduleRetryCustom(json, retryCount, delaySeconds);
                        } else if (response.code() == 400) {
                            logger.severe("Invalid request data. Enable debug mode for details.");
                            // DO NOT RETRY 400
                        } else {
                            scheduleRetry(json, retryCount);
                        }
                    }
                } finally {
                    response.close();
                }
            }
        });
    }

    private void scheduleRetry(String json, int retryCount) {
        if (retryCount >= BACKOFF_SECONDS.length) {
            logger.warning("Max retries reached. Discarding batch.");
            return;
        }

        int delaySeconds = BACKOFF_SECONDS[retryCount];
        scheduleRetryCustom(json, retryCount + 1, delaySeconds);
    }

    private void scheduleRetryCustom(String json, int nextRetryCount, int delaySeconds) {
        if (!plugin.isEnabled()) return;

        long delayTicks = delaySeconds * 20L;
        logger.info("Retrying batch send in " + delaySeconds + "s (Attempt " + nextRetryCount + "/" + BACKOFF_SECONDS.length + ")");

        new BukkitRunnable() {
            @Override
            public void run() {
                if (plugin.isEnabled()) {
                    sendToAPI(json, nextRetryCount);
                }
            }
        }.runTaskLaterAsynchronously(plugin, delayTicks);
    }

    public void sendToAPISync(String json) throws IOException {
        Request request = buildRequest(json);
        if (request == null) return;

        String usedApiKey = request.header("X-API-Key");

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String apiVersion = response.header("X-API-Version");
                logger.info("Connected to Pivot API version: " + apiVersion);
                String responseBody = "no body";
                        try {
                            if (response.body() != null) responseBody = response.body().string();
                        } catch (IOException e) {
                            logger.warning("Failed to read response body: " + e.getMessage());
                        }
                logger.info("Successfully sent events: " + redactSensitiveInfo(responseBody, usedApiKey));
                lastSuccessfulSendAt.set(System.currentTimeMillis());
            } else {
                String errorBody = "no error details";
                        try {
                            if (response.body() != null) errorBody = response.body().string();
                        } catch (IOException e) {
                            logger.warning("Failed to read error body: " + e.getMessage());
                        }
                logger.warning("Failed to send events: " + response.code() + " - " + redactSensitiveInfo(errorBody, usedApiKey));
            }
        }
    }

    public static String redactSensitiveInfo(String text, String apiKey) {
        if (text == null) return "null";
        String redacted = text;

        if (apiKey != null && !apiKey.isEmpty()) {
            redacted = redacted.replace(apiKey, "[REDACTED]");
        }

        redacted = redacted.replaceAll("pvt_[a-zA-Z0-9_]{10,}", "pvt_***");

        return redacted;
    }
}
