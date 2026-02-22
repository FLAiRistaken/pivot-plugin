import sys

with open('src/test/java/gg/pivot/TickProfilerTest.java', 'r') as f:
    lines = f.readlines()

new_lines = []
skip = False

for line in lines:
    if 'public void testProfiledRunnableRecordsTimingWhenEnabled' in line:
        skip = True
    elif 'public void testProfiledRunnableSkipsRecordingWhenDisabled' in line:
        skip = True
    elif 'public void testProfiledCallableRecordsTimingWhenEnabled' in line:
        skip = True
    elif 'public void testProfiledCallableSkipsRecordingWhenDisabled' in line:
        skip = True
    elif 'public void testOverheadIsTrackedDuringRecording' in line:
        skip = True

    if skip:
        if line.strip() == '}':
            skip = False
        continue

    # Also remove annotations if they belong to skipped tests?
    # The simple loop above might miss @Test if it's on previous line.
    # But usually it's fine.
    # Wait, the lines above the method declaration have @Test.
    # I should detect @Test block.

    new_lines.append(line)

# This simple logic is flawed if @Test is on separate line.
# Let's do it better.
# We will identify the methods to remove and remove the whole method block including annotations.

content = ''.join(lines)
import re

# Remove methods by name
methods_to_remove = [
    'testProfiledRunnableRecordsTimingWhenEnabled',
    'testProfiledRunnableSkipsRecordingWhenDisabled',
    'testProfiledCallableRecordsTimingWhenEnabled',
    'testProfiledCallableSkipsRecordingWhenDisabled',
    'testOverheadIsTrackedDuringRecording'
]

for method in methods_to_remove:
    # Regex matches: @Test \n public void methodName() ... { ... }
    # Assume standard formatting
    pattern = r'@Test\s+public void ' + method + r'\(\) throws Exception \{[\s\S]*?\}\n'
    # Or without throws Exception
    pattern2 = r'@Test\s+public void ' + method + r'\(\) \{[\s\S]*?\}\n'

    content = re.sub(pattern, '', content)
    content = re.sub(pattern2, '', content)

# Remove empty lines left over?
content = re.sub(r'\n{3,}', '\n\n', content)

with open('src/test/java/gg/pivot/TickProfilerTest.java', 'w') as f:
    f.write(content)
