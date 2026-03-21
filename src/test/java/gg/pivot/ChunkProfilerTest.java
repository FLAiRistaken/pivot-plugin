package gg.pivot;

import com.google.gson.JsonObject;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.world.ChunkLoadEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ChunkProfilerTest {
    private PivotPlugin plugin;
    private EventCollector eventCollector;
    private FileConfiguration config;
    private ChunkProfiler profiler;

    @BeforeEach
    public void setup() {
        plugin = mock(PivotPlugin.class);
        eventCollector = mock(EventCollector.class);
        config = mock(FileConfiguration.class);

        doReturn(config).when(plugin).getConfig();
        doReturn(true).when(config).getBoolean("profiling.chunk_profiling.enabled", true);
        doReturn(0.5).when(config).getDouble("profiling.chunk_profiling.overhead_threshold_ms", 0.5);
        doReturn(30).when(config).getInt("collection.batch-interval", 30);

        profiler = new ChunkProfiler(plugin, eventCollector);
    }

    @Test
    public void testFlushAndResetEmpty() {
        // Needs reflection to set enabled to true since default mock doesn't trigger properly
        profiler.flushAndReset();
        // The issue says: "flushAndReset() produces empty plugins array when no chunk events occurred"
        // And "produce CHUNK_PROFILE schema and add to EventCollector".
        // Wait, requirements say: "flushAndReset() produces empty plugins array when no chunk events occurred".
        // Ah, it SHOULD add it, but with empty plugins array. So the test should assert that.
        verify(eventCollector, times(1)).addProfilingEvent(any(JsonObject.class));
    }

    @Test
    public void testAccumulatorsReset() throws Exception {
        // Use reflection to increment totalChunksLoaded to simulate event
        Field f = ChunkProfiler.class.getDeclaredField("totalChunksLoaded");
        f.setAccessible(true);
        AtomicInteger loaded = (AtomicInteger) f.get(profiler);
        loaded.set(5);

        profiler.flushAndReset();

        assertEquals(0, loaded.get(), "Accumulators should reset to zero");
    }
}
