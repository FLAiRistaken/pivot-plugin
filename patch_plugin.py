import re

with open('src/main/java/gg/pivot/PivotPlugin.java', 'r') as f:
    content = f.read()

# Add fields for new profilers
content = re.sub(
    r'private TickProfiler tickProfiler;',
    'private TickProfiler tickProfiler;\n    private ChunkProfiler chunkProfiler;\n    private CommandProfiler commandProfiler;',
    content
)

# Initialize profilers
init_code = '''
        // Initialize TickProfiler
        tickProfiler = new TickProfiler(this, configManager);
        eventCollector.setTickProfiler(tickProfiler);

        // Initialize Phase 3B Profilers
        chunkProfiler = new ChunkProfiler(this, eventCollector);
        commandProfiler = new CommandProfiler(this, eventCollector);
        getServer().getPluginManager().registerEvents(chunkProfiler, this);
        getServer().getPluginManager().registerEvents(commandProfiler, this);
'''
content = re.sub(
    r'// Initialize TickProfiler.*?eventCollector\.setTickProfiler\(tickProfiler\);',
    init_code,
    content,
    flags=re.DOTALL
)

# Call chunkProfiler.flushAndReset()
content = re.sub(
    r'eventCollector\.flush\(\);',
    'if (chunkProfiler != null) chunkProfiler.flushAndReset();\n                eventCollector.flush();',
    content
)

# Disable profilers
content = re.sub(
    r'if \(tickProfiler != null\) \{\n            tickProfiler\.shutdown\(\);\n        \}',
    'if (tickProfiler != null) {\n            tickProfiler.shutdown();\n        }\n        if (chunkProfiler != null) {\n            chunkProfiler.disable();\n        }\n        if (commandProfiler != null) {\n            commandProfiler.disable();\n        }',
    content
)

with open('src/main/java/gg/pivot/PivotPlugin.java', 'w') as f:
    f.write(content)

with open('src/main/java/gg/pivot/PivotCommand.java', 'r') as f:
    content = f.read()

# Update handleStatus
# Fields: [Pivot] Status: CONNECTED or DISCONNECTED
# [Pivot] Server ID: <uuid from config>
# [Pivot] Last batch sent: Xs ago (use lastSuccessfulSendAt from ApiClient)
# [Pivot] Events in queue: X player, X profiling
# [Pivot] TPS Profiler: enabled (<mode>) or disabled
# [Pivot] Chunk Profiler: enabled or disabled
# [Pivot] Command Profiler: enabled or disabled
# [Pivot] API endpoint: <endpoint from config>
status_code = '''private boolean handleStatus(CommandSender sender) {
        String apiKey = plugin.getConfig().getString("api.key", "");
        boolean configured = !apiKey.isEmpty() && !apiKey.equals("paste_your_key_here");

        long lastSent = plugin.getEventCollector().getApiClient().getLastSuccessfulSendAt();
        boolean connected = configured && (System.currentTimeMillis() - lastSent < 90000) && lastSent > 0;
        String status = connected ? "CONNECTED" : "DISCONNECTED";

        String serverId = plugin.getConfig().getString("api.server-id", "unknown");

        String timeAgo = "Never";
        if (lastSent > 0) {
            long secondsAgo = (System.currentTimeMillis() - lastSent) / 1000;
            timeAgo = formatTimeAgo(secondsAgo);
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
