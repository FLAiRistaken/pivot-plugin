package gg.pivot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

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
        // Invoke the private static helper via reflection
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

    @Test
    public void testCollectSampleIncludesTaskCountAndEventCount() throws Exception {
        TickProfiler profiler = createDisabledProfiler();

        // Force enable profiling
        Field profilingEnabledField = TickProfiler.class.getDeclaredField("profilingEnabled");
        profilingEnabledField.setAccessible(true);
        profilingEnabledField.set(profiler, true);

        Field modeField = TickProfiler.class.getDeclaredField("mode");
        modeField.setAccessible(true);
        modeField.set(profiler, "custom_spigot");

        // Inject sample data
        Class<?> pluginSampleClass = null;
        for (Class<?> c : TickProfiler.class.getDeclaredClasses()) {
            if (c.getSimpleName().equals("PluginSample")) {
                pluginSampleClass = c;
                break;
            }
        }
        assertNotNull(pluginSampleClass, "PluginSample class not found");
        java.lang.reflect.Constructor<?> constructor = pluginSampleClass.getDeclaredConstructor(); constructor.setAccessible(true); Object sample = constructor.newInstance();

        java.lang.reflect.Method addMethod = pluginSampleClass.getDeclaredMethod("add", long.class);
        addMethod.setAccessible(true);
        addMethod.invoke(sample, 1000000L); // 1ms

        Field currentSpigotSamplesField = TickProfiler.class.getDeclaredField("currentSpigotSamples");
        currentSpigotSamplesField.setAccessible(true);
        java.util.concurrent.ConcurrentHashMap<String, Object> samples = (java.util.concurrent.ConcurrentHashMap) currentSpigotSamplesField.get(profiler);
        samples.put("TestPlugin", sample);

        // Mock Server and PluginManager
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getVersion()).thenReturn("1.20.4");

        PluginManager pm = mock(PluginManager.class);
        when(server.getPluginManager()).thenReturn(pm);
        when(pm.getPlugins()).thenReturn(new org.bukkit.plugin.Plugin[0]);

        // Fix TPSUtil (static state manipulation)
        Field tpsInitialized = TPSUtil.class.getDeclaredField("initialized");
        tpsInitialized.setAccessible(true);
        boolean wasInitialized = (boolean) tpsInitialized.get(null);
        tpsInitialized.set(null, true);

        // Also ensure isPaper and isSpigot are false to use fallback (safe)
        Field tpsIsPaper = TPSUtil.class.getDeclaredField("isPaper");
        tpsIsPaper.setAccessible(true);
        boolean wasPaper = (boolean) tpsIsPaper.get(null);
        tpsIsPaper.set(null, false);

        Field tpsIsSpigot = TPSUtil.class.getDeclaredField("isSpigot");
        tpsIsSpigot.setAccessible(true);
        boolean wasSpigot = (boolean) tpsIsSpigot.get(null);
        tpsIsSpigot.set(null, false);

        try {
            JsonObject result = profiler.collectSample();
            assertNotNull(result, "collectSample should return non-null result");

            JsonArray plugins = result.getAsJsonArray("plugins");
            assertEquals(1, plugins.size(), "Should have 1 plugin sample");

            JsonObject p = plugins.get(0).getAsJsonObject();

            assertTrue(p.has("event_count"), "Payload must contain event_count");
            assertTrue(p.has("task_count"), "Payload must contain task_count");
            assertEquals(0, p.get("task_count").getAsInt(), "task_count must default to 0");
        } finally {
            // Restore TPSUtil state to avoid polluting other tests if any
            tpsInitialized.set(null, wasInitialized);
            tpsIsPaper.set(null, wasPaper);
            tpsIsSpigot.set(null, wasSpigot);
        }
    }
}
