package gg.pivot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Chunk;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ChunkProfilerTest {
    private static final long MS = 1_000_000L;

    private PivotPlugin plugin;
    private EventCollector eventCollector;
    private FileConfiguration config;
    private ChunkProfiler profiler;
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final List<Plugin> registeredPlugins = new ArrayList<>();

    @BeforeEach
    public void setup() {
        plugin = mock(PivotPlugin.class);
        eventCollector = mock(EventCollector.class);
        config = mock(FileConfiguration.class);

        doReturn(config).when(plugin).getConfig();
        doReturn("PivotAnalytics").when(plugin).getName();
        doReturn(Logger.getLogger("test")).when(plugin).getLogger();
        doReturn(true).when(config).getBoolean("profiling.enabled", true);
        doReturn(true).when(config).getBoolean("profiling.chunk_profiling.enabled", false);
        doReturn(0.5).when(config).getDouble("profiling.chunk_profiling.overhead_threshold_ms", 0.5);
        doReturn(30).when(config).getInt("collection.batch-interval", 30);

        profiler = new ChunkProfiler(plugin, eventCollector, now::get);
    }

    @AfterEach
    public void unregisterListeners() {
        for (Plugin p : registeredPlugins) {
            ChunkLoadEvent.getHandlerList().unregister(p);
            ChunkUnloadEvent.getHandlerList().unregister(p);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ChunkLoadEvent loadEvent() {
        return new ChunkLoadEvent(mock(Chunk.class), false);
    }

    private ChunkUnloadEvent unloadEvent() {
        return new ChunkUnloadEvent(mock(Chunk.class));
    }

    /** Fires the LOWEST handler, lets {@code durationNanos} pass, then fires MONITOR. */
    private void timedLoad(long durationNanos) {
        ChunkLoadEvent e = loadEvent();
        profiler.onChunkLoadStart(e);
        now.addAndGet(durationNanos);
        profiler.onChunkLoadEnd(e);
    }

    private void timedUnload(long durationNanos) {
        ChunkUnloadEvent e = unloadEvent();
        profiler.onChunkUnloadStart(e);
        now.addAndGet(durationNanos);
        profiler.onChunkUnloadEnd(e);
    }

    private Plugin listeningPlugin(String name, String version, boolean onLoad, boolean onUnload) {
        Plugin p = mock(Plugin.class);
        doReturn(name).when(p).getName();
        doReturn(new PluginDescriptionFile(name, version, "test.Main")).when(p).getDescription();
        Listener listener = new Listener() {};
        if (onLoad) {
            ChunkLoadEvent.getHandlerList().register(
                    new RegisteredListener(listener, (l, ev) -> {}, EventPriority.NORMAL, p, false));
        }
        if (onUnload) {
            ChunkUnloadEvent.getHandlerList().register(
                    new RegisteredListener(listener, (l, ev) -> {}, EventPriority.NORMAL, p, false));
        }
        registeredPlugins.add(p);
        return p;
    }

    private JsonObject flushedEvent() {
        profiler.flushAndReset();
        ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
        verify(eventCollector, atLeastOnce()).addProfilingEvent(captor.capture());
        List<JsonObject> all = captor.getAllValues();
        return all.get(all.size() - 1);
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    @Test
    public void testFlushAndResetEmpty() {
        JsonObject event = flushedEvent();
        assertEquals(0, event.get("chunks_loaded").getAsInt());
        assertEquals(0, event.get("chunks_unloaded").getAsInt());
        assertEquals(30, event.get("sample_duration_seconds").getAsInt());
    }

    @Test
    public void testAccumulatorsReset() {
        timedLoad(2 * MS);
        timedUnload(MS);

        JsonObject first = flushedEvent();
        assertEquals(1, first.get("chunks_loaded").getAsInt());

        JsonObject second = flushedEvent();
        assertEquals(0, second.get("chunks_loaded").getAsInt(), "Accumulators should reset to zero");
        assertEquals(0, second.get("chunks_unloaded").getAsInt());
        assertEquals(0.0, second.get("load_listener_total_ms").getAsDouble());
        assertEquals(0.0, second.get("load_listener_max_ms").getAsDouble());
    }

    @Test
    public void testDisabledProfilerRecordsAndReportsNothing() {
        doReturn(false).when(config).getBoolean("profiling.chunk_profiling.enabled", false);
        profiler = new ChunkProfiler(plugin, eventCollector, now::get);

        timedLoad(5 * MS);
        profiler.flushAndReset();

        verify(eventCollector, never()).addProfilingEvent(any(JsonObject.class));
    }

    // ── what is reported ─────────────────────────────────────────────────────

    @Test
    public void testReportsWholeChainTimeAndNoPerPluginTimings() {
        listeningPlugin("WorldGuard", "7.0.9", true, false);
        listeningPlugin("Dynmap", "3.7", true, true);

        timedLoad(2 * MS);
        timedLoad(4 * MS);
        timedLoad(6 * MS);
        timedUnload(MS);

        JsonObject event = flushedEvent();
        assertEquals("CHUNK_PROFILE", event.get("type").getAsString());
        assertEquals(3, event.get("chunks_loaded").getAsInt());
        assertEquals(12.0, event.get("load_listener_total_ms").getAsDouble(), 0.001);
        assertEquals(6.0, event.get("load_listener_max_ms").getAsDouble(), 0.001);
        assertEquals(1, event.get("chunks_unloaded").getAsInt());
        assertEquals(1.0, event.get("unload_listener_total_ms").getAsDouble(), 0.001);
        assertEquals(1.0, event.get("unload_listener_max_ms").getAsDouble(), 0.001);

        // Plugins are listed by name and version only: the old equal split is gone.
        JsonArray plugins = event.getAsJsonArray("plugins");
        assertEquals(2, plugins.size());
        assertEquals("Dynmap", plugins.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("3.7", plugins.get(0).getAsJsonObject().get("version").getAsString());
        assertEquals("WorldGuard", plugins.get(1).getAsJsonObject().get("name").getAsString());
        for (int i = 0; i < plugins.size(); i++) {
            assertEquals(2, plugins.get(i).getAsJsonObject().size(), "only name and version per plugin");
        }
    }

    @Test
    public void testOwnPluginIsNotListed() {
        listeningPlugin("PivotAnalytics", "0.2.0", true, true);
        listeningPlugin("WorldGuard", "7.0.9", true, false);

        JsonArray plugins = flushedEvent().getAsJsonArray("plugins");
        assertEquals(1, plugins.size());
        assertEquals("WorldGuard", plugins.get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    public void testPluginListIsCappedAndTruncatedToWhatTheBackendAccepts() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < ChunkProfiler.MAX_PLUGIN_NAME_LENGTH + 20; i++) longName.append('x');
        for (int i = 0; i < ChunkProfiler.MAX_LISTED_PLUGINS + 5; i++) {
            listeningPlugin(String.format("%03d-%s", i, longName), "1.0", true, false);
        }

        JsonArray plugins = flushedEvent().getAsJsonArray("plugins");
        assertEquals(ChunkProfiler.MAX_LISTED_PLUGINS, plugins.size());
        for (int i = 0; i < plugins.size(); i++) {
            assertTrue(plugins.get(i).getAsJsonObject().get("name").getAsString().length()
                    <= ChunkProfiler.MAX_PLUGIN_NAME_LENGTH);
        }
    }

    // ── start/end pairing ────────────────────────────────────────────────────

    @Test
    public void testNestedEventsPairWithTheirOwnStart() {
        ChunkLoadEvent outer = loadEvent();
        ChunkLoadEvent inner = loadEvent();

        profiler.onChunkLoadStart(outer);
        now.addAndGet(MS);
        profiler.onChunkLoadStart(inner);
        now.addAndGet(2 * MS);
        profiler.onChunkLoadEnd(inner);   // 2ms
        now.addAndGet(7 * MS);
        profiler.onChunkLoadEnd(outer);   // 1 + 2 + 7 = 10ms

        JsonObject event = flushedEvent();
        assertEquals(2, event.get("chunks_loaded").getAsInt());
        assertEquals(12.0, event.get("load_listener_total_ms").getAsDouble(), 0.001);
        assertEquals(10.0, event.get("load_listener_max_ms").getAsDouble(), 0.001);
    }

    @Test
    public void testDisabledBetweenStartAndEndLeavesNoStaleStart() {
        ChunkLoadEvent first = loadEvent();
        profiler.onChunkLoadStart(first);          // enabled: start recorded
        profiler.disable();                        // toggled off between the two handlers
        now.addAndGet(MS);
        profiler.onChunkLoadEnd(first);            // must still consume its start

        // Re-enabled later. An event that began while profiling was off has no start...
        profiler.reload();
        ChunkLoadEvent straddling = loadEvent();
        // (its LOWEST handler ran while disabled, so nothing was pushed)
        now.addAndGet(500 * MS);
        profiler.onChunkLoadEnd(straddling);

        // ...and must not pick up the first event's stale start (500+ms of bogus time).
        JsonObject event = flushedEvent();
        assertEquals(0, event.get("chunks_loaded").getAsInt());
        assertEquals(0.0, event.get("load_listener_total_ms").getAsDouble());
    }

    @Test
    public void testEnabledBetweenStartAndEndRecordsNothingForThatEvent() {
        profiler.disable();
        ChunkLoadEvent e = loadEvent();
        profiler.onChunkLoadStart(e);              // disabled: no start
        profiler.reload();                         // enabled before MONITOR
        now.addAndGet(5 * MS);
        profiler.onChunkLoadEnd(e);

        assertEquals(0, flushedEvent().get("chunks_loaded").getAsInt());

        // The next whole event is measured normally.
        timedLoad(3 * MS);
        JsonObject event = flushedEvent();
        assertEquals(1, event.get("chunks_loaded").getAsInt());
        assertEquals(3.0, event.get("load_listener_total_ms").getAsDouble(), 0.001);
    }

    @Test
    public void testUnmatchedEndKeepsAnOuterEventsStart() {
        ChunkLoadEvent outer = loadEvent();
        profiler.onChunkLoadStart(outer);
        now.addAndGet(MS);

        // An inner event whose start was never recorded must not consume the outer's start.
        profiler.onChunkLoadEnd(loadEvent());

        now.addAndGet(4 * MS);
        profiler.onChunkLoadEnd(outer);

        JsonObject event = flushedEvent();
        assertEquals(1, event.get("chunks_loaded").getAsInt());
        assertEquals(5.0, event.get("load_listener_total_ms").getAsDouble(), 0.001);
    }

    @Test
    public void testStartsAboveAnEventWhoseEndNeverRanAreDropped() {
        ChunkLoadEvent outer = loadEvent();
        ChunkLoadEvent abandoned = loadEvent();
        profiler.onChunkLoadStart(outer);
        now.addAndGet(MS);
        profiler.onChunkLoadStart(abandoned);      // its MONITOR handler never runs
        now.addAndGet(MS);
        profiler.onChunkLoadEnd(outer);            // discards the abandoned start

        // Nothing is left behind for the next event to trip over.
        timedLoad(2 * MS);
        JsonObject event = flushedEvent();
        assertEquals(2, event.get("chunks_loaded").getAsInt());
        assertEquals(4.0, event.get("load_listener_total_ms").getAsDouble(), 0.001);
        assertEquals(2.0, event.get("load_listener_max_ms").getAsDouble(), 0.001);
    }

    @Test
    public void testAbandonedStartsAreBounded() {
        for (int i = 0; i < 500; i++) {
            profiler.onChunkLoadStart(loadEvent());   // never ended
        }
        timedLoad(2 * MS);                           // still pairs correctly

        JsonObject event = flushedEvent();
        assertEquals(1, event.get("chunks_loaded").getAsInt());
        assertEquals(2.0, event.get("load_listener_total_ms").getAsDouble(), 0.001);
    }
}
