import sys

with open('src/test/java/gg/pivot/ConfigSnapshotReporterTest.java', 'r') as f:
    lines = f.readlines()

new_lines = []
skip = False
for line in lines:
    if 'java.util.logging.Logger mockLogger = mock(java.util.logging.Logger.class);' in line:
        if skip:
            continue
        skip = True
    new_lines.append(line)

with open('src/test/java/gg/pivot/ConfigSnapshotReporterTest.java', 'w') as f:
    f.writelines(new_lines)
