package gg.pivot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class TickProfilerTest {

    @Mock
    PivotPlugin plugin;

    @Mock
    ConfigManager configManager;

    /** Creates a TickProfiler with profiling disabled so no Bukkit.getServer() call is made. */
    private TickProfiler createDisabledProfiler() {
        when(plugin.getLogger()).thenReturn(Logger.getGlobal());
        when(configManager.isProfilingEnabled()).thenReturn(false);
        return new TickProfiler(plugin, configManager);
    }

    // --- anonymize() ---

    @Test
    public void testAnonymizeIsDeterministicAndSHA256Based() throws Exception {
        // Invoke the package-private static helper via reflection
        java.lang.reflect.Method m = TickProfiler.class.getDeclaredMethod("anonymize", String.class);
        m.setAccessible(true);

        String result1 = (String) m.invoke(null, "ExamplePlugin");
        String result2 = (String) m.invoke(null, "ExamplePlugin");
        String result3 = (String) m.invoke(null, "OtherPlugin");

        // Deterministic for same input
        assertEquals(result1, result2, "anonymize must be deterministic");
        // Different inputs must not collide
        assertNotEquals(result1, result3, "anonymize must distinguish different plugin names");
        // Prefix
        assertTrue(result1.startsWith("Plugin_"), "anonymize result must start with Plugin_");
        // Not Java hashCode (which would be e.g. "Plugin_-1234567")
        assertFalse(result1.contains("-"), "SHA-256 hex must not contain hyphens");
    }

    // --- collectSample returns null when disabled ---

    @Test
    public void testCollectSampleReturnsNullWhenDisabled() {
        TickProfiler profiler = createDisabledProfiler();
        assertNull(profiler.collectSample(), "collectSample must return null when profiling is disabled");
    }

    // --- ProfiledRunnable: records timing when enabled, skips when disabled ---

    @Test
    public void testProfiledRunnableRecordsTimingWhenEnabled() throws Exception {
        TickProfiler profiler = createDisabledProfiler();

        // Manually enable profiling so the ProfiledRunnable records
        Field profilingEnabledField = TickProfiler.class.getDeclaredField("profilingEnabled");
        profilingEnabledField.setAccessible(true);
        profilingEnabledField.set(profiler, true);

        AtomicBoolean ran = new AtomicBoolean(false);
        Runnable wrapped = profiler.createProfiledRunnableForTesting("TestPlugin", () -> {
            ran.set(true);
            try { Thread.sleep(1); } catch (InterruptedException ignored) {}
        });

        wrapped.run();

        assertTrue(ran.get(), "Delegate must be executed");
        Map<String, TickProfiler.PluginSampleSnapshot> snapshot = profiler.getCurrentSamplesSnapshotForTesting();
        assertTrue(snapshot.containsKey("TestPlugin"), "Sample must be recorded for the plugin");
        assertEquals(1, snapshot.get("TestPlugin").sampleCount, "Exactly one sample must be recorded");
        assertTrue(snapshot.get("TestPlugin").totalTimeNano > 0, "Total time must be positive");
    }

    @Test
    public void testProfiledRunnableSkipsRecordingWhenDisabled() throws Exception {
        TickProfiler profiler = createDisabledProfiler();
        // profilingEnabled is already false

        AtomicBoolean ran = new AtomicBoolean(false);
        Runnable wrapped = profiler.createProfiledRunnableForTesting("TestPlugin", () -> ran.set(true));

        wrapped.run();

        assertTrue(ran.get(), "Delegate must still be executed even when disabled");
        Map<String, TickProfiler.PluginSampleSnapshot> snapshot = profiler.getCurrentSamplesSnapshotForTesting();
        assertFalse(snapshot.containsKey("TestPlugin"), "No sample must be recorded when profiling is disabled");
    }

    // --- ProfiledCallable: records timing when enabled, skips when disabled ---

    @Test
    public void testProfiledCallableRecordsTimingWhenEnabled() throws Exception {
        TickProfiler profiler = createDisabledProfiler();

        Field profilingEnabledField = TickProfiler.class.getDeclaredField("profilingEnabled");
        profilingEnabledField.setAccessible(true);
        profilingEnabledField.set(profiler, true);

        java.util.concurrent.Callable<Object> wrapped =
                profiler.createProfiledCallableForTesting("PluginA", () -> "result");

        Object result = wrapped.call();

        assertEquals("result", result, "Callable must return the delegate result");
        Map<String, TickProfiler.PluginSampleSnapshot> snapshot = profiler.getCurrentSamplesSnapshotForTesting();
        assertTrue(snapshot.containsKey("PluginA"), "Sample must be recorded");
        assertEquals(1, snapshot.get("PluginA").sampleCount);
    }

    @Test
    public void testProfiledCallableSkipsRecordingWhenDisabled() throws Exception {
        TickProfiler profiler = createDisabledProfiler();

        java.util.concurrent.Callable<Object> wrapped =
                profiler.createProfiledCallableForTesting("PluginA", () -> "result");

        Object result = wrapped.call();

        assertEquals("result", result, "Callable must still return the delegate result when disabled");
        Map<String, TickProfiler.PluginSampleSnapshot> snapshot = profiler.getCurrentSamplesSnapshotForTesting();
        assertFalse(snapshot.containsKey("PluginA"), "No sample must be recorded when profiling is disabled");
    }

    // --- overhead tracking ---

    @Test
    public void testOverheadIsTrackedDuringRecording() throws Exception {
        TickProfiler profiler = createDisabledProfiler();

        Field profilingEnabledField = TickProfiler.class.getDeclaredField("profilingEnabled");
        profilingEnabledField.setAccessible(true);
        profilingEnabledField.set(profiler, true);

        // Before any recording the overhead counter starts at 0
        assertEquals(0L, profiler.getOverheadNanoForTesting(), "Initial overhead must be 0");

        Runnable wrapped = profiler.createProfiledRunnableForTesting("P", () -> {});
        wrapped.run();

        assertTrue(profiler.getOverheadNanoForTesting() > 0, "Overhead must be positive after recording");
    }

    // --- shutdown ---

    @Test
    public void testShutdownDisablesProfiling() throws Exception {
        TickProfiler profiler = createDisabledProfiler();

        // Enable profiling manually first
        Field profilingEnabledField = TickProfiler.class.getDeclaredField("profilingEnabled");
        profilingEnabledField.setAccessible(true);
        profilingEnabledField.set(profiler, true);

        profiler.shutdown();

        assertFalse((boolean) profilingEnabledField.get(profiler), "profilingEnabled must be false after shutdown");

        Field autoDisabledField = TickProfiler.class.getDeclaredField("autoDisabled");
        autoDisabledField.setAccessible(true);
        assertTrue((boolean) autoDisabledField.get(profiler), "autoDisabled must be true after shutdown");
    }
}
