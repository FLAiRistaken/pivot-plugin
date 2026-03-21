package gg.pivot;

import gg.pivot.util.HostnameDetector;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

public class AttributionTest {

    private PivotPlugin plugin;
    private FileConfiguration config;
    private EventCollector eventCollector;
    private EventListener listener;
    private Player player;
    private final UUID playerId = UUID.randomUUID();

    @BeforeEach
    public void setup() {
        plugin = mock(PivotPlugin.class);
        config = mock(FileConfiguration.class);
        eventCollector = mock(EventCollector.class);

        doReturn(config).when(plugin).getConfig();
        doReturn(Logger.getGlobal()).when(plugin).getLogger();
        doReturn(eventCollector).when(plugin).getEventCollector();

        doReturn(true).when(config).getBoolean("privacy.track-hostnames", true);
        doReturn(true).when(config).getBoolean("collection.track-player-events", true);
        doReturn("play.pivot.gg").when(config).getString("api.default-hostname", "localhost");

        listener = new EventListener(plugin);
        player = mock(Player.class);
        doReturn(playerId).when(player).getUniqueId();
        doReturn("TestPlayer").when(player).getName();
    }

    @Test
    public void testHostnameAttributionFromVirtualHost() {
        // Mock a login event with a specific virtual host
        PlayerLoginEvent loginEvent = mock(PlayerLoginEvent.class);
        doReturn(player).when(loginEvent).getPlayer();
        doReturn("tiktok.pivot.gg").when(loginEvent).getHostname();

        // Fire login event to cache the hostname
        listener.onPlayerLogin(loginEvent);

        // Mock and fire join event
        PlayerJoinEvent joinEvent = new PlayerJoinEvent(player, "Joined");
        listener.onPlayerJoin(joinEvent);

        // Verify that the event collector was called with the correct hostname
        ArgumentCaptor<String> hostnameCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventCollector).addPlayerEvent(
                eq("PLAYER_JOIN"),
                eq(playerId.toString()),
                eq("TestPlayer"),
                hostnameCaptor.capture(),
                isNull(),
                isNull(),
                anyString()
        );

        assertEquals("tiktok.pivot.gg", hostnameCaptor.getValue(), "Hostname should be captured from virtual host");
    }

    @Test
    public void testHostnamePortIsStripped() {
        // Bukkit may return "host:port" from PlayerLoginEvent#getHostname()
        PlayerLoginEvent loginEvent = mock(PlayerLoginEvent.class);
        doReturn(player).when(loginEvent).getPlayer();
        doReturn("tiktok.pivot.gg:25565").when(loginEvent).getHostname();

        listener.onPlayerLogin(loginEvent);

        PlayerJoinEvent joinEvent = new PlayerJoinEvent(player, "Joined");
        listener.onPlayerJoin(joinEvent);

        ArgumentCaptor<String> hostnameCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventCollector).addPlayerEvent(
                eq("PLAYER_JOIN"),
                eq(playerId.toString()),
                eq("TestPlayer"),
                hostnameCaptor.capture(),
                isNull(),
                isNull(),
                anyString()
        );

        assertEquals("tiktok.pivot.gg", hostnameCaptor.getValue(), "Port should be stripped from virtual host");
    }

    @Test
    public void testHostnameFallbackToConfigDefault() {
        // Mock a login event with null virtual host
        PlayerLoginEvent loginEvent = mock(PlayerLoginEvent.class);
        doReturn(player).when(loginEvent).getPlayer();
        doReturn(null).when(loginEvent).getHostname();

        // Fire login event
        listener.onPlayerLogin(loginEvent);

        // Mock and fire join event
        PlayerJoinEvent joinEvent = new PlayerJoinEvent(player, "Joined");
        listener.onPlayerJoin(joinEvent);

        // Verify that the event collector was called with the default hostname
        ArgumentCaptor<String> hostnameCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventCollector).addPlayerEvent(
                eq("PLAYER_JOIN"),
                eq(playerId.toString()),
                eq("TestPlayer"),
                hostnameCaptor.capture(),
                isNull(),
                isNull(),
                anyString()
        );

        assertEquals("play.pivot.gg", hostnameCaptor.getValue(), "Hostname should fallback to configured default when null");
    }

    @Test
    public void testHostnameFallbackToConfigDefaultEmptyString() {
        // Mock a login event with empty virtual host
        PlayerLoginEvent loginEvent = mock(PlayerLoginEvent.class);
        doReturn(player).when(loginEvent).getPlayer();
        doReturn("").when(loginEvent).getHostname();

        // Fire login event
        listener.onPlayerLogin(loginEvent);

        // Mock and fire join event
        PlayerJoinEvent joinEvent = new PlayerJoinEvent(player, "Joined");
        listener.onPlayerJoin(joinEvent);

        // Verify that the event collector was called with the default hostname
        ArgumentCaptor<String> hostnameCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventCollector).addPlayerEvent(
                eq("PLAYER_JOIN"),
                eq(playerId.toString()),
                eq("TestPlayer"),
                hostnameCaptor.capture(),
                isNull(),
                isNull(),
                anyString()
        );

        assertEquals("play.pivot.gg", hostnameCaptor.getValue(), "Hostname should fallback to configured default when empty");
    }

    @ParameterizedTest
    @CsvSource({
        "example.com:25565, example.com",
        "example.com, example.com",
        "[::1]:25565, [::1]",
        "[::1], [::1]",
        "192.168.1.1:19132, 192.168.1.1",
        "tiktok.pivot.gg:25565, tiktok.pivot.gg",
        "2001:db8::1, 2001:db8::1",
        "::1, ::1"
    })
    public void testStripPortVariants(String input, String expected) {
        assertEquals(expected, HostnameDetector.stripPort(input));
    }

    @Test
    public void testStripPortNull() {
        assertNull(HostnameDetector.stripPort(null), "stripPort(null) should return null, not throw NPE");
    }
}
