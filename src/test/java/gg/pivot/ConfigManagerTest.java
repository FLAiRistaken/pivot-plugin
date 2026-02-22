package gg.pivot;

import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ConfigManagerTest {

    @Mock
    PivotPlugin plugin;

    @Mock
    FileConfiguration config;

    private ConfigManager configManager;

    @BeforeEach
    public void setUp() {
        when(plugin.getConfig()).thenReturn(config);
        configManager = new ConfigManager(plugin);
    }

    @Test
    public void testIsProfilingEnabledDefaultTrue() {
        when(config.getBoolean("profiling.enabled", true)).thenReturn(true);
        assertTrue(configManager.isProfilingEnabled(), "Default should be enabled");
    }

    @Test
    public void testIsProfilingEnabledCanBeDisabled() {
        when(config.getBoolean("profiling.enabled", true)).thenReturn(false);
        assertFalse(configManager.isProfilingEnabled(), "Should respect config false");
    }

    @Test
    public void testGetProfilingModeDefaultAuto() {
        when(config.getString("profiling.mode", "auto")).thenReturn("auto");
        assertEquals("auto", configManager.getProfilingMode());
    }

    @Test
    public void testGetProfilingModeCustom() {
        when(config.getString("profiling.mode", "auto")).thenReturn("custom_only");
        assertEquals("custom_only", configManager.getProfilingMode());
    }

    @Test
    public void testIsAnonymizePluginNamesDefaultFalse() {
        when(config.getBoolean("profiling.privacy.anonymize_plugin_names", false)).thenReturn(false);
        assertFalse(configManager.isAnonymizePluginNames(), "Default anonymize_plugin_names is false");
    }

    @Test
    public void testIsAnonymizePluginNamesEnabled() {
        when(config.getBoolean("profiling.privacy.anonymize_plugin_names", false)).thenReturn(true);
        assertTrue(configManager.isAnonymizePluginNames());
    }

    @Test
    public void testGetMaxOverheadMsDefault() {
        when(config.getDouble("profiling.performance.max_overhead_ms", 0.2)).thenReturn(0.2);
        assertEquals(0.2, configManager.getMaxOverheadMs(), 0.001, "Default max overhead should be 0.2ms");
    }

    @Test
    public void testGetMaxOverheadMsCustom() {
        when(config.getDouble("profiling.performance.max_overhead_ms", 0.2)).thenReturn(0.5);
        assertEquals(0.5, configManager.getMaxOverheadMs(), 0.001);
    }

    @Test
    public void testIsAutoDisableOnOverheadDefaultTrue() {
        when(config.getBoolean("profiling.performance.auto_disable_on_overhead", true)).thenReturn(true);
        assertTrue(configManager.isAutoDisableOnOverhead(), "Default auto_disable_on_overhead is true");
    }

    @Test
    public void testIsAutoDisableOnOverheadCanBeDisabled() {
        when(config.getBoolean("profiling.performance.auto_disable_on_overhead", true)).thenReturn(false);
        assertFalse(configManager.isAutoDisableOnOverhead());
    }
}
