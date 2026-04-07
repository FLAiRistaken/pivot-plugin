import sys

with open('src/main/java/gg/pivot/ChunkProfiler.java', 'r') as f:
    lines = f.readlines()

new_lines = []
skip_next = False
for i, line in enumerate(lines):
    if 'private volatile boolean autoDisabled = false;' in line:
        if skip_next:
            continue
        skip_next = True
    new_lines.append(line)

with open('src/main/java/gg/pivot/ChunkProfiler.java', 'w') as f:
    f.writelines(new_lines)
