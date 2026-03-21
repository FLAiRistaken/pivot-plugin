import re

with open('src/test/java/gg/pivot/CommandProfilerTest.java', 'r') as f:
    content = f.read()

content = re.sub(
    r'public void setup\(\) \{',
    '''public void setup() throws Exception {
        // Initialize TPSUtil via reflection
        java.lang.reflect.Field initField = TPSUtil.class.getDeclaredField("initialized");
        initField.setAccessible(true);
        initField.set(null, true);

        java.lang.reflect.Field tpsField = TPSUtil.class.getDeclaredField("currentTps");
        tpsField.setAccessible(true);
        tpsField.set(null, 20.0);
''',
    content
)

with open('src/test/java/gg/pivot/CommandProfilerTest.java', 'w') as f:
    f.write(content)
