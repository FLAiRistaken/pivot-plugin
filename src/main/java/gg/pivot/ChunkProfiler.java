package gg.pivot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Profiles chunk load and unload events per plugin.
 */
public class ChunkProfiler implements Listener {
    private final PivotPlugin plugin;
    private final EventCollector eventCollector;

    // Double-buffer accumulators: swapped atomically on flush to avoid races
    // between main-thread producers and async flush consumer.
    private final AtomicReference<ConcurrentHashMap<String, Double>> pluginTotalLoadTimeMs =
            new AtomicReference<>(new ConcurrentHashMap<>());
    private final AtomicReference<ConcurrentHashMap<String, Double>> pluginMaxLoadTimeMs =
            new AtomicReference<>(new ConcurrentHashMap<>());
    private final AtomicReference<ConcurrentHashMap<String, Integer>> pluginLoadEventCount =
            new AtomicReference<>(new ConcurrentHashMap<>());
    private final AtomicReference<ConcurrentHashMap<String, String>> pluginVersions =
            new AtomicReference<>(new ConcurrentHashMap<>());

    private final AtomicReference<ConcurrentHashMap<String, Double>> pluginTotalUnloadTimeMs =
            new AtomicReference<>(new ConcurrentHashMap<>());
    private final AtomicReference<ConcurrentHashMap<String, Double>> pluginMaxUnloadTimeMs =
            new AtomicReference<>(new ConcurrentHashMap<>());
    private final AtomicReference<ConcurrentHashMap<String, Integer>> pluginUnloadEventCount =
            new AtomicReference<>(new ConcurrentHashMap<>());

    private final AtomicInteger totalChunksLoaded = new AtomicInteger(0);
    private final AtomicInteger totalChunksUnloaded = new AtomicInteger(0);

    // Use a Deque (stack) so re-entrant chunk events on the same thread nest correctly:
    // inner event pushes/pops before the outer event's MONITOR handler runs.
    private final ThreadLocal<Deque<Long>> loadStartStack = ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<Deque<Long>> unloadStartStack = ThreadLocal.withInitial(ArrayDeque::new);

    private final AtomicLong overheadNano = new AtomicLong(0);
    private final AtomicInteger processedChunks = new AtomicInteger(0);

    private volatile boolean enabled = true;
    private final double overheadThresholdMs;

    public ChunkProfiler(PivotPlugin plugin, EventCollector eventCollector) {
        this.plugin = plugin;
        this.eventCollector = eventCollector;
        boolean globalEnabled = plugin.getConfig().getBoolean("profiling.enabled", true);
        boolean chunkEnabled = plugin.getConfig().getBoolean("profiling.chunk_profiling.enabled", false);
        this.enabled = globalEnabled && chunkEnabled;
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
        loadStartStack.get().push(System.nanoTime());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoadEnd(ChunkLoadEvent event) {
        if (!enabled) return;
        long end = System.nanoTime();
        Deque<Long> stack = loadStartStack.get();
        if (stack.isEmpty()) return;
        long start = stack.pop();

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

            ConcurrentHashMap<String, Double> totalLoad = pluginTotalLoadTimeMs.get();
            ConcurrentHashMap<String, Double> maxLoad = pluginMaxLoadTimeMs.get();
            ConcurrentHashMap<String, Integer> countLoad = pluginLoadEventCount.get();
            ConcurrentHashMap<String, String> versions = pluginVersions.get();

            for (Map.Entry<String, Plugin> entry : activePlugins.entrySet()) {
                String name = entry.getKey();
                Plugin p = entry.getValue();

                versions.putIfAbsent(name, p.getDescription().getVersion());
                totalLoad.merge(name, durationMs, Double::sum);

                maxLoad.merge(name, durationMs, (a, b) -> Math.max(a, b));

                countLoad.merge(name, 1, Integer::sum);
            }
        }

        checkOverhead(System.nanoTime() - startOverhead);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChunkUnloadStart(ChunkUnloadEvent event) {
        if (!enabled) return;
        unloadStartStack.get().push(System.nanoTime());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnloadEnd(ChunkUnloadEvent event) {
        if (!enabled) return;
        long end = System.nanoTime();
        Deque<Long> stack = unloadStartStack.get();
        if (stack.isEmpty()) return;
        long start = stack.pop();

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

            ConcurrentHashMap<String, Double> totalUnload = pluginTotalUnloadTimeMs.get();
            ConcurrentHashMap<String, Double> maxUnload = pluginMaxUnloadTimeMs.get();
            ConcurrentHashMap<String, Integer> countUnload = pluginUnloadEventCount.get();
            ConcurrentHashMap<String, String> versions = pluginVersions.get();

            for (Map.Entry<String, Plugin> entry : activePlugins.entrySet()) {
                String name = entry.getKey();
                Plugin p = entry.getValue();

                versions.putIfAbsent(name, p.getDescription().getVersion());
                totalUnload.merge(name, durationMs, Double::sum);
                maxUnload.merge(name, durationMs, (a, b) -> Math.max(a, b));
                countUnload.merge(name, 1, Integer::sum);
            }
        }

        checkOverhead(System.nanoTime() - startOverhead);
    }

    public void flushAndReset() {
        // Atomically swap accumulator maps so main-thread producers immediately write to
        // fresh maps while we safely process the captured snapshots.
        ConcurrentHashMap<String, Double> capturedTotalLoad = pluginTotalLoadTimeMs.getAndSet(new ConcurrentHashMap<>());
        ConcurrentHashMap<String, Double> capturedMaxLoad = pluginMaxLoadTimeMs.getAndSet(new ConcurrentHashMap<>());
        ConcurrentHashMap<String, Integer> capturedCountLoad = pluginLoadEventCount.getAndSet(new ConcurrentHashMap<>());
        ConcurrentHashMap<String, Double> capturedTotalUnload = pluginTotalUnloadTimeMs.getAndSet(new ConcurrentHashMap<>());
        ConcurrentHashMap<String, Double> capturedMaxUnload = pluginMaxUnloadTimeMs.getAndSet(new ConcurrentHashMap<>());
        ConcurrentHashMap<String, Integer> capturedCountUnload = pluginUnloadEventCount.getAndSet(new ConcurrentHashMap<>());
        ConcurrentHashMap<String, String> capturedVersions = pluginVersions.getAndSet(new ConcurrentHashMap<>());

        int chunksLoaded = totalChunksLoaded.getAndSet(0);
        int chunksUnloaded = totalChunksUnloaded.getAndSet(0);

        if (!enabled && chunksLoaded == 0 && chunksUnloaded == 0) return;

        JsonObject event = new JsonObject();
        event.addProperty("type", "CHUNK_PROFILE");

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        event.addProperty("timestamp", sdf.format(new Date()));

        int sampleDurationSeconds = plugin.getConfig().getInt("collection.batch-interval", 30);
        event.addProperty("sample_duration_seconds", sampleDurationSeconds);
        event.addProperty("chunks_loaded", chunksLoaded);
        event.addProperty("chunks_unloaded", chunksUnloaded);

        JsonArray pluginsArray = new JsonArray();

        Set<String> allPlugins = new HashSet<>();
        allPlugins.addAll(capturedTotalLoad.keySet());
        allPlugins.addAll(capturedTotalUnload.keySet());

        for (String name : allPlugins) {
            double totalLoad = capturedTotalLoad.getOrDefault(name, 0.0);
            double maxLoad = capturedMaxLoad.getOrDefault(name, 0.0);
            int countLoad = capturedCountLoad.getOrDefault(name, 0);

            double totalUnload = capturedTotalUnload.getOrDefault(name, 0.0);
            double maxUnload = capturedMaxUnload.getOrDefault(name, 0.0);
            int countUnload = capturedCountUnload.getOrDefault(name, 0);

            String version = capturedVersions.get(name);

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
