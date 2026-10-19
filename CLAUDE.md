# pivot-plugin — CLAUDE.md

Bukkit/Spigot/Paper plugin (`PivotAnalytics`) that collects player + performance + profiling events and POSTs them to the backend `/v1/ingest`. Java 8, Maven, OkHttp + Gson.

## Commands
- Build: `mvn package` (shaded jar; `dependency-reduced-pom.xml` is generated — don't edit).
- Test: `mvn test` (JUnit + Mockito; mock `OkHttpClient` via the package-private `EventCollector`/`ApiClient` constructors).
- Targets Spigot API; `plugin.yml` `api-version: '1.20'`.
- **Quiet runs for agents:** `mvn -q test` and `mvn -q package`. Show full output only when diagnosing a failure; paste only failures and the test summary into PRs.

## Layout (`src/main/java/gg/pivot/`)
- `PivotPlugin.java` — lifecycle (`onEnable`/`onDisable`), config validation, `isValidApiKeyFormat`, `getApiKey`/`getApiEndpoint`.
- `EventCollector.java` — batches events, owns the `OkHttpClient` (15s timeouts), validates key format before sending.
- `ApiClient.java` — HTTP send with exponential backoff; HTTPS enforcement; log redaction.
- `EventListener.java` — `PlayerLoginEvent` (caches hostname), `PlayerJoinEvent`/`PlayerQuitEvent`/`PlayerKickEvent`.
- `CommandProfiler.java`, `ChunkProfiler.java`, `TPSUtil.java`, `util/HostnameDetector.java`, `PivotCommand.java`.
- `src/main/resources/` — `config.yml`, `plugin.yml`.

## Conventions & invariants (privacy/security critical)
- **Auth:** send `X-API-Key: <pvt_ key>` (NOT `Authorization: Bearer`). Key comes from `config.yml` `api.key`.
- **HTTPS only:** `ApiClient.buildRequest` drops events if the endpoint isn't `https://`. Keep this.
- **Never log the API key.** Pass every logged response/error body through `ApiClient.redactSensitiveInfo(text, key)`; log only a **masked** key at startup. No `printStackTrace`/raw `getMessage` containing the key.
- **TLS:** use the default `OkHttpClient` trust store. **Never** add a custom `TrustManager`/`HostnameVerifier`/`sslSocketFactory` that bypasses verification.
- **No player IPs — ever.** Use `PlayerLoginEvent.getHostname()` (the server connect-host) → `HostnameDetector.detectHostname` (strips port). **Do not** call `player.getAddress()` / `event.getRealAddress()` or store any player IP, including direct-IP-join edge cases.
- **SLOW_COMMAND captures the command NAME only.** `CommandProfiler` takes `message.substring(0, firstSpace)` and discards arguments — keep it that way (args can contain passwords, e.g. `/login`). Never send full command text.
- **UUID anonymization:** honor `privacy.anonymize-players` (SHA-256 hash) where implemented.
- **Chunk profiling is whole-chain, never per plugin.** `ChunkProfiler` reports the LOWEST-to-MONITOR time of each chunk event plus the *list* of plugins that have a chunk listener. Bukkit gives no per-listener timing, so never divide or attribute it to a plugin (an equal split looks like a measurement and is not one). Pair starts and ends by event instance; the MONITOR handler must always take its start before checking `enabled`.
- **Shutdown is bounded.** `onDisable` runs on the main thread: SERVER_STOP and the final flush share `PivotPlugin.SHUTDOWN_BUDGET_MS` (3s) and use `ApiClient.sendToAPISync(json, timeoutMs)`; then `EventCollector.shutdown()` stops the OkHttp dispatcher and pool. Don't add other blocking network calls there.
- **Batches carry a `batch_id`** (UUID per flush, reused on every retry of that payload) so the backend can drop a replay. Generate it in `EventCollector`, not in `ApiClient`.
- **Batch interval default is `PivotPlugin.DEFAULT_BATCH_INTERVAL_SECONDS` (30)**; keep it equal to `config.yml` (`ConfigDefaultsTest` checks).
- **Permissions:** keep `plugin.yml` minimal — only `pivot.admin` (default `op`). Don't request Bukkit perms the plugin doesn't need.
- **Event handlers** on `PlayerLoginEvent` use `EventPriority.MONITOR, ignoreCancelled = true` (Paper warns otherwise).

The backend caps and validates everything (`player_name<=16`, `hostname<=255`, `command<=64`), but the plugin is the first line of defense for player privacy — uphold the invariants above regardless of backend behavior.

## Code navigation (token-savior)
If the `token-savior` MCP tools are available (local sessions on Joe's Mac), use them before Read/Grep: `find_symbol` to locate a definition, `get_function_source` / `get_class_source` instead of reading whole files, `search_codebase` to find existing code to reuse, and `get_change_impact` / `find_impacted_test_files` before editing. Fall back to Read/Grep only for non-code files or when the index can't answer. Rules: pivot-docs `current/ORCHESTRATOR_CONTEXT_V6.md` §8.
