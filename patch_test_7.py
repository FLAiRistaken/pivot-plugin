import sys

with open('src/test/java/gg/pivot/ConfigSnapshotReporterTest.java', 'r') as f:
    lines = f.readlines()

new_lines = []
for i, line in enumerate(lines):
    if i in (51, 52): # lines 52 and 53 (0-indexed)
        continue
    new_lines.append(line)

with open('src/test/java/gg/pivot/ConfigSnapshotReporterTest.java', 'w') as f:
    f.writelines(new_lines)
