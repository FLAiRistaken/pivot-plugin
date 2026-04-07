import sys

with open('src/test/java/gg/pivot/ConfigSnapshotReporterTest.java', 'r') as f:
    content = f.read()

# Add mock logger to server
content = content.replace('doReturn("Paper 1.20.4").when(server).getVersion();',
                          'doReturn("Paper 1.20.4").when(server).getVersion();\n        java.util.logging.Logger mockLogger = mock(java.util.logging.Logger.class);\n        doReturn(mockLogger).when(server).getLogger();')

with open('src/test/java/gg/pivot/ConfigSnapshotReporterTest.java', 'w') as f:
    f.write(content)
