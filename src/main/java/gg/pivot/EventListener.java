package gg.pivot;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;  // NEW: Import this
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class EventListener implements Listener {
    private final PivotPlugin plugin;

    // Cache hostname per player (since LoginEvent fires before JoinEvent)
    private final Map<UUID, String> hostnameCache = new HashMap<>();

    public EventListener(PivotPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent event) {
        // Capture hostname during login event
        String hostname = event.getHostname();
        UUID playerId = event.getPlayer().getUniqueId();

        // Store hostname for use in PlayerJoinEvent
        if (hostname != null && !hostname.isEmpty()) {
            hostnameCache.put(playerId, hostname);
            plugin.getLogger().info(String.format(
                "Player %s connecting via: %s",
                event.getPlayer().getName(),
                hostname
            ));
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();

        // Retrieve cached hostname from login event
        String hostname = hostnameCache.remove(playerId); // Remove after use

        plugin.getEventCollector().addPlayerEvent(
            "PLAYER_JOIN",
            event.getPlayer().getUniqueId().toString(),
            event.getPlayer().getName(),
            hostname  // Will be null if not in cache, which is fine
        );

        plugin.getLogger().info(String.format(
            "Player joined: %s (hostname: %s)",
            event.getPlayer().getName(),
            hostname != null ? hostname : "unknown"
        ));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Clean up cache in case player was kicked during login
        hostnameCache.remove(event.getPlayer().getUniqueId());

        plugin.getEventCollector().addPlayerEvent(
            "PLAYER_QUIT",
            event.getPlayer().getUniqueId().toString(),
            event.getPlayer().getName(),
            null  // No hostname on quit
        );
        plugin.getLogger().info("Player quit: " + event.getPlayer().getName());
    }
}
