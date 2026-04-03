import re

with open('src/main/java/gg/pivot/EventCollector.java', 'r') as f:
    content = f.read()

# I accidentally added profilingArray to sendServerStopEvent during regex replacement
content = re.sub(
    r'        serverArray\.add\(event\);\n        payload\.add\("server_events", serverArray\);\n\n        if \(profilingArray\.size\(\) > 0\) \{\n            payload\.add\("profiling_events", profilingArray\);\n        \}',
    '        serverArray.add(event);\n        payload.add("server_events", serverArray);',
    content
)

with open('src/main/java/gg/pivot/EventCollector.java', 'w') as f:
    f.write(content)
