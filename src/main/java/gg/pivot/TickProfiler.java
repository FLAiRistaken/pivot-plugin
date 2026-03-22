package gg.pivot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Handles automated performance profiling for plugins.
 * Supports hybrid mode: Paper Timings (v2) on Paper servers,
 * falling back to event listener wrapping on Spigot.
 */
public class TickProfiler {

    private final PivotPlugin plugin;
    private final ConfigManager configManager;
    private final Logger logger;

    private boolean isPaper;
    private volatile boolean profilingEnabled;
    private String mode;
    private volatile boolean autoDisabled = false;

    // Paper Timings state (reserved for future full implementation)
    private Class<?> timingsManagerClass;
    private Field handlersField;

    // Spigot custom profiling state
    private final List<WrappedListenerInfo> wrappedListeners = new ArrayList<>();
    // Plugin Name -> Sample
    private volatile ConcurrentHashMap<String, PluginSample> currentSpigotSamples = new ConcurrentHashMap<>();

    // Overhead tracking
    private final AtomicLong overheadNano = new AtomicLong(0);
    private final AtomicInteger overheadViolations = new AtomicInteger(0);

    // Tracks the actual elapsed time between collectSample() calls
    private final AtomicLong lastSampleTimestampMs = new AtomicLong(System.currentTimeMillis());

    // Listener-wrapping profiling state is reverted on shutdown using wrappedListeners

    /**
     * Initializes the TickProfiler.
     *
     * @param plugin        The main plugin instance.
     * @param configManager The configuration manager to retrieve profiling settings.
     */
    public TickProfiler(PivotPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.logger = plugin.getLogger();

        initialize();
    }

    /**
     * Initializes profiling based on configuration and server type.
     * <p>
     * Detects whether the server is running Paper by probing for Timings v2 classes.
     * When Paper is detected and {@code paper_timings} mode is active, profiling still
     * uses the custom listener-wrapping backend ({@code custom_spigot}) because the
     * full Paper Timings v2 collector is not yet implemented.
     * Falls back to custom Spigot profiling (listener wrapping) if Paper is not detected
     * or if {@code custom_only} is configured.
     * </p>
     */
    private void initialize() {
        if (!configManager.isProfilingEnabled()) {
            this.profilingEnabled = false;
            this.mode = "disabled";
            return;
        }

        String configuredMode = configManager.getProfilingMode();

        // Detect Paper
        boolean paperDetected = false;
        try {
            Class.forName("co.aikar.timings.Timings");
            // Check for TimingsManager to be sure we can access data
            timingsManagerClass = Class.forName("co.aikar.timings.TimingsManager");
            handlersField = timingsManagerClass.getDeclaredField("HANDLERS");
            handlersField.setAccessible(true);
            paperDetected = true;
        } catch (ClassNotFoundException | NoSuchFieldException e) {
            paperDetected = false;
        }

        if (configuredMode.equals("paper_only") && !paperDetected) {
            logger.warning("TickProfiler: paper_only mode requested but Paper Timings not found. Disabling.");
            this.profilingEnabled = false;
            this.mode = "disabled (missing paper)";
            return;
        }

        if (configuredMode.equals("custom_only")) {
            paperDetected = false;
        }

        if (paperDetected && !configuredMode.equals("custom_only")) {
            this.mode = "paper_timings";
            this.isPaper = true;
            // TODO: Implement full Paper Timings v2 collection. For now, use the custom
            // Spigot profiling backend even on Paper, since collectPaperSamples()
            // currently delegates to collectSpigotSamples().
            if (setupSpigotProfiling()) {
                this.profilingEnabled = true;
                logger.info("TickProfiler initialised in paper_timings mode (using custom_spigot backend)");
            } else {
                this.profilingEnabled = false;
                this.mode = "disabled (profiling setup failed)";
            }
        } else {
            this.mode = "custom_spigot";
            this.isPaper = false;
            if (setupSpigotProfiling()) {
                this.profilingEnabled = true;
                logger.info("TickProfiler initialised in custom_spigot mode");
            } else {
                this.profilingEnabled = false;
                this.mode = "disabled (profiling setup failed)";
            }
        }
    }

    /**
     * Sets up custom profiling for Spigot servers by wrapping registered listeners.
     * <p>
     * Iterates through all registered handlers and replaces them with {@link ProfiledRegisteredListener}.
     * This allows measuring execution time of each event listener.
     * </p>
     *
     * @return {@code true} if setup was successful, {@code false} otherwise.
     */
    private boolean setupSpigotProfiling() {
        try {
            wrappedListeners.clear();
            ArrayList<HandlerList> handlerLists = HandlerList.getHandlerLists();
            for (HandlerList handlerList : handlerLists) {
                RegisteredListener[] listeners = handlerList.getRegisteredListeners();
                for (RegisteredListener listener : listeners) {
                    Plugin plugin = listener.getPlugin();
                    if (plugin == null) continue;
                    String pluginName = plugin.getName();

                    ProfiledRegisteredListener wrapped = new ProfiledRegisteredListener(listener, pluginName, this);
                    handlerList.unregister(listener);
                    handlerList.register(wrapped);

                    wrappedListeners.add(new WrappedListenerInfo(handlerList, listener, wrapped));
                }
            }
            return true;
        } catch (Exception e) {
            logger.severe("TickProfiler: Failed to setup handler profiling: " + e.getMessage());
            e.printStackTrace();
            profilingEnabled = false;
            return false;
        }
    }

    /**
     * Collects performance samples from plugins.
     * <p>
     * Calculates profiling overhead and auto-disables if limits are exceeded.
     * Aggregates data from either Paper Timings or custom Spigot profiling.
     * </p>
     *
     * @return A {@link JsonObject} containing the profile data, or {@code null} if disabled or no data.
     */
    public JsonObject collectSample() {
        if (!profilingEnabled || autoDisabled) return null;

        long now = System.currentTimeMillis();
        long elapsedMs = now - lastSampleTimestampMs.getAndSet(now);
        // Cast is safe: sampling intervals are expected to be seconds to minutes,
        // well within int range.
        int durationSeconds = (int) Math.max(1, elapsedMs / 1000);

        // Check overhead using actual elapsed time
        long overhead = overheadNano.getAndSet(0);
        double maxOverheadMs = configManager.getMaxOverheadMs();
        long totalTicks = Math.max(1L, 20L * durationSeconds);
        double avgOverheadPerTickMs = (overhead / (double)totalTicks) / 1_000_000.0;

        if (avgOverheadPerTickMs > maxOverheadMs) {
            if (overheadViolations.incrementAndGet() >= 3 && configManager.isAutoDisableOnOverhead()) {
                autoDisabled = true;
                logger.warning("TickProfiler auto-disabled: overhead exceeded " + maxOverheadMs + "ms threshold (Avg: " + String.format("%.3f", avgOverheadPerTickMs) + "ms).");
                return null;
            }
        } else {
            overheadViolations.set(0);
        }

        JsonObject event = new JsonObject();
        event.addProperty("event_type", "TICK_PROFILE");
        event.addProperty("timestamp", System.currentTimeMillis());
        event.addProperty("sample_duration_seconds", durationSeconds);
        event.addProperty("server_tps", TPSUtil.getTPS());
        event.addProperty("server_version", plugin.getServer().getVersion());
        event.addProperty("total_plugins", plugin.getServer().getPluginManager().getPlugins().length);
        event.addProperty("profiling_mode", mode);

        JsonArray pluginsArray = new JsonArray();

        if (isPaper) {
            collectPaperSamples(pluginsArray, durationSeconds);
        } else {
            collectSpigotSamples(pluginsArray, durationSeconds);
        }

        if (pluginsArray.size() == 0) return null; // No data collected

        event.add("plugins", pluginsArray);
        return event;
    }

    /**
     * Collects samples using Paper Timings API.
     * <p>
     * Currently falls back to Spigot sampling as full Paper implementation is pending.
     * </p>
     *
     * @param pluginsArray    The JSON array to populate with plugin data.
     * @param durationSeconds The duration of the sample in seconds.
     */
    private void collectPaperSamples(JsonArray pluginsArray, int durationSeconds) {
        // Full Paper Timings v2 implementation via reflection is incomplete.
        // Fall back to the custom Spigot sampling which works on both Paper and Spigot.
        collectSpigotSamples(pluginsArray, durationSeconds);
    }

    private static String anonymize(String pluginName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(pluginName.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i] & 0xff));
            }
            return "Plugin_" + hex;
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be present; fall back to a stable hex representation
            return "Plugin_" + Integer.toHexString(pluginName.hashCode());
        }
    }

    /**
     * Collects samples from the custom Spigot profiling map.
     * <p>
     * Resets the counters in the current sample map to ensure thread safety while processing.
     * Aggregates execution times and counts for each plugin.
     * </p>
     *
     * @param pluginsArray    The JSON array to populate with plugin data.
     * @param durationSeconds The duration of the sample in seconds.
     */
    private void collectSpigotSamples(JsonArray pluginsArray, int durationSeconds) {
        boolean anonymize = configManager.isAnonymizePluginNames();
        long windowMillis = durationSeconds * 1000L;

        for (Map.Entry<String, PluginSample> entry : currentSpigotSamples.entrySet()) {
            String pluginName = entry.getKey();
            PluginSample sample = entry.getValue();

            // Atomically swap in a fresh window and take ownership of the completed one.
            // Any add() calls that captured the old window reference before the swap will
            // still complete their writes into the returned window, so no samples are lost.
            PluginSample.Window window = sample.swap();
            long totalTimeNano = window.totalTimeNano.get();
            long maxTimeNano = window.maxTimeNano.get();
            long sampleCount = window.sampleCount.get();
            if (sampleCount == 0) continue;
            // If sampleCount was incremented before the totals were written, re-read totals once
            // to avoid reporting a transient zero snapshot.
            if (totalTimeNano == 0 && maxTimeNano == 0) {
                totalTimeNano = window.totalTimeNano.get();
                maxTimeNano = window.maxTimeNano.get();
            }

            double avgTickTimeMs = (totalTimeNano / (double) sampleCount) / 1_000_000.0;
            double totalTimeMs = totalTimeNano / 1_000_000.0;
            double maxTimeMs = maxTimeNano / 1_000_000.0;
            double percentage = (totalTimeMs / windowMillis) * 100.0;

            JsonObject p = new JsonObject();
            p.addProperty("name", anonymize ? anonymize(pluginName) : pluginName);
            p.addProperty("version", "unknown"); // We could look up version if we had Plugin instance, but we only have name here.
            p.addProperty("avg_tick_time_ms", Math.round(avgTickTimeMs * 100.0) / 100.0);
            p.addProperty("max_tick_time_ms", Math.round(maxTimeMs * 100.0) / 100.0);
            p.addProperty("total_time_ms", Math.round(totalTimeMs * 100.0) / 100.0);
            p.addProperty("percentage_of_tick", Math.round(percentage * 1000.0) / 1000.0);
            p.addProperty("sample_count", sampleCount);
            p.addProperty("event_count", sampleCount);
            p.addProperty("task_count", 0);

            pluginsArray.add(p);
        }
    }

    // --- Spigot Listener Profiling ---

    /**
     * Shuts down the profiler, restoring any wrapped {@link RegisteredListener}s
     * to their original, unwrapped state. Must be called from {@code onDisable()}
     * and any config reload path to avoid leaving profiling listeners registered
     * after the plugin is disabled.
     */
    public synchronized void shutdown() {
        profilingEnabled = false;
        autoDisabled = true;

        for (WrappedListenerInfo info : wrappedListeners) {
            info.list.unregister(info.wrapped);
            info.list.register(info.original);
        }
        wrappedListeners.clear();
        logger.info("TickProfiler: Restored original listeners");
    }

    /**
     * Retrieves the {@link EventExecutor} from a {@link RegisteredListener} using reflection.
     * Needed to bypass Spigot API limitations when creating wrapped listeners.
     *
     * @param listener The original registered listener
     * @return The event executor
     */
    private static EventExecutor getExecutor(RegisteredListener listener) {
        try {
            Field executorField = RegisteredListener.class.getDeclaredField("executor");
            executorField.setAccessible(true);
            return (EventExecutor) executorField.get(listener);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get executor from RegisteredListener", e);
        }
    }


    /**
     * A wrapper for {@link RegisteredListener} that measures execution time.
     */
    private class ProfiledRegisteredListener extends RegisteredListener {
        private final RegisteredListener delegate;
        private final TickProfiler profiler;
        private final PluginSample cachedSample; // ⚡ Bolt Optimization: Cache sample reference

        public ProfiledRegisteredListener(RegisteredListener delegate, String pluginName, TickProfiler profiler) {
            super(delegate.getListener(), getExecutor(delegate), delegate.getPriority(), delegate.getPlugin(), delegate.isIgnoringCancelled());
            this.delegate = delegate;
            this.profiler = profiler;
            // Get or create the sample object once during registration to eliminate map lookups on every event
            this.cachedSample = profiler.currentSpigotSamples.computeIfAbsent(pluginName, k -> new PluginSample());
        }

        @Override
        public void callEvent(Event event) throws EventException {
            if (!profiler.profilingEnabled || profiler.autoDisabled) {
                delegate.callEvent(event);
                return;
            }
            long start = System.nanoTime();
            try {
                delegate.callEvent(event);
            } finally {
                // ⚡ Bolt Optimization: Reduce expensive System.nanoTime() calls to minimize overhead
                long end = System.nanoTime();
                long duration = end - start;
                cachedSample.add(duration);
                profiler.overheadNano.addAndGet(System.nanoTime() - end);
            }
        }
    }

    /**
     * Holds execution time stats for a single plugin.
     * <p>
     * Uses an {@link java.util.concurrent.atomic.AtomicReference} to a {@link Window} so that the
     * collector can atomically swap in a fresh window and read the completed one without splitting
     * a single {@link #add} call across two sampling windows.
     */
    private static class PluginSample {
        /** One sampling window's worth of counters. */
        static final class Window {
            final AtomicLong totalTimeNano = new AtomicLong(0);
            final AtomicLong maxTimeNano = new AtomicLong(0);
            final AtomicLong sampleCount = new AtomicLong(0);
        }

        private final AtomicReference<Window> active =
                new AtomicReference<>(new Window());

        void add(long duration) {
            Window w = active.get();
            // Increment sampleCount first so that a collector which checks sampleCount
            // as a gate will not skip a window after time has been recorded. With this
            // ordering, a concurrent collector may briefly see sampleCount > 0 while the
            // totals lag behind by one in-flight update, but it will never see
            // totalTimeNano/maxTimeNano updated while sampleCount is still 0.
            w.sampleCount.incrementAndGet();
            w.totalTimeNano.addAndGet(duration);
            w.maxTimeNano.accumulateAndGet(duration, Math::max);
        }

        /**
         * Atomically swaps in a fresh {@link Window} and returns the completed one.
         * The caller owns the returned window exclusively and can read its values without
         * competing with {@link #add} (any in-flight {@code add} that captured the old
         * reference before the swap will complete its writes into the returned window).
         */
        Window swap() {
            return active.getAndSet(new Window());
        }
    }

    // --- Test helpers (package-private) ---

    /** Returns the total profiling overhead accumulated so far, for unit tests. */
    long getOverheadNanoForTesting() {
        return overheadNano.get();
    }

    /** Immutable snapshot of a plugin's profiling data, for verification in tests. */
    static final class PluginSampleSnapshot {
        final long totalTimeNano;
        final long maxTimeNano;
        final long sampleCount;

        PluginSampleSnapshot(long totalTimeNano, long maxTimeNano, long sampleCount) {
            this.totalTimeNano = totalTimeNano;
            this.maxTimeNano = maxTimeNano;
            this.sampleCount = sampleCount;
        }
    }

    /** Returns an immutable snapshot of the current plugin samples map, for unit tests. */
    Map<String, PluginSampleSnapshot> getCurrentSamplesSnapshotForTesting() {
        Map<String, PluginSampleSnapshot> snapshot = new HashMap<>();
        for (Map.Entry<String, PluginSample> entry : currentSpigotSamples.entrySet()) {
            PluginSample sample = entry.getValue();
            PluginSample.Window w = sample.active.get();
            snapshot.put(entry.getKey(),
                    new PluginSampleSnapshot(w.totalTimeNano.get(), w.maxTimeNano.get(), w.sampleCount.get()));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Stores information about a wrapped listener to allow restoration on shutdown.
     */
    private static class WrappedListenerInfo {
        final HandlerList list;
        final RegisteredListener original;
        final RegisteredListener wrapped;

        WrappedListenerInfo(HandlerList list, RegisteredListener original, RegisteredListener wrapped) {
            this.list = list;
            this.original = original;
            this.wrapped = wrapped;
        }
    }
}
