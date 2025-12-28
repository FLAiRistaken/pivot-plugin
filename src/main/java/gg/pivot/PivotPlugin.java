package gg.pivot;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.logging.Logger;

public class PivotPlugin extends JavaPlugin {
    private Logger logger;
    private EventCollector eventCollector;

    @Override
    public void onEnable() {
        logger = getLogger();
        logger.info("=================================");
        logger.info(" Pivot Analytics Starting...    ");
        logger.info("=================================");

        saveDefaultConfig();

        String apiEndpoint = getConfig().getString("pivot.api-endpoint", "not set");
        String apiKey = getConfig().getString("pivot.api-key", "not set");

        logger.info("API Endpoint: " + apiEndpoint);
        logger.info("API Key: "
            + (apiKey.equals("not set") ? "NOT SET" : "***" + apiKey.substring(Math.max(0, apiKey.length() - 4))));

        // ADDED: Initialize TPS detection
        TPSUtil.initialize(this, logger);
        logger.info("TPS Detection: " + TPSUtil.getTPSInfo());

        // Initialize event collector
        eventCollector = new EventCollector(this);

        // Register event listener
        getServer().getPluginManager().registerEvents(new EventListener(this), this);

        // Start performance monitoring (every 30 seconds)
        new BukkitRunnable() {
            @Override
            public void run() {
                int playerCount = getServer().getOnlinePlayers().size();
                double tps = TPSUtil.getTPS(); // CHANGED: Real TPS
                eventCollector.addPerformanceEvent(tps, playerCount);
                logger.info(String.format("Collected stats - Players: %d, TPS: %.2f", playerCount, tps));
            }
        }.runTaskTimerAsynchronously(this, 0L, 600L); // 600 ticks = 30 seconds

        // Flush events every 60 seconds
        new BukkitRunnable() {
            @Override
            public void run() {
                eventCollector.flush();
            }
        }.runTaskTimerAsynchronously(this, 1200L, 1200L); // 1200 ticks = 60 seconds

        logger.info("Pivot Analytics enabled successfully!");
    }

    @Override
    public void onDisable() {
        // Flush any remaining events
        if (eventCollector != null) {
            eventCollector.flush();
        }
        logger.info("Pivot Analytics disabled. Goodbye!");
    }

    public EventCollector getEventCollector() {
        return eventCollector;
    }
}
