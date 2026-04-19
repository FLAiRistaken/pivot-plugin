package gg.pivot;

import com.google.gson.JsonObject;
import org.bukkit.configuration.file.FileConfiguration;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

public class ConfigSnapshotReporter {
    private final PivotPlugin plugin;
    private final EventCollector eventCollector;
    private final TickProfiler tickProfiler;
    private final ChunkProfiler chunkProfiler;
    private int batchCycleCount = 0;
    private static final int SNAPSHOT_INTERVAL_CYCLES = 20;

    public ConfigSnapshotReporter(PivotPlugin plugin, EventCollector eventCollector, TickProfiler tickProfiler, ChunkProfiler chunkProfiler) {
        this.plugin = plugin;
        this.eventCollector = eventCollector;
        this.tickProfiler = tickProfiler;
        this.chunkProfiler = chunkProfiler;
    }

    public void maybeEmitSnapshot() {
        boolean shouldEmit = false;
        if (batchCycleCount == 0) {
            shouldEmit = true;
        } else if (batchCycleCount % SNAPSHOT_INTERVAL_CYCLES == 0) {
            shouldEmit = true;
        }

        batchCycleCount++;

        if (!shouldEmit) {
            return;
        }

        FileConfiguration config = plugin.getConfig();
        JsonObject json = new JsonObject();

        json.addProperty("type", "CONFIG_SNAPSHOT");

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        json.addProperty("timestamp", sdf.format(new Date()));

        json.addProperty("plugin_version", plugin.getDescription().getVersion());
        json.addProperty("minecraft_version", plugin.getServer().getVersion());
        json.addProperty("api_endpoint", plugin.getApiEndpoint());
        json.addProperty("collection_enabled", config.getBoolean("collection.enabled", true));
        json.addProperty("collection_batch_interval_seconds", config.getInt("collection.batch-interval", 60));
        json.addProperty("collection_track_player_events", config.getBoolean("collection.track-player-events", true));
        json.addProperty("collection_track_performance", config.getBoolean("collection.track-performance", true));
        json.addProperty("collection_tps_sample_interval_seconds", config.getInt("collection.tps-sample-interval", 30));
        json.addProperty("privacy_anonymize_players", config.getBoolean("privacy.anonymize-players", false));
        json.addProperty("privacy_track_hostnames", config.getBoolean("privacy.track-hostnames", true));
        json.addProperty("debug_enabled", config.getBoolean("debug.enabled", false));
        json.addProperty("debug_log_batches", config.getBoolean("debug.log-batches", false));
        json.addProperty("profiling_enabled", config.getBoolean("profiling.enabled", true));
        json.addProperty("profiling_mode", config.getString("profiling.mode", "auto"));

        boolean profilingAutoDisabled = false;
        if (tickProfiler != null) {
            profilingAutoDisabled = tickProfiler.isAutoDisabled();
        }
        json.addProperty("profiling_auto_disabled", profilingAutoDisabled);

        json.addProperty("chunk_profiling_enabled", config.getBoolean("profiling.chunk_profiling.enabled", false));

        boolean chunkAutoDisabled = false;
        if (chunkProfiler != null) {
            chunkAutoDisabled = chunkProfiler.isAutoDisabled();
        }
        json.addProperty("chunk_profiling_auto_disabled", chunkAutoDisabled);

        json.addProperty("chunk_profiling_overhead_threshold_ms", config.getDouble("profiling.chunk_profiling.overhead_threshold_ms", 0.5));
        json.addProperty("command_profiling_enabled", config.getBoolean("profiling.command_profiling.enabled", false));
        json.addProperty("command_profiling_slow_threshold_ms", config.getInt("profiling.command_profiling.slow_threshold_ms", 100));
        json.addProperty("perf_max_overhead_ms", config.getDouble("profiling.performance.max_overhead_ms", 0.2));
        json.addProperty("perf_auto_disable_on_overhead", config.getBoolean("profiling.performance.auto_disable_on_overhead", true));

        json.addProperty("sampling_duration_seconds", config.getInt("profiling.sampling.duration_seconds", 30));
        json.addProperty("sampling_interval_minutes", config.getInt("profiling.sampling.interval_minutes", 5));
        json.addProperty("triggers_auto_profile_on_lag", config.getBoolean("profiling.triggers.auto_profile_on_lag", true));
        json.addProperty("triggers_lag_threshold_tps", config.getDouble("profiling.triggers.lag_threshold_tps", 18.0));
        json.addProperty("privacy_share_anonymous_data", config.getBoolean("profiling.privacy.share_anonymous_data", true));
        json.addProperty("anonymize_plugin_names", config.getBoolean("profiling.privacy.anonymize_plugin_names", false));

        eventCollector.addProfilingEvent(json);
    }
}
