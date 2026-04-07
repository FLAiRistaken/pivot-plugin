import sys

with open('src/test/java/gg/pivot/ConfigSnapshotReporterTest.java', 'r') as f:
    lines = f.readlines()

new_lines = []
skip = 0
for line in lines:
    if 'java.util.logging.Logger mockLogger = mock(java.util.logging.Logger.class);' in line:
        skip += 1
        if skip > 1:
            continue
    if 'doReturn(mockLogger).when(server).getLogger();' in line:
        if skip > 1:
            continue
    new_lines.append(line)

with open('src/test/java/gg/pivot/ConfigSnapshotReporterTest.java', 'w') as f:
    f.writelines(new_lines)
