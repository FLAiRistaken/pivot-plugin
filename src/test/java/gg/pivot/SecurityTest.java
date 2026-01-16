package gg.pivot;

import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class SecurityTest {

    @Mock
    private PivotPlugin plugin;

    @Mock
    private FileConfiguration config;

    @Mock
    private Logger logger;

    @Test
    public void testApiKeyTrimming() throws Exception {
        // Arrange
        String untrimmedKey = " pvt_secret_key ";
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(logger);
        // EventCollector calls getString("api.key", "")
        when(config.getString("api.key", "")).thenReturn(untrimmedKey);

        // Act
        EventCollector collector = new EventCollector(plugin);

        // Assert
        Field apiKeyField = EventCollector.class.getDeclaredField("apiKey");
        apiKeyField.setAccessible(true);
        String storedKey = (String) apiKeyField.get(collector);

        // Expectation: The key should be trimmed to ensure redaction works correctly
        assertEquals("pvt_secret_key", storedKey, "API Key should be trimmed of whitespace");
    }
}
