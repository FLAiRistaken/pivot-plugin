# Bug Audit: March 2026

This document summarizes the audit and resolution of the bug backlog from Phase 3A development.

## Item 1 — [MEDIUM] No exponential backoff on API failure
**Status:** 🔧 Fixed in this PR

**Details:**
- `EventCollector.java` was updated to include an overloaded `sendToAPI(String json, int attempt)` method.
- Failed HTTP requests (network exceptions or non-2xx response codes, excluding 401 and 400) now automatically trigger up to 3 retries.
- The retry logic employs exponential backoff delays of 5s, 15s, and 45s using `Bukkit.getScheduler().runTaskLaterAsynchronously()`.
- HTTP 401 and 400 responses are treated as permanent failures (configuration/request errors) and are deliberately not retried, matching the required specifications.

## Item 2 — [LOW] /pivot status command not implemented
**Status:** ✅ Already implemented

**Details:**
- The `/pivot status` command is already fully implemented in `src/main/java/gg/pivot/PivotCommand.java` and properly registered within `PivotPlugin.java` and `plugin.yml`.
- The current implementation accurately outputs the requested context, including configuration validation (API key mask), collection queue counts, last successful batch sent time, server performance (TPS and online players), privacy toggles, and debug status.
- No further changes were necessary.

## Item 3 — [LOW] Attribution end-to-end unverified — add unit test
**Status:** 🔧 Fixed in this PR

**Details:**
- `HostnameDetector.java` was added to standardise hostname detection and manage fallbacks safely.
- `EventListener.java` was updated to parse the player's connection hostname using `HostnameDetector.java` instead of direct calls.
- A new Mockito unit test `AttributionTest.java` was created to test end-to-end attribution logic. It verifies that:
  1. The hostname successfully flows from `PlayerLoginEvent` to the queued `PLAYER_JOIN` event.
  2. If the virtual host is null or an empty string, it correctly falls back to the server's configured default hostname (`api.default-hostname`).
