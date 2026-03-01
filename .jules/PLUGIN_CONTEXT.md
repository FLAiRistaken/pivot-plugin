# Plugin Context - Pivot Analytics Minecraft Plugin

**Version:** 0.2.0  
**Last Updated:** January 20, 2026
**Purpose:** Context document for Jules AI agent working on Pivot Analytics Minecraft plugin

---

## Tech Stack

- **Java 8** (for 1.8.8-1.21+ server compatibility)
- **Spigot API 1.20.4** (backwards compatible to 1.8.8)
- **Build Tool:** Maven 3.11.0
- **Dependencies:**
  - OkHttp 4.12.0 (HTTP client, shaded to `gg.pivot.lib.okhttp3`)
  - Gson 2.10.1 (JSON serialization, shaded to `gg.pivot.lib.gson`)
- **Compatibility:** Spigot, Paper, Purpur servers (1.8.8 - 1.21+)

---

## Directory Structure

```
pivot-plugin/
├── src/
│   ├── main/
│   │   ├── java/gg/pivot/
│   │   │   ├── PivotPlugin.java          # Main plugin class (onEnable/onDisable)
│   │   │   ├── EventListener.java        # Player join/quit event handler
│   │   │   ├── EventCollector.java       # Event batching and HTTP sending
│   │   │   ├── PivotCommand.java         # Command handler (/pivot status, reload, etc.)
│   │   │   ├── TPSUtil.java              # Cross-version TPS detection
│   │   │   ├── TickProfiler.java         # Plugin performance profiling
│   │   │   └── ConfigManager.java        # Configuration access and defaults
│   │   └── resources/
│   │       ├── config.yml                # User configuration (API key, intervals)
│   │       └── plugin.yml                # Plugin metadata
│   └── test/
│       └── java/gg/pivot/
│           ├── ConfigManagerTest.java         # Unit tests for config logic
│           ├── ConfigValidationTest.java      # Test config validation rules
│           ├── EventCollectorTest.java        # Test event batching logic
│           └── TickProfilerTest.java          # Test profiling logic
├── target/                               # Build output (gitignored)
│   └── PivotAnalytics-0.2.0-SNAPSHOT.jar # Shaded JAR with dependencies
├── pom.xml                               # Maven configuration
└── README.md                             # Setup instructions
```

---

## Key Patterns

### Event Batching
- Collect events in memory for configurable interval (default: 60 seconds)
- Send batch to API via `POST /v1/ingest`
- Clear queue after successful send
- Drop oldest events if queue exceeds limit (future enhancement)

### Async Operations
**CRITICAL:** ALL HTTP operations MUST run async:
```java
Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
    // HTTP call here
});
```
**Never** block main thread with network I/O.

### Thread Safety
- Use `ConcurrentLinkedQueue` for event collections (non-blocking)
- Example:
```java
private final Queue<JsonObject> playerEvents = new ConcurrentLinkedQueue<>();

public void addPlayerEvent(...) {
    playerEvents.add(event);
}
```

### Error Handling
- Log warnings for failed API calls, never crash server
- Specific error codes: 401 (auth), 429 (rate limit), 400 (bad data)
- Don't spam logs - single warning per batch failure

### Config Validation
- Validate on `onEnable()` - disable plugin if invalid
- Check API key starts with `pvt_`
- Verify intervals are sane (batch >= TPS sample)
- **Privacy Warning:** Log a warning if anonymization is enabled (`privacy.anonymize-players: true`).

### Sentinel API Key Security
- **Strict Validation:** API keys must start with `pvt_`, be at least 20 characters long, and contain only alphanumeric/underscore characters.
- **Secure Handling:** API keys are `volatile` in `EventCollector`, immediately trimmed of whitespace, and never logged in full.
- **Permission Locking:** `config.yml` permissions are checked on startup. If insecure (readable/writable by Group/Others), the plugin attempts to lock them to `600` (Owner R/W).
- **Masking:** API keys are masked in startup logs and `/pivot status`.
  - Keys > 8 chars: Show first/last 4 chars (e.g., `pvt_***1234`).
  - Keys <= 8 chars: Completely hidden ("Configured (Hidden)").
- **Redaction:**
  - `EventCollector.redactSensitiveInfo()` scrubs API keys and patterns resembling keys (`pvt_[a-zA-Z0-9_]{10,}`) from exception messages and logs.
  - PII (UUIDs, names, hostnames) is redacted from debug logs using `redactPii()`.
- **Race Condition Prevention:** Async callbacks retrieve the API key from the request header (`X-API-Key`) to ensure the key used for the request is the one used for redaction context, preventing leaks during reloads.

---

## Code Conventions

### Java Naming
- **Classes:** `PascalCase` (e.g., `EventCollector`)
- **Methods:** `camelCase` (e.g., `addPlayerEvent`)
- **Constants:** `UPPER_SNAKE_CASE` (e.g., `SAMPLE_SIZE`)
- **Private fields:** `camelCase` (e.g., `httpClient`)

### Logging
```java
// CORRECT
logger.info("Successfully sent events");

// WRONG
System.out.println("Successfully sent events");
```

### Async Pattern
```java
// CORRECT - Async task
new BukkitRunnable() {
    @Override
    public void run() {
        // HTTP call or heavy operation
    }
}.runTaskAsynchronously(plugin, 0L, intervalTicks);

// WRONG - Blocks main thread
httpClient.newCall(request).execute(); // Synchronous!
```

### Retry Logic (Future Enhancement)
- Exponential backoff: 5s → 10s → 20s → 40s
- Max 5 retries per batch
- After max retries, log error and drop batch

---

## Event Collection

### Event Types

**1. PLAYER_JOIN**
- **Trigger:** `PlayerJoinEvent`
- **Data Captured:**
  - `player_uuid` (String, with hyphens)
  - `player_name` (String, 1-16 chars)
  - `hostname` (String, nullable) - Captured from `PlayerLoginEvent`
  - `timestamp` (long, Unix milliseconds)
  - `connection_type` (String, "initial" or "reconnect")
- **Hostname Caching:** `PlayerLoginEvent` fires before `PlayerJoinEvent`, so cache hostname by UUID

**2. PLAYER_QUIT**
- **Trigger:** `PlayerQuitEvent`
- **Data Captured:**
  - `player_uuid` (String)
  - `player_name` (String)
  - `hostname` (null for quits)
  - `timestamp` (long)
  - `quit_reason` (String, nullable)
  - `session_clean` (boolean)

**3. TPS_SAMPLE**
- **Trigger:** Scheduled task (every 30 seconds, configurable)
- **Data Captured:**
  - `tps` (double, 0.0-20.0)
  - `player_count` (int)
  - `timestamp` (long)

**4. TICK_PROFILE**
- **Trigger:** Scheduled task (collected with batch flush)
- **Data Captured:**
  - `plugins` (Array of objects): Execution time per plugin
  - `sample_duration_seconds` (int)
  - `profiling_mode` (String, e.g. "paper_timings" or "custom_spigot")

**5. SERVER_START**
- **Trigger:** Plugin Enable
- **Data Captured:**
  - `server_version` (String)
  - `plugins_loaded` (int)
  - `timestamp` (long)

**6. SERVER_STOP**
- **Trigger:** Plugin Disable
- **Data Captured:**
  - `reason` (String, e.g. "manual")
  - `timestamp` (long)

### Batch Format

```json
{
  "batch_timestamp": 1736444400000,
  "player_events": [
    {
      "timestamp": 1736444400000,
      "event_type": "PLAYER_JOIN",
      "player_uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
      "player_name": "Notch",
      "hostname": "play.example.com"
    }
  ],
  "performance_events": [
    {
      "timestamp": 1736444400000,
      "tps": 19.8,
      "player_count": 12
    }
  ],
  "tick_profile_events": [
    {
        "event_type": "TICK_PROFILE",
        "timestamp": 1736444400000,
        "plugins": [ ... ]
    }
  ]
}
```

See `../docs/EVENT_SCHEMAS.md` for full specification.

---

## Tick Profiling

The `TickProfiler` implements a hybrid strategy to measure plugin performance impact without heavy external dependencies.

1.  **Paper Mode (`paper_timings`)**:
    *   Detects the presence of Timings v2 classes (e.g. `co.aikar.timings.TimingsManager`) for environment awareness only.
    *   Currently delegates sampling to the same custom listener-wrapping implementation as **Spigot Mode** (Paper sampling still uses the `custom_spigot` path).

2.  **Spigot Mode (`custom_spigot`)**:
    *   Used on Spigot/CraftBukkit servers or when configured as `custom_only`.
    *   Wraps every `RegisteredListener` with a `ProfiledRegisteredListener`.
    *   Measures execution time of event handlers using `System.nanoTime()`.
    *   **Overhead Protection:** When `profiling.performance.auto_disable_on_overhead` is enabled, automatically disables profiling after 3 consecutive ticks where internal profiling overhead exceeds `0.2ms` per tick (threshold configurable).

3.  **Configuration**:
    *   Controlled via `profiling` section in `config.yml`.
    *   Supports `auto`, `paper_only`, and `custom_only` modes. To disable profiling entirely, set `profiling.enabled: false`.

---

## API Integration

### Endpoint
```
POST https://api.pivotmc.dev/v1/ingest
```

### Authentication
```http
X-API-Key: pvt_xxxxxxxxxxxxxxxxxxxxx
Content-Type: application/json
```

**IMPORTANT:** Use `X-API-Key` header, NOT `Authorization: Bearer`.

### Configuration
```yaml
api:
  endpoint: "https://api.pivotmc.dev/v1/ingest"
  key: "pvt_xxxxx"  # Generated by dashboard
  server-id: "uuid"  # Optional, inferred from API key
```

### Error Handling
- **401 Unauthorized:** Invalid API key → Log severe error
- **429 Rate Limited:** Too many requests → Log warning, retry next batch
- **400 Bad Request:** Invalid data → Log error with details
- **500 Server Error:** Backend issue → Log warning, retry

### Response Format
```json
{
  "status": "success",
  "events_processed": 15
}
```

---

## Configuration Structure

### config.yml (Nested YAML)
```yaml
api:
  endpoint: "https://api.pivotmc.dev/v1/ingest"
  key: "pvt_xxxxx"
  server-id: ""

collection:
  enabled: true
  batch-interval: 60          # Seconds between API sends
  track-player-events: true
  track-performance: true
  tps-sample-interval: 30     # Seconds between TPS samples
  idle-throttling: true       # Reduce sampling when 0 players online

privacy:
  anonymize-players: false    # Hash UUIDs with SHA-256
  track-hostnames: true       # Capture join hostnames

profiling:
  enabled: true
  mode: auto                  # auto | paper_only | custom_only | disabled
  privacy:
    anonymize_plugin_names: false
  performance:
    max_overhead_ms: 0.2
    auto_disable_on_overhead: true

debug:
  enabled: false              # Verbose logging
  log-batches: false          # Log full JSON payloads
```

### Reading Config
```java
// Nested path
String apiKey = plugin.getConfig().getString("api.key");
int batchInterval = plugin.getConfig().getInt("collection.batch-interval", 60);
boolean debugEnabled = plugin.getConfig().getBoolean("debug.enabled", false);

// ConfigManager usage
ConfigManager config = new ConfigManager(plugin);
boolean profilingEnabled = config.isProfilingEnabled();
```

---

## Performance Requirements

### Target Metrics
- **Tick Impact:** <0.1ms per tick
- **TPS Stability:** Server TPS remains 20.0 with plugin enabled
- **Memory:** <50MB heap usage for event queue
- **Network:** Batch sends should not cause lag spikes

### Optimization Techniques
- Async all HTTP operations
- Batch events (don't send per-event)
- Limit queue size to prevent memory leaks
- Use efficient JSON serialization (Gson)
- **Bolt Optimizations:**
  - Defer anonymization (hashing) to async flush task
  - Direct queue draining to JsonArray (avoids intermediate allocations)
  - Lazy TPS calculation and cached server instances

### TPS Detection
**3 methods in priority order:**
1. **Paper API** (`Server.getTPS()`) - Native, most accurate
2. **Spigot Reflection** (`MinecraftServer.recentTps`) - NMS access
3. **Manual Calculation** (measure tick duration) - Universal fallback

**Idle Throttling:**
To conserve resources, the plugin supports `idle-throttling` (default: true). When 0 players are online, the TPS sampling interval is automatically multiplied by 4 (reduced frequency).

Supports 1.7.10+ including modded servers.

---

## Important Files

### PivotPlugin.java
**Purpose:** Main plugin lifecycle
- `onEnable()`: Initialize TPS detection, start tasks, register listeners/commands
- `onDisable()`: Cancel tasks, flush remaining events
- `validateConfig()`: Check API key format, intervals
- `startTasks()`: Schedule TPS sampling and batch flush tasks
- `restartTasks()`: Called by `/pivot reload` command to restart tasks with new config
- `getLastEventSentTime()`: Returns timestamp of last successful batch flush

### EventListener.java
**Purpose:** Bukkit event handlers
- `onPlayerLogin()`: Capture hostname
- `onPlayerJoin()`: Send JOIN event with cached hostname
- `onPlayerQuit()`: Send QUIT event, clean up cache

**CRITICAL:** PlayerLoginEvent uses `@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)` to prevent Paper server warnings.

### EventCollector.java
**Purpose:** Event batching and HTTP client
- `addPlayerEvent()`: Add event to queue (thread-safe)
- `addPerformanceEvent()`: Add TPS sample to queue
- `flush()`: Build JSON payload, send to API, clear queues
- `sendToAPI()`: OkHttp async POST request
- `hashUuid()`: SHA-256 hashing for UUID anonymization

### PivotCommand.java
**Purpose:** Command handler
- `/pivot status`: Show connection health, queued events, TPS, config
- `/pivot reload`: Reload config.yml, restart tasks
- `/pivot debug [on|off|toggle]`: Toggle debug logging
- `/pivot info`: Show plugin version, TPS method, links
- Tab completion for all commands

### TPSUtil.java
**Purpose:** Cross-version TPS detection
- `initialize()`: Detect best TPS method on startup
- `getTPS()`: Return current TPS (0.0-20.0)
- `getTPSInfo()`: Return detection method string
- Supports Paper, Spigot (1.12-1.16, 1.17+), Manual fallback

### TickProfiler.java
**Purpose:** Plugin performance profiling
- `initialize()`: Detect server type (Paper/Spigot) and setup profiling
- `collectSample()`: Gather performance data for plugins
- `setupSpigotProfiling()`: Wrap listeners for Spigot
- `shutdown()`: Unwrap listeners and cleanup

### ConfigManager.java
**Purpose:** Configuration wrapper
- `isProfilingEnabled()`: Check if profiling is enabled
- `getProfilingMode()`: Get profiling mode (auto/paper/custom)
- `getMaxOverheadMs()`: Get performance safety limits

---

## Common Pitfalls

### ❌ Blocking Main Thread
```java
// WRONG - Blocks server tick
Response response = httpClient.newCall(request).execute();
```

```java
// CORRECT - Async
httpClient.newCall(request).enqueue(new Callback() { ... });
```

### ❌ Not Handling Null Hostname
```java
// WRONG - NPE on direct IP connections
event.addProperty("hostname", hostname);
```

```java
// CORRECT - Check for null
if (hostname != null && !hostname.isEmpty()) {
    event.addProperty("hostname", hostname);
}
```

### ❌ Using Authorization Header
```java
// WRONG - API expects X-API-Key
.addHeader("Authorization", "Bearer " + apiKey)
```

```java
// CORRECT
.addHeader("X-API-Key", apiKey)
```

### ❌ Not Cleaning Up Caches
```java
// WRONG - Memory leak if player kicked during login
// (hostname stays in cache forever)
```

```java
// CORRECT - Clean up on quit
@EventHandler
public void onPlayerQuit(PlayerQuitEvent event) {
    hostnameCache.remove(event.getPlayer().getUniqueId());
}
```

### ❌ PlayerLoginEvent Without MONITOR Priority
```java
// WRONG - Causes Paper server warning
@EventHandler
public void onPlayerLogin(PlayerLoginEvent event) { ... }
```

```java
// CORRECT - No warning, runs last
@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
public void onPlayerLogin(PlayerLoginEvent event) { ... }
```

---

## Testing Checklist

### Build Test
```bash
mvn clean package
# Should produce: target/PivotAnalytics-0.2.0-SNAPSHOT.jar
# Size: ~3-4MB (includes shaded OkHttp + Gson)
```

### Compatibility Test
- Test on Java 8 server (1.8.8 - 1.12.2)
- Test on Java 17 server (1.20.4+)
- Test on Paper, Spigot, Purpur

### Functionality Test
1. Set API key in config.yml
2. Join server → Check console for "Player joined" log
3. Wait 60 seconds → Check for "Successfully sent events"
4. Run `/pivot status` → Verify connection status
5. Run `/pivot debug on` → Enable verbose logging
6. Rejoin server → Should see hostname capture logs

### Performance Test
- `/timings report` → Plugin should be <0.1ms per tick
- TPS should remain 20.0 with plugin enabled
- No lag spikes during event batching

---

## Related Documentation

Jules should read these files for context:

1. **`../docs/API_CONTRACT.md`**
   - Full API endpoint specifications
   - Authentication methods (X-API-Key format)
   - Error response codes
   - Rate limiting rules

2. **`../docs/EVENT_SCHEMAS.md`**
   - Canonical event format definitions
   - Validation rules (UUID format, timestamp ranges)
   - Backend processing flow
   - Example payloads

3. **`../docs/ARCHITECTURE.md`**
   - System-wide architecture
   - Data flow: Plugin → Backend → Dashboard
   - Database schema (TimescaleDB)
   - Deployment architecture

4. **`../docs/INTEGRATION_CHECKLIST.md`**
   - Cross-repo coordination guidelines
   - Breaking change procedures
   - Testing requirements
   - Rollback procedures

---

## Current Feature Status

### ✅ Implemented
- Event capture (PLAYER_JOIN, PLAYER_QUIT, TPS_SAMPLE, TICK_PROFILE)
- Hostname attribution with caching
- Cross-version TPS detection (Paper/Spigot/Manual)
- HTTP batching with OkHttp
- Configurable intervals and privacy settings
- UUID anonymization (SHA-256)
- Command system (/pivot status, reload, debug, info)
- Tab completion
- Debug logging
- Java 8 compatibility
- Tick Profiling (Paper/Spigot hybrid)

### ❌ Not Yet Implemented
- Event queue size limits (prevent memory leaks)
- Retry logic with exponential backoff
- bStats integration
- Health check pings to backend
- `/pivot test` command (send test event)
- Config migration helper (old → new format)

---

## Development Workflow

### Making Changes
1. Read relevant docs (API_CONTRACT.md, EVENT_SCHEMAS.md)
2. Update code following conventions above
3. Test on local server (Paper 1.20.4 recommended)
4. Build: `mvn clean package`
5. Verify no compilation errors
6. Test on multiple Minecraft versions

### Adding New Event Types
1. Update `EventCollector.addXEvent()` method
2. Update `EventListener` to capture new event
3. Notify Backend chat (schema change required)
4. Update EVENT_SCHEMAS.md
5. Version bump in pom.xml and plugin.yml

### Breaking Changes
- Coordinate with Backend chat (API contract changes)
- Update INTEGRATION_CHECKLIST.md
- Version bump to next major (0.2.0 → 0.3.0)
- Document migration path for existing users

---

## Quick Reference

### Build Commands
```bash
# Clean build
mvn clean package

# Skip tests (no tests yet, but for future)
mvn clean package -DskipTests

# View dependencies
mvn dependency:tree
```

### Console Commands
```bash
# Show status
/pivot status

# Reload config
/pivot reload

# Toggle debug
/pivot debug on
/pivot debug off
/pivot debug toggle

# Show info
/pivot info
```

### Config Paths
```java
plugin.getConfig().getString("api.endpoint")
plugin.getConfig().getString("api.key")
plugin.getConfig().getInt("collection.batch-interval", 60)
plugin.getConfig().getBoolean("privacy.track-hostnames", true)
plugin.getConfig().getBoolean("debug.enabled", false)
```

---

**End of Plugin Context Document**

*Generated for Jules AI Agent - January 9, 2026*
