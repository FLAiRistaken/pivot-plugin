package gg.pivot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Handles automated performance profiling for plugins.
 * Supports hybrid mode: Paper Timings (v2) on Paper servers,
 * falling back to custom scheduler wrapping on Spigot.
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
    private Object originalScheduler;
    private BukkitScheduler proxyScheduler;
    // Plugin Name -> Sample
    private volatile ConcurrentHashMap<String, PluginSample> currentSpigotSamples = new ConcurrentHashMap<>();

    // Overhead tracking
    private final AtomicLong overheadNano = new AtomicLong(0);
    private final AtomicInteger overheadViolations = new AtomicInteger(0);

    // Tracks the actual elapsed time between collectSample() calls
    private final AtomicLong lastSampleTimestampMs = new AtomicLong(System.currentTimeMillis());

    // Saved scheduler field reference for restoring the original scheduler on shutdown
    private volatile Field savedSchedulerField;

    public TickProfiler(PivotPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.logger = plugin.getLogger();

        initialize();
    }

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
            if (!setupSpigotProxy()) {
                logger.warning("TickProfiler: Spigot proxy setup failed; Paper mode will not have Spigot fallback.");
            }
            this.profilingEnabled = true;
            logger.info("TickProfiler initialised in paper_timings mode");
        } else {
            this.mode = "custom_spigot";
            this.isPaper = false;
            if (setupSpigotProxy()) {
                this.profilingEnabled = true;
                logger.info("TickProfiler initialised in custom_spigot mode");
            } else {
                this.profilingEnabled = false;
                this.mode = "disabled (proxy failed)";
            }
        }
    }

    private boolean setupSpigotProxy() {
        try {
            Server server = Bukkit.getServer();
            // Try to find the scheduler field in CraftServer
            // Usually 'scheduler' or 'taskScheduler'
            Field schedulerField = null;
            try {
                schedulerField = server.getClass().getDeclaredField("scheduler");
            } catch (NoSuchFieldException e) {
                try {
                    schedulerField = server.getClass().getDeclaredField("taskScheduler");
                } catch (NoSuchFieldException ex) {
                    logger.warning("TickProfiler: Could not find scheduler field in " + server.getClass().getName());
                    return false;
                }
            }

            schedulerField.setAccessible(true);
            this.originalScheduler = schedulerField.get(server);
            this.savedSchedulerField = schedulerField; // Store for shutdown()

            this.proxyScheduler = (BukkitScheduler) Proxy.newProxyInstance(
                BukkitScheduler.class.getClassLoader(),
                new Class[]{BukkitScheduler.class},
                new SchedulerInvocationHandler(originalScheduler)
            );

            // Replace the scheduler
            schedulerField.set(server, proxyScheduler);
            return true;
        } catch (Exception e) {
            logger.severe("TickProfiler: Failed to setup proxy: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

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

    private void collectSpigotSamples(JsonArray pluginsArray, int durationSeconds) {
        // Swap map
        ConcurrentHashMap<String, PluginSample> snapshot = currentSpigotSamples;
        currentSpigotSamples = new ConcurrentHashMap<>();

        boolean anonymize = configManager.isAnonymizePluginNames();
        long windowMillis = durationSeconds * 1000L;

        for (Map.Entry<String, PluginSample> entry : snapshot.entrySet()) {
            String pluginName = entry.getKey();
            PluginSample sample = entry.getValue();

            long totalTimeNano;
            long maxTimeNano;
            long sampleCount;
            synchronized (sample) {
                sampleCount = sample.sampleCount;
                if (sampleCount == 0) continue;
                totalTimeNano = sample.totalTimeNano;
                maxTimeNano = sample.maxTimeNano;
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
            p.addProperty("event_count", 0); // Not tracked in Spigot mode
            p.addProperty("task_count", sample.taskIds.size());

            pluginsArray.add(p);
        }
    }

    // --- Spigot Proxy ---

    private class SchedulerInvocationHandler implements InvocationHandler {
        private final Object delegate;

        public SchedulerInvocationHandler(Object delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // When profiling is off, delegate directly with no wrapping or overhead tracking
            if (!profilingEnabled || autoDisabled) {
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
            long startOverhead = System.nanoTime();
            try {
                // Intercept runTask*, schedule*, and callSyncMethod methods
                String name = method.getName();
                // Declared outside the if block so it remains accessible for BukkitTask ID tracking below.
                String trackedPluginName = null;
                if ((name.startsWith("runTask") || name.startsWith("schedule") || name.equals("callSyncMethod")) && args != null && args.length > 0) {
                    // Check args for Plugin and Runnable/Callable
                    Plugin pluginArg = null;
                    Runnable runnableArg = null;
                    Callable<?> callableArg = null;
                    int runnableIndex = -1;
                    int callableIndex = -1;

                    for (int i = 0; i < args.length; i++) {
                        if (args[i] instanceof Plugin) {
                            pluginArg = (Plugin) args[i];
                        } else if (args[i] instanceof Runnable) {
                            runnableArg = (Runnable) args[i];
                            runnableIndex = i;
                        } else if (args[i] instanceof Callable) {
                            callableArg = (Callable<?>) args[i];
                            callableIndex = i;
                        }
                    }

                    if (pluginArg != null) {
                        trackedPluginName = pluginArg.getName();
                        final String pluginName = trackedPluginName;

                        if (runnableArg != null && runnableIndex >= 0) {
                            // Wrap Runnable
                            args[runnableIndex] = new ProfiledRunnable(pluginName, runnableArg);
                        } else if (callableArg != null && callableIndex >= 0) {
                            // Wrap Callable
                            args[callableIndex] = new ProfiledCallable(pluginName, callableArg);
                        }
                    }
                }

                // Delegate
                Object result = method.invoke(delegate, args);

                // Track distinct scheduled task IDs per plugin
                if (result instanceof BukkitTask && trackedPluginName != null) {
                    BukkitTask task = (BukkitTask) result;
                    currentSpigotSamples.computeIfAbsent(trackedPluginName, k -> new PluginSample()).taskIds.add(task.getTaskId());
                }

                return result;

            } catch (InvocationTargetException e) {
                throw e.getCause();
            } finally {
                overheadNano.addAndGet(System.nanoTime() - startOverhead);
            }
        }
    }

    private class ProfiledRunnable implements Runnable {
        private final String pluginName;
        private final Runnable delegate;

        public ProfiledRunnable(String pluginName, Runnable delegate) {
            this.pluginName = pluginName;
            this.delegate = delegate;
        }

        @Override
        public void run() {
            if (!profilingEnabled || autoDisabled) {
                delegate.run();
                return;
            }
            long start = System.nanoTime();
            try {
                delegate.run();
            } finally {
                long duration = System.nanoTime() - start;
                record(pluginName, duration);
            }
        }
    }

    private class ProfiledCallable implements Callable<Object> {
        private final String pluginName;
        private final Callable<?> delegate;

        public ProfiledCallable(String pluginName, Callable<?> delegate) {
            this.pluginName = pluginName;
            this.delegate = delegate;
        }

        @Override
        public Object call() throws Exception {
            if (!profilingEnabled || autoDisabled) {
                return delegate.call();
            }
            long start = System.nanoTime();
            try {
                return delegate.call();
            } finally {
                long duration = System.nanoTime() - start;
                record(pluginName, duration);
            }
        }
    }

    private void record(String pluginName, long durationNano) {
        long startOverhead = System.nanoTime();
        try {
            PluginSample sample = currentSpigotSamples.computeIfAbsent(pluginName, k -> new PluginSample());
            sample.add(durationNano);
        } finally {
            overheadNano.addAndGet(System.nanoTime() - startOverhead);
        }
    }

    /**
     * Shuts down the profiler, restoring the original BukkitScheduler if a proxy
     * was installed. Must be called from {@code onDisable()} and any config reload
     * path to avoid leaving a stale proxy after the plugin is disabled.
     */
    public synchronized void shutdown() {
        profilingEnabled = false;
        autoDisabled = true;
        if (originalScheduler != null && savedSchedulerField != null) {
            try {
                Server server = Bukkit.getServer();
                savedSchedulerField.set(server, originalScheduler);
                logger.info("TickProfiler: Restored original scheduler");
            } catch (Exception e) {
                logger.severe("TickProfiler: Failed to restore original scheduler: " + e.getMessage());
            }
        }
    }

    private static class PluginSample {
        long totalTimeNano = 0;
        long maxTimeNano = 0;
        long sampleCount = 0;
        Set<Integer> taskIds = new CopyOnWriteArraySet<>();

        synchronized void add(long duration) {
            totalTimeNano += duration;
            if (duration > maxTimeNano) maxTimeNano = duration;
            sampleCount++;
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
        final Set<Integer> taskIds;

        PluginSampleSnapshot(long totalTimeNano, long maxTimeNano, long sampleCount, Set<Integer> taskIds) {
            this.totalTimeNano = totalTimeNano;
            this.maxTimeNano = maxTimeNano;
            this.sampleCount = sampleCount;
            this.taskIds = Collections.unmodifiableSet(new HashSet<>(taskIds));
        }
    }

    /** Returns an immutable snapshot of the current plugin samples map, for unit tests. */
    Map<String, PluginSampleSnapshot> getCurrentSamplesSnapshotForTesting() {
        Map<String, PluginSampleSnapshot> snapshot = new HashMap<>();
        for (Map.Entry<String, PluginSample> entry : currentSpigotSamples.entrySet()) {
            PluginSample sample = entry.getValue();
            long totalTimeNano;
            long maxTimeNano;
            long sampleCount;
            Set<Integer> taskIds;
            synchronized (sample) {
                totalTimeNano = sample.totalTimeNano;
                maxTimeNano = sample.maxTimeNano;
                sampleCount = sample.sampleCount;
                taskIds = new HashSet<>(sample.taskIds);
            }
            snapshot.put(entry.getKey(),
                    new PluginSampleSnapshot(totalTimeNano, maxTimeNano, sampleCount, taskIds));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /** Creates a profiled {@link Runnable} that can be used in unit tests. */
    Runnable createProfiledRunnableForTesting(String pluginName, Runnable delegate) {
        return new ProfiledRunnable(pluginName, delegate);
    }

    /** Creates a profiled {@link Callable} that can be used in unit tests. */
    Callable<Object> createProfiledCallableForTesting(String pluginName, Callable<?> delegate) {
        return new ProfiledCallable(pluginName, delegate);
    }
}
