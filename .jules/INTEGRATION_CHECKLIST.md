# Pivot Analytics - Cross-Repo Integration Checklist

**Version:** 1.0.0
**Last Updated:** December 28, 2025
**Purpose:** Coordination guide for changes across multiple AI chat contexts (Orchestrator, Backend, Dashboard, Plugin)

---

## Overview

This project is split across **4 AI chat contexts**:
1. **Orchestrator Chat** (this chat) - Architecture decisions, documentation
2. **Backend/API Chat** - Python/FastAPI implementation
3. **Frontend/Dashboard Chat** - Next.js/React implementation
4. **Plugin Chat** - Java/Spigot implementation

**Any change that crosses boundaries MUST follow this checklist to prevent breaking integrations.**

---

## Before Changing Event Structure

**Affected:** Plugin → Backend → Dashboard

- [ ] **Update EVENT_SCHEMAS.md** with new/changed fields
- [ ] **Notify Plugin Chat** - They must:
  - Update EventCollector.java field mappings
  - Rebuild .jar file (`mvn clean package`)
  - Test with local server before deployment
- [ ] **Notify Dashboard Chat** - They may need:
  - Update TypeScript interfaces
  - Modify chart data parsing
  - Add new UI fields if applicable
- [ ] **Update database migration** if adding/removing fields:
  - Create Alembic migration: `alembic revision -m "Add field_name to events"`
  - Test migration locally before production
- [ ] **Verify backward compatibility** - Old plugin versions should not break

### Examples of Event Structure Changes:
- Adding `server_region` field to TPS_SAMPLE events
- Renaming `hostname` to `connection_hostname`
- Adding new event type `PLAYER_KICK`

---

## Before Adding New Endpoint

**Affected:** Backend → Dashboard

- [ ] **Document in API_CONTRACT.md** with full spec:
  - Request/response schemas
  - Authentication requirements
  - Query parameters and defaults
  - Error responses (400, 401, 404, 422, 500)
- [ ] **Implement in Backend Chat**:
  - Add route to `app/api/v1/` module
  - Add Pydantic schemas to `app/schemas/`
  - Write database queries
  - Add authentication dependency (`get_current_user` or `get_api_key`)
  - Add rate limiting if analytics endpoint
- [ ] **Notify Dashboard Chat** for UI integration:
  - Provide endpoint URL
  - Share TypeScript interface for response
  - Confirm polling interval (default: 30 seconds)
- [ ] **Test with curl** before marking complete:
  ```
  curl -X GET "http://localhost:8000/v1/analytics/servers/{id}/new-endpoint" \
    -H "Authorization: Bearer <jwt_token>"
  ```
- [ ] **Update ARCHITECTURE.md** if endpoint changes data flow

### Examples of New Endpoints:
- `GET /v1/analytics/servers/{id}/player-retention` - Dashboard needs new chart
- `POST /v1/servers/{id}/alerts` - Dashboard needs alerts UI
- `GET /v1/analytics/servers/{id}/export` - Dashboard needs download button

---

## Before Changing Existing Endpoint

**Affected:** Backend → Dashboard (BREAKING CHANGE)

- [ ] **Version the endpoint** instead of changing in-place:
  - Old: `GET /v1/analytics/servers/{id}/performance`
  - New: `GET /v2/analytics/servers/{id}/performance`
  - Keep v1 working for 30 days minimum
- [ ] **Update API_CONTRACT.md** with deprecation notice
- [ ] **Notify Dashboard Chat immediately** - They must:
  - Update API client (`lib/api.ts`)
  - Test all affected components
  - Update TypeScript interfaces
- [ ] **Add migration path** in response (include `api_version: "v2"`)
- [ ] **Log usage of old endpoint** to track when safe to remove

### Examples of Breaking Changes:
- Changing `tps_stats.avg_tps` to `tps_stats.average_tps` (field rename)
- Changing date format from Unix ms to ISO 8601 strings
- Removing `health_score` field from response

---

## Before Deploying to Production

**Affected:** All Components

### Backend Deployment
- [ ] **Apply database migrations**:
  ```
  alembic upgrade head
  ```
- [ ] **Set environment variables**:
  - `DATABASE_URL` (PostgreSQL connection string)
  - `SECRET_KEY` (32-byte hex for JWT)
  - `CORS_ORIGINS` (dashboard URL)
- [ ] **Test health endpoint**: `GET /health` returns 200
- [ ] **Check logs** for startup errors
- [ ] **Verify TimescaleDB extension** is loaded

### Dashboard Deployment
- [ ] **Update ENV vars** on Vercel:
  - `NEXT_PUBLIC_API_URL=https://api.pivotmc.dev`
- [ ] **Test build locally**: `npm run build`
- [ ] **Clear Vercel cache** if static assets changed
- [ ] **Test authentication flow** (login → dashboard → API calls)

### Plugin Deployment
- [ ] **Rebuild .jar**: `mvn clean package`
- [ ] **Upload to releases**: GitHub Releases or `/v1/downloads/plugin/latest`
- [ ] **Update version in plugin.yml**: e.g., `version: 1.2.0`
- [ ] **Test on staging server** before announcing to users
- [ ] **Document breaking changes** in release notes

### Full Integration Test
- [ ] **Plugin → Backend**: Verify events appear in database within 60 seconds
- [ ] **Backend → Dashboard**: Refresh dashboard, confirm data loads
- [ ] **End-to-End**: Player join → Event ingested → Chart updates in dashboard

---

## Plugin Breaking Changes

**If you change these, Plugin Chat MUST update code:**

### Event Field Names
**Example:** Rename `player_uuid` to `uuid`
- [ ] Update `EventCollector.addPlayerEvent()` JSON property names
- [ ] Rebuild plugin
- [ ] **Downtime Risk:** HIGH - Old plugins send wrong schema → 422 errors

### Endpoint URLs
**Example:** Change `/v1/ingest` to `/v2/ingest`
- [ ] Update default `pivot.api-endpoint` in `config.yml`
- [ ] Document migration in release notes
- [ ] **Downtime Risk:** MEDIUM - Users must update config manually

### Authentication Format
**Example:** Change from `Bearer` to `X-API-Key` header
- [ ] Update `EventCollector.sendToAPI()` header logic
- [ ] Coordinate deployment (backend must support both formats during transition)
- [ ] **Downtime Risk:** CRITICAL - All plugins break simultaneously

### TPS Detection Logic
**Example:** Change sampling from 30s to 60s
- [ ] Update `PivotPlugin.onEnable()` task timer (600L → 1200L ticks)
- [ ] Update EVENT_SCHEMAS.md frequency documentation
- [ ] **Downtime Risk:** LOW - Data just arrives less frequently

---

## Dashboard Breaking Changes

**If you change these, Dashboard Chat MUST update code:**

### Response Field Names
**Example:** Change `tps_stats.avg_tps` to `tps_stats.average_tps`
- [ ] Update TypeScript interfaces in dashboard
- [ ] Update chart data mapping: `data={tps_history.map(d => d.average_tps)}`
- [ ] Test all affected components (TPSChart.tsx, InsightsPanel.tsx, etc.)
- [ ] **Downtime Risk:** HIGH - Charts show empty/broken

### Endpoint URLs
**Example:** Change `/v1/analytics/servers/{id}/performance-summary` to `/v1/performance`
- [ ] Update `lib/api.ts` function URLs
- [ ] Search codebase for hardcoded URLs
- [ ] **Downtime Risk:** HIGH - 404 errors, no data loads

### Date Range Parameters
**Example:** Change `?hours=24` to `?time_range=24h`
- [ ] Update TimeSelector.tsx query param builder
- [ ] Update all analytics API calls
- [ ] **Downtime Risk:** MEDIUM - Wrong data returned or validation errors

### Authentication Token Format
**Example:** Change JWT expiration from 7 days to 1 day
- [ ] Update localStorage refresh logic
- [ ] Add token refresh endpoint if needed
- [ ] **Downtime Risk:** MEDIUM - Users logged out more frequently

---

## Backend Breaking Changes

**If you change these, notify ALL chats:**

### Database Schema Changes
**Example:** Add new column `events.server_region`
- [ ] **Orchestrator Chat:** Update ARCHITECTURE.md database schema section
- [ ] **Backend Chat:** Create Alembic migration, update ORM models
- [ ] **Plugin Chat:** Optionally add field to event payload
- [ ] **Dashboard Chat:** No change needed (unless displaying new field)

### Rate Limiting Changes
**Example:** Change analytics from 100/hour to 50/hour
- [ ] Update API_CONTRACT.md rate limit documentation
- [ ] **Dashboard Chat:** Reduce polling frequency if needed (30s → 60s)
- [ ] **Downtime Risk:** LOW - Just slower updates

### New Required Fields
**Example:** Make `hostname` required (was optional)
- [ ] **Plugin Chat:** Ensure `hostname` always sent (even if empty string)
- [ ] Update EVENT_SCHEMAS.md validation rules
- [ ] Add database constraint: `ALTER TABLE events ALTER COLUMN hostname SET NOT NULL`
- [ ] **Downtime Risk:** HIGH - Old plugins rejected with 422 errors

---

## Communication Protocol

### Notify Pattern

**When making changes, post in relevant chat:**

```
🚨 BREAKING CHANGE ALERT 🚨

Component: Backend API
Change: Renamed field `avg_tps` → `average_tps` in /v1/analytics/servers/{id}/performance-summary
Affected: Dashboard Chat

Action Required:
1. Update TypeScript interface: tps_stats.average_tps
2. Update TPSChart.tsx data mapping
3. Test locally before deploying

Timeline: Deploy backend Friday 3 PM, dashboard must deploy by 5 PM

Questions: Reply in this chat or tag @orchestrator
```

### Non-Breaking Changes (Just FYI)

**Safe changes that don't require coordination:**
- Adding new optional fields to responses
- Adding new endpoints (if dashboard doesn't need them yet)
- Internal refactoring (no API changes)
- Performance optimizations
- Bug fixes that don't change behavior

---

## Testing Checklist

### Local Development Test
- [ ] Backend: `uvicorn app.main:app --reload` starts without errors
- [ ] Dashboard: `npm run dev` builds successfully
- [ ] Plugin: `mvn clean package` completes, .jar under 5 MB
- [ ] Database: `alembic current` shows latest migration

### Integration Test (Local)
1. [ ] Start backend: `http://localhost:8000`
2. [ ] Start dashboard: `http://localhost:3000`
3. [ ] Install plugin on test server
4. [ ] Configure plugin with local API endpoint
5. [ ] Join test server as player
6. [ ] Verify event appears in database: `SELECT * FROM events ORDER BY timestamp DESC LIMIT 1;`
7. [ ] Refresh dashboard, confirm data displayed

### Staging Test (Pre-Production)
- [ ] Deploy backend to Railway staging environment
- [ ] Deploy dashboard to Vercel preview deployment
- [ ] Test with staging API keys (separate from production)
- [ ] Verify no errors in logs (Railway + Vercel dashboards)

### Production Smoke Test
- [ ] Health check: `curl https://api.pivotmc.dev/health`
- [ ] Dashboard loads: `https://app.pivotmc.dev`
- [ ] Login works (test user)
- [ ] Create test server
- [ ] Verify setup wizard generates config
- [ ] Monitor error rates for 1 hour post-deploy

---

## Rollback Procedures

### Backend Rollback
```
# Railway
railway rollback  # Reverts to previous deployment

# Database migration rollback
alembic downgrade -1  # Undo last migration
```

### Dashboard Rollback
```
# Vercel
vercel rollback  # Via Vercel dashboard or CLI
```

### Plugin Rollback
- [ ] Re-upload previous .jar to `/v1/downloads/plugin/latest`
- [ ] Announce in Discord/docs: "Please redownload plugin to rollback"

---

## Emergency Contacts

**If integration breaks:**

1. **Check API_CONTRACT.md** - Verify expected behavior
2. **Check EVENT_SCHEMAS.md** - Verify event format
3. **Check ARCHITECTURE.md** - Verify data flow
4. **Notify Orchestrator Chat** - Coordinate fix across repos

---

## Changelog

**v1.0.0 (2025-12-28)**
- Initial checklist created
- Covers Plugin, Backend, Dashboard coordination
- Based on actual project architecture

---

**End of Integration Checklist**