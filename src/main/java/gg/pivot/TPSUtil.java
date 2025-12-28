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

public class TPSUtil {
    private static Method paperGetTPSMethod = null;
    private static Field spigotRecentTPSField = null;
    private static boolean isPaper = false;
    private static boolean isSpigot = false;
    private static boolean initialized = false;

    // Manual TPS calculation (works on ALL Bukkit versions)
    private static final Deque<Long> tickTimes = new ArrayDeque<>();
    private static long lastTickTime = 0;
    private static final int SAMPLE_SIZE = 100; // Average over 100 ticks (~5 seconds)
    private static double calculatedTPS = 20.0;

    /**
     * Initialize TPS detection - call once on plugin enable
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
            isSpigot = true;
            logger.info("TPS Method: Spigot reflection (good)");
            return;
        } catch (Exception ignored) {}

        // Try newer Spigot (1.17+)
        try {
            Class<?> minecraftServerClass = Class.forName("net.minecraft.server.MinecraftServer");
            spigotRecentTPSField = minecraftServerClass.getDeclaredField("recentTps");
            spigotRecentTPSField.setAccessible(true);
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
     * Manual tick measurement (called every tick)
     */
    private static void measureTick() {
        long currentTime = System.nanoTime();

        if (lastTickTime > 0) {
            long tickDuration = currentTime - lastTickTime;

            synchronized (tickTimes) {
                tickTimes.addLast(tickDuration);

                // Keep only last SAMPLE_SIZE measurements
                if (tickTimes.size() > SAMPLE_SIZE) {
                    tickTimes.removeFirst();
                }

                // Calculate TPS from average tick duration
                if (tickTimes.size() >= 20) { // Wait for at least 20 samples
                    long sum = 0;
                    for (long duration : tickTimes) {
                        sum += duration;
                    }
                    long avgTickNanos = sum / tickTimes.size();

                    // Convert to TPS (1 second = 1,000,000,000 nanoseconds)
                    // Target: 50ms per tick = 20 TPS
                    double avgTickMillis = avgTickNanos / 1_000_000.0;
                    calculatedTPS = Math.min(20.0, 1000.0 / avgTickMillis);
                }
            }
        }

        lastTickTime = currentTime;
    }

    /**
     * Get current server TPS
     * @return TPS (1-minute average) or calculated TPS
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

        // Spigot reflection method
        if (isSpigot && spigotRecentTPSField != null) {
            try {
                String version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
                Class<?> minecraftServerClass;

                try {
                    minecraftServerClass = Class.forName("net.minecraft.server." + version + ".MinecraftServer");
                } catch (ClassNotFoundException e) {
                    minecraftServerClass = Class.forName("net.minecraft.server.MinecraftServer");
                }

                Method getServerMethod = minecraftServerClass.getDeclaredMethod("getServer");
                Object minecraftServer = getServerMethod.invoke(null);
                double[] recentTps = (double[]) spigotRecentTPSField.get(minecraftServer);
                return Math.min(20.0, recentTps[0]);
            } catch (Exception e) {
                // Fall through
            }
        }

        // Manual calculation (universal fallback)
        synchronized (tickTimes) {
            return calculatedTPS;
        }
    }

    /**
     * Get detailed TPS info for debugging
     */
    public static String getTPSInfo() {
        if (isPaper) {
            return "Paper (native API)";
        } else if (isSpigot) {
            return "Spigot (reflection)";
        } else {
            synchronized (tickTimes) {
                return String.format("Manual calculation (%d samples)", tickTimes.size());
            }
        }
    }
}
