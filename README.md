# Pivot Analytics Plugin

Real-time analytics and performance monitoring for Minecraft servers. Pivot helps you understand your player base, monitor server performance (TPS), and track retention metrics.

## Features

- **Player Analytics:** Track joins, quits, sessions, and retention cohorts.
- **Performance Monitoring:** Real-time TPS tracking and player count correlation.
- **Privacy Focused:** Optional UUID hashing and hostname tracking controls.
- **Cross-Version:** Supports Spigot/Paper 1.8.8 through 1.21+.
- **Lightweight:** Async data processing with minimal impact on server performance (<0.1ms tick impact).

## Installation

1. **Download** the latest `PivotAnalytics-x.x.x.jar`.
2. Place the JAR file in your server's `plugins/` folder.
3. **Restart** your server to generate the default configuration.
4. Open `plugins/PivotAnalytics/config.yml` and add your **API Key**:
   ```yaml
   api:
     key: "pvt_your_key_here"
   ```
   *Get your API key from the [Pivot Dashboard](https://app.pivotmc.dev).*
5. Run `/pivot reload` to apply changes.

## Configuration

The `config.yml` allows you to customize data collection and privacy settings.

```yaml
api:
  endpoint: "https://api.pivotmc.dev/v1/ingest"
  key: "pvt_xxxxx"

collection:
  enabled: true
  batch-interval: 60          # Seconds between API sends
  track-player-events: true
  track-performance: true
  tps-sample-interval: 30     # Seconds between TPS samples

privacy:
  anonymize-players: false    # Hash UUIDs for privacy
  track-hostnames: true       # Capture join hostnames (e.g., play.example.com)

debug:
  enabled: false              # Enable verbose logging
```

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/pivot status` | Check connection status, queue size, and TPS. | `pivot.admin` |
| `/pivot reload` | Reload configuration from config.yml. | `pivot.admin` |
| `/pivot debug [on\|off]` | Toggle verbose debug logging. | `pivot.admin` |
| `/pivot info` | Show plugin version and technical info. | `pivot.admin` |

## Permissions

- `pivot.admin`: Grants access to all Pivot commands (default: OP).

## Support

- **Documentation:** [docs.pivotmc.dev](https://docs.pivotmc.dev)
- **Dashboard:** [app.pivotmc.dev](https://app.pivotmc.dev)
- **Discord:** [Join our community](https://discord.gg/pivot)
