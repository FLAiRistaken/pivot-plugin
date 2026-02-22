import sys
import re

with open('src/main/java/gg/pivot/TickProfiler.java', 'r') as f:
    content = f.read()

# Helper method inside ProfiledRegisteredListener
helper_pattern = r'private static EventExecutor getExecutor\(RegisteredListener listener\) \{[\s\S]*?\}\s*(?=public ProfiledRegisteredListener)'

match = re.search(helper_pattern, content)
if match:
    helper_code = match.group(0)
    # Remove it from inside ProfiledRegisteredListener
    content = content.replace(helper_code, '')

    # Add it before ProfiledRegisteredListener class definition
    # Find 'private class ProfiledRegisteredListener'
    content = content.replace('private class ProfiledRegisteredListener', helper_code + '\n    private class ProfiledRegisteredListener')

else:
    print('Helper method not found')

with open('src/main/java/gg/pivot/TickProfiler.java', 'w') as f:
    f.write(content)
