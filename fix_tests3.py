import re

with open('src/test/java/gg/pivot/CommandProfilerTest.java', 'r') as f:
    content = f.read()

content = re.sub(
    r'java\.lang\.reflect\.Field tpsField = TPSUtil\.class\.getDeclaredField\("currentTps"\);\n        tpsField\.setAccessible\(true\);\n        tpsField\.set\(null, 20\.0\);',
    '''// Mock TPSUtil initialization
        java.lang.reflect.Field isSpigotField = TPSUtil.class.getDeclaredField("isSpigot");
        isSpigotField.setAccessible(true);
        isSpigotField.set(null, false);

        java.lang.reflect.Field isPaperField = TPSUtil.class.getDeclaredField("isPaper");
        isPaperField.setAccessible(true);
        isPaperField.set(null, false);
''',
    content
)

with open('src/test/java/gg/pivot/CommandProfilerTest.java', 'w') as f:
    f.write(content)
