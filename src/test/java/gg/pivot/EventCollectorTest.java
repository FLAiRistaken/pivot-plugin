package gg.pivot;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
}
