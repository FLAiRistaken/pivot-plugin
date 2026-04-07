import sys

with open('src/main/java/gg/pivot/PivotPlugin.java', 'r') as f:
    content = f.read()

# Add configSnapshotReporter to fields
content = content.replace('private CommandProfiler commandProfiler;',
                          'private CommandProfiler commandProfiler;\n    private ConfigSnapshotReporter configSnapshotReporter;')

# Initialize configSnapshotReporter
content = content.replace('getServer().getPluginManager().registerEvents(commandProfiler, this);',
                          'getServer().getPluginManager().registerEvents(commandProfiler, this);\n\n        this.configSnapshotReporter = new ConfigSnapshotReporter(this, eventCollector, tickProfiler, chunkProfiler);')

# Modify flush task call order
old_flush_task = '''        // Start batch flush task
        flushTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (chunkProfiler != null) chunkProfiler.flushAndReset();
                eventCollector.flush();
                lastEventSentTime = System.currentTimeMillis();
            }
        }.runTaskTimerAsynchronously(this, batchIntervalTicks, batchIntervalTicks);'''

new_flush_task = '''        // Start batch flush task
        flushTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (configSnapshotReporter != null) configSnapshotReporter.maybeEmitSnapshot();
                if (chunkProfiler != null) chunkProfiler.flushAndReset();
                eventCollector.flush();
                lastEventSentTime = System.currentTimeMillis();
            }
        }.runTaskTimerAsynchronously(this, batchIntervalTicks, batchIntervalTicks);'''

content = content.replace(old_flush_task, new_flush_task)

with open('src/main/java/gg/pivot/PivotPlugin.java', 'w') as f:
    f.write(content)
