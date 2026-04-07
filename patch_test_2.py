import sys

with open('src/test/java/gg/pivot/ConfigSnapshotReporterTest.java', 'r') as f:
    content = f.read()

# Fix mock logger
old = '''        doReturn("Paper 1.20.4").when(server).getVersion();
        java.util.logging.Logger mockLogger = mock(java.util.logging.Logger.class);
        doReturn(mockLogger).when(server).getLogger();'''

new = '''        doReturn("Paper 1.20.4").when(server).getVersion();
        java.util.logging.Logger mockLogger = java.util.logging.Logger.getLogger("test");
        doReturn(mockLogger).when(server).getLogger();'''

content = content.replace(old, new)

with open('src/test/java/gg/pivot/ConfigSnapshotReporterTest.java', 'w') as f:
    f.write(content)
