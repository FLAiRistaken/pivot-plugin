package gg.pivot;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The shipped config.yml and the code fallbacks must agree, and the defaults must validate cleanly. */
public class ConfigDefaultsTest {
    private FileConfiguration bundled;

    @BeforeEach
    public void loadBundledConfig() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml must be on the classpath");
            bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    public void testBundledBatchIntervalIsThirtySecondsAndMatchesTheCodeFallback() {
        assertEquals(30, bundled.getInt("collection.batch-interval"));
        assertEquals(PivotPlugin.DEFAULT_BATCH_INTERVAL_SECONDS, bundled.getInt("collection.batch-interval"),
                "config.yml and PivotPlugin.DEFAULT_BATCH_INTERVAL_SECONDS must not drift apart");
    }

    @Test
    public void testNoCodeDefaultStillUsesSixtySeconds() throws Exception {
        java.nio.file.Path root = java.nio.file.Paths.get("src/main/java/gg/pivot");
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path file : (Iterable<java.nio.file.Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String source = new String(java.nio.file.Files.readAllBytes(file), StandardCharsets.UTF_8);
                assertFalse(source.matches("(?s).*\"collection\\.batch-interval\"\\s*,\\s*60\\s*\\).*"),
                        file + " still falls back to a 60s batch interval");
            }
        }
    }

    private List<LogRecord> validate(int batchInterval, int tpsInterval) throws Exception {
        FileConfiguration config = new YamlConfiguration();
        config.set("collection.batch-interval", batchInterval);
        config.set("collection.tps-sample-interval", tpsInterval);

        PivotPlugin plugin = mock(PivotPlugin.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getApiKey()).thenReturn("pvt_validkey1234567890");
        when(plugin.getApiEndpoint()).thenReturn("https://api.pivotmc.dev/v1/ingest");
        doCallRealMethod().when(plugin).validateConfig();

        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        List<LogRecord> records = new ArrayList<>();
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        Field loggerField = PivotPlugin.class.getDeclaredField("logger");
        loggerField.setAccessible(true);
        loggerField.set(plugin, logger);

        assertTrue(plugin.validateConfig());
        return records;
    }

    @Test
    public void testShippedDefaultsValidateWithoutWarnings() throws Exception {
        List<LogRecord> records = validate(bundled.getInt("collection.batch-interval"),
                bundled.getInt("collection.tps-sample-interval"));
        assertTrue(records.stream().noneMatch(r -> r.getLevel().intValue() >= Level.WARNING.intValue()),
                "fresh installs must not start with a warning: " + records.stream().map(LogRecord::getMessage).reduce("", String::concat));
    }

    @Test
    public void testTpsIntervalLongerThanTheBatchStillWarns() throws Exception {
        List<LogRecord> records = validate(30, 60);
        assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.WARNING
                && r.getMessage().contains("tps-sample-interval")));
    }
}
