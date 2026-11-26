# Player reports and replay clips

That'sABan integrates with the separate, MIT-licensed [FlashbackServer](https://github.com/Zeffut/FlashbackServer) Paper plugin. This is a Paper-compatible alternative to the original Fabric-only ServerReplay mod; it creates **Flashback `.flashback` files**, not ReplayMod `.mcpr` files.

## Requirements

- Paper 1.21.11 and Java 21.
- That'sABan with reports enabled.
- [FlashbackServer](https://github.com/Zeffut/FlashbackServer) installed as a separate plugin. Its upstream project supports Paper 1.21.5–26.3 and is MIT-licensed.
- The [Flashback client mod](https://modrinth.com/mod/flashback) on the staff member's client to open the saved replay.

The upstream repository currently has no published GitHub release asset, so build the plugin JAR from its source (the integration was checked against commit `2a1549593b78b4bce3cb3f9d48f9869d4e39c513`):

```bash
git clone https://github.com/Zeffut/FlashbackServer.git
cd FlashbackServer
git checkout 2a1549593b78b4bce3cb3f9d48f9869d4e39c513
./gradlew build --no-daemon
cp build/libs/FlashbackServer-*.jar /path/to/your/server/plugins/
```

That'sABan does not bundle or shade its API. Install FlashbackServer as a separate plugin and restart the server.

## FlashbackServer configuration

In `plugins/FlashbackServer/config.yml`, set:

```yaml
clips:
  window-seconds: 60
telemetry:
  enabled: false
```

The rolling clip implementation retains two keyframe segments. A configured window of 60 seconds guarantees at least roughly 60 seconds of evidence, but the actual clip can be longer (up to approximately twice the configured window) because the buffer rotates on keyframe boundaries. The FlashbackServer telemetry is independent of That'sABan; it is recommended to disable it explicitly as shown above.

Restart the server after changing this file. That'sABan checks that the configured window is at least the configured minimum and refuses to create a report if it is too short.

## Player command

```text
/report <Spieler> <Grund>
```

The target must be online. A player cannot report themselves. The reason is limited by `reports.max-reason-length` (default 200 characters). Each reporter has a cooldown (default 60 seconds).

That'sABan automatically arms a rolling clip buffer for each player after they join. When a report is submitted, it saves the target's already-buffered replay. It does not start recording only after the report, because that would miss the preceding gameplay.

## Staff commands

| Command | Effect |
|---|---|
| `/reports` or `/reports list` | Opens the interactive staff GUI for players; the console receives a text list |
| `/reports view <ID>` | Opens the report detail GUI for players; the console receives text details |
| `/reports offend <ID>` | Invoke the existing `/offend <player>` command as the staff sender; normal permission and Bedrock checks still apply |
| `/reports resolve <ID>` | Mark a report as resolved |
| `/reports dismiss <ID>` | Dismiss a report |

The replay path in `/reports view` is clickable to copy. Download the `.flashback` file from the server using your normal server file access, then open it with the Flashback client mod. The replay is not streamed to the staff member's Minecraft client by this plugin.

The GUI provides filters for open, all, failed, resolved and dismissed reports, paginates through the report queue, and opens a detail view with replay-path copying plus the existing offend/resolve/dismiss actions. All inventory interactions are locked to the plugin menu so staff cannot accidentally move items while using it. The command subcommands remain available for console use and automation.

## Permissions

- `thatsaban.report` (default: true): submit player reports.
- `thatsaban.reports` (default: op): list and manage reports.
- `thatsaban.reports.notify` (default: op): receive new-report notifications.

## Stored data and limitations

Report metadata is stored in `plugins/ThatsABan/reports.yml`; replay files are stored under `plugins/ThatsABan/reports/`. The metadata contains reporter/target UUIDs and names, the reason, timestamp, status and replay path. Replays contain captured gameplay packets and may therefore include player-visible chat or other in-game content. Restrict access to the report directory and backups to trusted staff, and define a retention/deletion policy for your server.

If FlashbackServer is missing, disabled, or has an unavailable API, the rest of That'sABan still loads but report capture is unavailable. Existing reports remain readable. A player whose rolling buffer was not armed before the report cannot receive a retroactive clip.
