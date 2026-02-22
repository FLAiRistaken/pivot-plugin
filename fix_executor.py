import sys

# We need to add a helper method and update the constructor call.

helper_method = """        private static EventExecutor getExecutor(RegisteredListener listener) {
            try {
                Field executorField = RegisteredListener.class.getDeclaredField("executor");
                executorField.setAccessible(true);
                return (EventExecutor) executorField.get(listener);
            } catch (Exception e) {
                throw new RuntimeException("Failed to get executor from RegisteredListener", e);
            }
        }
"""

# Update constructor call
# super(delegate.getListener(), delegate.getExecutor(), ...
# to
# super(delegate.getListener(), getExecutor(delegate), ...

with open('src/main/java/gg/pivot/TickProfiler.java', 'r') as f:
    content = f.read()

# Replace constructor call
content = content.replace('delegate.getExecutor()', 'getExecutor(delegate)')

# Add helper method inside ProfiledRegisteredListener
# Insert it before the constructor
content = content.replace('public ProfiledRegisteredListener(', helper_method + '\n        public ProfiledRegisteredListener(')

with open('src/main/java/gg/pivot/TickProfiler.java', 'w') as f:
    f.write(content)
