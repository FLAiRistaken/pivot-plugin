// src/main/java/gg/pivot/EventListener.java
package gg.pivot;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Listens for Bukkit events to capture player activity.
 * <p>
 * Captures:
 * <ul>
 *   <li>PlayerLoginEvent: To capture the hostname used to connect (MONITOR priority).</li>
 *   <li>PlayerJoinEvent: To record player joins and attribute them to hostnames.</li>
 *   <li>PlayerQuitEvent: To record player quits and clean up caches.</li>
 * </ul>
 * </p>
 */
public class EventListener implements Listener {
    private final PivotPlugin plugin;

    // Cache hostname per player (LoginEvent fires before JoinEvent)
    private final Map<UUID, String> hostnameCache = new HashMap<>();

    public EventListener(PivotPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent event) {
        // Only capture hostname if tracking enabled
        if (!plugin.getConfig().getBoolean("privacy.track-hostnames", true)) {
            return;
        }

        String hostname = event.getHostname();
        UUID playerId = event.getPlayer().getUniqueId();

        if (hostname != null && !hostname.isEmpty()) {
            hostnameCache.put(playerId, hostname);

            if (plugin.getConfig().getBoolean("debug.enabled", false)) {
                plugin.getLogger().info(String.format(
                        "Player %s connecting via: %s",
                        event.getPlayer().getName(),
                        hostname
                ));
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Check if player event tracking is enabled
        if (!plugin.getConfig().getBoolean("collection.track-player-events", true)) {
            hostnameCache.remove(event.getPlayer().getUniqueId());
            return;
        }

        UUID playerId = event.getPlayer().getUniqueId();
        String hostname = hostnameCache.remove(playerId);

        plugin.getEventCollector().addPlayerEvent(
                "PLAYER_JOIN",
                event.getPlayer().getUniqueId().toString(),
                event.getPlayer().getName(),
                hostname
        );

        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info(String.format(
                    "Player joined: %s (hostname: %s)",
                    event.getPlayer().getName(),
                    hostname != null ? hostname : "unknown"
            ));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Clean up cache
        hostnameCache.remove(event.getPlayer().getUniqueId());

        // Check if player event tracking is enabled
        if (!plugin.getConfig().getBoolean("collection.track-player-events", true)) {
            return;
        }

        plugin.getEventCollector().addPlayerEvent(
                "PLAYER_QUIT",
                event.getPlayer().getUniqueId().toString(),
                event.getPlayer().getName(),
                null
        );

        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info("Player quit: " + event.getPlayer().getName());
        }
    }
}
