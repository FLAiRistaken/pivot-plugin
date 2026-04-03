import re

with open('src/main/java/gg/pivot/ApiClient.java', 'r') as f:
    content = f.read()

# Fix the scheduleRetry vs scheduleRetryCustom logic since we need:
# 401 response: do NOT retry
# 429 response: retry after the Retry-After header value in seconds, or 60s if header absent
# 500 / IOException: retry using the backoff schedule

content = re.sub(
    r'private void scheduleRetry\(String json, int retryCount\) \{',
    '''private void scheduleRetry(String json, int retryCount) {
        if (retryCount >= BACKOFF_SECONDS.length) {
            logger.warning("Max retries reached. Discarding batch.");
            return;
        }
        int delaySeconds = BACKOFF_SECONDS[retryCount];
        scheduleRetryCustom(json, retryCount + 1, delaySeconds, retryCount + 1);
    }

    private void scheduleRetryCustom(String json, int nextRetryCount, int delaySeconds, int attemptLabel) {
        if (!plugin.isEnabled()) return;

        long delayTicks = delaySeconds * 20L;
        logger.info("Retrying batch send in " + delaySeconds + "s (Attempt " + attemptLabel + "/" + BACKOFF_SECONDS.length + ")");

        new BukkitRunnable() {
            @Override
            public void run() {
                if (plugin.isEnabled()) {
                    sendToAPI(json, nextRetryCount);
                }
            }
        }.runTaskLaterAsynchronously(plugin, delayTicks);
    }''',
    content
)

# Replace scheduleRetryCustom signature
content = re.sub(
    r'private void scheduleRetryCustom\(String json, int nextRetryCount, int delaySeconds\) \{.*?(?=public void sendToAPISync)',
    '',
    content,
    flags=re.DOTALL
)

# Update the 429 call
content = re.sub(
    r'scheduleRetryCustom\(json, retryCount, delaySeconds\);',
    'scheduleRetryCustom(json, retryCount, delaySeconds, retryCount + 1);',
    content
)

with open('src/main/java/gg/pivot/ApiClient.java', 'w') as f:
    f.write(content)
