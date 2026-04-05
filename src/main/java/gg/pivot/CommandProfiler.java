package gg.pivot;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Profiles player commands to detect slow commands.
 */
public class CommandProfiler implements Listener {
    private final PivotPlugin plugin;
    private final EventCollector eventCollector;
    private final ConcurrentHashMap<UUID, CommandTiming> activeTimings = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    private volatile boolean enabled;
    private final double slowThresholdMs;

    private static final int PRUNE_SIZE_THRESHOLD = 50;
    private static final long PRUNE_INTERVAL_NANOS = 60L * 1_000_000_000L; // 60 seconds
    private final AtomicLong lastPruneNano = new AtomicLong(0L);

    private static final ThreadLocal<MessageDigest> SHA256_DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available!", e);
        }
    });

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
        this(plugin, eventCollector, System::nanoTime);
    }

    CommandProfiler(PivotPlugin plugin, EventCollector eventCollector, LongSupplier clock) {
        this.plugin = plugin;
        this.eventCollector = eventCollector;
        this.clock = clock;
        boolean globalEnabled = plugin.getConfig().getBoolean("profiling.enabled", true);
        boolean commandEnabled = plugin.getConfig().getBoolean("profiling.command_profiling.enabled", false);
        this.enabled = globalEnabled && commandEnabled;
        this.slowThresholdMs = plugin.getConfig().getDouble("profiling.command_profiling.slow_threshold_ms", 100.0);
    }

    public void disable() {
        this.enabled = false;
        this.activeTimings.clear();
    }

    /**
     * Reloads configuration and re-evaluates the enabled state.
     * <p>
     * Called when the plugin configuration is reloaded via {@code /pivot reload}.
     * This avoids permanently disabling the profiler after a reload.
     * </p>
     */
    public void reload() {
        boolean globalEnabled = plugin.getConfig().getBoolean("profiling.enabled", true);
        boolean commandEnabled = plugin.getConfig().getBoolean("profiling.command_profiling.enabled", false);
        this.enabled = globalEnabled && commandEnabled;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandStart(PlayerCommandPreprocessEvent event) {
        if (!enabled) return;

        String message = event.getMessage();
        if (message == null || message.isEmpty()) return;

        int spaceIndex = message.indexOf(' ');
        String commandLabel = (spaceIndex == -1) ? message : message.substring(0, spaceIndex);
        if (commandLabel.startsWith("/")) {
            commandLabel = commandLabel.substring(1);
        }

        double tps = TPSUtil.getTPS();
        int playersOnline = plugin.getOnlinePlayerCount();

        activeTimings.put(event.getPlayer().getUniqueId(),
            new CommandTiming(commandLabel, clock.getAsLong(), tps, playersOnline));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommandEnd(PlayerCommandPreprocessEvent event) {
        if (!enabled) return;

        UUID playerId = event.getPlayer().getUniqueId();
        CommandTiming timing = activeTimings.remove(playerId);

        if (timing == null) return;

        long endNanos = clock.getAsLong();
        long durationNanos = endNanos - timing.startNanos;
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
            boolean anonymize = plugin.getConfig().getBoolean("privacy.anonymize-players", false);
            String playerUuidValue = anonymize ? hashUuid(playerId.toString()) : playerId.toString();
            slowCommandEvent.addProperty("player_uuid", playerUuidValue);
            slowCommandEvent.addProperty("server_tps_during", timing.tpsAtStart);
            slowCommandEvent.addProperty("players_online", timing.playersOnlineAtStart);

            eventCollector.addProfilingEvent(slowCommandEvent);
        }

        if (activeTimings.size() > PRUNE_SIZE_THRESHOLD || endNanos - lastPruneNano.get() > PRUNE_INTERVAL_NANOS) {
            pruneStaleTimings(endNanos);
            lastPruneNano.set(endNanos);
        }
    }

    private void pruneStaleTimings(long now) {
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

    private String hashUuid(String uuid) {
        MessageDigest digest = SHA256_DIGEST.get();
        byte[] hash = digest.digest(uuid.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
