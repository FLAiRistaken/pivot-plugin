import sys

with open('src/main/java/gg/pivot/TickProfiler.java', 'r') as f:
    lines = f.readlines()

new_lines = []
skip = False

wrapped_listeners_added = False

for line in lines:
    stripped = line.strip()
    if 'private Object originalScheduler;' in stripped:
        continue
    if 'private BukkitScheduler proxyScheduler;' in stripped:
        if not wrapped_listeners_added:
            new_lines.append('    private final List<WrappedListenerInfo> wrappedListeners = new ArrayList<>();\n')
            wrapped_listeners_added = True
        continue
    if 'private volatile Field savedSchedulerField;' in stripped:
        continue

    new_lines.append(line)

# Add WrappedListenerInfo class at the end, before the last brace
if new_lines[-1].strip() == '}':
    new_lines.pop() # remove last brace

    new_lines.append('\n    private static class WrappedListenerInfo {\n')
    new_lines.append('        final HandlerList list;\n')
    new_lines.append('        final RegisteredListener original;\n')
    new_lines.append('        final RegisteredListener wrapped;\n\n')
    new_lines.append('        WrappedListenerInfo(HandlerList list, RegisteredListener original, RegisteredListener wrapped) {\n')
    new_lines.append('            this.list = list;\n')
    new_lines.append('            this.original = original;\n')
    new_lines.append('            this.wrapped = wrapped;\n')
    new_lines.append('        }\n')
    new_lines.append('    }\n')
    new_lines.append('}\n')

with open('src/main/java/gg/pivot/TickProfiler.java', 'w') as f:
    f.writelines(new_lines)
