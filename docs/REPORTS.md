# Player reports and replay clips

That'sABan embeds the MIT-licensed FlashbackServer recording engine. **No separate FlashbackServer plugin is required.** The embedded engine includes separate NMS adapters for Paper 1.21.11 and Paper 26.3.

The server creates native Flashback `.flashback` replay files. These are not MP4 videos or ReplayMod `.mcpr` files. Staff can open them with the [Flashback client mod](https://modrinth.com/mod/flashback).

## Requirements

- Paper 1.21.11 with Java 21, or Paper 26.3 with Java 25.
- That'sABan with `reports.enabled: true`.
- Staff client with the Flashback mod installed to view replay files.

Replay capture is available on Paper 1.21.11 and Paper 26.3. Each version uses its own NMS adapter. Other server versions fail closed for replay capture instead of using an incompatible adapter.

## Rolling evidence buffer

That'sABan arms a rolling buffer for each online player after join. It records outgoing gameplay packets in memory and periodically creates a fresh world-state keyframe. When a report is submitted, the buffer is saved to a report-specific `.flashback` file.

Default configuration:

```yaml
reports:
  enabled: true
  cooldown-seconds: 60
  max-reason-length: 200
  clip-window-seconds: 60
```

A report is accepted only after the target has a complete 60-second buffer. Because the recorder rotates keyframes in two segments, a saved replay contains at least 60 seconds and can be up to approximately 120 seconds depending on when the report is submitted. The configured value is clamped to 60–120 seconds. Restart the server after changing it.

The packet capture handler runs on the player's Netty connection and forwards data into a bounded, thread-safe in-memory buffer. Replay serialization and file writing run asynchronously. Entity movement is recorded through the version-specific adapter as Flashback `move_entities` actions.

## Player command

```text
/report <Spieler> <Grund>
```

The target must be online and have a complete buffer. A player cannot report themselves. The reason is limited by `reports.max-reason-length` (default 200 characters). Each reporter has a cooldown (default 60 seconds).

## Staff commands

| Command | Effect |
|---|---|
| `/reports` or `/reports list` | Opens the interactive staff GUI for players; the console receives a text list |
| `/reports view <ID>` | Opens the report detail GUI for players; the console receives text details |
| `/reports offend <ID>` | Invokes the existing `/offend <player>` command as the staff sender; normal permission and Bedrock checks still apply |
| `/reports resolve <ID>` | Marks a report as resolved |
| `/reports dismiss <ID>` | Dismisses a report |

The replay path in `/reports view` is clickable to copy. Download the `.flashback` file from the server using normal server file access, then open it with the Flashback client mod. The replay is not streamed to the staff member's Minecraft client.

## Permissions

- `thatsaban.report` (default: true): submit player reports.
- `thatsaban.reports` (default: op): list and manage reports.
- `thatsaban.reports.notify` (default: op): receive new-report notifications.

## Storage and privacy

Report metadata is stored in `plugins/ThatsABan/reports.yml`; replay files are stored under `plugins/ThatsABan/reports/`. Metadata includes reporter and target UUIDs/names, the reason, timestamp, status and replay path. Replays contain captured gameplay packets and may include player-visible chat or other in-game content.

Restrict access to the report directory and backups to trusted staff. Define a retention and deletion policy for your server. The embedded Flashback engine does not send telemetry or upload clips anywhere; files remain on the server until an administrator removes them.

## Attribution and license

The embedded recording engine is derived from [Zeffut/FlashbackServer](https://github.com/Zeffut/FlashbackServer), commit `2a1549593b78b4bce3cb3f9d48f9869d4e39c513`, under the MIT License. The full upstream license notice is included at `licenses/FlashbackServer-MIT-LICENSE`.
