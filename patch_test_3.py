import sys

with open('src/test/java/gg/pivot/ConfigSnapshotReporterTest.java', 'r') as f:
    content = f.read()

# Fix mock logger
old = '''        try {
            if (Bukkit.getServer() == null) {
                Bukkit.setServer(server);
            }
        } catch (UnsupportedOperationException e) {
            // Already set or unsupported in test environment
        }'''

new = '''        java.util.logging.Logger mockLogger = java.util.logging.Logger.getLogger("test");
        doReturn(mockLogger).when(server).getLogger();
        try {
            if (Bukkit.getServer() == null) {
                Bukkit.setServer(server);
            }
        } catch (UnsupportedOperationException e) {
            // Already set or unsupported in test environment
        }'''

content = content.replace(old, new)
content = content.replace('''        java.util.logging.Logger mockLogger = java.util.logging.Logger.getLogger("test");
        doReturn(mockLogger).when(server).getLogger();''', '', 1)

with open('src/test/java/gg/pivot/ConfigSnapshotReporterTest.java', 'w') as f:
    f.write(content)
