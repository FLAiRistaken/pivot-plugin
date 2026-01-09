# Pivot Analytics API Contract

**Version:** 1.1.0
**Base URL:** `https://api.pivotmc.dev/v1`
**Environment:** Production
**Last Updated:** January 7, 2026

---

## Table of Contents

1. [Authentication](#authentication)
2. [Rate Limiting](#rate-limiting)
3. [Error Response Format](#error-response-format)
4. [Endpoints](#endpoints)
   - [Authentication](#authentication-endpoints)
   - [Server Management](#server-management-endpoints)
   - [Event Ingestion](#event-ingestion-endpoints)
   - [Analytics](#analytics-endpoints)
   - [Downloads](#downloads-endpoints)
5. [Data Types & Models](#data-types--models)

---

## Authentication

### JWT Authentication (Dashboard/Web)

Used by web dashboard and authenticated users.

**Header:**
```
Authorization: Bearer <jwt_token>
```

**Token Format:**
- Algorithm: HS256
- Expiration: 7 days (10,080 minutes)
- Payload: `{"sub": "<user_id>"}`

**How to Obtain:**
1. Register via `POST /v1/auth/register`
2. Login via `POST /v1/auth/login`
3. Include `access_token` from response in all subsequent requests

---

### API Key Authentication (Plugin)

Used by Minecraft plugin for event ingestion.

**Header:**
```
X-API-Key: pvt_<random_token>
```

**Key Format:**
- Prefix: `pvt_`
- Length: 64 characters
- Auto-generated on server creation
- Scoped to single server
- Expires after 1 year (can be rotated)

---

## Rate Limiting

| Endpoint Type | Limit | Window |
|--------------|-------|--------|
| Authentication | Unlimited | - |
| Event Ingestion | Unlimited | - |
| Analytics Queries | 100 requests | 1 hour |
| Server Management | Unlimited | - |

**Rate Limit Headers:**
```
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 95
X-RateLimit-Reset: 1735398000
```

**Rate Limit Exceeded Response:**
```json
{
  "error": {
    "code": "RATE_LIMIT_EXCEEDED",
    "message": "Rate limit exceeded. Try again in 3600 seconds."
  }
}
```

---

## Error Response Format

All errors follow a standardized format with error codes:

```json
{
  "error": {
    "code": "ERROR_CODE",
    "message": "Human-readable error message",
    "details": null
  }
}
```

**Common Error Codes:**

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `VALIDATION_ERROR` | 400 | Invalid request data or parameters |
| `UNAUTHORIZED` | 401 | Missing or invalid authentication |
| `FORBIDDEN` | 403 | Valid auth but insufficient permissions |
| `SERVER_NOT_FOUND` | 404 | Server resource doesn't exist |
| `USER_NOT_FOUND` | 404 | User resource doesn't exist |
| `RATE_LIMIT_EXCEEDED` | 429 | Too many requests |
| `INTERNAL_ERROR` | 500 | Server-side error |

**Validation Error with Details:**
```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Request validation failed",
    "details": [
      {
        "field": "email",
        "message": "value is not a valid email address"
      },
      {
        "field": "password",
        "message": "ensure this value has at least 8 characters"
      }
    ]
  }
}
```

**Common HTTP Status Codes:**

| Code | Meaning | Usage |
|------|---------|-------|
| 200 | OK | Successful GET/POST |
| 201 | Created | Resource created successfully |
| 204 | No Content | Successful DELETE (no response body) |
| 400 | Bad Request | Invalid input data |
| 401 | Unauthorized | Missing/invalid authentication |
| 403 | Forbidden | Valid auth but insufficient permissions |
| 404 | Not Found | Resource doesn't exist |
| 422 | Unprocessable Entity | Validation error (Pydantic) |
| 429 | Too Many Requests | Rate limit exceeded |
| 500 | Internal Server Error | Server-side error |

---

## Endpoints

### Authentication Endpoints

#### `POST /v1/auth/register`

Register a new user account.

**Request Body:**
```json
{
  "email": "user@example.com",
  "password": "securepassword123",
  "full_name": "John Doe"
}
```

**Field Validation:**
- `email`: Must be valid email format
- `password`: Minimum 8 characters
- `full_name`: Optional

**Success Response (201 Created):**
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "token_type": "bearer",
  "user": {
    "id": "4fc857d6-27a1-497e-b02c-98be2683b4bf",
    "email": "user@example.com",
    "full_name": "John Doe",
    "is_active": true,
    "subscription_tier": "free",
    "created_at": "2026-01-07T10:00:00Z"
  }
}
```

**Error Responses:**
- `400 Bad Request`: Email already registered
  ```json
  {
    "error": {
      "code": "VALIDATION_ERROR",
      "message": "Email already registered"
    }
  }
  ```

---

#### `POST /v1/auth/login`

Authenticate existing user.

**Request Body:**
```json
{
  "email": "user@example.com",
  "password": "securepassword123"
}
```

**Success Response (200 OK):**
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "token_type": "bearer",
  "user": {
    "id": "4fc857d6-27a1-497e-b02c-98be2683b4bf",
    "email": "user@example.com",
    "full_name": "John Doe",
    "is_active": true,
    "subscription_tier": "free",
    "created_at": "2026-01-07T10:00:00Z"
  }
}
```

**Error Responses:**
- `401 Unauthorized`: Invalid credentials
  ```json
  {
    "error": {
      "code": "UNAUTHORIZED",
      "message": "Incorrect email or password"
    }
  }
  ```
- `403 Forbidden`: Account disabled
  ```json
  {
    "error": {
      "code": "FORBIDDEN",
      "message": "Account is disabled"
    }
  }
  ```

---

### Server Management Endpoints

#### `POST /v1/servers`

Create a new Minecraft server and generate API key.

**Authentication:** Required (JWT)

**Request Body:**
```json
{
  "name": "My Survival Server",
  "description": "Main survival world",
  "hostname": "play.example.com"
}
```

**Field Validation:**
- `name`: Required, 1-255 characters
- `description`: Optional
- `hostname`: Optional

**Success Response (201 Created):**
```json
{
  "server": {
    "id": "f86dfb9d-f74e-40dd-8c62-4c53833d1477",
    "user_id": "4fc857d6-27a1-497e-b02c-98be2683b4bf",
    "name": "My Survival Server",
    "description": "Main survival world",
    "hostname": "play.example.com",
    "is_active": true,
    "last_event_at": null,
    "created_at": "2026-01-07T10:00:00Z"
  },
  "api_key": {
    "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "server_id": "f86dfb9d-f74e-40dd-8c62-4c53833d1477",
    "key": "pvt_uP1JM4bRsoizE-sgBIlVu1458F9VKqmnwebxktAUHAQ",
    "name": "My Survival Server API Key",
    "is_active": true,
    "expires_at": "2027-01-07T10:00:00Z",
    "created_at": "2026-01-07T10:00:00Z",
    "last_used_at": null
  }
}
```

**⚠️ Important:** The `api_key.key` is only shown once. Store it securely!

---

#### `GET /v1/servers`

List all **active** servers for authenticated user (excludes deleted servers).

**Authentication:** Required (JWT)

**Behavior:**
- Returns only servers where `is_deleted=false`
- Soft-deleted servers are hidden from this list
- Sorted by `created_at` descending (newest first)

**Success Response (200 OK):**
```json
[
  {
    "id": "f86dfb9d-f74e-40dd-8c62-4c53833d1477",
    "user_id": "4fc857d6-27a1-497e-b02c-98be2683b4bf",
    "name": "My Survival Server",
    "description": "Main survival world",
    "hostname": "play.example.com",
    "is_active": true,
    "last_event_at": "2026-01-07T10:00:00Z",
    "current_players": 0,
    "peak_players_24h": 0,
    "avg_tps_24h": null,
    "created_at": "2026-01-07T10:00:00Z"
  }
]
```
**Note:** `current_players`, `peak_players_24h`, and `avg_tps_24h` are placeholders for future computed metrics. Current implementation always returns `0` or `null`.

---

#### `GET /v1/servers/{server_id}`

Get details for specific server.

**Authentication:** Required (JWT)

**Path Parameters:**
- `server_id` (UUID): Server identifier

**Success Response (200 OK):**
```json
{
  "id": "f86dfb9d-f74e-40dd-8c62-4c53833d1477",
  "user_id": "4fc857d6-27a1-497e-b02c-98be2683b4bf",
  "name": "My Survival Server",
  "description": "Main survival world",
  "hostname": "play.example.com",
  "is_active": true,
  "last_event_at": "2026-01-07T10:00:00Z",
  "current_players": 0,
  "peak_players_24h": 0,
  "avg_tps_24h": null,
  "created_at": "2026-01-07T10:00:00Z"
}
```
**Note:** `current_players`, `peak_players_24h`, and `avg_tps_24h` are placeholders for future computed metrics. Current implementation always returns `0` or `null`.

**Error Responses:**
- `404 Not Found`: Server doesn't exist or user doesn't own it
  ```json
  {
    "error": {
      "code": "SERVER_NOT_FOUND",
      "message": "Server not found: f86dfb9d-f74e-40dd-8c62-4c53833d1477"
    }
  }
  ```

---

#### `PUT /v1/servers/{server_id}`

Update server details (name and/or hostname).

**Authentication:** Required (JWT)

**Path Parameters:**
- `server_id` (UUID): Server identifier

**Request Body:**
```json
{
  "name": "Updated Server Name",
  "hostname": "new.hostname.com"
}
```

**Field Validation:**
- `name` (optional): 1-100 characters
- `hostname` (optional): Max 255 characters
- At least one field must be provided

**Success Response (200 OK):**
```json
{
  "id": "f86dfb9d-f74e-40dd-8c62-4c53833d1477",
  "user_id": "4fc857d6-27a1-497e-b02c-98be2683b4bf",
  "name": "Updated Server Name",
  "description": "Main survival world",
  "hostname": "new.hostname.com",
  "is_active": true,
  "last_event_at": "2026-01-07T10:00:00Z",
  "current_players": 0,
  "peak_players_24h": 0,
  "avg_tps_24h": null,
  "created_at": "2026-01-07T10:00:00Z"
}
```
**Note:** `current_players`, `peak_players_24h`, and `avg_tps_24h` are placeholders for future computed metrics. Current implementation always returns `0` or `null`.

**Error Responses:**
- `404 Not Found`: Server doesn't exist
  ```json
  {
    "error": {
      "code": "SERVER_NOT_FOUND",
      "message": "Server not found: f86dfb9d-f74e-40dd-8c62-4c53833d1477"
    }
  }
  ```
- `403 Forbidden`: User doesn't own server or server is deleted
  ```json
  {
    "error": {
      "code": "FORBIDDEN",
      "message": "You don't have permission to update this server"
    }
  }
  ```

---

#### `DELETE /v1/servers/{server_id}`

Soft delete a server (data retained for compliance).

**Authentication:** Required (JWT)

**Path Parameters:**
- `server_id` (UUID): Server identifier

**Success Response (204 No Content):**
No response body.

**Behavior:**
- Server marked as `is_deleted=true`
- All event data retained in database
- API keys automatically deactivated (`is_active=false`)
- Server no longer appears in `GET /v1/servers` list
- Server cannot be updated or accessed after deletion

**Error Responses:**
- `404 Not Found`: Server doesn't exist
  ```json
  {
    "error": {
      "code": "SERVER_NOT_FOUND",
      "message": "Server not found: f86dfb9d-f74e-40dd-8c62-4c53833d1477"
    }
  }
  ```
- `403 Forbidden`: User doesn't own server
  ```json
  {
    "error": {
      "code": "FORBIDDEN",
      "message": "You don't have permission to delete this server"
    }
  }
  ```
- `400 Bad Request`: Server already deleted
  ```json
  {
    "error": {
      "code": "VALIDATION_ERROR",
      "message": "Server is already deleted"
    }
  }
  ```

---

#### `GET /v1/servers/{server_id}/setup`

Generate plugin configuration file for server setup.

**Authentication:** Required (JWT)

**Path Parameters:**
- `server_id` (UUID): Server identifier

**Success Response (200 OK):**
```json
{
  "server_id": "f86dfb9d-f74e-40dd-8c62-4c53833d1477",
  "server_name": "My Survival Server",
  "config_yaml": "# Pivot Analytics Plugin Configuration\n...",
  "api_endpoint": "https://api.pivotmc.dev",
  "instructions": "\n## Setup Instructions\n..."
}
```

**Usage:** Frontend extracts `config_yaml` field and displays it for copy-paste.

**Error Responses:**
- `404 Not Found`: Server or API key not found
  ```json
  {
    "error": {
      "code": "SERVER_NOT_FOUND",
      "message": "No active API key found for this server. Please contact support."
    }
  }
  ```

---

#### `GET /v1/servers/{server_id}/status`

Check server connection health and data ingestion status.

**Authentication:** Required (JWT)

**Path Parameters:**
- `server_id` (UUID): Server identifier

**Success Response (200 OK):**
```json
{
  "server_id": "f86dfb9d-f74e-40dd-8c62-4c53833d1477",
  "server_name": "My Survival Server",
  "is_receiving_data": true,
  "last_event_timestamp": "2026-01-07T10:00:00Z",
  "setup_complete": true,
  "status_message": "Connected! Receiving live data.",
  "minutes_since_last_event": 0
}
```

**Status Message Logic:**
- `"Waiting for first data..."` → `setup_complete: false`, never received events
- `"Connected! Receiving live data."` → Last event < 5 minutes ago
- `"Connected. Last data received X minutes ago."` → Last event 5-60 minutes ago
- `"Connection lost. Server may be offline or plugin disabled."` → Last event > 60 minutes ago

**Error Responses:**
- `404 Not Found`: Server not found
  ```json
  {
    "error": {
      "code": "SERVER_NOT_FOUND",
      "message": "Server not found: f86dfb9d-f74e-40dd-8c62-4c53833d1477"
    }
  }
  ```

---

#### `POST /v1/servers/{server_id}/rotate-key`

Rotate API key for a server (invalidates old key immediately).

**Authentication:** Required (JWT)

**Path Parameters:**
- `server_id` (UUID): Server identifier

**Success Response (200 OK):**
```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "server_id": "f86dfb9d-f74e-40dd-8c62-4c53833d1477",
  "key": "pvt_NEW_KEY_HERE_abcdef1234567890",
  "name": "Default Key",
  "is_active": true,
  "expires_at": "2027-01-07T10:00:00Z",
  "created_at": "2026-01-07T10:00:00Z",
  "last_used_at": null
}
```

**⚠️ Important:**
- Old API key is invalidated immediately
- New key shown only once - store securely
- Plugin must be reconfigured with new key
- Key expires after 1 year (configurable)

**Error Responses:**
- `404 Not Found`: Server doesn't exist
  ```json
  {
    "error": {
      "code": "SERVER_NOT_FOUND",
      "message": "Server not found: f86dfb9d-f74e-40dd-8c62-4c53833d1477"
    }
  }
  ```
- `403 Forbidden`: User doesn't own server
  ```json
  {
    "error": {
      "code": "FORBIDDEN",
      "message": "You don't have permission to access this server"
    }
  }
  ```

---

#### `GET /v1/servers/{server_id}/api-key`

Get current API key metadata for a server (for Settings page display).

**Authentication:** Required (JWT)

**Path Parameters:**
- `server_id` (UUID): Server identifier

**Success Response (200 OK):**
```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "server_id": "f86dfb9d-f74e-40dd-8c62-4c53833d1477",
  "key": "pvt_uP1JM4bRsoizE-sgBIlVu1458F9VKqmnwebxktAUHAQ",
  "name": "Default Key",
  "is_active": true,
  "last_used_at": "2026-01-09T00:55:00Z",
  "expires_at": "2027-01-07T10:00:00Z",
  "created_at": "2026-01-07T10:00:00Z"
}

**Frontend Usage:**
- Settings page: Display current key + expiration status
- Dashboard header: Show key expiration warnings
- Troubleshooting UI: Verify key is active and recently used

**Error Responses:**
- `404 Not Found`: Server doesn't exist or user doesn't own it
  ```json
  {
    "error": {
      "code": "SERVER_NOT_FOUND",
      "message": "Server not found: f86dfb9d-f74e-40dd-8c62-4c53833d1477"
    }
  }
  ```
- `404 Not Found`: No active API key found for server
  ```json
  {
    "error": {
      "code": "SERVER_NOT_FOUND",
      "message": "No active API key found for this server"
    }
  }
  ```
- `403 Forbidden`: User doesn't own server
  ```json
  {
    "error": {
      "code": "FORBIDDEN",
      "message": "You don't have permission to access this server"
    }
  }
  ```

---

### Event Ingestion Endpoints

#### `POST /v1/ingest`

Ingest batch of events from Minecraft plugin.

**Authentication:** Required (API Key)

**Header:**
```
X-API-Key: pvt_uP1JM4bRsoizE-sgBIlVu1458F9VKqmnwebxktAUHAQ
```

**Request Body:**
```json
{
  "batch_timestamp": 1735398000000,
  "player_events": [
    {
      "timestamp": 1735398000000,
      "event_type": "PLAYER_JOIN",
      "player_uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
      "player_name": "Notch",
      "hostname": "play.example.com"
    },
    {
      "timestamp": 1735398300000,
      "event_type": "PLAYER_QUIT",
      "player_uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
      "player_name": "Notch",
      "hostname": null
    }
  ],
  "performance_events": [
    {
      "timestamp": 1735398000000,
      "tps": 19.8,
      "player_count": 12
    }
  ]
}
```

**Field Descriptions:**

**PlayerEvent:**
- `timestamp` (int): Unix timestamp in milliseconds
- `event_type` (string): `"PLAYER_JOIN"` or `"PLAYER_QUIT"`
- `player_uuid` (string): Minecraft player UUID (with hyphens)
- `player_name` (string): Player's in-game name
- `hostname` (string, optional): Join hostname for attribution tracking

**PerformanceEvent:**
- `timestamp` (int): Unix timestamp in milliseconds
- `tps` (float): Server TPS (ticks per second), 0-20
- `player_count` (int): Online player count at sample time

**Timestamp Validation:**
- Must be positive integer
- Cannot be more than 1 hour in the future (clock drift tolerance)
- Cannot be older than 7 days (stale data protection)
- Invalid timestamps return `VALIDATION_ERROR`

**Success Response (200 OK):**
```json
{
  "status": "success",
  "events_processed": 3
}
```

**Error Responses:**
- `401 Unauthorized`: Invalid API key
  ```json
  {
    "error": {
      "code": "UNAUTHORIZED",
      "message": "Invalid API key"
    }
  }
  ```
- `400 Bad Request`: Invalid timestamp
  ```json
  {
    "error": {
      "code": "VALIDATION_ERROR",
      "message": "Invalid timestamp: -1 (negative value)"
    }
  }
  ```

**Plugin Behavior:**
- Batches events every 30 seconds (configurable)
- Includes all player joins/quits since last batch
- Includes TPS samples every 5 seconds (configurable)
- Retries failed requests with exponential backoff

---

### Analytics Endpoints

All analytics endpoints require JWT authentication and are rate-limited to 100 requests/hour.

---

#### `GET /v1/analytics/servers/{server_id}/performance-summary`

Get performance overview and TPS history for dashboard.

**Authentication:** Required (JWT)

**Rate Limit:** 100 requests/hour

**Path Parameters:**
- `server_id` (UUID): Server identifier

**Query Parameters:**
- `hours` (int, default=24): Time window to analyze (1-168 hours)

**Example Request:**
```
GET /v1/analytics/servers/f86dfb9d-f74e-40dd-8c62-4c53833d1477/performance-summary?hours=24
```

**Success Response (200 OK):**
```json
{
  "server_id": "f86dfb9d-f74e-40dd-8c62-4c53833d1477",
  "server_name": "My Survival Server",
  "period_hours": 24,
  "tps_stats": {
    "avg_tps": 19.65,
    "min_tps": 14.2,
    "max_tps": 20.0,
    "sample_count": 17280,
    "lag_samples": 124,
    "lag_percentage": 0.72
  },
  "tps_history": [
    {
      "time": "2026-01-06T10:00:00Z",
      "avg_tps": 19.8,
      "min_tps": 19.2,
      "max_tps": 20.0
    }
  ],
  "player_stats": {
    "total_joins": 245,
    "total_quits": 198,
    "unique_players": 89,
    "peak_players": 45
  },
  "health_score": 95
}
```

**Health Score Logic:**
- 100: Perfect (avg TPS ≥ 19.5)
- 85: Good (avg TPS 18-19.5)
- 60: Fair (avg TPS 15-18)
- 30: Poor (avg TPS < 15)

---

#### `GET /v1/servers/{server_id}/performance/compare`

Compare two time periods for performance metrics (e.g., this week vs last week).

**Authentication:** Required (JWT)

**Rate Limit:** 100 requests/hour

**Path Parameters:**
- `server_id` (UUID): Server identifier

**Query Parameters:**
- `current_hours` (int, default=24): Hours for current period (1-168)
- `compare_hours` (int, default=24): Hours for comparison period (1-168)

**Example Request:**
```
GET /v1/servers/f86dfb9d-f74e-40dd-8c62-4c53833d1477/performance/compare?current_hours=168&compare_hours=168
```
*Compares this week (last 168 hours) vs previous week (168 hours before that)*

**Success Response (200 OK):**
```json
{
  "server_id": "f86dfb9d-f74e-40dd-8c62-4c53833d1477",
  "server_name": "My Survival Server",
  "current": {
    "period": "current",
    "start_time": "2025-12-31T10:00:00",
    "end_time": "2026-01-07T10:00:00",
    "performance": {
      "avg_tps": 19.65,
      "min_tps": 14.2,
      "max_tps": 20.0,
      "tps_samples": 17280,
      "lag_samples": 124,
      "lag_percentage": 0.72
    },
    "players": {
      "unique_players": 89,
      "total_joins": 245,
      "total_quits": 198,
      "net_change": 47,
      "peak_concurrent": 45
    }
  },
  "previous": {
    "period": "previous",
    "start_time": "2025-12-24T10:00:00",
    "end_time": "2025-12-31T10:00:00",
    "performance": {
      "avg_tps": 19.2,
      "min_tps": 15.8,
      "max_tps": 20.0,
      "tps_samples": 16800,
      "lag_samples": 89,
      "lag_percentage": 0.53
    },
    "players": {
      "unique_players": 74,
      "total_joins": 198,
      "total_quits": 165,
      "net_change": 33,
      "peak_concurrent": 38
    }
  },
  "deltas": {
    "performance": {
      "avg_tps_change": 2.34,
      "lag_percentage_change": 35.85
    },
    "players": {
      "unique_players_change": 20.27,
      "total_joins_change": 23.74
    }
  },
  "comparison_summary": {
    "better_performance": true,
    "player_growth": true
  }
}
```

**Frontend Usage:**
- Overlay current and previous period on same chart
- Show percentage changes with color coding (green = improvement, red = decline)
- Use for "This Week vs Last Week" visualizations

---

#### `GET /v1/analytics/servers/{server_id}/hostname-attribution`

Marketing attribution analysis by join hostname.

**Authentication:** Required (JWT)

**Rate Limit:** 100 requests/hour

**Path Parameters:**
- `server_id` (UUID): Server identifier

**Query Parameters:**
- `days` (int, default=7): Days to analyze (1-90)

**Example Request:**
```
GET /v1/analytics/servers/f86dfb9d-f74e-40dd-8c62-4c53833d1477/hostname-attribution?days=7
```

**Success Response (200 OK):**
```json
{
  "server_id": "f86dfb9d-f74e-40dd-8c62-4c53833d1477",
  "server_name": "My Survival Server",
  "period_days": 7,
  "summary": {
    "total_unique_players": 156,
    "total_joins": 489,
    "hostname_count": 4
  },
  "attributions": [
    {
      "hostname": "play.example.com",
      "unique_players": 89,
      "total_joins": 267,
      "avg_joins_per_player": 3.0,
      "percentage_of_traffic": 57.05,
      "first_seen": "2025-12-31T10:00:00Z",
      "last_seen": "2026-01-07T10:00:00Z"
    }
  ],
  "marketing_insights": [
    {
      "title": "'play.example.com' outperforming other sources",
      "message": "'play.example.com' drove 89 unique players."
    }
  ]
}
```

**Notes:**
- `hostname: null` represents direct connections (no custom hostname)
- Sorted by `unique_players` descending

---

#### `GET /v1/analytics/servers/{server_id}/lag-churn`

Correlate TPS drops with player disconnects.

**Authentication:** Required (JWT)

**Rate Limit:** 100 requests/hour

**Path Parameters:**
- `server_id` (UUID): Server identifier

**Query Parameters:**
- `hours` (int, default=24): Hours to analyze (1-720)
- `bucket_minutes` (int, default=5): Time bucket size (1-60 minutes)

**Example Request:**
```
GET /v1/analytics/servers/f86dfb9d-f74e-40dd-8c62-4c53833d1477/lag-churn?hours=24&bucket_minutes=5
```

**Success Response (200 OK):**
```json
{
  "server_id": "f86dfb9d-f74e-40dd-8c62-4c53833d1477",
  "server_name": "My Survival Server",
  "period_hours": 24,
  "bucket_minutes": 5,
  "tps_samples": [
    {
      "time": "2026-01-07T10:00:00Z",
      "avg_tps": 19.8,
      "min_tps": 19.2,
      "max_tps": 20.0,
      "samples": 60
    }
  ],
  "quit_events": [
    {
      "time": "2026-01-07T10:00:00Z",
      "quit_count": 2,
      "unique_players": 2
    }
  ],
  "join_events": [
    {
      "time": "2026-01-07T10:00:00Z",
      "join_count": 5,
      "unique_players": 5
    }
  ],
  "analysis_tip": "Overlay quit_events on tps_samples chart. Spikes in quits during TPS drops indicate lag-induced churn."
}
```

**Frontend Usage:**
- Overlay `quit_events` on `tps_samples` chart
- Visual correlation shows lag-induced player exodus

---

#### `GET /v1/analytics/servers/{server_id}/insights`

AI-generated insights and recommendations.

**Authentication:** Required (JWT)

**Rate Limit:** 100 requests/hour

**Path Parameters:**
- `server_id` (UUID): Server identifier

**Query Parameters:**
- `hours` (int, default=24): Hours to analyze (1-720)

**Example Request:**
```
GET /v1/analytics/servers/f86dfb9d-f74e-40dd-8c62-4c53833d1477/insights?hours=24
```

**Success Response (200 OK):**
```json
{
  "server_id": "f86dfb9d-f74e-40dd-8c62-4c53833d1477",
  "server_name": "My Survival Server",
  "analysis_period_hours": 24,
  "insights": [
    {
      "severity": "critical",
      "category": "performance",
      "title": "Lag spike caused player exodus",
      "message": "TPS dropped to 12.3 at 14:25 UTC. This coincided with 8 player disconnects within 5 minutes.",
      "recommendation": "Check server logs for plugin errors at this timestamp.",
      "timestamp": "2026-01-07T14:25:00Z"
    }
  ],
  "total_insights": 3,
  "critical_count": 1,
  "warning_count": 0,
  "info_count": 2,
  "generated_at": "2026-01-07T10:00:00Z"
}
```

**Insight Severity Levels:**
- `critical`: Requires immediate action
- `warning`: Needs attention
- `success`: Positive metrics
- `info`: Operational guidance

**Analysis Modules:**
1. Lag Patterns
2. Consecutive Lag Detection
3. Player Growth Trends
4. Marketing ROI
5. Hostname Health
6. Weekend Patterns
7. Retention Analysis
8. Peak Time Detection

---

### Downloads Endpoints

#### `GET /v1/downloads/plugin/latest`

Download the latest Pivot Analytics plugin JAR file.

**Authentication:** None (public endpoint)

**Success Response (200 OK):**
- **Content-Type:** `application/java-archive`
- **Content-Disposition:** `attachment; filename="PivotPlugin.jar"`
- **Body:** Binary JAR file

**Error Responses:**
- `404 Not Found`: Plugin file not available
  ```json
  {
    "error": {
      "code": "NOT_FOUND",
      "message": "Plugin file not found"
    }
  }
  ```

---

## Data Types & Models

### User

```typescript
{
  id: UUID
  email: string
  full_name: string | null
  is_active: boolean
  subscription_tier: "free" | "pro" | "enterprise"
  created_at: DateTime
}
```

---

### Server

```typescript
{
  id: UUID
  user_id: UUID
  name: string
  description: string | null
  hostname: string | null
  is_active: boolean
  is_deleted: boolean  // Soft delete flag
  last_event_at: DateTime | null
  current_players: int  // Always 0 (not yet computed)
  peak_players_24h: int  // Always 0 (not yet computed)
  avg_tps_24h: float | null  // Always null (not yet computed)
  created_at: DateTime
}
```

---

### APIKey

```typescript
{
  id: UUID
  server_id: UUID
  key: string  // Format: "pvt_<random>"
  name: string | null
  is_active: boolean
  expires_at: DateTime | null  // Default: 1 year from creation
  created_at: DateTime
  last_used_at: DateTime | null
}
```

**Key Lifecycle:**
- Auto-generated on server creation
- Expires after 1 year by default
- Can be rotated via `/rotate-key` endpoint
- Deactivated on server deletion

---

### Event (Internal)

```typescript
{
  id: UUID
  timestamp: DateTime
  server_id: UUID
  event_type: "PLAYER_JOIN" | "PLAYER_QUIT" | "TPS_SAMPLE"
  player_uuid: UUID | null
  player_name: string | null
  hostname: string | null
  tps: number | null
  player_count: number | null
}
```

---

### Insight

```typescript
{
  severity: "critical" | "warning" | "success" | "info"
  category: "performance" | "retention" | "marketing" | "operations"
  title: string
  message: string
  recommendation: string
  timestamp: DateTime | null
}
```

---

## Example Integration Flows

### 1. New User Onboarding

```
1. POST /v1/auth/register
   → Returns JWT token + user object

2. POST /v1/servers
   Headers: Authorization: Bearer <token>
   → Returns server object + API key (one-time display)

3. GET /v1/servers/{id}/setup
   Headers: Authorization: Bearer <token>
   → Returns pre-filled config.yml

4. User downloads plugin: GET /v1/downloads/plugin/latest

5. User installs plugin + pastes config

6. Plugin starts sending events: POST /v1/ingest
   Headers: X-API-Key: <api_key>

7. Poll for connection: GET /v1/servers/{id}/status
   → Returns setup_complete: true when data arrives

8. View analytics: GET /v1/analytics/servers/{id}/performance-summary
```

---

### 2. Dashboard Data Loading

```
1. User logs in: POST /v1/auth/login
   → Returns JWT token

2. Fetch server list: GET /v1/servers
   → Returns all user's active servers

3. User selects server from dropdown

4. Parallel fetch:
   - GET /v1/analytics/servers/{id}/performance-summary
   - GET /v1/analytics/servers/{id}/hostname-attribution
   - GET /v1/analytics/servers/{id}/lag-churn
   - GET /v1/analytics/servers/{id}/insights

5. Render charts + insights

6. Poll status every 5s: GET /v1/servers/{id}/status
   → Updates "Connected!" indicator
```

---

### 3. Server Management Flow

```
1. User views server settings

2. Update server details: PUT /v1/servers/{id}
   Body: {"name": "New Name", "hostname": "new.host.com"}

3. Rotate compromised API key: POST /v1/servers/{id}/rotate-key
   → Returns new key (show once, update plugin config)

4. Compare performance: GET /v1/servers/{id}/performance/compare?current_hours=168&compare_hours=168
   → "This Week vs Last Week" visualization

5. Delete server: DELETE /v1/servers/{id}
   → Server hidden, data retained, API keys deactivated
```

---

## Notes for Development Teams

### Backend Team
- All dates/times are UTC
- UUIDs are hyphenated format
- TimescaleDB handles time-series data (events table)
- Rate limiting via slowapi (100/hour for analytics)
- JWT expires in 7 days
- API keys expire after 1 year (configurable)
- Soft delete: `is_deleted` flag, data retained

### Frontend Team
- Store JWT in localStorage: `localStorage.setItem('token', response.access_token)`
- Include in all requests: `Authorization: Bearer ${token}`
- Poll `/status` endpoint every 5 seconds during onboarding
- Refresh analytics every 30-60 seconds (respects rate limits)
- Handle 401 → redirect to login
- Display API key only once on server creation/rotation
- Deleted servers don't appear in `GET /v1/servers`
- Show expiration warnings for API keys nearing expiry

### Plugin Team
- Send batches every 30 seconds (configurable)
- Include `X-API-Key` header in all requests
- Retry failed requests with exponential backoff (5s, 10s, 20s, 40s)
- Queue events locally if API is unreachable
- Timestamps are Unix milliseconds (validated server-side)
- Player UUIDs must include hyphens
- Handle API key expiration gracefully (401 response)

---

## Changelog

**v1.2.0 (2026-01-09)**
- Added `GET /v1/servers/{server_id}/api-key` (retrieve API key metadata for Settings page)

**v1.1.0 (2026-01-07)**
- Added `PUT /v1/servers/{server_id}` (update server)
- Added `DELETE /v1/servers/{server_id}` (soft delete)
- Added `POST /v1/servers/{server_id}/rotate-key` (key rotation)
- Added `GET /v1/servers/{server_id}/performance/compare` (period comparison)
- Updated error response format with error codes
- Added `expires_at` field to APIKey model
- Added `is_deleted` field to Server model
- Added placeholder computed fields (current_players, peak_players_24h, avg_tps_24h)
- Updated `GET /v1/servers` to filter deleted servers
- Enhanced timestamp validation in ingest endpoint


**v1.0.0 (2025-12-28)**
- Initial API contract
- All core endpoints documented
- Authentication flows defined
- Analytics modules complete

---

**End of API Contract**
