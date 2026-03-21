package gg.pivot;

import com.google.gson.JsonObject;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class CommandProfilerTest {
    private PivotPlugin plugin;
    private EventCollector eventCollector;
    private FileConfiguration config;
    private CommandProfiler profiler;

    @BeforeEach
    public void setup() throws Exception {
        // Initialize TPSUtil via reflection
        java.lang.reflect.Field initField = TPSUtil.class.getDeclaredField("initialized");
        initField.setAccessible(true);
        initField.set(null, true);

        // Mock TPSUtil initialization
        java.lang.reflect.Field isSpigotField = TPSUtil.class.getDeclaredField("isSpigot");
        isSpigotField.setAccessible(true);
        isSpigotField.set(null, false);

        java.lang.reflect.Field isPaperField = TPSUtil.class.getDeclaredField("isPaper");
        isPaperField.setAccessible(true);
        isPaperField.set(null, false);


        plugin = mock(PivotPlugin.class);
        eventCollector = mock(EventCollector.class);
        config = mock(FileConfiguration.class);

        doReturn(config).when(plugin).getConfig();
        doReturn(true).when(config).getBoolean("profiling.command_profiling.enabled", true);
        doReturn(100.0).when(config).getDouble("profiling.command_profiling.slow_threshold_ms", 100.0);

        profiler = new CommandProfiler(plugin, eventCollector);
    }

    @Test
    public void testSlowCommand() throws Exception {
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        doReturn(uuid).when(player).getUniqueId();
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        doReturn(server).when(player).getServer();
        doReturn(new java.util.ArrayList<>()).when(server).getOnlinePlayers();

        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, "/set args");

        profiler.onCommandStart(event);

        // Modify timing to be 101ms ago
        Field timingsField = CommandProfiler.class.getDeclaredField("activeTimings");
        timingsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<UUID, Object> timings = (ConcurrentHashMap<UUID, Object>) timingsField.get(profiler);

        Object timing = timings.get(uuid);
        Field startNanosField = timing.getClass().getDeclaredField("startNanos");
        startNanosField.setAccessible(true);
        startNanosField.set(timing, System.nanoTime() - 101_000_000L);

        profiler.onCommandEnd(event);

        verify(eventCollector, times(1)).addProfilingEvent(any(JsonObject.class));
    }

    @Test
    public void testFastCommand() throws Exception {
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        doReturn(uuid).when(player).getUniqueId();
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        doReturn(server).when(player).getServer();
        doReturn(new java.util.ArrayList<>()).when(server).getOnlinePlayers();

        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, "/set args");

        profiler.onCommandStart(event);
        // It's instantaneous
        profiler.onCommandEnd(event);

        verify(eventCollector, never()).addProfilingEvent(any(JsonObject.class));
    }
}
