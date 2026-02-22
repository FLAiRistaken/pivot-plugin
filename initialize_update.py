import sys

new_method = """    private void initialize() {
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
            this.profilingEnabled = true;
            logger.info("TickProfiler initialised in paper_timings mode");
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
    }"""

with open('src/main/java/gg/pivot/TickProfiler.java', 'r') as f:
    content = f.read()

start_marker = 'private void initialize() {'
# Find the closing brace of initialize method. It ends before 'private boolean setupSpigotProxy() {'
end_marker = 'private boolean setupSpigotProxy() {'

start_pos = content.find(start_marker)
end_pos = content.find(end_marker)

if start_pos != -1 and end_pos != -1:
    # We replace from start_pos up to end_pos, excluding end_pos
    # But we need to be careful with newlines.
    # The new_method string includes the closing brace.
    # The end_marker starts the next method.
    # The original content has newlines between methods.

    # Let's adjust end_pos to include the closing brace of initialize() but keep spacing.
    # A safer way is to find the closing brace matching the opening brace.

    # Alternative: use regex or just assume the structure based on previous cat.
    # The structure is standard.

    # Let's just use the end_marker as the boundary.
    # But we need to make sure we don't cut off anything.
    # The end_marker is the START of the next method.
    # So everything before it is part of initialize (and whitespace).

    new_content = content[:start_pos] + new_method + '\n\n    ' + content[end_pos:]

    with open('src/main/java/gg/pivot/TickProfiler.java', 'w') as f:
        f.write(new_content)
else:
    print('Could not find initialize method block')
