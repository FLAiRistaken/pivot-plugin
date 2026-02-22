import sys
import re

with open('src/main/java/gg/pivot/TickProfiler.java', 'r') as f:
    content = f.read()

# 1. Replace setupSpigotProxy with setupSpigotProfiling
setup_new = """    private boolean setupSpigotProfiling() {
        try {
            wrappedListeners.clear();
            ArrayList<HandlerList> handlerLists = HandlerList.getHandlerLists();
            for (HandlerList handlerList : handlerLists) {
                RegisteredListener[] listeners = handlerList.getRegisteredListeners();
                for (RegisteredListener listener : listeners) {
                    Plugin plugin = listener.getPlugin();
                    if (plugin == null) continue;
                    String pluginName = plugin.getName();

                    ProfiledRegisteredListener wrapped = new ProfiledRegisteredListener(listener, pluginName, this);
                    handlerList.unregister(listener);
                    handlerList.register(wrapped);

                    wrappedListeners.add(new WrappedListenerInfo(handlerList, listener, wrapped));
                }
            }
            return true;
        } catch (Exception e) {
            logger.severe("TickProfiler: Failed to setup handler profiling: " + e.getMessage());
            e.printStackTrace();
            profilingEnabled = false;
            return false;
        }
    }"""

# Use regex to replace setupSpigotProxy method
# It starts with 'private boolean setupSpigotProxy() {' and ends before 'public JsonObject collectSample() {'
# Wait, collectSample comes after setupSpigotProxy?
# Let's check the file content structure again.
# initialize() -> setupSpigotProxy() -> collectSample()
content = re.sub(r'private boolean setupSpigotProxy\(\)\s*\{[\s\S]*?\}\s*(?=public JsonObject collectSample)', setup_new + '\n\n    ', content)

# 2. Update collectSpigotSamples to fix event_count
# p.addProperty("event_count", 0); // Not tracked in Spigot mode
# p.addProperty("task_count", sample.taskIds.size());
# Replace with:
# p.addProperty("event_count", sampleCount);
# p.addProperty("task_count", 0);

content = content.replace('p.addProperty("event_count", 0); // Not tracked in Spigot mode', 'p.addProperty("event_count", sampleCount);')
content = content.replace('p.addProperty("task_count", sample.taskIds.size());', 'p.addProperty("task_count", 0);')

# 3. Remove SchedulerInvocationHandler, ProfiledRunnable, ProfiledCallable
# They are between '// --- Spigot Proxy ---' and 'private void record' or similar.
# Let's find '// --- Spigot Proxy ---' and delete everything until 'private void record'
# Wait, 'record' is used by ProfiledRunnable.
# No, 'record' is a method in TickProfiler.
# The inner classes call 'record'.
# So 'record' comes AFTER the inner classes?
# Let's check  output.
# SchedulerInvocationHandler ...
# ProfiledRunnable ...
# ProfiledCallable ...
# private void record ...

# So delete from 'private class SchedulerInvocationHandler' to just before 'private void record'
content = re.sub(r'private class SchedulerInvocationHandler[\s\S]*?(?=private void record)', '', content)

# 4. Rewrite shutdown
shutdown_new = """    public synchronized void shutdown() {
        profilingEnabled = false;
        autoDisabled = true;

        for (WrappedListenerInfo info : wrappedListeners) {
            info.list.unregister(info.wrapped);
            info.list.register(info.original);
        }
        wrappedListeners.clear();
        logger.info("TickProfiler: Restored original listeners");
    }"""

content = re.sub(r'public synchronized void shutdown\(\)\s*\{[\s\S]*?\}\s*(?=private static class PluginSample)', shutdown_new + '\n\n    ', content)

# 5. Add ProfiledRegisteredListener
# I can add it before PluginSample
profiled_listener = """    private class ProfiledRegisteredListener extends RegisteredListener {
        private final RegisteredListener delegate;
        private final String pluginName;
        private final TickProfiler profiler;

        public ProfiledRegisteredListener(RegisteredListener delegate, String pluginName, TickProfiler profiler) {
            super(delegate.getListener(), delegate.getExecutor(), delegate.getPriority(), delegate.getPlugin(), delegate.isIgnoringCancelled());
            this.delegate = delegate;
            this.pluginName = pluginName;
            this.profiler = profiler;
        }

        @Override
        public void callEvent(Event event) throws EventException {
            if (!profiler.profilingEnabled || profiler.autoDisabled) {
                delegate.callEvent(event);
                return;
            }
            long start = System.nanoTime();
            try {
                delegate.callEvent(event);
            } finally {
                long duration = System.nanoTime() - start;
                profiler.record(pluginName, duration);
            }
        }
    }

    """
# Insert before 'private static class PluginSample'
content = content.replace('private static class PluginSample', profiled_listener + 'private static class PluginSample')

# 6. Remove test helpers that use removed classes
# createProfiledRunnableForTesting, createProfiledCallableForTesting
# They are at the end.
content = re.sub(r'/\*\* Creates a profiled \{@link Runnable\}[\s\S]*?\}\s*\}', '}', content)
# This regex might match the last closing brace of the class.
# The file ends with:
# createProfiledCallableForTesting ... }
# WrappedListenerInfo ... }
# } (TickProfiler)
# I added WrappedListenerInfo at the end.
# So I should remove createProfiledRunnableForTesting and createProfiledCallableForTesting.

content = re.sub(r'/\*\* Creates a profiled \{@link Runnable\}[\s\S]*?createProfiledCallableForTesting[\s\S]*?\}\s*', '', content)

with open('src/main/java/gg/pivot/TickProfiler.java', 'w') as f:
    f.write(content)
