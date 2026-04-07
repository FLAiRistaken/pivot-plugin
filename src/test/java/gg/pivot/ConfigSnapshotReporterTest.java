package gg.pivot;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ConfigSnapshotReporterTest {

    private PivotPlugin plugin;
    private EventCollector eventCollector;
    private TickProfiler tickProfiler;
    private ChunkProfiler chunkProfiler;
    private ConfigSnapshotReporter reporter;
    private FileConfiguration config;
    private Server server;

    @BeforeEach
    public void setUp() {
        plugin = mock(PivotPlugin.class);
        eventCollector = mock(EventCollector.class);
        tickProfiler = mock(TickProfiler.class);
        chunkProfiler = mock(ChunkProfiler.class);
        config = mock(FileConfiguration.class);
        server = mock(Server.class);

        PluginDescriptionFile pdf = mock(PluginDescriptionFile.class);
        doReturn("1.4.0").when(pdf).getVersion();
        doReturn(pdf).when(plugin).getDescription();
        doReturn(config).when(plugin).getConfig();
        doReturn(server).when(plugin).getServer();


        java.util.logging.Logger mockLogger = mock(java.util.logging.Logger.class);
        doReturn(mockLogger).when(server).getLogger();
        try {
            if (Bukkit.getServer() == null) {
                Bukkit.setServer(server);
            }
        } catch (UnsupportedOperationException e) {
            // Already set or unsupported in test environment
        }

        doReturn("Paper 1.20.4").when(server).getVersion();

        // Setup default mock config values
        doReturn("https://api.pivotmc.dev/v1/ingest").when(config).getString("api.endpoint");
        doReturn(true).when(config).getBoolean("collection.enabled");
        doReturn(30).when(config).getInt("collection.batch-interval");
        doReturn(true).when(config).getBoolean("collection.track-player-events");
        doReturn(true).when(config).getBoolean("collection.track-performance");
        doReturn(5).when(config).getInt("collection.tps-sample-interval");
        doReturn(false).when(config).getBoolean("privacy.anonymize-players");
        doReturn(true).when(config).getBoolean("privacy.track-hostnames");
        doReturn(false).when(config).getBoolean("debug.enabled");
        doReturn(false).when(config).getBoolean("debug.log-batches");
        doReturn(true).when(config).getBoolean("profiling.enabled");
        doReturn("auto").when(config).getString("profiling.mode");
        doReturn(false).when(config).getBoolean("profiling.chunk_profiling.enabled");
        doReturn(0.5).when(config).getDouble("profiling.chunk_profiling.overhead_threshold_ms");
        doReturn(false).when(config).getBoolean("profiling.command_profiling.enabled");
        doReturn(100).when(config).getInt("profiling.command_profiling.slow_threshold_ms");
        doReturn(0.2).when(config).getDouble("profiling.performance.max_overhead_ms");
        doReturn(true).when(config).getBoolean("profiling.performance.auto_disable_on_overhead");
        doReturn(30).when(config).getInt("profiling.sampling.duration_seconds");
        doReturn(5).when(config).getInt("profiling.sampling.interval_minutes");
        doReturn(true).when(config).getBoolean("profiling.triggers.auto_profile_on_lag");
        doReturn(18.0).when(config).getDouble("profiling.triggers.lag_threshold_tps");
        doReturn(true).when(config).getBoolean("profiling.privacy.share_anonymous_data");
        doReturn(false).when(config).getBoolean("profiling.privacy.anonymize_plugin_names");

        doReturn(false).when(tickProfiler).isAutoDisabled();
        doReturn(false).when(chunkProfiler).isAutoDisabled();

        reporter = new ConfigSnapshotReporter(plugin, eventCollector, tickProfiler, chunkProfiler);
    }

    @Test
    public void testFirstCallEmitsSnapshot() {
        reporter.maybeEmitSnapshot();
        verify(eventCollector, times(1)).addProfilingEvent(any(JsonObject.class));
    }

    @Test
    public void testSecondToNineteenthCallsDoNotEmit() {
        reporter.maybeEmitSnapshot(); // cycle 0 (emits)
        verify(eventCollector, times(1)).addProfilingEvent(any(JsonObject.class));

        for (int i = 1; i < 20; i++) {
            reporter.maybeEmitSnapshot(); // cycles 1 through 19
        }

        // Should still only have emitted once
        verify(eventCollector, times(1)).addProfilingEvent(any(JsonObject.class));
    }

    @Test
    public void testTwentiethCallEmitsSnapshot() {
        for (int i = 0; i <= 20; i++) {
            reporter.maybeEmitSnapshot();
        }

        // Should have emitted at cycle 0 and cycle 20
        verify(eventCollector, times(2)).addProfilingEvent(any(JsonObject.class));
    }

    @Test
    public void testSnapshotPayloadValues() {
        doReturn(true).when(tickProfiler).isAutoDisabled();
        doReturn(true).when(chunkProfiler).isAutoDisabled();

        reporter.maybeEmitSnapshot();

        ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
        verify(eventCollector).addProfilingEvent(captor.capture());

        JsonObject json = captor.getValue();

        assertEquals("CONFIG_SNAPSHOT", json.get("type").getAsString());
        assertTrue(json.has("timestamp"));
        assertEquals("1.4.0", json.get("plugin_version").getAsString());
        // Bukkit.getVersion() might not be perfectly mockable if static depending on environment,
        // but we verify it's added.
        assertTrue(json.has("minecraft_version"));

        assertEquals("https://api.pivotmc.dev/v1/ingest", json.get("api_endpoint").getAsString());
        assertTrue(json.get("collection_enabled").getAsBoolean());
        assertEquals(30, json.get("collection_batch_interval_seconds").getAsInt());
        assertTrue(json.get("collection_track_player_events").getAsBoolean());
        assertTrue(json.get("collection_track_performance").getAsBoolean());
        assertEquals(5, json.get("collection_tps_sample_interval_seconds").getAsInt());

        assertFalse(json.get("privacy_anonymize_players").getAsBoolean());
        assertTrue(json.get("privacy_track_hostnames").getAsBoolean());

        assertFalse(json.get("debug_enabled").getAsBoolean());
        assertFalse(json.get("debug_log_batches").getAsBoolean());

        assertTrue(json.get("profiling_enabled").getAsBoolean());
        assertEquals("auto", json.get("profiling_mode").getAsString());
        assertTrue(json.get("profiling_auto_disabled").getAsBoolean());

        assertFalse(json.get("chunk_profiling_enabled").getAsBoolean());
        assertTrue(json.get("chunk_profiling_auto_disabled").getAsBoolean());
        assertEquals(0.5, json.get("chunk_profiling_overhead_threshold_ms").getAsDouble());

        assertFalse(json.get("command_profiling_enabled").getAsBoolean());
        assertEquals(100, json.get("command_profiling_slow_threshold_ms").getAsInt());

        assertEquals(0.2, json.get("perf_max_overhead_ms").getAsDouble());
        assertTrue(json.get("perf_auto_disable_on_overhead").getAsBoolean());

        assertEquals(30, json.get("unimplemented_sampling_duration_seconds").getAsInt());
        assertEquals(5, json.get("unimplemented_sampling_interval_minutes").getAsInt());
        assertTrue(json.get("unimplemented_triggers_auto_profile_on_lag").getAsBoolean());
        assertEquals(18.0, json.get("unimplemented_triggers_lag_threshold_tps").getAsDouble());
        assertTrue(json.get("unimplemented_privacy_share_anonymous_data").getAsBoolean());
        assertFalse(json.get("unimplemented_anonymize_plugin_names").getAsBoolean());

        // Verify getConfig was called at emit time
        verify(plugin, times(1)).getConfig();
    }
}
