// src/main/java/gg/pivot/PivotCommand.java
package gg.pivot;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PivotCommand implements CommandExecutor, TabCompleter {
    private final PivotPlugin plugin;

    public PivotCommand(PivotPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // No subcommand = show help
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subcommand = args[0].toLowerCase();

        switch (subcommand) {
            case "status":
                return handleStatus(sender);

            case "reload":
                return handleReload(sender);

            case "debug":
                return handleDebug(sender, args);

            case "info":
                return handleInfo(sender);

            case "help":
                sendHelp(sender);
                return true;

            default:
                sender.sendMessage(ChatColor.RED + "Unknown subcommand: " + args[0]);
                sender.sendMessage(ChatColor.YELLOW + "Use /pivot help for available commands");
                return true;
        }
    }

    /**
     * Handle /pivot status
     */
    private boolean handleStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "========== Pivot Analytics Status ==========");

        // Configuration status
        String apiKey = plugin.getConfig().getString("api.key", "");
        boolean configured = !apiKey.isEmpty() && !apiKey.equals("paste_your_key_here");

        sender.sendMessage(ChatColor.AQUA + "Configuration:");
        sender.sendMessage("  " + (configured ? ChatColor.GREEN + "✓" : ChatColor.RED + "✗")
                + ChatColor.WHITE + " API Key: "
                + (configured ? "Configured" : ChatColor.RED + "NOT SET"));

        String endpoint = plugin.getConfig().getString("api.endpoint", "not set");
        sender.sendMessage("  " + ChatColor.WHITE + "Endpoint: " + ChatColor.GRAY + endpoint);

        // Collection status
        boolean collectionEnabled = plugin.getConfig().getBoolean("collection.enabled", true);
        sender.sendMessage(ChatColor.AQUA + "Collection:");
        sender.sendMessage("  " + (collectionEnabled ? ChatColor.GREEN + "✓" : ChatColor.YELLOW + "⚠")
                + ChatColor.WHITE + " Status: "
                + (collectionEnabled ? ChatColor.GREEN + "Enabled" : ChatColor.YELLOW + "Disabled"));

        if (collectionEnabled) {
            // Event statistics
            int playerEventCount = plugin.getEventCollector().getPlayerEventCount();
            int perfEventCount = plugin.getEventCollector().getPerformanceEventCount();

            sender.sendMessage("  " + ChatColor.WHITE + "Queued Events:");
            sender.sendMessage("    " + ChatColor.GRAY + "Player: " + ChatColor.WHITE + playerEventCount);
            sender.sendMessage("    " + ChatColor.GRAY + "Performance: " + ChatColor.WHITE + perfEventCount);

            // Last event sent
            long lastEventTime = plugin.getLastEventSentTime();
            if (lastEventTime > 0) {
                long secondsAgo = (System.currentTimeMillis() - lastEventTime) / 1000;
                String timeAgo = formatTimeAgo(secondsAgo);

                boolean isHealthy = secondsAgo < 120; // Healthy if sent within 2 minutes
                sender.sendMessage("  " + (isHealthy ? ChatColor.GREEN + "✓" : ChatColor.YELLOW + "⚠")
                        + ChatColor.WHITE + " Last Sent: " + ChatColor.GRAY + timeAgo);
            } else {
                sender.sendMessage("  " + ChatColor.YELLOW + "⚠" + ChatColor.WHITE
                        + " Last Sent: " + ChatColor.GRAY + "Never");
            }
        }

        // Server performance
        sender.sendMessage(ChatColor.AQUA + "Server Performance:");
        double tps = TPSUtil.getTPS();
        ChatColor tpsColor = tps >= 19.5 ? ChatColor.GREEN : (tps >= 15.0 ? ChatColor.YELLOW : ChatColor.RED);
        sender.sendMessage("  " + ChatColor.WHITE + "TPS: " + tpsColor + String.format("%.2f", tps)
                + ChatColor.GRAY + " (" + TPSUtil.getTPSInfo() + ")");
        sender.sendMessage("  " + ChatColor.WHITE + "Online Players: " + ChatColor.GRAY
                + plugin.getServer().getOnlinePlayers().size());

        // Privacy settings
        boolean anonymize = plugin.getConfig().getBoolean("privacy.anonymize-players", false);
        boolean trackHostnames = plugin.getConfig().getBoolean("privacy.track-hostnames", true);
        sender.sendMessage(ChatColor.AQUA + "Privacy:");
        sender.sendMessage("  " + ChatColor.WHITE + "Anonymize Players: "
                + (anonymize ? ChatColor.YELLOW + "Yes" : ChatColor.GREEN + "No"));
        sender.sendMessage("  " + ChatColor.WHITE + "Track Hostnames: "
                + (trackHostnames ? ChatColor.GREEN + "Yes" : ChatColor.YELLOW + "No"));

        // Debug status
        boolean debug = plugin.getConfig().getBoolean("debug.enabled", false);
        sender.sendMessage(ChatColor.AQUA + "Debug:");
        sender.sendMessage("  " + ChatColor.WHITE + "Mode: "
                + (debug ? ChatColor.YELLOW + "Enabled" : ChatColor.GRAY + "Disabled"));

        sender.sendMessage(ChatColor.GOLD + "==========================================");

        return true;
    }

    /**
     * Handle /pivot reload
     */
    private boolean handleReload(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Reloading Pivot Analytics configuration...");

        try {
            // Reload config
            plugin.reloadConfig();

            // Restart tasks with new intervals
            plugin.restartTasks();

            sender.sendMessage(ChatColor.GREEN + "✓ Configuration reloaded successfully!");
            sender.sendMessage(ChatColor.GRAY + "New intervals:");
            sender.sendMessage("  Batch: " + plugin.getConfig().getInt("collection.batch-interval", 60) + "s");
            sender.sendMessage("  TPS Sample: " + plugin.getConfig().getInt("collection.tps-sample-interval", 30) + "s");

            // Warn if collection disabled
            if (!plugin.getConfig().getBoolean("collection.enabled", true)) {
                sender.sendMessage(ChatColor.YELLOW + "⚠ Warning: Data collection is disabled!");
            }

        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "✗ Failed to reload configuration!");
            sender.sendMessage(ChatColor.RED + "Error: " + e.getMessage());
            plugin.getLogger().severe("Config reload failed: " + e.getMessage());
            return true;
        }

        return true;
    }

    /**
     * Handle /pivot debug [on|off|toggle]
     */
    private boolean handleDebug(CommandSender sender, String[] args) {
        boolean currentDebug = plugin.getConfig().getBoolean("debug.enabled", false);

        // No argument = show current state
        if (args.length == 1) {
            sender.sendMessage(ChatColor.AQUA + "Debug mode is currently: "
                    + (currentDebug ? ChatColor.GREEN + "ENABLED" : ChatColor.GRAY + "DISABLED"));
            sender.sendMessage(ChatColor.GRAY + "Use /pivot debug [on|off|toggle]");
            return true;
        }

        String action = args[1].toLowerCase();
        boolean newDebug;

        switch (action) {
            case "on":
            case "enable":
            case "true":
                newDebug = true;
                break;

            case "off":
            case "disable":
            case "false":
                newDebug = false;
                break;

            case "toggle":
                newDebug = !currentDebug;
                break;

            default:
                sender.sendMessage(ChatColor.RED + "Invalid option: " + args[1]);
                sender.sendMessage(ChatColor.GRAY + "Use: on, off, or toggle");
                return true;
        }

        // Update config
        plugin.getConfig().set("debug.enabled", newDebug);
        plugin.saveConfig();

        sender.sendMessage(ChatColor.GREEN + "✓ Debug mode "
                + (newDebug ? ChatColor.GREEN + "ENABLED" : ChatColor.GRAY + "DISABLED"));

        if (newDebug) {
            sender.sendMessage(ChatColor.YELLOW + "Console will now show verbose event logs");
        }

        return true;
    }

    /**
     * Handle /pivot info
     */
    private boolean handleInfo(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "========== Pivot Analytics Info ==========");
        sender.sendMessage(ChatColor.AQUA + "Plugin Information:");
        sender.sendMessage("  " + ChatColor.WHITE + "Version: " + ChatColor.GRAY
                + plugin.getDescription().getVersion());
        sender.sendMessage("  " + ChatColor.WHITE + "Author: " + ChatColor.GRAY
                + plugin.getDescription().getAuthors().get(0));
        sender.sendMessage("  " + ChatColor.WHITE + "Website: " + ChatColor.GRAY
                + plugin.getDescription().getWebsite());

        sender.sendMessage(ChatColor.AQUA + "Technical Details:");
        sender.sendMessage("  " + ChatColor.WHITE + "TPS Detection: " + ChatColor.GRAY
                + TPSUtil.getTPSInfo());
        sender.sendMessage("  " + ChatColor.WHITE + "Java Version: " + ChatColor.GRAY
                + System.getProperty("java.version"));
        sender.sendMessage("  " + ChatColor.WHITE + "Server Version: " + ChatColor.GRAY
                + plugin.getServer().getVersion());

        sender.sendMessage(ChatColor.AQUA + "Resources:");
        sender.sendMessage("  " + ChatColor.GRAY + "Dashboard: " + ChatColor.AQUA
                + "https://app.pivotmc.dev");
        sender.sendMessage("  " + ChatColor.GRAY + "Documentation: " + ChatColor.AQUA
                + "https://docs.pivotmc.dev");
        sender.sendMessage("  " + ChatColor.GRAY + "Support: " + ChatColor.AQUA
                + "https://discord.gg/pivot");

        sender.sendMessage(ChatColor.GOLD + "==========================================");

        return true;
    }

    /**
     * Show help message
     */
    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "========== Pivot Analytics Commands ==========");
        sender.sendMessage(ChatColor.AQUA + "/pivot status" + ChatColor.GRAY
                + " - Show connection and configuration status");
        sender.sendMessage(ChatColor.AQUA + "/pivot reload" + ChatColor.GRAY
                + " - Reload configuration from file");
        sender.sendMessage(ChatColor.AQUA + "/pivot debug [on|off|toggle]" + ChatColor.GRAY
                + " - Toggle debug logging");
        sender.sendMessage(ChatColor.AQUA + "/pivot info" + ChatColor.GRAY
                + " - Show plugin version and technical info");
        sender.sendMessage(ChatColor.AQUA + "/pivot help" + ChatColor.GRAY
                + " - Show this help message");
        sender.sendMessage(ChatColor.GOLD + "============================================");
    }

    /**
     * Format seconds into human-readable time
     */
    private String formatTimeAgo(long seconds) {
        if (seconds < 60) {
            return seconds + " seconds ago";
        } else if (seconds < 3600) {
            long minutes = seconds / 60;
            return minutes + " minute" + (minutes > 1 ? "s" : "") + " ago";
        } else {
            long hours = seconds / 3600;
            return hours + " hour" + (hours > 1 ? "s" : "") + " ago";
        }
    }

    /**
     * Tab completion for /pivot command
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            // Main subcommands
            List<String> subcommands = Arrays.asList("status", "reload", "debug", "info", "help");
            String input = args[0].toLowerCase();

            for (String subcommand : subcommands) {
                if (subcommand.startsWith(input)) {
                    completions.add(subcommand);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            // Debug options
            List<String> options = Arrays.asList("on", "off", "toggle");
            String input = args[1].toLowerCase();

            for (String option : options) {
                if (option.startsWith(input)) {
                    completions.add(option);
                }
            }
        }

        return completions;
    }
}
