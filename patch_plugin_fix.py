import re

with open('src/main/java/gg/pivot/PivotCommand.java', 'r') as f:
    content = f.read()

# Fix the formatTimeAgo logic, the original issue says:
# [Pivot] Last batch sent: Xs ago (use lastSuccessfulSendAt from ApiClient)
# I need to ensure my code handles formatTimeAgo correctly. If the secondsAgo < 0 (can happen if clock skew or right at start) just 0.
# The code is already doing it inside handleStatus, but I need to ensure it's correct.

status_code = '''private boolean handleStatus(CommandSender sender) {
        String apiKey = plugin.getConfig().getString("api.key", "");
        boolean configured = !apiKey.isEmpty() && !apiKey.equals("paste_your_key_here");

        long lastSent = plugin.getEventCollector().getApiClient().getLastSuccessfulSendAt();
        boolean connected = configured && lastSent > 0 && (System.currentTimeMillis() - lastSent <= 90000);
        String status = connected ? "CONNECTED" : "DISCONNECTED";

        String serverId = plugin.getConfig().getString("api.server-id", "");
        if (serverId == null || serverId.isEmpty()) serverId = "unknown";

        String timeAgo = "Never";
        if (lastSent > 0) {
            long secondsAgo = (System.currentTimeMillis() - lastSent) / 1000;
            if (secondsAgo < 0) secondsAgo = 0;
            timeAgo = secondsAgo + "s ago";
        }

        int playerQueue = plugin.getEventCollector().getPlayerEventCount();
        int profilingQueue = plugin.getEventCollector().getProfilingEventCount();

        String tpsMode = plugin.getConfig().getString("profiling.mode", "auto");
        boolean tpsEnabled = plugin.getConfig().getBoolean("profiling.enabled", true);
        String tpsStatus = tpsEnabled ? "enabled (" + tpsMode + ")" : "disabled";

        boolean chunkEnabled = plugin.getConfig().getBoolean("profiling.chunk_profiling.enabled", true);
        boolean commandEnabled = plugin.getConfig().getBoolean("profiling.command_profiling.enabled", true);

        String endpoint = plugin.getConfig().getString("api.endpoint", "not set");

        sender.sendMessage("[Pivot] Status: " + status);
        sender.sendMessage("[Pivot] Server ID: " + serverId);
        sender.sendMessage("[Pivot] Last batch sent: " + timeAgo);
        sender.sendMessage("[Pivot] Events in queue: " + playerQueue + " player, " + profilingQueue + " profiling");
        sender.sendMessage("[Pivot] TPS Profiler: " + tpsStatus);
        sender.sendMessage("[Pivot] Chunk Profiler: " + (chunkEnabled ? "enabled" : "disabled"));
        sender.sendMessage("[Pivot] Command Profiler: " + (commandEnabled ? "enabled" : "disabled"));
        sender.sendMessage("[Pivot] API endpoint: " + endpoint);

        return true;
    }'''

content = re.sub(
    r'private boolean handleStatus\(CommandSender sender\) \{.*?return true;\n    \}',
    status_code,
    content,
    flags=re.DOTALL
)

with open('src/main/java/gg/pivot/PivotCommand.java', 'w') as f:
    f.write(content)
