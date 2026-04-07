package gg.pivot;

import com.google.gson.JsonObject;
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

    @BeforeEach
    public void setUp() {
        plugin = mock(PivotPlugin.class);
        eventCollector = mock(EventCollector.class);
        tickProfiler = mock(TickProfiler.class);
        chunkProfiler = mock(ChunkProfiler.class);
        config = mock(FileConfiguration.class);

        org.bukkit.Server server = mock(org.bukkit.Server.class);

        PluginDescriptionFile pdf = mock(PluginDescriptionFile.class);
        doReturn("1.4.0").when(pdf).getVersion();
        doReturn(pdf).when(plugin).getDescription();
        doReturn(config).when(plugin).getConfig();
        doReturn(server).when(plugin).getServer();
        doReturn("Paper 1.20.4").when(server).getVersion();
        doReturn("https://api.pivotmc.dev/v1/ingest").when(plugin).getApiEndpoint();

        // Setup default mock config values (matching 2-arg overloads used in ConfigSnapshotReporter)
        doReturn(true).when(config).getBoolean(eq("collection.enabled"), anyBoolean());
        doReturn(30).when(config).getInt(eq("collection.batch-interval"), anyInt());
        doReturn(true).when(config).getBoolean(eq("collection.track-player-events"), anyBoolean());
        doReturn(true).when(config).getBoolean(eq("collection.track-performance"), anyBoolean());
        doReturn(5).when(config).getInt(eq("collection.tps-sample-interval"), anyInt());
        doReturn(false).when(config).getBoolean(eq("privacy.anonymize-players"), anyBoolean());
        doReturn(true).when(config).getBoolean(eq("privacy.track-hostnames"), anyBoolean());
        doReturn(false).when(config).getBoolean(eq("debug.enabled"), anyBoolean());
        doReturn(false).when(config).getBoolean(eq("debug.log-batches"), anyBoolean());
        doReturn(true).when(config).getBoolean(eq("profiling.enabled"), anyBoolean());
        doReturn("auto").when(config).getString(eq("profiling.mode"), anyString());
        doReturn(false).when(config).getBoolean(eq("profiling.chunk_profiling.enabled"), anyBoolean());
        doReturn(0.5).when(config).getDouble(eq("profiling.chunk_profiling.overhead_threshold_ms"), anyDouble());
        doReturn(false).when(config).getBoolean(eq("profiling.command_profiling.enabled"), anyBoolean());
        doReturn(100).when(config).getInt(eq("profiling.command_profiling.slow_threshold_ms"), anyInt());
        doReturn(0.2).when(config).getDouble(eq("profiling.performance.max_overhead_ms"), anyDouble());
        doReturn(true).when(config).getBoolean(eq("profiling.performance.auto_disable_on_overhead"), anyBoolean());
        doReturn(30).when(config).getInt(eq("profiling.sampling.duration_seconds"), anyInt());
        doReturn(5).when(config).getInt(eq("profiling.sampling.interval_minutes"), anyInt());
        doReturn(true).when(config).getBoolean(eq("profiling.triggers.auto_profile_on_lag"), anyBoolean());
        doReturn(18.0).when(config).getDouble(eq("profiling.triggers.lag_threshold_tps"), anyDouble());
        doReturn(true).when(config).getBoolean(eq("profiling.privacy.share_anonymous_data"), anyBoolean());
        doReturn(false).when(config).getBoolean(eq("profiling.privacy.anonymize_plugin_names"), anyBoolean());

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
        assertEquals("Paper 1.20.4", json.get("minecraft_version").getAsString());

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

        assertEquals(30, json.get("sampling_duration_seconds").getAsInt());
        assertEquals(5, json.get("sampling_interval_minutes").getAsInt());
        assertTrue(json.get("triggers_auto_profile_on_lag").getAsBoolean());
        assertEquals(18.0, json.get("triggers_lag_threshold_tps").getAsDouble());
        assertTrue(json.get("privacy_share_anonymous_data").getAsBoolean());
        assertFalse(json.get("anonymize_plugin_names").getAsBoolean());

        // Verify getConfig was called at emit time
        verify(plugin, times(1)).getConfig();
    }
}
