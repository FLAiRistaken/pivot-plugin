package gg.pivot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Profiles chunk load and unload events per plugin.
 */
public class ChunkProfiler implements Listener {
    private final PivotPlugin plugin;
    private final EventCollector eventCollector;

    private final ConcurrentHashMap<String, Double> pluginTotalLoadTimeMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> pluginMaxLoadTimeMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> pluginLoadEventCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> pluginVersions = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Double> pluginTotalUnloadTimeMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> pluginMaxUnloadTimeMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> pluginUnloadEventCount = new ConcurrentHashMap<>();

    private final AtomicInteger totalChunksLoaded = new AtomicInteger(0);
    private final AtomicInteger totalChunksUnloaded = new AtomicInteger(0);

    private final ThreadLocal<Long> loadStartTimeNano = ThreadLocal.withInitial(() -> 0L);
    private final ThreadLocal<Long> unloadStartTimeNano = ThreadLocal.withInitial(() -> 0L);

    private final AtomicLong overheadNano = new AtomicLong(0);
    private final AtomicInteger processedChunks = new AtomicInteger(0);

    private volatile boolean enabled = true;
    private final double overheadThresholdMs;

    public ChunkProfiler(PivotPlugin plugin, EventCollector eventCollector) {
        this.plugin = plugin;
        this.eventCollector = eventCollector;
        this.enabled = plugin.getConfig().getBoolean("profiling.chunk_profiling.enabled", true);
        this.overheadThresholdMs = plugin.getConfig().getDouble("profiling.chunk_profiling.overhead_threshold_ms", 0.5);
    }

    public void disable() {
        this.enabled = false;
    }

    private void checkOverhead(long overhead) {
        overheadNano.addAndGet(overhead);
        int count = processedChunks.incrementAndGet();

        // Check every 100 chunks to avoid constant calculation
        if (count >= 100) {
            long totalOverhead = overheadNano.getAndSet(0);
            processedChunks.set(0);

            double avgOverheadMs = (totalOverhead / (double) count) / 1_000_000.0;
            if (avgOverheadMs > overheadThresholdMs) {
                plugin.getLogger().warning("ChunkProfiler auto-disabled: overhead exceeded " + overheadThresholdMs + "ms threshold (Avg: " + String.format("%.3f", avgOverheadMs) + "ms).");
                this.enabled = false;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChunkLoadStart(ChunkLoadEvent event) {
        if (!enabled) return;
        loadStartTimeNano.set(System.nanoTime());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoadEnd(ChunkLoadEvent event) {
        if (!enabled) return;
        long end = System.nanoTime();
        long start = loadStartTimeNano.get();
        if (start == 0) return; // Ignore if LOWEST didn't run

        long startOverhead = System.nanoTime();

        long durationNano = end - start;
        totalChunksLoaded.incrementAndGet();

        // Attribute duration equally to all registered listeners
        RegisteredListener[] listeners = ChunkLoadEvent.getHandlerList().getRegisteredListeners();

        // Count unique plugins
        Map<String, Plugin> activePlugins = new HashMap<>();
        for (RegisteredListener listener : listeners) {
            Plugin p = listener.getPlugin();
            if (p != null && !p.getName().equals(plugin.getName())) { // Exclude ourselves
                activePlugins.put(p.getName(), p);
            }
        }

        int pluginCount = activePlugins.size();

        if (pluginCount > 0) {
            double durationMs = (durationNano / 1_000_000.0) / pluginCount;

            for (Map.Entry<String, Plugin> entry : activePlugins.entrySet()) {
                String name = entry.getKey();
                Plugin p = entry.getValue();

                pluginVersions.putIfAbsent(name, p.getDescription().getVersion());

                pluginTotalLoadTimeMs.merge(name, durationMs, Double::sum);

                Double currentMax = pluginMaxLoadTimeMs.get(name);
                if (currentMax == null || durationMs > currentMax) {
                    pluginMaxLoadTimeMs.put(name, durationMs);
                }

                pluginLoadEventCount.merge(name, 1, Integer::sum);
            }
        }

        checkOverhead(System.nanoTime() - startOverhead);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChunkUnloadStart(ChunkUnloadEvent event) {
        if (!enabled) return;
        unloadStartTimeNano.set(System.nanoTime());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnloadEnd(ChunkUnloadEvent event) {
        if (!enabled) return;
        long end = System.nanoTime();
        long start = unloadStartTimeNano.get();
        if (start == 0) return;

        long startOverhead = System.nanoTime();

        long durationNano = end - start;
        totalChunksUnloaded.incrementAndGet();

        RegisteredListener[] listeners = ChunkUnloadEvent.getHandlerList().getRegisteredListeners();

        Map<String, Plugin> activePlugins = new HashMap<>();
        for (RegisteredListener listener : listeners) {
            Plugin p = listener.getPlugin();
            if (p != null && !p.getName().equals(plugin.getName())) {
                activePlugins.put(p.getName(), p);
            }
        }

        int pluginCount = activePlugins.size();

        if (pluginCount > 0) {
            double durationMs = (durationNano / 1_000_000.0) / pluginCount;

            for (Map.Entry<String, Plugin> entry : activePlugins.entrySet()) {
                String name = entry.getKey();
                Plugin p = entry.getValue();

                pluginVersions.putIfAbsent(name, p.getDescription().getVersion());

                pluginTotalUnloadTimeMs.merge(name, durationMs, Double::sum);

                Double currentMax = pluginMaxUnloadTimeMs.get(name);
                if (currentMax == null || durationMs > currentMax) {
                    pluginMaxUnloadTimeMs.put(name, durationMs);
                }

                pluginUnloadEventCount.merge(name, 1, Integer::sum);
            }
        }

        checkOverhead(System.nanoTime() - startOverhead);
    }

    public void flushAndReset() {
        if (!enabled && totalChunksLoaded.get() == 0 && totalChunksUnloaded.get() == 0) return;

        JsonObject event = new JsonObject();
        event.addProperty("type", "CHUNK_PROFILE");

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        event.addProperty("timestamp", sdf.format(new Date()));

        int sampleDurationSeconds = plugin.getConfig().getInt("collection.batch-interval", 30);
        event.addProperty("sample_duration_seconds", sampleDurationSeconds);
        event.addProperty("chunks_loaded", totalChunksLoaded.getAndSet(0));
        event.addProperty("chunks_unloaded", totalChunksUnloaded.getAndSet(0));

        JsonArray pluginsArray = new JsonArray();

        Set<String> allPlugins = new HashSet<>();
        allPlugins.addAll(pluginTotalLoadTimeMs.keySet());
        allPlugins.addAll(pluginTotalUnloadTimeMs.keySet());

        for (String name : allPlugins) {
            Double totalLoadObj = pluginTotalLoadTimeMs.remove(name);
            double totalLoad = totalLoadObj != null ? totalLoadObj : 0;
            Double maxLoadObj = pluginMaxLoadTimeMs.remove(name);
            double maxLoad = maxLoadObj != null ? maxLoadObj : 0;
            Integer countLoadObj = pluginLoadEventCount.remove(name);
            int countLoad = countLoadObj != null ? countLoadObj : 0;

            Double totalUnloadObj = pluginTotalUnloadTimeMs.remove(name);
            double totalUnload = totalUnloadObj != null ? totalUnloadObj : 0;
            Double maxUnloadObj = pluginMaxUnloadTimeMs.remove(name);
            double maxUnload = maxUnloadObj != null ? maxUnloadObj : 0;
            Integer countUnloadObj = pluginUnloadEventCount.remove(name);
            int countUnload = countUnloadObj != null ? countUnloadObj : 0;

            String version = pluginVersions.remove(name);

            double avgLoad = countLoad > 0 ? totalLoad / countLoad : 0;
            double avgUnload = countUnload > 0 ? totalUnload / countUnload : 0;

            if (avgLoad < 0.01 && avgUnload < 0.01) {
                continue;
            }

            JsonObject p = new JsonObject();
            p.addProperty("name", name);
            p.addProperty("version", version != null ? version : "unknown");

            if (countLoad > 0) {
                p.addProperty("avg_load_time_ms", Math.round(avgLoad * 100.0) / 100.0);
                p.addProperty("max_load_time_ms", Math.round(maxLoad * 100.0) / 100.0);
                p.addProperty("total_load_events", countLoad);
            }

            if (countUnload > 0) {
                p.addProperty("avg_unload_time_ms", Math.round(avgUnload * 100.0) / 100.0);
                p.addProperty("max_unload_time_ms", Math.round(maxUnload * 100.0) / 100.0);
                p.addProperty("total_unload_events", countUnload);
            }

            pluginsArray.add(p);
        }

        event.add("plugins", pluginsArray);
        eventCollector.addProfilingEvent(event);
    }

    public boolean isEnabled() {
        return enabled;
    }
}
