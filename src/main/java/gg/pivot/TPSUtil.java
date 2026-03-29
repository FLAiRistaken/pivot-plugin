package gg.pivot;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Logger;

/**
 * Utility class for cross-version Server TPS (Ticks Per Second) monitoring.
 * <p>
 * This class attempts to retrieve TPS using the most accurate method available:
 * <ol>
 *   <li>Paper API ({@code Server.getTPS()}) - Most accurate</li>
 *   <li>Spigot Reflection ({@code MinecraftServer.recentTps}) - Access internal server fields</li>
 *   <li>Manual Calculation - Fallback that measures tick duration (works on all versions)</li>
 * </ol>
 * </p>
 * <p>
 * <b>Bolt Optimizations:</b>
 * <ul>
 *   <li>Caches {@code MinecraftServer} instance to avoid repeated reflection lookups.</li>
 *   <li>Calculates TPS lazily on demand (in {@code getTPS()}) rather than every tick to minimize main thread impact.</li>
 * </ul>
 * </p>
 */
public class TPSUtil {
    private static Method paperGetTPSMethod = null;
    private static Field spigotRecentTPSField = null;
    private static Object spigotServerInstance = null; // ⚡ Bolt Optimization: Cache server instance
    private static boolean isPaper = false;
    private static boolean isSpigot = false;
    private static boolean initialized = false;

    // Manual TPS calculation (works on ALL Bukkit versions)
    // ⚡ Bolt Optimization: Use primitive array for circular buffer to avoid Long autoboxing
    private static final int SAMPLE_SIZE = 100; // Average over 100 ticks (~5 seconds)
    private static final long[] tickTimes = new long[SAMPLE_SIZE];
    private static int tickIndex = 0;
    private static int tickCount = 0;
    private static long lastTickTime = 0;
    private static double calculatedTPS = 20.0;

    /**
     * Initialize TPS detection - call once on plugin enable.
     * <p>
     * Detects available TPS methods (Paper, Spigot, or Manual) and initializes
     * cached fields to optimize performance.
     * </p>
     *
     * @param plugin The plugin instance.
     * @param logger The plugin logger.
     */
    public static void initialize(Plugin plugin, Logger logger) {
        if (initialized) return;
        initialized = true;

        // Try Paper API first
        try {
            paperGetTPSMethod = Server.class.getDeclaredMethod("getTPS");
            isPaper = true;
            logger.info("TPS Method: Paper native API (best)");
            return;
        } catch (NoSuchMethodException ignored) {}

        // Try Spigot reflection (1.12+)
        try {
            String version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
            Class<?> minecraftServerClass = Class.forName("net.minecraft.server." + version + ".MinecraftServer");
            spigotRecentTPSField = minecraftServerClass.getDeclaredField("recentTps");
            spigotRecentTPSField.setAccessible(true);

            // ⚡ Bolt Optimization: Cache server instance to avoid looking it up every tick
            Method getServerMethod = minecraftServerClass.getDeclaredMethod("getServer");
            spigotServerInstance = getServerMethod.invoke(null);

            isSpigot = true;
            logger.info("TPS Method: Spigot reflection (good)");
            return;
        } catch (Exception ignored) {}

        // Try newer Spigot (1.17+)
        try {
            Class<?> minecraftServerClass = Class.forName("net.minecraft.server.MinecraftServer");
            spigotRecentTPSField = minecraftServerClass.getDeclaredField("recentTps");
            spigotRecentTPSField.setAccessible(true);

            // ⚡ Bolt Optimization: Cache server instance to avoid looking it up every tick
            Method getServerMethod = minecraftServerClass.getDeclaredMethod("getServer");
            spigotServerInstance = getServerMethod.invoke(null);

            isSpigot = true;
            logger.info("TPS Method: Spigot reflection 1.17+ (good)");
            return;
        } catch (Exception ignored) {}

        // Fallback: Manual calculation (works on ALL versions including old Bukkit/Forge)
        logger.info("TPS Method: Manual tick measurement (universal fallback)");
        logger.info("This method works on ALL Bukkit versions including 1.7.10+ modpacks");

        // Start tick measurement task
        new BukkitRunnable() {
            @Override
            public void run() {
                measureTick();
            }
        }.runTaskTimer(plugin, 0L, 1L); // Run every tick
    }

    /**
     * Manual tick measurement (called every tick).
     * <p>
     * Records the duration of the last tick into a rolling buffer.
     * Used only when native TPS methods are unavailable.
     * </p>
     */
    private static void measureTick() {
        long currentTime = System.nanoTime();

        if (lastTickTime > 0) {
            long tickDuration = currentTime - lastTickTime;

            synchronized (tickTimes) {
                tickTimes[tickIndex] = tickDuration;
                tickIndex = (tickIndex + 1) % SAMPLE_SIZE;
                if (tickCount < SAMPLE_SIZE) {
                    tickCount++;
                }

                // ⚡ Bolt Optimization: Don't calculate TPS every tick on main thread
                // Calculation moved to getTPS() to be performed on demand (async)
            }
        }

        lastTickTime = currentTime;
    }

    /**
     * Get current server TPS.
     * <p>
     * Returns the 1-minute average TPS from the most accurate source available.
     * Tries the Paper API first (cleanest), falls back to Spigot's internal
     * NMS Reflection, and finally uses manual calculation measuring tick duration.
     * </p>
     *
     * @return TPS (capped at 20.0).
     * @throws IllegalStateException If called before initialization.
     */
    public static double getTPS() {
        if (!initialized) {
            throw new IllegalStateException("TPSUtil not initialized!");
        }

        // Paper method (cleanest)
        if (isPaper && paperGetTPSMethod != null) {
            try {
                double[] tpsArray = (double[]) paperGetTPSMethod.invoke(Bukkit.getServer());
                return Math.min(20.0, tpsArray[0]);
            } catch (Exception e) {
                // Fall through
            }
        }

        // ⚡ Bolt Optimization: Use cached server instance
        if (isSpigot && spigotRecentTPSField != null && spigotServerInstance != null) {
            try {
                double[] recentTps = (double[]) spigotRecentTPSField.get(spigotServerInstance);
                return Math.min(20.0, recentTps[0]);
            } catch (Exception e) {
                // Fall through
            }
        }

        // Manual calculation (universal fallback)
        /*
         * TPS Calculation Logic:
         * 1. A BukkitRunnable (measureTick) runs every tick and records the nanosecond duration between ticks
         *    into a rolling buffer (tickTimes) up to a maximum of SAMPLE_SIZE (100 ticks / ~5 seconds).
         * 2. When TPS is requested (on demand), it sums the recorded durations and calculates the average
         *    nanoseconds per tick.
         * 3. The average is converted to milliseconds, and TPS is derived by dividing 1000ms by the average.
         * 4. The result is capped at 20.0 TPS (the maximum possible on Minecraft).
         */
        synchronized (tickTimes) {
            // ⚡ Bolt Optimization: Calculate on demand instead of every tick
            if (tickCount < 20) {
                return 20.0;
            }
            long sum = 0;
            for (int i = 0; i < tickCount; i++) {
                sum += tickTimes[i];
            }
            long avgTickNanos = sum / tickCount;

            // Convert to TPS (1 second = 1,000,000,000 nanoseconds)
            // Target: 50ms per tick = 20 TPS
            double avgTickMillis = avgTickNanos / 1_000_000.0;
            return Math.min(20.0, 1000.0 / avgTickMillis);
        }
    }

    /**
     * Get detailed TPS info for debugging.
     *
     * @return A string describing the active TPS detection method (e.g., "Paper (native API)").
     */
    public static String getTPSInfo() {
        if (isPaper) {
            return "Paper (native API)";
        } else if (isSpigot) {
            return "Spigot (reflection)";
        } else {
            synchronized (tickTimes) {
                return String.format("Manual calculation (%d samples)", tickCount);
            }
        }
    }
}
