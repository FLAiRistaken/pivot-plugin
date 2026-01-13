// src/main/java/gg/pivot/EventListener.java
package gg.pivot;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Listens for Bukkit events to capture player activity.
 * <p>
 * Captures:
 * <ul>
 *   <li>PlayerLoginEvent: To capture the hostname used to connect.</li>
 *   <li>PlayerJoinEvent: To record player joins and attribute them to hostnames.</li>
 *   <li>PlayerQuitEvent: To record player quits and clean up caches.</li>
 * </ul>
 * </p>
 */
public class EventListener implements Listener {
    private final PivotPlugin plugin;

    // Cache hostname per player (LoginEvent fires before JoinEvent)
    private final Map<UUID, String> hostnameCache = new HashMap<>();

    // Track players seen this session for connection_type
    private final Set<UUID> seenPlayers = new HashSet<>();

    // Store kick reasons to enrich quit events
    private final Map<UUID, String> kickReasons = new HashMap<>();

    public EventListener(PivotPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Captures the hostname used by the player to connect.
     *
     * @param event The PlayerLoginEvent
     */
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

    /**
     * Records a player join event and attributes it to the cached hostname.
     *
     * @param event The PlayerJoinEvent
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Check if player event tracking is enabled
        if (!plugin.getConfig().getBoolean("collection.track-player-events", true)) {
            hostnameCache.remove(event.getPlayer().getUniqueId());
            return;
        }

        UUID playerId = event.getPlayer().getUniqueId();
        String hostname = hostnameCache.remove(playerId);

        String connectionType = seenPlayers.contains(playerId) ? "reconnect" : "initial";
        seenPlayers.add(playerId);

        plugin.getEventCollector().addPlayerEvent(
                "PLAYER_JOIN",
                event.getPlayer().getUniqueId().toString(),
                event.getPlayer().getName(),
                hostname,
                null,
                null,
                connectionType
        );

        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info(String.format(
                    "Player joined: %s (hostname: %s)",
                    event.getPlayer().getName(),
                    hostname != null ? hostname : "unknown"
            ));
        }
    }

    /**
     * Capture kick reason to enrich the subsequent quit event.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerKick(PlayerKickEvent event) {
        if (plugin.getConfig().getBoolean("collection.track-player-events", true)) {
            kickReasons.put(event.getPlayer().getUniqueId(), event.getReason());
        }
    }

    /**
     * Records a player quit event and cleans up any remaining cache data.
     *
     * @param event The PlayerQuitEvent
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();

        // Clean up cache
        hostnameCache.remove(playerId);

        // Check if player event tracking is enabled
        if (!plugin.getConfig().getBoolean("collection.track-player-events", true)) {
            kickReasons.remove(playerId); // Ensure cleanup even if tracking is disabled
            return;
        }

        String reason = kickReasons.remove(playerId);
        boolean sessionClean;

        // If we have a stored kick reason, it's unclean
        if (reason != null) {
            sessionClean = false;
        } else {
            // Check quit message for timeout patterns if no kick event occurred
            String quitMessage = event.getQuitMessage();
            if (quitMessage != null && (
                    quitMessage.toLowerCase().contains("timeout") ||
                    quitMessage.toLowerCase().contains("timed out")
            )) {
                reason = "disconnect.timeout";
                sessionClean = false;
            } else {
                reason = "disconnect.quitting";
                sessionClean = true;
            }
        }

        plugin.getEventCollector().addPlayerEvent(
                "PLAYER_QUIT",
                playerId.toString(),
                event.getPlayer().getName(),
                null,
                reason,
                sessionClean,
                null
        );

        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info("Player quit: " + event.getPlayer().getName() + " (" + reason + ")");
        }
    }
}
