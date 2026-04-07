import sys

with open('src/main/java/gg/pivot/ChunkProfiler.java', 'r') as f:
    content = f.read()

# Add autoDisabled variable
content = content.replace('private volatile boolean enabled = true;',
                          'private volatile boolean enabled = true;\n    private volatile boolean autoDisabled = false;')

# Add isAutoDisabled method
content = content.replace('public boolean isEnabled() {',
                          'public boolean isAutoDisabled() {\n        return autoDisabled;\n    }\n\n    public boolean isEnabled() {')

# Set autoDisabled = true in checkOverhead
content = content.replace('this.enabled = false;\n            }\n        }\n    }',
                          'this.enabled = false;\n                this.autoDisabled = true;\n            }\n        }\n    }')

# Set autoDisabled = false in reload
content = content.replace('boolean chunkEnabled = plugin.getConfig().getBoolean("profiling.chunk_profiling.enabled", false);\n        this.enabled = globalEnabled && chunkEnabled;\n    }',
                          'boolean chunkEnabled = plugin.getConfig().getBoolean("profiling.chunk_profiling.enabled", false);\n        this.enabled = globalEnabled && chunkEnabled;\n        this.autoDisabled = false;\n    }')

with open('src/main/java/gg/pivot/ChunkProfiler.java', 'w') as f:
    f.write(content)
