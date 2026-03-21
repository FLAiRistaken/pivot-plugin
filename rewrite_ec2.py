import re

with open('src/main/java/gg/pivot/EventCollector.java', 'r') as f:
    content = f.read()

# Make sure we didn't remove getApiClient
content = content.replace(
    'apiClient.sendToAPI(json);\n    }\n\n    /**',
    '''apiClient.sendToAPI(json);
    }

    public ApiClient getApiClient() {
        return apiClient;
    }

    /**'''
)

with open('src/main/java/gg/pivot/EventCollector.java', 'w') as f:
    f.write(content)
