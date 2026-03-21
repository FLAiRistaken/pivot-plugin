import re

with open('src/main/java/gg/pivot/EventCollector.java', 'r') as f:
    content = f.read()

# Add profilingEvents queue
content = re.sub(
    r'private final Queue<ServerEventData> serverEvents = new ConcurrentLinkedQueue<>\(\);',
    'private final Queue<ServerEventData> serverEvents = new ConcurrentLinkedQueue<>();\n    private final Queue<JsonObject> profilingEvents = new ConcurrentLinkedQueue<>();\n\n    private ApiClient apiClient;',
    content
)

# Initialize ApiClient
content = re.sub(
    r'this\.httpClient = httpClient;',
    'this.httpClient = httpClient;\n        this.apiClient = new ApiClient(plugin, httpClient);',
    content
)

# Add addProfilingEvent and getProfilingEventCount
content = re.sub(
    r'public void reload\(\) \{',
    '''public void addProfilingEvent(JsonObject event) {
        profilingEvents.add(event);
    }

    public int getProfilingEventCount() {
        return profilingEvents.size();
    }

    public void reload() {''',
    content
)

# Call apiClient.reload()
content = re.sub(
    r'this\.apiKey = trimmedKey;\n        \}',
    'this.apiKey = trimmedKey;\n        }\n        if (this.apiClient != null) this.apiClient.reload();',
    content
)

# Change sendToAPISync
content = re.sub(
    r'sendToAPISync\(payload\.toString\(\)\);',
    'apiClient.sendToAPISync(payload.toString());',
    content
)

content = re.sub(
    r'logger\.warning\("Failed to send SERVER_STOP event: " \+ redactSensitiveInfo\(errorMsg, this\.apiKey\)\);',
    'logger.warning("Failed to send SERVER_STOP event: " + ApiClient.redactSensitiveInfo(errorMsg, this.apiKey));',
    content
)

# Update early return in flush
content = re.sub(
    r'if \(playerEvents\.isEmpty\(\) && performanceEvents\.isEmpty\(\) && serverEvents\.isEmpty\(\) && tickProfileEvent == null\) \{',
    'if (playerEvents.isEmpty() && performanceEvents.isEmpty() && serverEvents.isEmpty() && tickProfileEvent == null && profilingEvents.isEmpty()) {',
    content
)

# Drain profiling events
content = re.sub(
    r'if \(debugEnabled\) \{\n            logger\.info\("Events to send - Player: "',
    '''JsonArray profilingArray = new JsonArray();
        JsonObject pe;
        while ((pe = profilingEvents.poll()) != null) {
            profilingArray.add(pe);
        }

        if (debugEnabled) {
            logger.info("Events to send - Player: "''',
    content
)

# Update double check
content = re.sub(
    r'if \(playerArray\.size\(\) == 0 && perfArray\.size\(\) == 0 && serverArray\.size\(\) == 0 && tickProfileEvent == null\) \{',
    'if (playerArray.size() == 0 && perfArray.size() == 0 && serverArray.size() == 0 && tickProfileEvent == null && profilingArray.size() == 0) {',
    content
)

# Add profiling_events to payload
content = re.sub(
    r'payload\.add\("server_events", serverArray\);',
    'payload.add("server_events", serverArray);\n\n        if (profilingArray.size() > 0) {\n            payload.add("profiling_events", profilingArray);\n        }',
    content
)

# Remove buildRequest, sendToAPI, sendToAPISync, redactSensitiveInfo
# and replace sendToAPI(json) with apiClient.sendToAPI(json)
content = re.sub(
    r'sendToAPI\(json\);',
    'apiClient.sendToAPI(json);',
    content
)

# Let's completely remove the old methods
method_pattern = r'private Request buildRequest.*?private String redactPii'
content = re.sub(method_pattern, 'private String redactPii', content, flags=re.DOTALL)

with open('src/main/java/gg/pivot/EventCollector.java', 'w') as f:
    f.write(content)
