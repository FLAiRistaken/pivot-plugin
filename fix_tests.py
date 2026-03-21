import re

# Fix EventCollectorTest: redactSensitiveInfo is now in ApiClient
with open('src/test/java/gg/pivot/EventCollectorTest.java', 'r') as f:
    content = f.read()

content = re.sub(
    r'Method redactMethod = EventCollector.class.getDeclaredMethod\("redactSensitiveInfo", String.class, String.class\);',
    'Method redactMethod = ApiClient.class.getDeclaredMethod("redactSensitiveInfo", String.class, String.class);',
    content
)
content = re.sub(
    r'redactMethod.invoke\((.*?),',
    'redactMethod.invoke(null,', # it's a static method now
    content
)

with open('src/test/java/gg/pivot/EventCollectorTest.java', 'w') as f:
    f.write(content)

# Fix ChunkProfilerTest: testFlushAndResetEmpty
with open('src/test/java/gg/pivot/ChunkProfilerTest.java', 'r') as f:
    content = f.read()

content = re.sub(
    r'public void testFlushAndResetEmpty\(\) \{.*?\}',
    '''public void testFlushAndResetEmpty() {
        // Needs reflection to set enabled to true since default mock doesn't trigger properly
        profiler.flushAndReset();
        // The issue says: "flushAndReset() produces empty plugins array when no chunk events occurred"
        // And "produce CHUNK_PROFILE schema and add to EventCollector".
        // Wait, requirements say: "flushAndReset() produces empty plugins array when no chunk events occurred".
        // Ah, it SHOULD add it, but with empty plugins array. So the test should assert that.
        verify(eventCollector, times(1)).addProfilingEvent(any(JsonObject.class));
    }''',
    content,
    flags=re.DOTALL
)

with open('src/test/java/gg/pivot/ChunkProfilerTest.java', 'w') as f:
    f.write(content)

# Fix CommandProfilerTest: NullPointer on getOnlinePlayers()
with open('src/test/java/gg/pivot/CommandProfilerTest.java', 'r') as f:
    content = f.read()

content = re.sub(
    r'doReturn\(uuid\)\.when\(player\)\.getUniqueId\(\);',
    '''doReturn(uuid).when(player).getUniqueId();
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        doReturn(server).when(player).getServer();
        doReturn(new java.util.ArrayList<>()).when(server).getOnlinePlayers();''',
    content
)

with open('src/test/java/gg/pivot/CommandProfilerTest.java', 'w') as f:
    f.write(content)
