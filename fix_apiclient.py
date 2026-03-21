import re

with open('src/main/java/gg/pivot/ApiClient.java', 'r') as f:
    content = f.read()

# Add try-catch around response.body().string()
content = re.sub(
    r'String responseBody = response\.body\(\) != null \? response\.body\(\)\.string\(\) : "no body";',
    '''String responseBody = "no body";
                        try {
                            if (response.body() != null) responseBody = response.body().string();
                        } catch (IOException e) {
                            logger.warning("Failed to read response body: " + e.getMessage());
                        }''',
    content
)

content = re.sub(
    r'String errorBody = response\.body\(\) != null \? response\.body\(\)\.string\(\) : "no error details";',
    '''String errorBody = "no error details";
                        try {
                            if (response.body() != null) errorBody = response.body().string();
                        } catch (IOException e) {
                            logger.warning("Failed to read error body: " + e.getMessage());
                        }''',
    content
)

with open('src/main/java/gg/pivot/ApiClient.java', 'w') as f:
    f.write(content)
