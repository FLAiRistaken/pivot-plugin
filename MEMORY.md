- [PivotAnalytics Plugin Overview](README.md) — Minecraft server analytics: player events, TPS, profiling
- [CLAUDE.md](CLAUDE.md) — Privacy/security invariants, build commands, API auth details

## Core Architecture

**Entry point:** [PivotPlugin.java](src/main/java/gg/pivot/PivotPlugin.java) (lifecycle, config validation, API key extraction)

**Event pipeline:**
- [EventListener.java](src/main/java/gg/pivot/EventListener.java) — captures PlayerLogin/Join/Quit/Kick events
- [EventCollector.java](src/main/java/gg/pivot/EventCollector.java) — batches events, manages OkHttpClient (15s timeout), validates keys
- [ApiClient.java](src/main/java/gg/pivot/ApiClient.java) — HTTP POST with exponential backoff, HTTPS enforcement, log redaction

**Profiling subsystem:**
- [CommandProfiler.java](src/main/java/gg/pivot/CommandProfiler.java) — captures command NAME only (no args for privacy)
- [ChunkProfiler.java](src/main/java/gg/pivot/ChunkProfiler.java) — chunk-level perf analysis
- [TickProfiler.java](src/main/java/gg/pivot/TickProfiler.java) — plugin tick profiling
- [TPSUtil.java](src/main/java/gg/pivot/TPSUtil.java) — real-time TPS tracking

**Utilities:**
- [HostnameDetector.java](src/main/java/gg/pivot/util/HostnameDetector.java) — extracts join hostname (no player IPs)
- [ConfigManager.java](src/main/java/gg/pivot/ConfigManager.java) — loads config.yml
- [ConfigSnapshotReporter.java](src/main/java/gg/pivot/ConfigSnapshotReporter.java) — config snapshot logic
- [PivotCommand.java](src/main/java/gg/pivot/PivotCommand.java) — `/pivot` command handler

## Privacy & Security (CRITICAL)

- **API Key:** `X-API-Key` header only; never log unmasked; redact in all error messages via `ApiClient.redactSensitiveInfo`
- **HTTPS only:** `ApiClient.buildRequest` drops non-https events. Never add custom TrustManager/HostnameVerifier
- **No player IPs:** Use `EventListener.PlayerLoginEvent.getHostname()` → `HostnameDetector.detectHostname`. Never call `player.getAddress()`
- **Command args stripped:** `CommandProfiler` extracts command NAME only; args discarded (can contain passwords)
- **UUID anonymization:** honor `privacy.anonymize-players` (SHA-256 hash) if implemented

## Build & Test

**Build:** `mvn package` (shades OkHttp/Gson; generates `dependency-reduced-pom.xml`)
**Test:** `mvn test` (JUnit 5 + Mockito; mock OkHttpClient via package-private `EventCollector`/`ApiClient` constructors)
**Config:** `src/main/resources/config.yml`, `plugin.yml` (api-version: 1.20)

## Key Invariants

- Batch interval: configurable (default 60s)
- TPS sample interval: configurable (default 30s)
- Idle throttling: reduce sampling when 0 players online
- Event handlers: `EventPriority.MONITOR, ignoreCancelled=true` (Paper requirement)
- Permissions: only `pivot.admin` (default: op)
- Java 8, target Spigot API, OkHttp 4.12.0, Gson 2.10.1

## Entry Points for New Work

- **Adding events:** extend `EventListener.java` + post via `EventCollector.queueEvent()`
- **Adding metrics:** add `*Profiler` class, wire into `PivotPlugin.onEnable()`
- **Config changes:** update `config.yml`, extend `ConfigManager`, validate in `PivotPlugin` init
- **Security fixes:** review `CLAUDE.md` invariants first, test with `EventCollectorTest` mocks
