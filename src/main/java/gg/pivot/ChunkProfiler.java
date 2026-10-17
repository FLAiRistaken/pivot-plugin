package gg.pivot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * Measures how long chunk load/unload events spend in the listener chain.
 * <p>
 * Bukkit gives no per-listener timing, so the only honest number is the time from the
 * LOWEST to the MONITOR handler of each event: the whole chain, covering the server and
 * every plugin listening at LOWEST..HIGHEST priority (MONITOR listeners are excluded).
 * That total is reported per sample together with the plugins that have a chunk listener
 * registered. It is deliberately <b>not</b> divided between plugins: an equal split looks
 * like a per-plugin measurement and is not one.
 * </p>
 */
public class ChunkProfiler implements Listener {
    // The backend rejects a whole batch if these are exceeded (ChunkPluginSample / ChunkProfileEvent).
    static final int MAX_PLUGIN_NAME_LENGTH = 64;
    static final int MAX_PLUGIN_VERSION_LENGTH = 32;
    static final int MAX_LISTED_PLUGINS = 100;

    /** Backstop only: a start whose MONITOR never ran. Normal depth is 1 (2+ when re-entrant). */
    private static final int MAX_PENDING_DEPTH = 64;

    private final PivotPlugin plugin;
    private final EventCollector eventCollector;
    private final LongSupplier nanoClock;

    /** One sample window. Swapped atomically on flush so producers and the flush never race on a half-reset window. */
    private static final class Window {
        final LongAdder loaded = new LongAdder();
        final LongAdder loadNanos = new LongAdder();
        final LongAccumulator loadMaxNanos = new LongAccumulator(Math::max, 0L);
        final LongAdder unloaded = new LongAdder();
        final LongAdder unloadNanos = new LongAdder();
        final LongAccumulator unloadMaxNanos = new LongAccumulator(Math::max, 0L);

        boolean isEmpty() {
            return loaded.sum() == 0 && unloaded.sum() == 0;
        }
    }

    private final AtomicReference<Window> window = new AtomicReference<>(new Window());

    /** The LOWEST-priority timestamp for an event, matched to its MONITOR handler by event instance. */
    private static final class PendingStart {
        final Event event;
        final long startNanos;

        PendingStart(Event event, long startNanos) {
            this.event = event;
            this.startNanos = startNanos;
        }
    }

    // A stack (not a single slot) so re-entrant chunk events on the same thread nest correctly.
    private final ThreadLocal<Deque<PendingStart>> loadStarts = ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<Deque<PendingStart>> unloadStarts = ThreadLocal.withInitial(ArrayDeque::new);

    private final AtomicLong overheadNano = new AtomicLong(0);
    private final AtomicInteger processedChunks = new AtomicInteger(0);

    private volatile boolean enabled = true;
    private volatile boolean autoDisabled = false;
    private final double overheadThresholdMs;

    public ChunkProfiler(PivotPlugin plugin, EventCollector eventCollector) {
        this(plugin, eventCollector, System::nanoTime);
    }

    /** Package-private so tests can drive the clock. */
    ChunkProfiler(PivotPlugin plugin, EventCollector eventCollector, LongSupplier nanoClock) {
        this.plugin = plugin;
        this.eventCollector = eventCollector;
        this.nanoClock = nanoClock;
        boolean globalEnabled = plugin.getConfig().getBoolean("profiling.enabled", true);
        boolean chunkEnabled = plugin.getConfig().getBoolean("profiling.chunk_profiling.enabled", false);
        this.enabled = globalEnabled && chunkEnabled;
        this.overheadThresholdMs = plugin.getConfig().getDouble("profiling.chunk_profiling.overhead_threshold_ms", 0.5);
    }

    public void disable() {
        this.enabled = false;
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
        boolean chunkEnabled = plugin.getConfig().getBoolean("profiling.chunk_profiling.enabled", false);
        this.enabled = globalEnabled && chunkEnabled;
        this.autoDisabled = false;
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
                this.autoDisabled = true;
            }
        }
    }

    private void pushStart(Deque<PendingStart> stack, Event event) {
        if (stack.size() >= MAX_PENDING_DEPTH) {
            stack.clear();
        }
        stack.push(new PendingStart(event, nanoClock.getAsLong()));
    }

    /**
     * Removes and returns the start recorded for {@code event}, or null if there is none.
     * <p>
     * Starts above it belong to events whose MONITOR handler never ran, so they are dropped.
     * If there is no start for this event (profiling was off at LOWEST and on by MONITOR) the
     * stack is left alone: it may hold a valid start for an outer event still in flight.
     * </p>
     */
    private PendingStart takeStart(Deque<PendingStart> stack, Event event) {
        boolean present = false;
        for (PendingStart pending : stack) {
            if (pending.event == event) {
                present = true;
                break;
            }
        }
        if (!present) {
            return null;
        }
        PendingStart pending;
        do {
            pending = stack.pop();
        } while (pending.event != event);
        return pending;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChunkLoadStart(ChunkLoadEvent event) {
        if (!enabled) return;
        pushStart(loadStarts.get(), event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoadEnd(ChunkLoadEvent event) {
        long end = nanoClock.getAsLong();
        // Always take our start first, even when disabled, so a toggle between the two
        // handlers cannot leave a stale start behind for a later event to pick up.
        PendingStart start = takeStart(loadStarts.get(), event);
        if (start == null || !enabled) return;

        long startOverhead = nanoClock.getAsLong();

        long durationNano = Math.max(0L, end - start.startNanos);
        Window w = window.get();
        w.loaded.increment();
        w.loadNanos.add(durationNano);
        w.loadMaxNanos.accumulate(durationNano);

        checkOverhead(nanoClock.getAsLong() - startOverhead);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChunkUnloadStart(ChunkUnloadEvent event) {
        if (!enabled) return;
        pushStart(unloadStarts.get(), event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnloadEnd(ChunkUnloadEvent event) {
        long end = nanoClock.getAsLong();
        PendingStart start = takeStart(unloadStarts.get(), event);
        if (start == null || !enabled) return;

        long startOverhead = nanoClock.getAsLong();

        long durationNano = Math.max(0L, end - start.startNanos);
        Window w = window.get();
        w.unloaded.increment();
        w.unloadNanos.add(durationNano);
        w.unloadMaxNanos.accumulate(durationNano);

        checkOverhead(nanoClock.getAsLong() - startOverhead);
    }

    /**
     * Plugins that have a chunk load or unload listener registered right now, excluding this
     * plugin, as name to version. Read once per flush rather than per event.
     */
    private Map<String, String> listeningPlugins() {
        Map<String, String> listening = new TreeMap<>();
        collectListeners(ChunkLoadEvent.getHandlerList(), listening);
        collectListeners(ChunkUnloadEvent.getHandlerList(), listening);
        return listening;
    }

    private void collectListeners(HandlerList handlers, Map<String, String> into) {
        for (RegisteredListener listener : handlers.getRegisteredListeners()) {
            Plugin p = listener.getPlugin();
            if (p == null || p.getName().equals(plugin.getName())) continue; // exclude ourselves
            String version = p.getDescription() != null ? p.getDescription().getVersion() : null;
            into.putIfAbsent(p.getName(), version != null ? version : "unknown");
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static double toMs(long nanos) {
        return Math.round((nanos / 1_000_000.0) * 100.0) / 100.0;
    }

    public void flushAndReset() {
        // Fast path: skip all allocations when disabled and nothing has been recorded.
        // Event handlers only record while enabled, so this peek is safe.
        if (!enabled && window.get().isEmpty()) return;

        // Swap the window so main-thread producers immediately write to a fresh one while
        // this thread reads the captured snapshot.
        Window captured = window.getAndSet(new Window());

        JsonObject event = new JsonObject();
        event.addProperty("type", "CHUNK_PROFILE");

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        event.addProperty("timestamp", sdf.format(new Date()));

        int sampleDurationSeconds = plugin.getConfig().getInt("collection.batch-interval", PivotPlugin.DEFAULT_BATCH_INTERVAL_SECONDS);
        event.addProperty("sample_duration_seconds", sampleDurationSeconds);
        event.addProperty("chunks_loaded", captured.loaded.sum());
        event.addProperty("chunks_unloaded", captured.unloaded.sum());

        // Whole listener-chain time for the sample (server and every plugin), not per plugin.
        event.addProperty("load_listener_total_ms", toMs(captured.loadNanos.sum()));
        event.addProperty("load_listener_max_ms", toMs(captured.loadMaxNanos.get()));
        event.addProperty("unload_listener_total_ms", toMs(captured.unloadNanos.sum()));
        event.addProperty("unload_listener_max_ms", toMs(captured.unloadMaxNanos.get()));

        JsonArray pluginsArray = new JsonArray();
        for (Map.Entry<String, String> listening : listeningPlugins().entrySet()) {
            if (pluginsArray.size() >= MAX_LISTED_PLUGINS) break;
            JsonObject p = new JsonObject();
            p.addProperty("name", truncate(listening.getKey(), MAX_PLUGIN_NAME_LENGTH));
            p.addProperty("version", truncate(listening.getValue(), MAX_PLUGIN_VERSION_LENGTH));
            pluginsArray.add(p);
        }
        event.add("plugins", pluginsArray);

        eventCollector.addProfilingEvent(event);
    }

    public boolean isAutoDisabled() {
        return autoDisabled;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
