import re

with open('src/main/resources/config.yml', 'r') as f:
    content = f.read()

# Add config keys
new_config = '''  mode: auto

  chunk_profiling:
    enabled: true
    overhead_threshold_ms: 0.5
  command_profiling:
    enabled: true
    slow_threshold_ms: 100'''

content = re.sub(
    r'  mode: auto',
    new_config,
    content
)

with open('src/main/resources/config.yml', 'w') as f:
    f.write(content)

with open('src/main/resources/plugin.yml', 'r') as f:
    content = f.read()

# Ensure /pivot command is there. It already is but check.
