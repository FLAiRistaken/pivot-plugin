package gg.pivot;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Profiles player commands to detect slow commands.
 */
public class CommandProfiler implements Listener {
    private final PivotPlugin plugin;
    private final EventCollector eventCollector;
    private final ConcurrentHashMap<UUID, CommandTiming> activeTimings = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    private final double slowThresholdMs;

    static class CommandTiming {
        final String commandLabel;
        final long startNanos;
        final double tpsAtStart;
        final int playersOnlineAtStart;

        CommandTiming(String commandLabel, long startNanos, double tpsAtStart, int playersOnlineAtStart) {
            this.commandLabel = commandLabel;
            this.startNanos = startNanos;
            this.tpsAtStart = tpsAtStart;
            this.playersOnlineAtStart = playersOnlineAtStart;
        }
    }

    public CommandProfiler(PivotPlugin plugin, EventCollector eventCollector) {
        this.plugin = plugin;
        this.eventCollector = eventCollector;
        this.enabled = plugin.getConfig().getBoolean("profiling.command_profiling.enabled", true);
        this.slowThresholdMs = plugin.getConfig().getDouble("profiling.command_profiling.slow_threshold_ms", 100.0);
    }

    public void disable() {
        this.enabled = false;
        this.activeTimings.clear();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandStart(PlayerCommandPreprocessEvent event) {
        if (!enabled) return;

        String message = event.getMessage();
        if (message == null || message.isEmpty()) return;

        String commandLabel = message.split(" ")[0];
        if (commandLabel.startsWith("/")) {
            commandLabel = commandLabel.substring(1);
        }

        double tps = TPSUtil.getTPS();
        int playersOnline = plugin.getOnlinePlayerCount();

        activeTimings.put(event.getPlayer().getUniqueId(),
            new CommandTiming(commandLabel, System.nanoTime(), tps, playersOnline));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommandEnd(PlayerCommandPreprocessEvent event) {
        if (!enabled) return;

        UUID playerId = event.getPlayer().getUniqueId();
        CommandTiming timing = activeTimings.remove(playerId);

        if (timing == null) return;

        long durationNanos = System.nanoTime() - timing.startNanos;
        double durationMs = durationNanos / 1_000_000.0;

        if (durationMs >= slowThresholdMs) {
            String executorPlugin = "unknown";
            try {
                PluginCommand cmd = Bukkit.getPluginCommand(timing.commandLabel);
                if (cmd != null) {
                    Plugin p = cmd.getPlugin();
                    if (p != null) {
                        executorPlugin = p.getName();
                    }
                }
            } catch (Exception e) {
                // Ignore
            }

            JsonObject slowCommandEvent = new JsonObject();
            slowCommandEvent.addProperty("type", "SLOW_COMMAND");

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            slowCommandEvent.addProperty("timestamp", sdf.format(new Date()));

            slowCommandEvent.addProperty("command", timing.commandLabel);
            slowCommandEvent.addProperty("executor_plugin", executorPlugin);
            slowCommandEvent.addProperty("duration_ms", Math.round(durationMs));
            slowCommandEvent.addProperty("player_uuid", playerId.toString());
            slowCommandEvent.addProperty("server_tps_during", timing.tpsAtStart);
            slowCommandEvent.addProperty("players_online", timing.playersOnlineAtStart);

            eventCollector.addProfilingEvent(slowCommandEvent);
        }

        pruneStaleTimings();
    }

    private void pruneStaleTimings() {
        long now = System.nanoTime();
        // 5 minutes in nanoseconds
        long staleThreshold = 5L * 60L * 1_000_000_000L;

        Iterator<Map.Entry<UUID, CommandTiming>> it = activeTimings.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, CommandTiming> entry = it.next();
            if (now - entry.getValue().startNanos > staleThreshold) {
                it.remove();
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }
}
