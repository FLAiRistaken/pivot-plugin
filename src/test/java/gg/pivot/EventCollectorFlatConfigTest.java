package gg.pivot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.bukkit.configuration.file.FileConfiguration;

import java.lang.reflect.Field;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
public class EventCollectorFlatConfigTest {

    @Mock
    PivotPlugin plugin;

    @Mock
    FileConfiguration config;

    @Mock
    Logger logger;

    @Test
    public void testFlatConfigKeys() throws Exception {
        // Setup mocks
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getApiKey()).thenCallRealMethod(); // Call the real method on the mock

        // Simulate flat config structure (what the user has)
        // Nested keys return null
        when(config.getString("api.key")).thenReturn(null);

        // Flat keys return values
        when(config.getString("api-key")).thenReturn("pvt_flat_key_1234567890");

        EventCollector collector = new EventCollector(plugin);

        // Access private apiKey field
        Field apiKeyField = EventCollector.class.getDeclaredField("apiKey");
        apiKeyField.setAccessible(true);
        String apiKey = (String) apiKeyField.get(collector);

        // This assertion creates the failure: currently it will be null because code doesn't read flat keys
        // After fix, this should pass
        assertEquals("pvt_flat_key_1234567890", apiKey, "Should support flat api-key config");
    }

    @Test
    public void testKeyWithHyphens() throws Exception {
        // Setup mocks
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getApiKey()).thenCallRealMethod(); // Call the real method on the mock

        // Key with hyphens
        String hyphenKey = "pvt_key-with-hyphens-1234567890";
        when(config.getString("api.key")).thenReturn(hyphenKey);

        EventCollector collector = new EventCollector(plugin);

        Field apiKeyField = EventCollector.class.getDeclaredField("apiKey");
        apiKeyField.setAccessible(true);
        String apiKey = (String) apiKeyField.get(collector);

        // This assertion creates the failure: currently regex rejects hyphens
        // After fix, this should pass
        assertEquals(hyphenKey, apiKey, "Should accept keys with hyphens");
    }
}
