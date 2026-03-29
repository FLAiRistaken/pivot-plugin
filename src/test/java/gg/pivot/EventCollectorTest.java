package gg.pivot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Queue;
import java.util.logging.Logger;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;

@ExtendWith(MockitoExtension.class)
public class EventCollectorTest {

    @Mock
    PivotPlugin plugin;

    @Mock
    org.bukkit.configuration.file.FileConfiguration config;

    @Test
    public void testRedactSensitiveInfo() throws Exception {
        // Setup mocks for EventCollector constructor
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getApiKey()).thenCallRealMethod();
        when(config.getString("api.key")).thenReturn("pvt_validkey1234567890");
        when(plugin.getLogger()).thenReturn(Logger.getGlobal());

        EventCollector collector = new EventCollector(plugin);

        Method redactMethod = EventCollector.class.getDeclaredMethod("redactSensitiveInfo", String.class, String.class);
        redactMethod.setAccessible(true);

        String apiKey = "pvt_secret1234567890";

        // Test 1: Specific API key redaction
        String message = "Failed to connect to https://api.pivotmc.dev/v1/ingest?key=pvt_secret1234567890";
        String redacted = (String) redactMethod.invoke(collector, message, apiKey);
        assertEquals("Failed to connect to https://api.pivotmc.dev/v1/ingest?key=[REDACTED]", redacted, "Should redact specific API key");

        // Test 2: Generic pattern redaction (Defense in Depth)
        // This asserts the behavior I plan to implement (redacting pvt_... tokens)
        String otherKey = "pvt_othersecret12345";
        String message2 = "Another key leaked: pvt_othersecret12345 in the wild";
        String redacted2 = (String) redactMethod.invoke(collector, message2, apiKey);

        // Note: The regex I plan is pvt_[a-zA-Z0-9_]{10,} -> pvt_***
        // pvt_othersecret12345 is > 10 chars.
        assertEquals("Another key leaked: pvt_*** in the wild", redacted2, "Should redact generic pvt_ pattern");

        // Test 3: Short pvt_ tokens (might be false positives, e.g. pvt_ltd)
        // If I use {10,} it should skip short ones.
        String safeMessage = "This is pvt_ltd company";
        String redacted3 = (String) redactMethod.invoke(collector, safeMessage, apiKey);
        assertEquals(safeMessage, redacted3, "Should NOT redact short pvt_ tokens");
    }

    @Test
    public void testApiKeyValidation() throws Exception {
        // Setup mocks
        when(plugin.getLogger()).thenReturn(Logger.getGlobal());
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getApiKey()).thenCallRealMethod();

        // Helper to check apiKey field via reflection
        Field apiKeyField = EventCollector.class.getDeclaredField("apiKey");
        apiKeyField.setAccessible(true);

        // Test 1: Valid Key
        when(config.getString("api.key")).thenReturn("pvt_validkey1234567890");
        EventCollector collector = new EventCollector(plugin);
        assertEquals("pvt_validkey1234567890", apiKeyField.get(collector), "Valid key should be accepted");

        // Test 2: Invalid Characters (Security Enhancement)
        when(config.getString("api.key")).thenReturn("pvt_invalidkey!@#$$%^");
        EventCollector collector2 = new EventCollector(plugin);
        assertNull(apiKeyField.get(collector2), "Key with invalid chars should be rejected (null)");

        // Test 3: Too Short
        when(config.getString("api.key")).thenReturn("pvt_short");
        EventCollector collector3 = new EventCollector(plugin);
        assertNull(apiKeyField.get(collector3), "Short key should be rejected (null)");

        // Test 4: Wrong Prefix
        when(config.getString("api.key")).thenReturn("abc_validlenghtbutwrongprefix");
        EventCollector collector4 = new EventCollector(plugin);
        assertNull(apiKeyField.get(collector4), "Key with wrong prefix should be rejected (null)");
    }

    @Test
    public void testFlushSerializesPerformanceAndServerEventsAndDrainsQueues() throws Exception {
        // Setup: valid API key and endpoint so flush() proceeds to build and send JSON
        when(plugin.getLogger()).thenReturn(Logger.getGlobal());
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getApiKey()).thenReturn("pvt_validkey1234567890");
        when(config.getBoolean("debug.enabled", false)).thenReturn(false);
        when(config.getBoolean("debug.log-batches", false)).thenReturn(false);
        when(config.getBoolean("privacy.anonymize-players", false)).thenReturn(false);
        when(plugin.getApiEndpoint()).thenReturn("https://api.example.com/v1/ingest");

        // Inject a mock OkHttpClient via the package-private constructor to capture the outgoing HTTP request
        OkHttpClient mockHttpClient = mock(OkHttpClient.class);
        Call mockCall = mock(Call.class);
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        when(mockHttpClient.newCall(requestCaptor.capture())).thenReturn(mockCall);
        doNothing().when(mockCall).enqueue(any(Callback.class));

        EventCollector collector = new EventCollector(plugin, mockHttpClient);

        // Obtain references to the private queues to assert their state
        Field perfEventsField = EventCollector.class.getDeclaredField("performanceEvents");
        perfEventsField.setAccessible(true);
        Queue<?> perfQueue = (Queue<?>) perfEventsField.get(collector);

        Field serverEventsField = EventCollector.class.getDeclaredField("serverEvents");
        serverEventsField.setAccessible(true);
        Queue<?> serverQueue = (Queue<?>) serverEventsField.get(collector);

        // Enqueue one performance event and one server start event
        collector.addPerformanceEvent(19.5, 7);
        collector.addServerStartEvent("git-Paper-123", 15);

        assertEquals(1, perfQueue.size(), "Performance event should be queued before flush");
        assertEquals(1, serverQueue.size(), "Server event should be queued before flush");

        // Run flush – this drains the queues and serializes events to JSON
        collector.flush();

        // Queues must be empty after flush
        assertEquals(0, perfQueue.size(), "Performance event queue must be empty after flush");
        assertEquals(0, serverQueue.size(), "Server event queue must be empty after flush");

        // Parse the captured JSON payload and verify field names
        Request capturedRequest = requestCaptor.getValue();
        assertNotNull(capturedRequest, "HTTP request should have been captured");
        assertNotNull(capturedRequest.body(), "HTTP request body should not be null");
        Buffer buffer = new Buffer();
        capturedRequest.body().writeTo(buffer);
        String json = buffer.readUtf8();

        JsonObject payload = JsonParser.parseString(json).getAsJsonObject();

        // Verify performance event JSON fields
        JsonArray perfArray = payload.getAsJsonArray("performance_events");
        assertNotNull(perfArray, "performance_events array must be present in payload");
        assertEquals(1, perfArray.size(), "Exactly one performance event must be serialized");
        JsonObject perfEvent = perfArray.get(0).getAsJsonObject();
        assertTrue(perfEvent.has("timestamp"), "Performance event must have 'timestamp' field");
        assertTrue(perfEvent.has("tps"), "Performance event must have 'tps' field");
        assertTrue(perfEvent.has("player_count"), "Performance event must have 'player_count' field");
        assertEquals(19.5, perfEvent.get("tps").getAsDouble(), 0.001);
        assertEquals(7, perfEvent.get("player_count").getAsInt());

        // Verify server event JSON fields, including the hardcoded SERVER_START event_type
        JsonArray serverArray = payload.getAsJsonArray("server_events");
        assertNotNull(serverArray, "server_events array must be present in payload");
        assertEquals(1, serverArray.size(), "Exactly one server event must be serialized");
        JsonObject serverEvent = serverArray.get(0).getAsJsonObject();
        assertTrue(serverEvent.has("timestamp"), "Server event must have 'timestamp' field");
        assertTrue(serverEvent.has("event_type"), "Server event must have 'event_type' field");
        assertEquals("SERVER_START", serverEvent.get("event_type").getAsString(),
                "Server event_type must be 'SERVER_START'");
        assertTrue(serverEvent.has("server_version"), "Server event must have 'server_version' field");
        assertEquals("git-Paper-123", serverEvent.get("server_version").getAsString());
        assertTrue(serverEvent.has("plugins_loaded"), "Server event must have 'plugins_loaded' field");
        assertEquals(15, serverEvent.get("plugins_loaded").getAsInt());
    }

    @Test
    public void testFlushDoesNotDropTickProfileEventWhenQueuesEmpty() throws Exception {
        // Use a mock logger so we can assert on log messages
        Logger mockLogger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(mockLogger);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getApiKey()).thenReturn(null); // sendToAPI will be a no-op; this test only verifies early-return logic
        // Enable debug mode so the "No events to send" path becomes observable
        when(config.getBoolean("debug.enabled", false)).thenReturn(true);
        when(config.getBoolean("debug.log-batches", false)).thenReturn(false);
        when(config.getBoolean("privacy.anonymize-players", false)).thenReturn(false);

        EventCollector collector = new EventCollector(plugin);

        // Create a mock TickProfiler that returns a non-null sample event
        TickProfiler mockProfiler = mock(TickProfiler.class);
        JsonObject profileEvent = new JsonObject();
        profileEvent.addProperty("event_type", "TICK_PROFILE");
        profileEvent.addProperty("timestamp", 1234567890L);
        when(mockProfiler.collectSample()).thenReturn(profileEvent);
        collector.setTickProfiler(mockProfiler);

        // All event queues are empty – only the tick profile event is present
        collector.flush();

        // Verify collectSample() was invoked
        verify(mockProfiler).collectSample();
        // Verify the early-return was NOT taken (the "No events to send" log must NOT appear)
        verify(mockLogger, never()).info("No events to send");
    }

    @Test
    public void testRetryPendingCoalescesAndClearsOnSuccess() throws Exception {
        when(plugin.getLogger()).thenReturn(Logger.getGlobal());
        when(plugin.getApiKey()).thenReturn("pvt_validkey1234567890");
        when(plugin.getApiEndpoint()).thenReturn("https://api.example.com/v1/ingest");

        OkHttpClient mockHttpClient = mock(OkHttpClient.class);
        Call mockCall = mock(Call.class);
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        ArgumentCaptor<Callback> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
        when(mockHttpClient.newCall(requestCaptor.capture())).thenReturn(mockCall);
        doNothing().when(mockCall).enqueue(callbackCaptor.capture());

        EventCollector collector = new EventCollector(plugin, mockHttpClient);

        Field retryPendingField = EventCollector.class.getDeclaredField("retryPending");
        retryPendingField.setAccessible(true);
        AtomicBoolean retryPending = (AtomicBoolean) retryPendingField.get(collector);

        Method sendToApi = EventCollector.class.getDeclaredMethod("sendToAPI", String.class, int.class);
        sendToApi.setAccessible(true);

        sendToApi.invoke(collector, "{}", 1);
        assertTrue(retryPending.get(), "retryPending should be set for the first send");

        // A second top-level send while retryPending is true should be coalesced (no new call).
        sendToApi.invoke(collector, "{}", 1);
        verify(mockHttpClient, times(1)).newCall(any(Request.class));

        // Complete the in-flight request successfully to clear the flag.
        Callback cb = callbackCaptor.getValue();
        assertNotNull(cb, "Callback should be captured");
        Request builtRequest = requestCaptor.getValue();

        when(mockCall.request()).thenReturn(builtRequest); // Fixed: Mock the request on the Call object

        // Add a mock ResponseBody to prevent the "response is not eligible for a body" error
        okhttp3.ResponseBody dummyBody = okhttp3.ResponseBody.create("{\"status\":\"success\"}", okhttp3.MediaType.parse("application/json"));

        Response successResponse = new Response.Builder()
                .code(200)
                .protocol(Protocol.HTTP_1_1)
                .message("OK")
                .request(builtRequest)
                .body(dummyBody)
                .build();
        cb.onResponse(mockCall, successResponse);

        assertFalse(retryPending.get(), "retryPending should clear after successful response");
    }

    @Test
    public void testRetryPendingClearedOnBuildFailure() throws Exception {
        when(plugin.getLogger()).thenReturn(Logger.getGlobal());
        when(plugin.getApiKey()).thenReturn("pvt_validkey1234567890");
        // Build request will fail because endpoint is missing
        when(plugin.getApiEndpoint()).thenReturn(null);

        OkHttpClient mockHttpClient = mock(OkHttpClient.class);
        EventCollector collector = new EventCollector(plugin, mockHttpClient);

        Field retryPendingField = EventCollector.class.getDeclaredField("retryPending");
        retryPendingField.setAccessible(true);
        AtomicBoolean retryPending = (AtomicBoolean) retryPendingField.get(collector);

        Method sendToApi = EventCollector.class.getDeclaredMethod("sendToAPI", String.class, int.class);
        sendToApi.setAccessible(true);

        sendToApi.invoke(collector, "{}", 1);

        assertFalse(retryPending.get(), "retryPending should be cleared when buildRequest fails");
        verify(mockHttpClient, never()).newCall(any(Request.class));
    }

    @Test
    public void testHashUuidIsDeterministicAndReusesThreadLocal() throws Exception {
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getApiKey()).thenCallRealMethod();
        when(config.getString("api.key")).thenReturn("pvt_validkey1234567890");
        when(plugin.getLogger()).thenReturn(Logger.getGlobal());

        EventCollector collector = new EventCollector(plugin);

        // Verify ThreadLocal reuse: the same MessageDigest instance must be returned on every get() within this thread
        Field sha256Field = EventCollector.class.getDeclaredField("SHA256_DIGEST");
        sha256Field.setAccessible(true);
        @SuppressWarnings("unchecked")
        ThreadLocal<MessageDigest> threadLocal = (ThreadLocal<MessageDigest>) sha256Field.get(null);
        MessageDigest instanceBefore = threadLocal.get();

        Method hashUuidMethod = EventCollector.class.getDeclaredMethod("hashUuid", String.class);
        hashUuidMethod.setAccessible(true);

        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        String result1 = (String) hashUuidMethod.invoke(collector, uuid);
        String result2 = (String) hashUuidMethod.invoke(collector, uuid);

        MessageDigest instanceAfter = threadLocal.get();

        // Must be non-null and exactly 64 hex characters (SHA-256 output)
        assertNotNull(result1, "hashUuid must return a non-null result");
        assertEquals(64, result1.length(), "SHA-256 hash must be 64 hex characters");

        // Deterministic: same UUID must always produce the same hash
        assertEquals(result1, result2, "hashUuid must be deterministic for the same input");

        // Different UUIDs must produce different hashes
        String otherUuid = "00000000-0000-0000-0000-000000000001";
        String result3 = (String) hashUuidMethod.invoke(collector, otherUuid);
        assertNotEquals(result1, result3, "hashUuid must distinguish different UUIDs");

        // Explicit ThreadLocal reuse: the same MessageDigest instance must be returned across all calls on this thread
        assertSame(instanceBefore, instanceAfter, "SHA256_DIGEST ThreadLocal must reuse the same MessageDigest instance within a thread");
    }
}
