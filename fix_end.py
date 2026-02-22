import sys

with open('src/main/java/gg/pivot/TickProfiler.java', 'r') as f:
    lines = f.readlines()

# Remove last 2 lines if they are just braces
if lines[-1].strip() == '}':
    lines.pop()
if lines[-1].strip() == '}':
    lines.pop()
if lines[-1].strip() == '':
    lines.pop()

# Check if WrappedListenerInfo is already there (it shouldn't be based on grep)
# Add it
lines.append('\n    private static class WrappedListenerInfo {\n')
lines.append('        final HandlerList list;\n')
lines.append('        final RegisteredListener original;\n')
lines.append('        final RegisteredListener wrapped;\n\n')
lines.append('        WrappedListenerInfo(HandlerList list, RegisteredListener original, RegisteredListener wrapped) {\n')
lines.append('            this.list = list;\n')
lines.append('            this.original = original;\n')
lines.append('            this.wrapped = wrapped;\n')
lines.append('        }\n')
lines.append('    }\n')
lines.append('}\n')

with open('src/main/java/gg/pivot/TickProfiler.java', 'w') as f:
    f.writelines(lines)
