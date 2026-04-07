import sys

with open('src/test/java/gg/pivot/ConfigSnapshotReporterTest.java', 'r') as f:
    content = f.read()

# Mock Logger properly
old = '''        try {
            if (Bukkit.getServer() == null) {
                Bukkit.setServer(server);
            }
        } catch (UnsupportedOperationException e) {
            // Already set or unsupported in test environment
        }'''

new = '''        java.util.logging.Logger mockLogger = mock(java.util.logging.Logger.class);
        doReturn(mockLogger).when(server).getLogger();
        try {
            if (Bukkit.getServer() == null) {
                Bukkit.setServer(server);
            }
        } catch (UnsupportedOperationException e) {
            // Already set or unsupported in test environment
        }'''

content = content.replace(old, new)

with open('src/test/java/gg/pivot/ConfigSnapshotReporterTest.java', 'w') as f:
    f.write(content)
