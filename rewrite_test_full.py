import sys

new_content = """package gg.pivot;

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
}
"""

with open('src/test/java/gg/pivot/TickProfilerTest.java', 'w') as f:
    f.write(new_content)
