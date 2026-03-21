import re

with open('src/main/java/gg/pivot/EventCollector.java', 'r') as f:
    content = f.read()

# I need to completely remove buildRequest, sendToAPI, sendToAPISync, and redactSensitiveInfo
# from EventCollector since I'll put them in ApiClient.
# I will find the 'private Request buildRequest(String json)' and remove until 'private String redactPii'

pattern = r'private Request buildRequest\(String json\).*?(?=private String redactPii\(String json\))'
content = re.sub(pattern, '', content, flags=re.DOTALL)

with open('src/main/java/gg/pivot/EventCollector.java', 'w') as f:
    f.write(content)
