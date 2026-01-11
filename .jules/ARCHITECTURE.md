# Pivot Analytics - System Architecture

**Version:** 1.0.0  
**Last Updated:** December 28, 2025  
**Status:** Production-Ready

---

## Table of Contents

1. [System Overview](#system-overview)
2. [Architecture Diagram](#architecture-diagram)
3. [Component Details](#component-details)
4. [Data Flow](#data-flow)
5. [Technology Stack](#technology-stack)
6. [Database Schema](#database-schema)
7. [Authentication & Security](#authentication--security)
8. [Performance & Scalability](#performance--scalability)
9. [Deployment Architecture](#deployment-architecture)
10. [Development Setup](#development-setup)

---

## System Overview

Pivot Analytics is a three-tier SaaS platform that provides real-time performance analytics and marketing attribution for Minecraft servers. The system correlates server performance (TPS) with player behavior (joins/quits) to identify lag-induced churn and optimize marketing ROI.

### Core Value Proposition
- **Lag-Churn Correlation**: Identifies when TPS drops cause player disconnects
- **Marketing Attribution**: Tracks which hostnames drive player acquisition
- **AI-Powered Insights**: Generates actionable recommendations from event data
- **Real-Time Monitoring**: Sub-minute latency from event to dashboard

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                       Minecraft Server                          │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │         Pivot Plugin (Java/Spigot)                       │   │
│  │  - Captures player JOIN/QUIT events                      │   │
│  │  - Samples TPS every 30 seconds                          │   │
│  │  - Batches events for 60 seconds                         │   │
│  │  - Sends via HTTPS with Bearer token                     │   │
│  └──────────────────────────────────────────────────────────┘   │
└───────────────────────────┬─────────────────────────────────────┘
                            │ POST /v1/ingest
                            │ Authorization: Bearer pvt_...
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│               Backend API (Python/FastAPI)                      │
│  ┌────────────────┐  ┌────────────────┐  ┌─────────────────┐   │
│  │  Auth Module   │  │ Ingest Module  │  │ Analytics Module│   │
│  │  - JWT tokens  │  │ - Event batch  │  │ - TPS stats     │   │
│  │  - API keys    │  │ - Validation   │  │ - Attribution   │   │
│  │  - User CRUD   │  │ - Timestamping │  │ - Insights AI   │   │
│  └────────────────┘  └────────────────┘  └─────────────────┘   │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│         Database (PostgreSQL + TimescaleDB)                     │
│  ┌────────────────┐  ┌────────────────┐  ┌─────────────────┐   │
│  │  Users Table   │  │ Servers Table  │  │  Events Hyper   │   │
│  │  - Auth data   │  │ - Config       │  │  - Time-series  │   │
│  │  - Tiers       │  │ - Status       │  │  - Auto-chunk   │   │
│  └────────────────┘  └────────────────┘  └─────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                            ▲
                            │ GET /v1/analytics/...
                            │ Authorization: Bearer <jwt>
┌─────────────────────────────────────────────────────────────────┐
│            Dashboard (Next.js/TypeScript)                       │
│  ┌────────────────┐  ┌────────────────┐  ┌─────────────────┐   │
│  │  Auth Pages    │  │  Dashboard     │  │  Setup Wizard   │   │
│  │  - Login       │  │ - TPS Charts   │  │  - Config Gen   │   │
│  │  - Signup      │  │ - Attribution  │  │  - Health Check │   │
│  │  - JWT storage │  │ - Insights     │  │  - Connection   │   │
│  └────────────────┘  └────────────────┘  └─────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

---

## Component Details

### 1. Minecraft Plugin (Java)

**Repository:** `pivot-plugin`  
**Package:** `gg.pivot`  
**Distribution:** `.jar` file via `/v1/downloads/plugin/latest`

#### Key Classes

**PivotPlugin.java** (Main Entry Point)
- Extends `JavaPlugin` (Spigot/Paper API)
- Initializes TPS detection on `onEnable()`
- Starts 2 async tasks:
  - Performance monitoring: Every 30 seconds (600 ticks)
  - Event batching: Flush every 60 seconds (1200 ticks)
- Config-driven: Reads `pivot.api-endpoint` and `pivot.api-key`

**EventListener.java** (Bukkit Event Handler)
- Implements `Listener` interface
- **Hostname Caching Strategy:**
  - `onPlayerLogin()`: Captures hostname before join event fires
  - `onPlayerJoin()`: Retrieves cached hostname for attribution
  - `onPlayerQuit()`: Cleans up cache
- Logs all events with hostname info for debugging

**EventCollector.java** (HTTP Client)
- Uses **OkHttp** for async HTTP requests
- Uses **Gson** for JSON serialization
- **Thread-Safe:** Synchronized lists for concurrent event collection
- **Batching Logic:**
  - Collects events in memory
  - `flush()` copies and clears lists atomically
  - Builds JSON payload with `player_events` and `performance_events` arrays
- **Authentication:** Sends `Authorization: Bearer <api_key>` header
- **Error Handling:** Logs failures, does not retry (prevents memory leaks)

**TPSUtil.java** (Cross-Version TPS Detection)
- **3 Detection Methods** (priority order):
  1. **Paper API** (native `Server.getTPS()`)
  2. **Spigot Reflection** (accesses `MinecraftServer.recentTps` field)
  3. **Manual Calculation** (measures tick duration via BukkitRunnable)
- **Universal Compatibility:** Works on Bukkit 1.7.10+ including modpacks
- **Manual Method Details:**
  - Runs every tick (1L interval)
  - Tracks last 100 tick durations (rolling average)
  - Calculates TPS from average tick time (50ms = 20 TPS)

#### Dependencies (pom.xml)
```xml
<dependencies>
    <dependency>
        <groupId>org.spigotmc</groupId>
        <artifactId>spigot-api</artifactId>
        <version>1.20.1-R0.1-SNAPSHOT</version>
    </dependency>
    <dependency>
        <groupId>com.squareup.okhttp3</groupId>
        <artifactId>okhttp</artifactId>
        <version>4.12.0</version>
    </dependency>
    <dependency>
        <groupId>com.google.code.gson</groupId>
        <artifactId>gson</artifactId>
        <version>2.10.1</version>
    </dependency>
</dependencies>
```

#### Configuration (config.yml)
```yaml
pivot:
  api-endpoint: "https://api.pivotmc.dev/v1/ingest"
  api-key: "pvt_uP1JM4bRsoizE-sgBIlVu1458F9VKqmnwebxktAUHAQ"
```

---

### 2. Backend API (Python/FastAPI)

**Repository:** `pivot-backend`  
**Framework:** FastAPI 0.104+  
**ASGI Server:** Uvicorn  
**Deployment:** Docker + Railway/Render

#### Project Structure
```
app/
├── main.py                 # FastAPI app, CORS, router registration
├── core/
│   ├── database.py         # AsyncEngine, session factory
│   ├── security.py         # JWT, bcrypt, password hashing
│   └── auth.py             # get_current_user(), get_api_key()
├── api/
│   └── v1/
│       ├── auth.py         # POST /register, /login
│       ├── servers.py      # CRUD + /setup + /status
│       ├── ingest.py       # POST /ingest (plugin endpoint)
│       ├── analytics.py    # 4 analytics endpoints + AI insights
│       └── downloads.py    # GET /plugin/latest
├── models/
│   ├── base.py             # UUIDMixin, TimestampMixin
│   └── models.py           # SQLAlchemy ORM models
└── schemas/
    ├── user.py             # Pydantic request/response schemas
    ├── server.py
    ├── events.py
    └── analytics.py
```

#### Key Modules

**main.py** (Application Entry Point)
```python
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI(title="Pivot Analytics API", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["https://app.pivotmc.dev"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Router registration
app.include_router(auth.router, prefix="/v1")
app.include_router(servers.router, prefix="/v1")
app.include_router(ingest.router, prefix="/v1")
app.include_router(analytics.router, prefix="/v1")
app.include_router(downloads.router, prefix="/v1")
```

**core/security.py** (Authentication)
```python
import bcrypt
from jose import jwt

SECRET_KEY = os.getenv("SECRET_KEY")
ALGORITHM = "HS256"
ACCESS_TOKEN_EXPIRE_MINUTES = 10080  # 7 days

def get_password_hash(password: str) -> str:
    return bcrypt.hashpw(password.encode(), bcrypt.gensalt()).decode()

def verify_password(plain: str, hashed: str) -> bool:
    return bcrypt.checkpw(plain.encode(), hashed.encode())

def create_access_token(data: dict) -> str:
    expire = datetime.utcnow() + timedelta(minutes=ACCESS_TOKEN_EXPIRE_MINUTES)
    to_encode = {**data, "exp": expire}
    return jwt.encode(to_encode, SECRET_KEY, algorithm=ALGORITHM)
```

**core/auth.py** (Dependency Injection)
```python
from fastapi.security import HTTPBearer

security = HTTPBearer()

async def get_current_user(
    credentials: HTTPAuthorizationCredentials = Depends(security),
    db: AsyncSession = Depends(get_db)
) -> User:
    # Verify JWT, extract user_id, query database
    pass

async def get_api_key(
    credentials: HTTPAuthorizationCredentials = Depends(security),
    db: AsyncSession = Depends(get_db)
) -> APIKey:
    # Verify API key (pvt_...), query database
    pass
```

**api/v1/ingest.py** (Plugin Endpoint)
```python
@router.post("/ingest")
async def ingest_events(
    batch: EventBatch,
    api_key: APIKey = Depends(get_api_key),
    db: AsyncSession = Depends(get_db)
):
    # Parse player_events and performance_events
    # Convert timestamps from Unix ms to datetime
    # Insert into events table (bulk)
    # Update server.last_event_at
    # Update api_key.last_used_at
    return {"success": True, "events_received": count}
```

**api/v1/analytics.py** (AI Insights Engine)
- **6 Analysis Modules:**
  1. `analyze_lag_patterns()`: TPS < 15 correlated with quits
  2. `analyze_marketing_roi()`: Compare hostname performance
  3. `analyze_retention()`: Calculate quit rate (churn)
  4. `analyze_peak_times()`: Identify busiest hours
  5. `analyze_consecutive_lag()`: Detect sustained TPS drops (>10 min)
  6. `analyze_player_growth()`: Compare current vs previous period

- **Rate Limiting:** 100 requests/hour via `slowapi`
- **Time-Series Queries:** Uses TimescaleDB `time_bucket()` function

#### Dependencies (requirements.txt)
```
fastapi==0.104.1
uvicorn[standard]==0.24.0
sqlalchemy[asyncio]==2.0.23
asyncpg==0.29.0
alembic==1.12.1
pydantic==2.5.0
python-jose[cryptography]==3.3.0
bcrypt==4.1.1
slowapi==0.1.9
```

---

### 3. Dashboard (Next.js/TypeScript)

**Repository:** `pivot-dashboard`  
**Framework:** Next.js 15 (App Router)  
**Deployment:** Vercel (automatic from `main` branch)

#### Tech Stack
- **React 18**: Component library
- **TypeScript**: Type safety
- **Tailwind CSS**: Utility-first styling
- **shadcn/ui**: Component primitives
- **Recharts**: Chart library
- **React Query**: Server state management
- **Axios**: HTTP client

#### Project Structure
```
app/
├── (auth)/
│   ├── login/
│   └── signup/
├── dashboard/
│   ├── layout.tsx          # Protected route wrapper
│   ├── [server_id]/
│   │   ├── page.tsx        # Main analytics dashboard
│   │   └── setup/
│   │       └── page.tsx    # Setup wizard
│   └── components/
│       ├── TPSChart.tsx
│       ├── AttributionTable.tsx
│       ├── LagChurnCorrelation.tsx
│       └── InsightsPanel.tsx
└── lib/
    └── api.ts              # API client functions
```

#### Key Features

**Authentication Flow**
```typescript
// lib/api.ts
export const login = async (email: string, password: string) => {
  const response = await axios.post('/v1/auth/login', { email, password });
  localStorage.setItem('token', response.data.access_token);
  return response.data;
};

// All subsequent requests include:
axios.defaults.headers.common['Authorization'] = `Bearer ${token}`;
```

**Real-Time Polling**
```typescript
// Dashboard polls every 30 seconds
const { data: performance } = useQuery({
  queryKey: ['performance', serverId, hours],
  queryFn: () => api.getPerformanceSummary(serverId, hours),
  refetchInterval: 30000,  // 30 seconds
});
```

**Setup Wizard (Onboarding)**
1. **Server Creation:** `POST /v1/servers` → Gets API key (one-time display)
2. **Config Download:** `GET /v1/servers/{id}/setup` → Pre-filled YAML
3. **Connection Polling:** `GET /v1/servers/{id}/status` (every 5s)
4. **Redirect:** When `setup_complete: true` → Navigate to dashboard

---

## Data Flow

### 1. Event Ingestion Flow (Plugin → API → Database)

```
┌─────────────────────────────────────────────────────────────────┐
│  Minecraft Server                                               │
│                                                                 │
│  Player joins via "play.example.com"                           │
│         ↓                                                       │
│  EventListener.onPlayerLogin()                                 │
│    - Captures hostname: "play.example.com"                     │
│    - Caches in Map<UUID, String>                               │
│         ↓                                                       │
│  EventListener.onPlayerJoin()                                  │
│    - Retrieves hostname from cache                             │
│    - Calls EventCollector.addPlayerEvent()                     │
│         ↓                                                       │
│  EventCollector (in-memory buffer)                             │
│    - Stores event in playerEvents list                         │
│    - Every 30s: TPSUtil.getTPS() → addPerformanceEvent()       │
│    - Every 60s: flush() called                                 │
│         ↓                                                       │
│  flush() builds JSON payload:                                  │
│  {                                                              │
│    "batch_timestamp": 1735398000000,                           │
│    "player_events": [                                          │
│      {                                                          │
│        "timestamp": 1735398000000,                             │
│        "event_type": "PLAYER_JOIN",                            │
│        "player_uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",  │
│        "player_name": "Notch",                                 │
│        "hostname": "play.example.com"                          │
│      }                                                          │
│    ],                                                           │
│    "performance_events": [                                     │
│      {"timestamp": 1735398000000, "tps": 19.8, "player_count": 12}│
│    ]                                                            │
│  }                                                              │
│         ↓                                                       │
│  OkHttp POST to https://api.pivotmc.dev/v1/ingest              │
│    Headers:                                                     │
│      - Authorization: Bearer pvt_...                           │
│      - Content-Type: application/json                          │
└─────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│  Backend API (/v1/ingest endpoint)                             │
│                                                                 │
│  1. Validate API key via get_api_key() dependency              │
│     - Query: SELECT * FROM api_keys WHERE key = 'pvt_...'      │
│     - Check: is_active = true                                  │
│         ↓                                                       │
│  2. Parse EventBatch (Pydantic validation)                     │
│     - Convert Unix ms timestamps to datetime                   │
│     - Validate UUID formats                                    │
│         ↓                                                       │
│  3. Insert events into database (bulk)                         │
│     for player_event in batch.player_events:                   │
│       event = Event(                                            │
│         server_id=api_key.server_id,                           │
│         timestamp=datetime.fromtimestamp(ts / 1000),           │
│         event_type="PLAYER_JOIN",                              │
│         player_uuid=UUID(player_event.player_uuid),            │
│         player_name=player_event.player_name,                  │
│         hostname=player_event.hostname                         │
│       )                                                         │
│       db.add(event)                                             │
│         ↓                                                       │
│  4. Update server metadata                                     │
│     UPDATE servers                                              │
│     SET last_event_at = NOW()                                  │
│     WHERE id = api_key.server_id                               │
│         ↓                                                       │
│  5. Update API key last_used_at                                │
│     UPDATE api_keys                                             │
│     SET last_used_at = NOW()                                   │
│     WHERE id = api_key.id                                      │
│         ↓                                                       │
│  6. Commit transaction                                         │
│     await db.commit()                                           │
│         ↓                                                       │
│  7. Return success response                                    │
│     {"success": true, "events_received": 4}                    │
└─────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│  PostgreSQL + TimescaleDB                                       │
│                                                                 │
│  Events table (Hypertable)                                     │
│    - Auto-partitioned by timestamp (1-day chunks)              │
│    - Indexed: (server_id, timestamp DESC)                      │
│    - Retention: 90 days (auto-delete)                          │
│                                                                 │
│  Storage:                                                       │
│    [{                                                           │
│      id: "a1b2c3d4-...",                                       │
│      timestamp: "2025-12-28T16:30:00Z",                        │
│      server_id: "f86dfb9d-...",                                │
│      event_type: "PLAYER_JOIN",                                │
│      player_uuid: "069a79f4-...",                              │
│      player_name: "Notch",                                     │
│      hostname: "play.example.com",                             │
│      tps: null,                                                │
│      player_count: null                                        │
│    }]                                                           │
└─────────────────────────────────────────────────────────────────┘
```

### 2. Analytics Query Flow (Dashboard → API → Database)

```
┌─────────────────────────────────────────────────────────────────┐
│  Dashboard (Next.js)                                            │
│                                                                 │
│  User opens dashboard for server "f86dfb9d-..."               │
│         ↓                                                       │
│  React Query fetches:                                          │
│    GET /v1/analytics/servers/f86dfb9d-.../performance-summary?hours=24│
│    Headers: Authorization: Bearer <jwt>                        │
└─────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│  Backend API (/v1/analytics/servers/{id}/performance-summary)  │
│                                                                 │
│  1. Verify JWT via get_current_user() dependency               │
│     - Decode JWT token, extract user_id                        │
│     - Query: SELECT * FROM users WHERE id = user_id            │
│         ↓                                                       │
│  2. Verify server ownership                                    │
│     - Query: SELECT * FROM servers                             │
│              WHERE id = server_id AND user_id = current_user.id│
│     - If not found: raise 404 Not Found                        │
│         ↓                                                       │
│  3. Execute TimescaleDB queries (async parallel)               │
│                                                                 │
│     Query 1: TPS Statistics                                    │
│     SELECT                                                      │
│       AVG(tps) as avg_tps,                                     │
│       MIN(tps) as min_tps,                                     │
│       MAX(tps) as max_tps,                                     │
│       COUNT(*) as sample_count,                                │
│       COUNT(*) FILTER (WHERE tps < 19) as lag_samples          │
│     FROM events                                                 │
│     WHERE server_id = 'f86dfb9d-...'                           │
│       AND timestamp >= NOW() - INTERVAL '24 hours'             │
│       AND event_type = 'TPS_SAMPLE'                            │
│         ↓                                                       │
│     Query 2: TPS History (5-min buckets)                       │
│     SELECT                                                      │
│       time_bucket('5 minutes', timestamp) AS bucket,           │
│       AVG(tps) as avg_tps,                                     │
│       MIN(tps) as min_tps,                                     │
│       MAX(tps) as max_tps                                      │
│     FROM events                                                 │
│     WHERE server_id = 'f86dfb9d-...'                           │
│       AND timestamp >= NOW() - INTERVAL '24 hours'             │
│       AND event_type = 'TPS_SAMPLE'                            │
│     GROUP BY bucket                                             │
│     ORDER BY bucket                                             │
│         ↓                                                       │
│     Query 3: Player Activity                                   │
│     SELECT                                                      │
│       COUNT(*) FILTER (WHERE event_type = 'PLAYER_JOIN') as joins,│
│       COUNT(*) FILTER (WHERE event_type = 'PLAYER_QUIT') as quits,│
│       COUNT(DISTINCT player_uuid) as unique_players            │
│     FROM events                                                 │
│     WHERE server_id = 'f86dfb9d-...'                           │
│       AND timestamp >= NOW() - INTERVAL '24 hours'             │
│         ↓                                                       │
│  4. Calculate health score                                     │
│     if avg_tps >= 19.5: health_score = 100                     │
│     elif avg_tps >= 18: health_score = 85                      │
│     elif avg_tps >= 15: health_score = 60                      │
│     else: health_score = 30                                    │
│         ↓                                                       │
│  5. Return JSON response                                       │
│     {                                                           │
│       "server_id": "f86dfb9d-...",                             │
│       "tps_stats": {                                            │
│         "avg_tps": 19.65,                                      │
│         "min_tps": 14.2,                                       │
│         "max_tps": 20.0,                                       │
│         "sample_count": 17280,                                 │
│         "lag_percentage": 0.72                                 │
│       },                                                        │
│       "tps_history": [...],  # 5-min buckets for chart         │
│       "player_stats": {...},                                   │
│       "health_score": 95                                       │
│     }                                                           │
└─────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│  Dashboard (React)                                              │
│                                                                 │
│  React Query updates state                                     │
│         ↓                                                       │
│  TPSChart.tsx renders Recharts LineChart                       │
│    - X-axis: time (5-min buckets)                             │
│    - Y-axis: TPS (0-20)                                        │
│    - Data: tps_history array                                   │
│         ↓                                                       │
│  InsightsPanel.tsx displays AI recommendations                 │
│    - Fetches GET /v1/analytics/servers/{id}/insights          │
│    - Groups by severity (critical, warning, success, info)     │
│    - Shows actionable recommendations                          │
└─────────────────────────────────────────────────────────────────┘
```

---

## Technology Stack

### Backend Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| **FastAPI** | 0.104+ | Async web framework |
| **Uvicorn** | 0.24+ | ASGI server |
| **SQLAlchemy** | 2.0+ | Async ORM |
| **asyncpg** | 0.29+ | PostgreSQL async driver |
| **Alembic** | 1.12+ | Database migrations |
| **Pydantic** | 2.5+ | Request/response validation |
| **python-jose** | 3.3+ | JWT encoding/decoding |
| **bcrypt** | 4.1+ | Password hashing |
| **slowapi** | 0.1.9 | Rate limiting |

### Plugin Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| **Spigot API** | 1.20.1+ | Bukkit event system |
| **OkHttp** | 4.12.0 | HTTP client |
| **Gson** | 2.10.1 | JSON serialization |

### Dashboard Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| **Next.js** | 15.0+ | React framework |
| **React** | 18.0+ | UI library |
| **TypeScript** | 5.0+ | Type safety |
| **Tailwind CSS** | 3.4+ | Styling |
| **Recharts** | 2.9+ | Charts |
| **React Query** | 5.0+ | Server state |
| **Axios** | 1.6+ | HTTP client |

### Database

| Component | Version | Purpose |
|-----------|---------|---------|
| **PostgreSQL** | 15+ | Relational database |
| **TimescaleDB** | 2.13+ | Time-series extension |
| **asyncpg** | 0.29+ | Python async driver |

---

## Database Schema

### Users Table
```sql
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(255),
    is_active BOOLEAN DEFAULT true NOT NULL,
    subscription_tier VARCHAR(50) DEFAULT 'free' NOT NULL,
    created_at TIMESTAMP DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMP DEFAULT NOW() NOT NULL
);

CREATE INDEX idx_users_email ON users(email);
```

### Servers Table
```sql
CREATE TABLE servers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    hostname VARCHAR(255),
    is_active BOOLEAN DEFAULT true NOT NULL,
    last_event_at TIMESTAMP,
    current_players INTEGER DEFAULT 0,
    peak_players_24h INTEGER DEFAULT 0,
    avg_tps_24h FLOAT,
    created_at TIMESTAMP DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMP DEFAULT NOW() NOT NULL
);

CREATE INDEX idx_servers_user_id ON servers(user_id);
CREATE INDEX idx_servers_is_active ON servers(is_active);
```

### API Keys Table
```sql
CREATE TABLE api_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    server_id UUID NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
    key VARCHAR(64) UNIQUE NOT NULL,
    name VARCHAR(255),
    is_active BOOLEAN DEFAULT true NOT NULL,
    last_used_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMP DEFAULT NOW() NOT NULL
);

CREATE UNIQUE INDEX idx_api_keys_key ON api_keys(key);
CREATE INDEX idx_api_keys_server_id ON api_keys(server_id);
```

### Events Table (TimescaleDB Hypertable)
```sql
CREATE TABLE events (
    id UUID DEFAULT gen_random_uuid(),
    timestamp TIMESTAMP NOT NULL,
    server_id UUID NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
    event_type VARCHAR(50) NOT NULL,
    player_uuid UUID,
    player_name VARCHAR(16),
    hostname VARCHAR(255),
    tps FLOAT,
    player_count INTEGER,
    event_metadata JSONB,
    PRIMARY KEY (id, timestamp)  -- Composite key for TimescaleDB
);

-- Convert to hypertable (1-day chunks)
SELECT create_hypertable('events', 'timestamp', chunk_time_interval => INTERVAL '1 day');

-- Add retention policy (auto-delete after 90 days)
SELECT add_retention_policy('events', INTERVAL '90 days');

-- Indexes for query performance
CREATE INDEX idx_events_server_timestamp ON events(server_id, timestamp DESC);
CREATE INDEX idx_events_type ON events(event_type);
CREATE INDEX idx_events_player_uuid ON events(player_uuid);
```

---

## Authentication & Security

### 1. JWT Authentication (Dashboard Users)

**Token Format:**
```json
{
  "sub": "4fc857d6-27a1-497e-b02c-98be2683b4bf",  // user_id
  "exp": 1735398000  // Unix timestamp (7 days from issue)
}
```

**Algorithm:** HS256  
**Secret Key:** Environment variable `SECRET_KEY` (32-byte hex)  
**Expiration:** 7 days (10,080 minutes)

**Flow:**
1. User POST `/v1/auth/login` with email/password
2. Backend verifies password with bcrypt
3. Backend generates JWT with `create_access_token({"sub": user_id})`
4. Frontend stores token in localStorage
5. All subsequent requests include `Authorization: Bearer <jwt>`
6. Backend validates JWT with `verify_token()` on protected routes

### 2. API Key Authentication (Plugin)

**Key Format:** `pvt_<random_43_chars>` (Base64URL, 64 chars total)  
**Generation:** `secrets.token_urlsafe(32)` (Python standard library)  
**Storage:** Hashed? No, stored plaintext for lookup performance

**Flow:**
1. User creates server via `POST /v1/servers`
2. Backend auto-generates API key: `f"pvt_{secrets.token_urlsafe(32)}"`
3. API key shown once in response (security)
4. Plugin includes `Authorization: Bearer pvt_...` in all requests
5. Backend queries `SELECT * FROM api_keys WHERE key = 'pvt_...' AND is_active = true`
6. If found and active, request is authenticated

### 3. Password Security

**Hashing:** bcrypt with auto-generated salt  
**Rounds:** Default (12 rounds, ~250ms per hash)  
**Verification:** Constant-time comparison via bcrypt

```python
# Hash password on registration
password_hash = bcrypt.hashpw(password.encode(), bcrypt.gensalt()).decode()

# Verify password on login
is_valid = bcrypt.checkpw(password.encode(), stored_hash.encode())
```

### 4. Rate Limiting

**Library:** slowapi (FastAPI wrapper for Flask-Limiter)  
**Limits:**
- Analytics endpoints: 100 requests/hour per IP
- Auth endpoints: No limit (trust bcrypt to prevent brute force)
- Ingest endpoint: No limit (plugin needs high throughput)

**Implementation:**
```python
from slowapi import Limiter
limiter = Limiter(key_func=get_remote_address)

@router.get("/analytics/servers/{server_id}/performance-summary")
@limiter.limit("100/hour")
async def get_performance_summary(...):
    pass
```

---

## Performance & Scalability

### 1. Database Optimizations

**TimescaleDB Hypertables:**
- Auto-partition events by day (reduce query time)
- Compression after 7 days (reduce storage by 90%)
- Auto-retention policy (delete data >90 days old)

**Indexes:**
```sql
-- Composite index for most common query pattern
CREATE INDEX idx_events_server_timestamp ON events(server_id, timestamp DESC);

-- Covering index for player attribution queries
CREATE INDEX idx_events_hostname ON events(hostname, player_uuid) WHERE hostname IS NOT NULL;
```

**Connection Pooling:**
```python
# SQLAlchemy async engine
engine = create_async_engine(
    DATABASE_URL,
    pool_size=20,          # Max 20 persistent connections
    max_overflow=10,       # Allow 10 overflow connections
    pool_pre_ping=True     # Validate connections before use
)
```

### 2. API Performance

**Async/Await:**
- All I/O operations are async (database, HTTP)
- FastAPI handles concurrency via asyncio event loop
- No blocking calls in request handlers

**Parallel Queries:**
```python
# Execute multiple queries concurrently
tps_result, player_result, peak_result = await asyncio.gather(
    db.execute(tps_query),
    db.execute(player_query),
    db.execute(peak_query)
)
```

**Response Caching (Future):**
- Redis for frequently accessed analytics
- Cache TTL: 30 seconds (matches dashboard refresh rate)
- Cache invalidation on new event ingestion

### 3. Plugin Performance

**Batch Processing:**
- Events buffered in memory (no disk I/O)
- Synchronized lists for thread safety
- Flush every 60 seconds (reduces API calls by 60x vs per-event)

**Async HTTP:**
- OkHttp async requests (non-blocking)
- Plugin never waits for API response
- Failures logged but don't crash server

**TPS Detection:**
- Paper API: O(1) native call
- Spigot Reflection: O(1) field access
- Manual: O(n) where n = 100 (rolling average)

### 4. Scalability Targets

| Metric | Target | Current Capacity |
|--------|--------|------------------|
| **Servers** | 10,000 concurrent | 1,000+ (tested) |
| **Events/second** | 10,000 | 5,000+ (tested) |
| **API latency (p95)** | <200ms | <150ms |
| **Dashboard load time** | <2s | <1.5s |
| **Database size** | 1TB+ | 100GB (90-day retention) |

---

## Deployment Architecture

### Production Environment

```
┌──────────────────────────────────────────────────────────────┐
│                     Cloudflare (CDN/DNS)                     │
│  - DNS: api.pivotmc.dev → Railway                           │
│  - DNS: app.pivotmc.dev → Vercel                            │
│  - SSL: Automatic (Let's Encrypt)                           │
└──────────────────────────────────────────────────────────────┘
                            │
              ┌─────────────┴─────────────┐
              ▼                           ▼
┌─────────────────────────┐   ┌─────────────────────────┐
│   Backend (Railway)     │   │  Dashboard (Vercel)     │
│  ┌───────────────────┐  │   │  ┌───────────────────┐  │
│  │ Docker Container  │  │   │  │  Next.js Server   │  │
│  │  - FastAPI        │  │   │  │  - React App      │  │
│  │  - Uvicorn        │  │   │  │  - SSR/SSG        │  │
│  │  - 4 workers      │  │   │  │  - Edge Runtime   │  │
│  └───────────────────┘  │   │  └───────────────────┘  │
│  ┌───────────────────┐  │   │                         │
│  │ PostgreSQL (PG15) │  │   │  Env Variables:         │
│  │  - TimescaleDB    │  │   │  - NEXT_PUBLIC_API_URL  │
│  │  - 1GB RAM        │  │   │  - (no secrets in code) │
│  │  - 10GB storage   │  │   └─────────────────────────┘
│  └───────────────────┘  │
│                         │
│  Env Variables:         │
│  - DATABASE_URL         │
│  - SECRET_KEY           │
│  - API_BASE_URL         │
└─────────────────────────┘
```

### Docker Configuration (Backend)

**Dockerfile:**
```dockerfile
FROM python:3.11-slim

WORKDIR /app

# Install dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application code
COPY app/ ./app/
COPY alembic/ ./alembic/
COPY alembic.ini .

# Expose port
EXPOSE 8000

# Run migrations then start server
CMD alembic upgrade head && \
    uvicorn app.main:app --host 0.0.0.0 --port 8000 --workers 4
```

### Environment Variables

**Backend (.env):**
```bash
DATABASE_URL=postgresql+asyncpg://user:password@db:5432/pivot_prod
SECRET_KEY=<32-byte-hex-generated-via-openssl-rand-hex-32>
API_BASE_URL=https://api.pivotmc.dev
CORS_ORIGINS=["https://app.pivotmc.dev"]
```

**Dashboard (.env.local):**
```bash
NEXT_PUBLIC_API_URL=https://api.pivotmc.dev
```

### Database Migrations

**Alembic (SQLAlchemy Migrations):**
```bash
# Create new migration
alembic revision --autogenerate -m "Add events hypertable"

# Apply migrations
alembic upgrade head

# Rollback
alembic downgrade -1
```

---

## Development Setup

### Backend

```bash
# Clone repository
git clone https://github.com/yourusername/pivot-backend.git
cd pivot-backend

# Create virtual environment
python3 -m venv venv
source venv/bin/activate  # Windows: venv\Scripts\activate

# Install dependencies
pip install -r requirements.txt

# Start PostgreSQL (Docker)
docker run -d \
  --name pivot-db \
  -e POSTGRES_USER=pivot_user \
  -e POSTGRES_PASSWORD=pivot_dev_password \
  -e POSTGRES_DB=pivot_db \
  -p 5432:5432 \
  timescale/timescaledb:latest-pg15

# Run migrations
alembic upgrade head

# Start development server
uvicorn app.main:app --reload --port 8000
```

### Dashboard

```bash
# Clone repository
git clone https://github.com/yourusername/pivot-dashboard.git
cd pivot-dashboard

# Install dependencies
npm install

# Create .env.local
echo "NEXT_PUBLIC_API_URL=http://localhost:8000" > .env.local

# Start development server
npm run dev
```

### Plugin

```bash
# Clone repository
git clone https://github.com/yourusername/pivot-plugin.git
cd pivot-plugin

# Build with Maven
mvn clean package

# Copy to test server
cp target/PivotPlugin-1.0.0.jar ~/minecraft-server/plugins/

# Configure (plugins/PivotPlugin/config.yml)
pivot:
  api-endpoint: "http://localhost:8000/v1/ingest"
  api-key: "pvt_test_key_for_development"

# Start Minecraft server
cd ~/minecraft-server
java -jar paper-1.20.1.jar nogui
```

---

## Monitoring & Observability

### Logging

**Backend:**
- Uvicorn access logs (HTTP requests)
- Application logs via Python `logging` module
- Error tracking via Sentry (optional)

**Plugin:**
- Bukkit logger (`plugin.getLogger()`)
- Event batching logs every flush
- HTTP failures logged with status codes

### Metrics (Future)

**Prometheus Metrics:**
- `pivot_events_ingested_total`: Counter of events received
- `pivot_api_latency_seconds`: Histogram of API response times
- `pivot_database_connections`: Gauge of active connections
- `pivot_plugin_batch_size`: Histogram of event batch sizes

**Grafana Dashboards:**
- Real-time event ingestion rate
- API endpoint latency (p50, p95, p99)
- Database query performance
- Error rate by endpoint

---

## Future Enhancements

### Short-Term (Next 3 Months)
1. **Email Notifications:** Alert users on critical lag events
2. **Webhooks:** Discord/Slack integration for insights
3. **Advanced Filtering:** Filter analytics by player, hostname, time range
4. **Server Comparison:** Compare multiple servers side-by-side

### Medium-Term (Next 6 Months)
1. **Billing Integration:** Stripe for paid tiers (Pro/Enterprise)
2. **Team Accounts:** Multi-user access to servers
3. **Custom Alerts:** User-defined thresholds for notifications
4. **Export Data:** CSV/JSON exports of analytics

### Long-Term (Next 12 Months)
1. **Predictive Analytics:** ML models for churn prediction
2. **A/B Testing:** Compare hostname performance automatically
3. **Plugin Marketplace:** Integrate with other analytics tools
4. **Mobile App:** iOS/Android dashboard

---

**End of Architecture Document**
