# Pivot Analytics - Event Schemas

**Version:** 1.0.0  
**Last Updated:** December 28, 2025  
**Purpose:** Canonical reference for all event types, payloads, and validation rules

---

## Table of Contents

1. [Overview](#overview)
2. [Event Batch Format](#event-batch-format)
3. [Player Events](#player-events)
   - [PLAYER_JOIN](#player_join)
   - [PLAYER_QUIT](#player_quit)
4. [Performance Events](#performance-events)
   - [TPS_SAMPLE](#tps_sample)
5. [Validation Rules](#validation-rules)
6. [Error Responses](#error-responses)
7. [Examples](#examples)
8. [Plugin Implementation](#plugin-implementation)

---

## Overview

Events are the core data primitive in Pivot Analytics. All data originates from the Minecraft plugin, which captures player activity and server performance metrics, then sends them in batches to the API.

### Event Lifecycle

```
┌─────────────────────────────────────────────────────────────────┐
│  1. Event Occurs (Player joins, TPS sampled)                   │
│     ↓                                                           │
│  2. Plugin Listener Captures Event                             │
│     ↓                                                           │
│  3. EventCollector Stores in Memory Buffer                     │
│     ↓                                                           │
│  4. Every 60 seconds: flush() Triggered                        │
│     ↓                                                           │
│  5. JSON Payload Built (EventBatch)                            │
│     ↓                                                           │
│  6. POST /v1/ingest with Authorization: Bearer <api_key>       │
│     ↓                                                           │
│  7. Backend Validates Pydantic Schema (EventBatch)             │
│     ↓                                                           │
│  8. Events Inserted into PostgreSQL + TimescaleDB              │
│     ↓                                                           │
│  9. Analytics Queries Aggregate Events                         │
│     ↓                                                           │
│ 10. Dashboard Displays Charts/Insights                         │
└─────────────────────────────────────────────────────────────────┘
```

### Event Categories

| Category | Event Types | Frequency | Purpose |
|----------|-------------|-----------|---------|
| **Player Activity** | `PLAYER_JOIN`, `PLAYER_QUIT` | Per player action | Track sessions, attribution |
| **Performance** | `TPS_SAMPLE` | Every 30 seconds | Monitor server health |

---

## Event Batch Format

All events are sent in batches via `POST /v1/ingest` endpoint.

### EventBatch Schema

```json
{
  "server_id": "f86dfb9d-f74e-40dd-8c62-4c53833d1477",
  "batch_timestamp": 1735398000000,
  "player_events": [
    {/* PlayerEvent objects */}
  ],
  "performance_events": [
    {/* PerformanceEvent objects */}
  ]
}
```

### Fields

| Field | Type | Required | Description | Validation |
|-------|------|----------|-------------|------------|
| `server_id` | string (UUID) | No | Server identifier | Optional, inferred from API key |
| `batch_timestamp` | integer | Yes | When batch was created | Unix timestamp (milliseconds) |
| `player_events` | array | Yes | Player JOIN/QUIT events | Can be empty array `[]` |
| `performance_events` | array | Yes | TPS samples | Can be empty array `[]` |

### Pydantic Schema (Python)

```python
from pydantic import BaseModel
from typing import List, Optional

class EventBatch(BaseModel):
    server_id: Optional[str] = None
    batch_timestamp: int
    player_events: List[PlayerEvent] = []
    performance_events: List[PerformanceEvent] = []
```

### Java Implementation

```java
// EventCollector.java
JsonObject payload = new JsonObject();
payload.addProperty("batch_timestamp", System.currentTimeMillis());

JsonArray playerArray = new JsonArray();
for (JsonObject event : playerEvents) {
    playerArray.add(event);
}
payload.add("player_events", playerArray);

JsonArray perfArray = new JsonArray();
for (JsonObject event : performanceEvents) {
    perfArray.add(event);
}
payload.add("performance_events", perfArray);
```

---

## Player Events

### PLAYER_JOIN

Triggered when a player joins the server.

#### Schema

```json
{
  "timestamp": 1735398000000,
  "event_type": "PLAYER_JOIN",
  "player_uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
  "player_name": "Notch",
  "hostname": "play.example.com"
}
```

#### Fields

| Field | Type | Required | Description | Validation | Example |
|-------|------|----------|-------------|------------|---------|
| `timestamp` | integer | Yes | When player joined | Unix timestamp (ms), must be ≤ current time | `1735398000000` |
| `event_type` | string | Yes | Event type identifier | Must be exactly `"PLAYER_JOIN"` | `"PLAYER_JOIN"` |
| `player_uuid` | string | Yes | Minecraft player UUID | Valid UUID with hyphens, 36 chars | `"069a79f4-44e9-4726-a5be-fca90e38aaf5"` |
| `player_name` | string | Yes | Player's in-game name | 1-16 characters, alphanumeric + underscore | `"Notch"` |
| `hostname` | string | No | Join hostname for attribution | 1-255 characters, can be null | `"play.example.com"` |

#### Purpose

- **Marketing Attribution:** Track which hostname drove the player (e.g., `youtube.example.com` vs `discord.example.com`)
- **Session Tracking:** Start of player session (paired with PLAYER_QUIT)
- **Growth Analysis:** Count unique players per day/week/month

#### Hostname Capture Logic

The plugin captures hostname during `PlayerLoginEvent` (before `PlayerJoinEvent` fires):

```java
// EventListener.java
private final Map<UUID, String> hostnameCache = new HashMap<>();

@EventHandler
public void onPlayerLogin(PlayerLoginEvent event) {
    String hostname = event.getHostname();
    UUID playerId = event.getPlayer().getUniqueId();
    if (hostname != null && !hostname.isEmpty()) {
        hostnameCache.put(playerId, hostname);
    }
}

@EventHandler
public void onPlayerJoin(PlayerJoinEvent event) {
    UUID playerId = event.getPlayer().getUniqueId();
    String hostname = hostnameCache.remove(playerId);

    plugin.getEventCollector().addPlayerEvent(
        "PLAYER_JOIN",
        event.getPlayer().getUniqueId().toString(),
        event.getPlayer().getName(),
        hostname  // Can be null if not in cache
    );
}
```

#### Database Storage

```sql
INSERT INTO events (
    server_id, 
    timestamp, 
    event_type, 
    player_uuid, 
    player_name, 
    hostname
) VALUES (
    'f86dfb9d-f74e-40dd-8c62-4c53833d1477',
    '2025-12-28 16:30:00',
    'PLAYER_JOIN',
    '069a79f4-44e9-4726-a5be-fca90e38aaf5',
    'Notch',
    'play.example.com'
);
```

#### Analytics Use Cases

1. **Hostname Attribution Table:**
   ```sql
   SELECT 
     hostname,
     COUNT(DISTINCT player_uuid) as unique_players,
     COUNT(*) as total_joins
   FROM events
   WHERE event_type = 'PLAYER_JOIN'
     AND hostname IS NOT NULL
   GROUP BY hostname
   ORDER BY unique_players DESC;
   ```

2. **New vs Returning Players:**
   ```sql
   SELECT 
     player_uuid,
     MIN(timestamp) as first_join,
     COUNT(*) as total_joins
   FROM events
   WHERE event_type = 'PLAYER_JOIN'
   GROUP BY player_uuid;
   ```

---

### PLAYER_QUIT

Triggered when a player leaves the server.

#### Schema

```json
{
  "timestamp": 1735398300000,
  "event_type": "PLAYER_QUIT",
  "player_uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
  "player_name": "Notch",
  "hostname": null
}
```

#### Fields

| Field | Type | Required | Description | Validation | Example |
|-------|------|----------|-------------|------------|---------|
| `timestamp` | integer | Yes | When player quit | Unix timestamp (ms) | `1735398300000` |
| `event_type` | string | Yes | Event type identifier | Must be exactly `"PLAYER_QUIT"` | `"PLAYER_QUIT"` |
| `player_uuid` | string | Yes | Minecraft player UUID | Valid UUID with hyphens | `"069a79f4-44e9-4726-a5be-fca90e38aaf5"` |
| `player_name` | string | Yes | Player's in-game name | 1-16 characters | `"Notch"` |
| `hostname` | null | No | Always null for quit events | Must be `null` | `null` |

#### Purpose

- **Session Duration:** Calculate time between JOIN and QUIT
- **Churn Analysis:** Identify when players leave (especially during lag)
- **Retention Metrics:** Calculate quit rate (quits / joins)

#### Plugin Implementation

```java
// EventListener.java
@EventHandler
public void onPlayerQuit(PlayerQuitEvent event) {
    // Clean up hostname cache (in case player was kicked during login)
    hostnameCache.remove(event.getPlayer().getUniqueId());

    plugin.getEventCollector().addPlayerEvent(
        "PLAYER_QUIT",
        event.getPlayer().getUniqueId().toString(),
        event.getPlayer().getName(),
        null  // No hostname on quit
    );
}
```

#### Database Storage

```sql
INSERT INTO events (
    server_id, 
    timestamp, 
    event_type, 
    player_uuid, 
    player_name, 
    hostname
) VALUES (
    'f86dfb9d-f74e-40dd-8c62-4c53833d1477',
    '2025-12-28 16:35:00',
    'PLAYER_QUIT',
    '069a79f4-44e9-4726-a5be-fca90e38aaf5',
    'Notch',
    NULL
);
```

#### Analytics Use Cases

1. **Lag-to-Churn Correlation:**
   ```sql
   -- Find quits that occurred during lag spikes
   SELECT 
     quit_events.timestamp,
     COUNT(*) as quit_count,
     AVG(tps_events.tps) as avg_tps_at_quit
   FROM events quit_events
   LEFT JOIN events tps_events 
     ON tps_events.server_id = quit_events.server_id
     AND tps_events.timestamp BETWEEN quit_events.timestamp - INTERVAL '5 minutes'
                                  AND quit_events.timestamp
     AND tps_events.event_type = 'TPS_SAMPLE'
   WHERE quit_events.event_type = 'PLAYER_QUIT'
   GROUP BY quit_events.timestamp
   HAVING AVG(tps_events.tps) < 15;
   ```

2. **Session Duration:**
   ```sql
   SELECT 
     join_events.player_uuid,
     join_events.timestamp as join_time,
     quit_events.timestamp as quit_time,
     EXTRACT(EPOCH FROM (quit_events.timestamp - join_events.timestamp)) as session_seconds
   FROM events join_events
   JOIN events quit_events
     ON quit_events.player_uuid = join_events.player_uuid
     AND quit_events.timestamp > join_events.timestamp
     AND quit_events.event_type = 'PLAYER_QUIT'
   WHERE join_events.event_type = 'PLAYER_JOIN';
   ```

---

## Performance Events

### TPS_SAMPLE

Periodic server performance snapshot.

#### Schema

```json
{
  "timestamp": 1735398000000,
  "tps": 19.8,
  "player_count": 12
}
```

#### Fields

| Field | Type | Required | Description | Validation | Example |
|-------|------|----------|-------------|------------|---------|
| `timestamp` | integer | Yes | When sample was taken | Unix timestamp (ms) | `1735398000000` |
| `tps` | float | Yes | Ticks per second | 0.0 - 20.0 (inclusive) | `19.8` |
| `player_count` | integer | Yes | Online players | ≥ 0 | `12` |

#### Purpose

- **Performance Monitoring:** Track server health over time
- **Lag Detection:** Identify TPS drops (< 19.0 = lag)
- **Correlation Analysis:** Match TPS drops with player quits

#### Sampling Frequency

The plugin samples TPS every **30 seconds** (configurable):

```java
// PivotPlugin.java
new BukkitRunnable() {
    @Override
    public void run() {
        int playerCount = getServer().getOnlinePlayers().size();
        double tps = TPSUtil.getTPS();  // 3 detection methods
        eventCollector.addPerformanceEvent(tps, playerCount);
    }
}.runTaskTimerAsynchronously(this, 0L, 600L);  // 600 ticks = 30 seconds
```

#### TPS Detection Methods

The plugin uses 3 methods (priority order):

1. **Paper API** (fastest, most accurate):
   ```java
   Method paperGetTPSMethod = Server.class.getDeclaredMethod("getTPS");
   double[] tpsArray = (double[]) paperGetTPSMethod.invoke(Bukkit.getServer());
   return tpsArray[0];  // 1-minute average
   ```

2. **Spigot Reflection** (works on most servers):
   ```java
   Class<?> minecraftServerClass = Class.forName("net.minecraft.server.MinecraftServer");
   Field recentTPSField = minecraftServerClass.getDeclaredField("recentTps");
   recentTPSField.setAccessible(true);
   Object minecraftServer = minecraftServerClass.getDeclaredMethod("getServer").invoke(null);
   double[] recentTps = (double[]) recentTPSField.get(minecraftServer);
   return recentTps[0];
   ```

3. **Manual Calculation** (universal fallback):
   ```java
   // Measure tick duration over 100 ticks (rolling average)
   // 50ms per tick = 20 TPS
   long avgTickNanos = sum / tickTimes.size();
   double avgTickMillis = avgTickNanos / 1_000_000.0;
   return Math.min(20.0, 1000.0 / avgTickMillis);
   ```

#### Database Storage

```sql
INSERT INTO events (
    server_id, 
    timestamp, 
    event_type, 
    tps, 
    player_count
) VALUES (
    'f86dfb9d-f74e-40dd-8c62-4c53833d1477',
    '2025-12-28 16:30:00',
    'TPS_SAMPLE',
    19.8,
    12
);
```

#### Analytics Use Cases

1. **Performance Summary (Dashboard):**
   ```sql
   SELECT 
     AVG(tps) as avg_tps,
     MIN(tps) as min_tps,
     MAX(tps) as max_tps,
     COUNT(*) as sample_count,
     COUNT(*) FILTER (WHERE tps < 19.0) as lag_samples
   FROM events
   WHERE event_type = 'TPS_SAMPLE'
     AND timestamp >= NOW() - INTERVAL '24 hours';
   ```

2. **TPS History (5-minute buckets for chart):**
   ```sql
   SELECT 
     time_bucket('5 minutes', timestamp) AS bucket,
     AVG(tps) as avg_tps,
     MIN(tps) as min_tps,
     MAX(tps) as max_tps
   FROM events
   WHERE event_type = 'TPS_SAMPLE'
     AND timestamp >= NOW() - INTERVAL '24 hours'
   GROUP BY bucket
   ORDER BY bucket;
   ```

3. **Lag Spike Detection (AI Insights):**
   ```sql
   -- Find TPS drops below 15 with nearby player quits
   SELECT 
     tps_events.timestamp as lag_time,
     tps_events.tps,
     COUNT(quit_events.id) as concurrent_quits
   FROM events tps_events
   LEFT JOIN events quit_events
     ON quit_events.server_id = tps_events.server_id
     AND quit_events.timestamp BETWEEN tps_events.timestamp - INTERVAL '5 minutes'
                                   AND tps_events.timestamp + INTERVAL '5 minutes'
     AND quit_events.event_type = 'PLAYER_QUIT'
   WHERE tps_events.event_type = 'TPS_SAMPLE'
     AND tps_events.tps < 15.0
   GROUP BY tps_events.timestamp, tps_events.tps
   HAVING COUNT(quit_events.id) >= 5;  -- 5+ quits during lag = critical
   ```

---

## Validation Rules

### Field-Level Validation

#### Timestamps

| Rule | Validation | Error Message |
|------|------------|---------------|
| **Format** | Integer (Unix milliseconds) | `"timestamp must be an integer"` |
| **Range** | ≤ current time + 1 hour | `"timestamp cannot be in the future"` |
| **Min Value** | ≥ 0 | `"timestamp must be positive"` |

**Example:**
```python
# Backend validation (ingest.py)
from datetime import datetime

timestamp_seconds = player_event.timestamp / 1000
event_datetime = datetime.fromtimestamp(timestamp_seconds)

# Ensure timestamp is not too far in the future (allow 1 hour clock skew)
if event_datetime > datetime.now() + timedelta(hours=1):
    raise ValueError("Timestamp too far in the future")
```

#### Player UUIDs

| Rule | Validation | Error Message |
|------|------------|---------------|
| **Format** | Valid UUID v4 with hyphens | `"player_uuid must be a valid UUID"` |
| **Pattern** | `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx` | `"Invalid UUID format"` |
| **Case** | Lowercase preferred | (No error, stored as-is) |

**Example:**
```python
import uuid

try:
    player_uuid_obj = uuid.UUID(player_event.player_uuid)
except ValueError:
    raise HTTPException(status_code=422, detail="Invalid player UUID format")
```

#### Player Names

| Rule | Validation | Error Message |
|------|------------|---------------|
| **Length** | 1-16 characters | `"player_name must be 1-16 characters"` |
| **Characters** | Alphanumeric + underscore | `"player_name contains invalid characters"` |
| **Whitespace** | No leading/trailing spaces | (Auto-trimmed) |

**Minecraft Name Rules:**
- 3-16 characters (older accounts can be 1-2 chars)
- `[a-zA-Z0-9_]+` pattern
- Case-sensitive

#### Hostnames

| Rule | Validation | Error Message |
|------|------------|---------------|
| **Length** | 1-255 characters (if present) | `"hostname too long (max 255)"` |
| **Null** | Can be null/omitted | (No error) |
| **Format** | No strict validation | (Stored as-is) |

**Common Values:**
- `play.example.com` (main domain)
- `youtube.example.com` (YouTube campaign)
- `discord.example.com` (Discord link)
- `null` (direct connection or missing)

#### TPS Values

| Rule | Validation | Error Message |
|------|------------|---------------|
| **Type** | Float/decimal | `"tps must be a number"` |
| **Range** | 0.0 - 20.0 (inclusive) | `"tps must be between 0 and 20"` |
| **Precision** | Up to 2 decimal places recommended | (No error, stored as-is) |

**Interpretation:**
- `20.0`: Perfect (server running at target tickrate)
- `19.0-19.9`: Good (slight lag, imperceptible)
- `15.0-18.9`: Moderate lag (noticeable to players)
- `< 15.0`: Severe lag (unplayable)

#### Player Count

| Rule | Validation | Error Message |
|------|------------|---------------|
| **Type** | Integer | `"player_count must be an integer"` |
| **Range** | ≥ 0 | `"player_count cannot be negative"` |
| **Max** | No upper limit enforced | (No error) |

### Batch-Level Validation

#### EventBatch

| Rule | Validation | Error Message |
|------|------------|---------------|
| **Empty Batch** | At least 1 event (player or performance) | (No error, accepted) |
| **Batch Size** | Max 1000 events per batch (soft limit) | `"Batch too large, split into multiple requests"` |
| **Duplicate Timestamps** | Allowed (multiple events per timestamp) | (No error) |
| **Server ID** | Optional (inferred from API key) | (No error if missing) |

**Backend Handling:**
```python
# ingest.py
@router.post("/ingest")
async def ingest_events(
    batch: EventBatch,
    api_key: APIKey = Depends(get_api_key),
    db: AsyncSession = Depends(get_db)
):
    server_id = api_key.server_id  # Always use API key's server

    # Process all events
    events_created = 0
    for player_event in batch.player_events:
        # Validate and insert
        events_created += 1

    for perf_event in batch.performance_events:
        # Validate and insert
        events_created += 1

    return {"success": True, "events_received": events_created}
```

---

## Error Responses

### 422 Unprocessable Entity (Pydantic Validation)

**Cause:** Event schema doesn't match Pydantic model

**Example Request:**
```json
{
  "batch_timestamp": "not_a_number",  // Should be integer
  "player_events": [
    {
      "timestamp": 1735398000000,
      "event_type": "PLAYER_JOIN",
      "player_uuid": "invalid-uuid",  // Missing hyphens
      "player_name": "ThisNameIsTooLongForMinecraft"  // > 16 chars
    }
  ],
  "performance_events": []
}
```

**Error Response:**
```json
{
  "detail": [
    {
      "type": "int_parsing",
      "loc": ["body", "batch_timestamp"],
      "msg": "Input should be a valid integer",
      "input": "not_a_number"
    },
    {
      "type": "uuid_parsing",
      "loc": ["body", "player_events", 0, "player_uuid"],
      "msg": "Input should be a valid UUID",
      "input": "invalid-uuid"
    },
    {
      "type": "string_too_long",
      "loc": ["body", "player_events", 0, "player_name"],
      "msg": "String should have at most 16 characters",
      "input": "ThisNameIsTooLongForMinecraft"
    }
  ]
}
```

### 401 Unauthorized (Invalid API Key)

**Cause:** Missing or invalid `Authorization: Bearer` token

**Error Response:**
```json
{
  "detail": "Invalid API key"
}
```

### 500 Internal Server Error (Database Failure)

**Cause:** Database connection lost, constraint violation

**Error Response:**
```json
{
  "detail": "Internal server error"
}
```

**Common Causes:**
- PostgreSQL connection timeout
- Foreign key constraint violation (server deleted mid-request)
- Disk space full

---

## Examples

### Complete Request (Multiple Event Types)

**Request:**
```http
POST /v1/ingest HTTP/1.1
Host: api.pivotmc.dev
Authorization: Bearer pvt_uP1JM4bRsoizE-sgBIlVu1458F9VKqmnwebxktAUHAQ
Content-Type: application/json

{
  "batch_timestamp": 1735398000000,
  "player_events": [
    {
      "timestamp": 1735397970000,
      "event_type": "PLAYER_JOIN",
      "player_uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
      "player_name": "Notch",
      "hostname": "play.example.com"
    },
    {
      "timestamp": 1735397985000,
      "event_type": "PLAYER_JOIN",
      "player_uuid": "61699b2e-d327-4a01-9f1e-0ea8c3f06bc6",
      "player_name": "jeb_",
      "hostname": "youtube.example.com"
    },
    {
      "timestamp": 1735397995000,
      "event_type": "PLAYER_QUIT",
      "player_uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
      "player_name": "Notch",
      "hostname": null
    }
  ],
  "performance_events": [
    {
      "timestamp": 1735397970000,
      "tps": 19.8,
      "player_count": 12
    },
    {
      "timestamp": 1735398000000,
      "tps": 19.7,
      "player_count": 13
    }
  ]
}
```

**Response (200 OK):**
```json
{
  "success": true,
  "events_received": 5,
  "server_id": "f86dfb9d-f74e-40dd-8c62-4c53833d1477"
}
```

### Empty Batch (Valid)

**Request:**
```json
{
  "batch_timestamp": 1735398000000,
  "player_events": [],
  "performance_events": []
}
```

**Response (200 OK):**
```json
{
  "success": true,
  "events_received": 0,
  "server_id": "f86dfb9d-f74e-40dd-8c62-4c53833d1477"
}
```

### Minimal Batch (Performance Only)

**Request:**
```json
{
  "batch_timestamp": 1735398000000,
  "player_events": [],
  "performance_events": [
    {
      "timestamp": 1735398000000,
      "tps": 20.0,
      "player_count": 0
    }
  ]
}
```

**Response (200 OK):**
```json
{
  "success": true,
  "events_received": 1,
  "server_id": "f86dfb9d-f74e-40dd-8c62-4c53833d1477"
}
```

### Player Join Without Hostname (Valid)

**Request:**
```json
{
  "batch_timestamp": 1735398000000,
  "player_events": [
    {
      "timestamp": 1735398000000,
      "event_type": "PLAYER_JOIN",
      "player_uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
      "player_name": "Notch",
      "hostname": null
    }
  ],
  "performance_events": []
}
```

**Response (200 OK):**
```json
{
  "success": true,
  "events_received": 1,
  "server_id": "f86dfb9d-f74e-40dd-8c62-4c53833d1477"
}
```

**Note:** Missing hostname is valid for:
- Direct IP connections
- BungeeCord/Velocity servers (hostname not forwarded)
- Players connecting before hostname cache populated

---

## Plugin Implementation

### Java Event Collection Flow

```java
// 1. Event occurs (player joins)
@EventHandler
public void onPlayerJoin(PlayerJoinEvent event) {
    UUID playerId = event.getPlayer().getUniqueId();
    String hostname = hostnameCache.remove(playerId);

    plugin.getEventCollector().addPlayerEvent(
        "PLAYER_JOIN",
        event.getPlayer().getUniqueId().toString(),
        event.getPlayer().getName(),
        hostname
    );
}

// 2. Event stored in memory
public void addPlayerEvent(String eventType, String playerUuid, String playerName, String hostname) {
    JsonObject event = new JsonObject();
    event.addProperty("timestamp", System.currentTimeMillis());
    event.addProperty("event_type", eventType);
    event.addProperty("player_uuid", playerUuid);
    event.addProperty("player_name", playerName);

    if (hostname != null && !hostname.isEmpty()) {
        event.addProperty("hostname", hostname);
    }

    synchronized (playerEvents) {
        playerEvents.add(event);
    }
}

// 3. Every 60 seconds: flush()
public void flush() {
    List<JsonObject> playerBatch;
    List<JsonObject> perfBatch;

    synchronized (playerEvents) {
        playerBatch = new ArrayList<>(playerEvents);
        playerEvents.clear();
    }

    synchronized (performanceEvents) {
        perfBatch = new ArrayList<>(performanceEvents);
        performanceEvents.clear();
    }

    if (playerBatch.isEmpty() && perfBatch.isEmpty()) {
        return;  // Nothing to send
    }

    JsonObject payload = new JsonObject();
    payload.addProperty("batch_timestamp", System.currentTimeMillis());

    JsonArray playerArray = new JsonArray();
    for (JsonObject event : playerBatch) {
        playerArray.add(event);
    }
    payload.add("player_events", playerArray);

    JsonArray perfArray = new JsonArray();
    for (JsonObject event : perfBatch) {
        perfArray.add(event);
    }
    payload.add("performance_events", perfArray);

    sendToAPI(payload.toString());
}

// 4. HTTP POST
private void sendToAPI(String json) {
    String apiEndpoint = plugin.getConfig().getString("pivot.api-endpoint");
    String apiKey = plugin.getConfig().getString("pivot.api-key");

    RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));

    Request request = new Request.Builder()
        .url(apiEndpoint)
        .addHeader("Authorization", "Bearer " + apiKey)
        .addHeader("Content-Type", "application/json")
        .post(body)
        .build();

    httpClient.newCall(request).enqueue(new Callback() {
        @Override
        public void onResponse(Call call, Response response) throws IOException {
            if (response.isSuccessful()) {
                logger.info("Successfully sent events: " + response.body().string());
            } else {
                logger.warning("Failed to send events: " + response.code());
            }
            response.close();
        }

        @Override
        public void onFailure(Call call, IOException e) {
            logger.warning("Failed to send events: " + e.getMessage());
        }
    });
}
```

---

## Backend Processing

### Python Ingest Flow

```python
# api/v1/ingest.py
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession
from datetime import datetime, timezone
import uuid

@router.post("/ingest")
async def ingest_events(
    batch: EventBatch,
    api_key: APIKey = Depends(get_api_key),
    db: AsyncSession = Depends(get_db)
):
    server_id = api_key.server_id
    events_created = 0

    # Process player events (JOIN/QUIT)
    for player_event in batch.player_events:
        event = Event(
            server_id=server_id,
            timestamp=datetime.fromtimestamp(player_event.timestamp / 1000),
            event_type=player_event.event_type,
            player_uuid=uuid.UUID(player_event.player_uuid),
            player_name=player_event.player_name,
            hostname=player_event.hostname
        )
        db.add(event)
        events_created += 1

    # Process performance events (TPS samples)
    for perf_event in batch.performance_events:
        event = Event(
            server_id=server_id,
            timestamp=datetime.fromtimestamp(perf_event.timestamp / 1000),
            event_type="TPS_SAMPLE",
            tps=perf_event.tps,
            player_count=perf_event.player_count
        )
        db.add(event)
        events_created += 1

    # Update server last_event_at
    await db.execute(
        update(Server)
        .where(Server.id == server_id)
        .values(last_event_at=datetime.now(timezone.utc).replace(tzinfo=None))
    )

    # Update API key last_used_at
    await db.execute(
        update(APIKey)
        .where(APIKey.id == api_key.id)
        .values(last_used_at=datetime.now(timezone.utc).replace(tzinfo=None))
    )

    await db.commit()

    return {
        "success": True,
        "events_received": events_created,
        "server_id": str(server_id)
    }
```

---

## Changelog

**v1.0.0 (2025-12-28)**
- Initial event schema documentation
- 3 event types: PLAYER_JOIN, PLAYER_QUIT, TPS_SAMPLE
- Validation rules documented
- Plugin implementation examples

---

**End of Event Schemas Documentation**
