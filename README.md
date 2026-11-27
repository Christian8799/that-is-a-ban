# That's a Ban

**That's a Ban** is a Paper plugin that connects existing anti-cheat plugins with staff moderation and privacy tools. It does **not** implement its own cheat-detection checks. Layer 1/Layer 2 permission handling is provided through LuckPerms.

The plugin also includes an optional protected admin spectator mode, a local GeoIP region filter, and an opt-in pseudonymous account-link network.

**Documentation:** [English](README.md) · [Deutsch](README.de.md) · [Player reports and replay clips](docs/REPORTS.md)

## Dependency links

The links below point to the official project pages, source repositories, or download pages. Optional integrations are only needed when you use the corresponding feature; they are not bundled into the That'sABan JAR.

### Server platform and build runtime

| Dependency | Version / role | Link |
|---|---|---|
| Paper | Required server platform; API target 1.21.11; replay adapters for 1.21.11 and 26.3 | [Paper downloads](https://papermc.io/downloads/paper) · [Documentation](https://docs.papermc.io/paper/) |
| Java | Required runtime: Java 21 for Paper 1.21.11; Java 25 for Paper 26.3 | [Eclipse Temurin JDK 21](https://adoptium.net/temurin/releases/?version=21) |
| Gradle | Build system | [Gradle](https://gradle.org/) |

### Direct Gradle dependencies

| Dependency | Gradle coordinate | Link |
|---|---|---|
| Paper API | `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT` (`compileOnly`) | [Paper API](https://github.com/PaperMC/Paper) |
| LuckPerms API | `net.luckperms:api:5.4` (`compileOnly`) | [LuckPerms](https://luckperms.net/) · [API source](https://github.com/LuckPerms/LuckPerms) |
| ProtocolLib | `net.dmulloy2:ProtocolLib:5.5.0-SNAPSHOT` (`compileOnly`) | [Download ProtocolLib 5.5.0-SNAPSHOT server JAR](https://github.com/dmulloy2/ProtocolLib/releases/download/dev-build/ProtocolLib.jar) · [Source](https://github.com/dmulloy2/ProtocolLib) |
| IP2Location Java | `com.ip2location:ip2location-java:8.13.0` (`implementation`; included in the plugin JAR) | [Official Java library](https://github.com/ip2location/ip2location-java) · [Documentation](https://www.ip2location.com/documentation/ip2location-libraries/java) |

### Optional server-plugin integrations

| Plugin | Used for | Link |
|---|---|---|
| SuperVanish | Optional vanish mode and permanent-admin vanish enforcement | [SpigotMC resource](https://www.spigotmc.org/resources/supervanish-be-invisible.1331/) · [Source](https://github.com/LeonMangler/SuperVanish) |
| Flashback replay engine | Embedded in That'sABan; no separate server plugin | Native `.flashback` report replays; staff clients need [Flashback](https://modrinth.com/mod/flashback) and the [That'sABan Replay Viewer](https://github.com/Christian8799/ThatsABan-ReplayViewer) Fabric mod |
| GrimAC | Optional Layer 1 anti-cheat integration | [Official source](https://github.com/GrimAnticheat/Grim) · [Modrinth](https://modrinth.com/plugin/grim) |
| CheatDetector | Optional Layer 1 integration | [SpigotMC resource](https://www.spigotmc.org/resources/cheatdetector.134018/) |
| FoxGate | Optional Layer 2 AntiVPN integration | [Modrinth](https://modrinth.com/plugin/foxgate) · [Source](https://github.com/IDCTeam-Group/FoxGate-Issues) |
| Hand Shaker | Optional Layer 2 integration | [Modrinth](https://modrinth.com/plugin/hand-shaker) · [Source](https://github.com/LyingYi/Hand-Shaker) |
| Geyser | Optional Bedrock-player detection | [Official downloads](https://geysermc.org/download) · [Source](https://github.com/GeyserMC/Geyser) |
| Floodgate | Optional Bedrock-player detection/API | [Hangar](https://hangar.papermc.io/GeyserMC/Floodgate) · [Source](https://github.com/GeyserMC/Floodgate) |

### External GeoIP database

| Dependency | Role | Link |
|---|---|---|
| IP2Location LITE DB3 BIN (IPv6) | Optional external database file for the local country/region filter; not bundled in the JAR | [Download DB3](https://lite.ip2location.com/database/db3-ip-country-region-city) · [License and terms](https://lite.ip2location.com/) |



## Contents

- [Requirements and optional integrations](#requirements-and-optional-integrations)
- [Player reports and replay clips](docs/REPORTS.md)
- [Installation](#installation)
- [How Layer 1 and Layer 2 work](#how-layer-1-and-layer-2-work)
- [Configuration reference](#configuration-reference)
- [Commands](#commands)
- [Permissions](#permissions)
- [Protected admin spectator](#protected-admin-spectator)
- [Account-network limitations](#account-network-limitations)
- [Privacy and GDPR](#privacy-and-gdpr)
- [Install the GeoIP database](#install-the-geoip-database)
- [Proxy and network setup](#proxy-and-network-setup)
- [Build](#build)
- [Troubleshooting](#troubleshooting)

---

## Requirements and optional integrations

| Component | Status | Purpose |
|---|---|---|
| Paper 1.21.11 / 26.3 | Required | Both versions supported; replay capture uses a version-specific embedded NMS adapter |
| Java 21 / 25 | Required | Java 21 for Paper 1.21.11; Java 25 for Paper 26.3 |
| LuckPerms | Optional; required for Layer 2 | Permission markers, Layer 2 nodes, `/offend`, and LuckPerms-backed privacy actions |
| ProtocolLib 5.5.0-SNAPSHOT | Optional; required for `/adminspec` | Packet translation for protected admin spectating |
| SuperVanish | Optional | Explicit vanish observation mode and permanent-admin vanish enforcement |
| Flashback replay engine | Embedded; no separate server plugin required | Rolling `.flashback` evidence clips for player reports; see [report setup](docs/REPORTS.md) |
| IP2Location LITE DB3 BIN (IPv6) | External optional file | Local country/region filter |
| GrimAC, CheatDetector | Optional integrations | Listed as Layer 1 integrations by default |
| FoxGate, HandShaker | Optional integrations | Listed as Layer 2 integrations by default |
| Geyser/Floodgate | Optional | Additional Bedrock-player detection |

Optional plugins are not bundled into the That'sABan JAR. If an integration is unavailable, only its dependent feature should be disabled where possible:

- Without LuckPerms, the LinkStore, local GeoIP checks, and store-backed `/thatsaban info` and `/thatsaban network` remain available. Layer 2, `/offend`, `/thatsaban unoffend`, and `/thatsaban forget` require LuckPerms.
- Without ProtocolLib, or if required packet mappings are incompatible, `/adminspec` is disabled.
- Without SuperVanish, normal admin spectating remains available. The explicit `vanish` argument requires SuperVanish. Permanent admin accounts are hidden using Bukkit visibility as a fallback.
- If the GeoIP database is missing or unreadable at startup, only the country/region filter is disabled. If the database is loaded but an individual lookup fails, the login is denied (fail closed).

## Installation

1. Install Paper 1.21.11 with Java 21, or Paper 26.3 with Java 25.
2. Put the That'sABan JAR in the server's `plugins` directory and start the server once. This creates `plugins/ThatsABan/config.yml`.
3. Stop the server and edit the configuration as needed.
4. Install LuckPerms for Layer 2. Download the [ProtocolLib 5.5.0-SNAPSHOT server plugin JAR](https://github.com/dmulloy2/ProtocolLib/releases/download/dev-build/ProtocolLib.jar) for `/adminspec`. Report replay capture is embedded; staff clients need both the [Flashback client mod](https://modrinth.com/mod/flashback) and the [That'sABan Replay Viewer Fabric mod](https://github.com/Christian8799/ThatsABan-ReplayViewer). See [Player reports and replay clips](docs/REPORTS.md) and the [Replay Viewer setup guide](https://github.com/Christian8799/ThatsABan-ReplayViewer#installation).
5. If you use the country/region filter, install the IP2Location database as described in [Install the GeoIP database](#install-the-geoip-database).
6. Start the server and check the startup log for disabled optional features or missing integrations.
7. Grant staff permissions deliberately; see [Permissions](#permissions).

Restart the server after changing `config.yml`. There is no plugin-specific reload command. Do not edit the file while the server is running.

### Layer 2 bypass for the default group

The default Layer 2 integration list contains FoxGate and HandShaker. To make their bypass permissions available to normal players, while That'sABan removes those bypasses from Layer 2 players, set the nodes on the LuckPerms default group:

```text
/lp group default permission set foxav.bypass true
/lp group default permission set handshaker.bypass true
```

The exact nodes must match the installed versions of your anti-cheat plugins. If you use different plugins, update both `layer2.negated-permissions` and the integration list.

---

## How Layer 1 and Layer 2 work

| Layer | Meaning |
|---|---|
| Layer 1 | Default state. Anti-cheat plugins listed in `integrations.layer1-plugins` are expected to protect ordinary players. |
| Layer 2 | Additional staff classification. `/offend` applies the `thatsaban.layer2` marker and removes the configured bypass permissions. |

That'sABan does not run cheat checks itself and does not automatically ban players because of an account-network match.

Bedrock players are excluded from `/offend` and Layer 2 actions. Detection uses Floodgate/Geyser APIs when available, Floodgate UUID information, and configurable name prefixes.

---

## Configuration reference

The active file is `plugins/ThatsABan/config.yml`. Every operator-editable option shipped in the current default configuration is documented below. The complete default file is included first as a copyable reference.

### Complete default configuration

```yaml
# That's a Ban configuration (values match the shipped defaults)
config-version: 4

layer2:
  marker-permission: "thatsaban.layer2"
  negated-permissions:
    - "foxav.bypass"
    - "handshaker.bypass"
  exempt-permission: "thatsaban.exempt"

offend:
  kick-online-target: false

reports:
  enabled: true
  cooldown-seconds: 60
  max-reason-length: 200
  clip-window-seconds: 60

privacy:
  # Optional absolute path outside the plugin folder; empty = plugins/ThatsABan/ip-hmac.key.
  # THATSABAN_HMAC_KEY_PATH environment variable takes precedence.
  hmac-key-path: ""
  # Disabled by default. When enabled, audit entries contain UUIDs, not player names/IPs.
  audit-logging: false

bedrock:
  name-prefixes:
    - "."

country-filter:
  enabled: true
  database-file: "geoip/IP2LOCATION-LITE-DB3.IPV6.BIN"
  allowed-region-names:
    - "Bayern"
    - "Bavaria"
  allow-private-addresses: false

alt-detection:
  enabled: false
  retention-days: 30
  offender-retention-days: 365
  auto-apply: false
  retroactive: false
  ignore-private-addresses: true
  ignored-ips: []

integrations:
  layer1-plugins:
    - "GrimAC"
    - "CheatDetector"
  layer2-plugins:
    - "FoxGate"
    - "HandShaker"

messages:
  prefix: "<gray>[<red>that-s-a-ban</red>]</gray> "
  usage: "<red>Benutzung: /offend Spielername"
  player-not-found: "<red>Spieler <white><player></white> ist dem Server nicht bekannt."
  bedrock-immune: "<yellow><player></yellow> ist ein Bedrock-Spieler und immun gegen /offend."
  exempt: "<yellow><player></yellow> ist ausgenommen (thatsaban.exempt)."
  already: "<yellow><player></yellow> ist bereits in Layer 2."
  done: "<green><player></green> wurde in <red>Layer 2</red> versetzt."
  done-alts: "<gray>Zusätzlich automatisch in Layer 2 versetzt: <white><alts></white>"
  error: "<red>Layer 2 konnte nicht gesetzt werden - siehe Konsole."
  kick-relogin: "<red>Deine Sicherheitsstufe wurde geändert. Bitte verbinde dich erneut."
  alt-suggestion-staff: "<yellow>Prüfung erforderlich:</yellow> <white><player></white> teilt eine markierte IP. Vor einer Einstufung bitte Kontext prüfen; bei Bestätigung /offend <player> verwenden."
  alt-auto-applied-staff: "<red>Automatische Einstufung:</red> <white><player></white> wurde wegen einer IP-Übereinstimmung in Layer 2 versetzt."
  country-denied: "<red>Verbindungen sind nur mit einer bayerischen IP-Adresse erlaubt."
  country-lookup-failed: "<red>Deine IP-Adresse konnte lokal nicht geografisch zugeordnet werden. Bitte versuche es später erneut."
```

### General YAML rules

- YAML indentation matters. Use spaces, never tabs.
- Indent list entries below their key and prefix each item with `-`.
- Quote strings containing special characters, colons, or MiniMessage tags.
- `config-version` is an internal migration version. Do not change it manually.
- Older configuration files may be migrated and receive privacy-friendly defaults.
- Changes take effect after the next server/plugin start.

### Complete list of operator-editable options

| Key | Default | Description and accepted values |
|---|---|---|
| `config-version` | `4` | Internal migration version. Do not edit manually. |
| `layer2.marker-permission` | `thatsaban.layer2` | LuckPerms marker node identifying a Layer 2 player. |
| `layer2.negated-permissions` | `foxav.bypass`, `handshaker.bypass` | List of bypass nodes removed/negated for Layer 2 players. Adapt these to your installed anti-cheat plugins. |
| `layer2.exempt-permission` | `thatsaban.exempt` | Players with this node are exempt from `/offend` and IP linking. |
| `offend.kick-online-target` | `false` | If true, kick an online target after a successful `/offend` action using `messages.kick-relogin`. |
| `reports.enabled` | `true` | Enables player reports. Uses the embedded Flashback replay engine; no separate server plugin is required. |
| `reports.cooldown-seconds` | `60` | Minimum time between reports submitted by the same player. |
| `reports.max-reason-length` | `200` | Maximum report reason length in characters. |
| `reports.clip-window-seconds` | `60` | Rolling replay keyframe interval in seconds; default 60, and saved clips may be up to twice as long. Restart required after changes. |
| `privacy.hmac-key-path` | empty | Optional absolute path for the HMAC secret. Empty uses `plugins/ThatsABan/ip-hmac.key`. |
| `THATSABAN_HMAC_KEY_PATH` | unset | Environment variable overriding `privacy.hmac-key-path`. Use an absolute path to store the key outside the plugin folder. |
| `privacy.audit-logging` | `false` | Logs successful `/offend` actions with actor and target UUIDs only; no player names or IP addresses. |
| `bedrock.name-prefixes` | `["."]` | Additional Bedrock name prefixes. A matching non-empty prefix classifies the player as Bedrock. |
| `country-filter.enabled` | `true` | Enables/disables GeoIP region checks during login. |
| `country-filter.database-file` | `geoip/IP2LOCATION-LITE-DB3.IPV6.BIN` | Database path relative to the plugin data folder. |
| `country-filter.allowed-region-names` | `Bayern`, `Bavaria` | Allowed region names. The filter also requires country code Germany (`DE`). |
| `country-filter.allow-private-addresses` | `false` | If true, private, loopback, link-local and any-local addresses bypass GeoIP lookup. |
| `alt-detection.enabled` | `false` | Master switch for IP-based account linking. When false, no new IP links are collected and the network feature is disabled. |
| `alt-detection.retention-days` | `30` | Retention period for IP HMACs and links, in days. Effective range: 1–3650. |
| `alt-detection.offender-retention-days` | `365` | Retention for plugin-managed Layer 2/offender marks since last activity. Effective range: 1–3650 days; `0` = never expires (permanent Layer 2). |
| `alt-detection.auto-apply` | `false` | If true, matching accounts can automatically be classified into Layer 2. If false, staff receive suggestions only. |
| `alt-detection.retroactive` | `false` | If true, existing stored IP matches are considered at login. This does not enable automatic classification by itself. |
| `alt-detection.ignore-private-addresses` | `true` | Excludes loopback, private, link-local and any-local addresses from IP linking. |
| `alt-detection.ignored-ips` | `[]` | Exact IP text values excluded from linking. Values must match Java's host-address representation. |
| `integrations.layer1-plugins` | `GrimAC`, `CheatDetector` | Plugin names checked/reported as Layer 1 integrations at startup. Diagnostic list only; does not install or configure them. |
| `integrations.layer2-plugins` | `FoxGate`, `HandShaker` | Plugin names checked/reported as Layer 2 integrations at startup. Actual permission changes use `layer2.negated-permissions` and LuckPerms. |
| `messages.*` | see below | All message values can be customized using Adventure MiniMessage. |

#### Message keys and placeholders

All `messages.*` values can be edited. Adventure MiniMessage tags such as `<red>`, `<green>`, `<yellow>`, `<gray>`, `<white>`, and `<bold>` are supported.

| Key | Purpose | Placeholders |
|---|---|---|
| `messages.prefix` | Prefix added by the message helper | — |
| `messages.usage` | `/offend` usage message | — |
| `messages.player-not-found` | Target player is unknown | `<player>` |
| `messages.bedrock-immune` | Target is a Bedrock player | `<player>` |
| `messages.exempt` | Target has the exempt permission | `<player>` |
| `messages.already` | Target is already Layer 2 | `<player>` |
| `messages.done` | Layer 2 assignment succeeded | `<player>` |
| `messages.done-alts` | Additional accounts classified | `<alts>` |
| `messages.error` | Layer 2 action failed | — |
| `messages.kick-relogin` | Kick message when `offend.kick-online-target` is true | — |
| `messages.alt-suggestion-staff` | Staff suggestion when auto-apply is off | `<player>` |
| `messages.alt-auto-applied-staff` | Staff notice after automatic classification | `<player>` |
| `messages.country-denied` | Login rejected because the region is not allowed | — |
| `messages.country-lookup-failed` | GeoIP lookup failed for this login | — |

The prefix is added to messages sent through the message helper. Country-filter messages are used as login-denial reasons without the prefix. Keep placeholders unchanged so the plugin can replace them.

### Plugin-managed configuration data (do not edit)

The spectator feature stores state in the main config file when necessary. These keys are internal state, **not operator settings**:

| Key | Purpose |
|---|---|
| `admin-spectator.permanent-players` | UUIDs of permanently forced Spectator accounts |
| `admin-spectator.permanent-snapshots` | Saved original state of permanent accounts |
| `admin-spectator.pending-permanent-restores` | Restores queued for offline/later processing |

Do not manually edit, delete, or rename these sections. Doing so can prevent restoration of the original game mode or vanish state.

There are currently no additional operator-configurable spectator settings in `config.yml`. The four-chunk observation/sending distance, packet handling, and restoration behavior are implemented in code.

### Alt-detection examples

**Enable network collection with manual staff review:**

```yaml
alt-detection:
  enabled: true
  retention-days: 30
  offender-retention-days: 365
  auto-apply: false
  retroactive: false
  ignore-private-addresses: true
  ignored-ips: []
```

Matches are reported to staff with `thatsaban.notify`; staff can review the context and deliberately use `/offend <player>`.

Set `auto-apply: true` only if you accept automatic Layer 2 classification. Set `retroactive: true` only if previously stored matches should also be considered. `retroactive` alone does not enable auto-apply.

**Important:** A shared IP address does not prove that two accounts belong to the same person. Families, shared homes, schools, CGNAT, mobile networks and VPN exits can connect unrelated players. Treat the network as a staff lead, never as an automatic ban reason.

IPv6 addresses are truncated to /64 before HMAC generation. Raw IP addresses are not stored in the LinkStore database. HMAC values are still pseudonymous and may remain personal data.

### HMAC key handling

- Never delete the key or share it between unrelated servers.
- If the key is lost, existing IP-HMAC links can no longer be compared with newly collected addresses.
- To move the key for the first time, stop the server and securely move the existing key to the new location before restarting. A newly generated key cannot reproduce existing hashes.
- A separate key path does not protect against a compromised server account or backups containing both the database and key.
- The plugin creates `data.yml` and the key with restrictive file permissions, but operators must also secure filesystem access and backups.

---

## Commands

### Layer 2

| Command | Description | Permission |
|---|---|---|
| `/offend <player>` | Deliberately classifies a Java player into Layer 2 | `thatsaban.offend` |
| `/report <player> <reason>` | Submits a report and saves the target's rolling replay clip | `thatsaban.report` |
| `/reports` | Opens the interactive staff GUI with filters, pagination and report details | `thatsaban.reports` |
| `/reports [view|offend|resolve|dismiss] <ID>` | Opens a detail view or performs the selected staff action; subcommands also work from console | `thatsaban.reports`; `thatsaban.offend` for offend |
| `/thatsaban unoffend <player|UUID>` | Removes the plugin-managed Layer 2/offender classification | `thatsaban.layer2.remove` or `thatsaban.admin` |

### Privacy and account-network commands

| Command | Description | Permission |
|---|---|---|
| `/thatsaban info <player|UUID>` | Shows the stored plugin record and, when LuckPerms is available, relevant Layer 2 details | `thatsaban.privacy.read` or `thatsaban.admin` |
| `/thatsaban network <player|UUID> [1-3]` | Shows direct/indirect IP-HMAC links. Default depth 2, maximum depth 3 and 50 additional accounts | `thatsaban.network` or `thatsaban.admin` |
| `/thatsaban forget <player|UUID> confirm` | Deletes the plugin record, player references, IP links and directly plugin-managed Layer 2 nodes | `thatsaban.privacy.erase` or `thatsaban.admin` |

Without LuckPerms, `/thatsaban info` and `/thatsaban network` remain available as store-only queries. The network command still requires `alt-detection.enabled: true` and a writable LinkStore. `unoffend` and `forget` require LuckPerms.

Deletion does not remove unrelated LuckPerms permissions assigned by other plugins or groups. Inherited or context-specific nodes must be reviewed separately.

---

## Permissions

Defaults below come from `plugin.yml`. `op` means granted to server operators by default; `false` means not granted by default.

| Permission | Default | Purpose |
|---|---|---|
| `thatsaban.admin` | op | Additional umbrella permission accepted by privacy administration commands |
| `thatsaban.offend` | op | Use `/offend` |
| `thatsaban.report` | true | Submit `/report` |
| `thatsaban.reports` | op | View and manage reports |
| `thatsaban.reports.notify` | op | Receive new-report notifications |
| `thatsaban.privacy.read` | op | Use `/thatsaban info` |
| `thatsaban.layer2.remove` | op | Use `/thatsaban unoffend` |
| `thatsaban.privacy.erase` | op | Use `/thatsaban forget` |
| `thatsaban.network` | op | Use `/thatsaban network` |
| `thatsaban.notify` | op | Receive staff notices about IP matches |
| `thatsaban.view-linked-accounts` | op | See linked account names; otherwise UUIDs are shown |
| `thatsaban.spectator` | op | Use `/adminspec` |
| `thatsaban.spectator.permanent` | op | Manage permanent spectator accounts; plugin.yml declares this as a child of `thatsaban.spectator` |
| `thatsaban.layer2` | false | LuckPerms marker node for Layer 2 |
| `thatsaban.exempt` | false | Exemption from `/offend` and IP linking |

Privacy command handlers explicitly accept `thatsaban.admin` as an alternative to their individual permissions. It is not a Bukkit permission inheritance rule for every other plugin permission.

Permanent spectator management checks `thatsaban.spectator.permanent`; the child permission also grants `thatsaban.spectator`.

---

## Protected admin spectator

Admin spectating requires ProtocolLib. It translates the coordinates sent to the observing client into a separate coordinate space and filters or suppresses position payloads that cannot safely be translated. The target player is not teleported.

### Commands

| Command | Effect |
|---|---|
| `/adminspec observe <player>` | Starts protected observation without SuperVanish |
| `/adminspec observe <player> vanish` | Starts observation and additionally activates SuperVanish |
| `/adminspec off` | Ends observation and restores the moderator's saved state |
| `/adminspec permanent add <player|UUID>` | Permanently enforces Spectator for the account and saves its previous state |
| `/adminspec permanent remove <player|UUID>` | Removes permanent enforcement and restores the saved state |

The target must be online for `observe`. Permanent accounts can be managed by known player name or UUID, including from the server console.

### Dependencies and behavior

- ProtocolLib is required for `/adminspec`. Use the ProtocolLib 5.5.0-SNAPSHOT Paper/server plugin build. A platform-neutral Maven artifact is not an installable server plugin.
- SuperVanish is used only for the explicit `vanish` mode and vanish enforcement for permanent accounts.
- Without SuperVanish, normal observation remains available. Permanent accounts are hidden using Bukkit player visibility.
- The observer is hidden from players who are already online and players who join during the session.
- The observer's view and send distances are reduced to four chunks during observation.
- The coordinate offset remains stable when switching targets. A target is rejected if the translated position would exceed the safe client coordinate range.
- `/adminspec off` restores the saved location, game mode, flight state, invulnerability, movement speeds, view distances and, where applicable, the previous vanish state.
- Permanent accounts are re-enforced on login and periodically.

### Packet coverage and compatibility

This feature is version-sensitive and is not a complete anti-cheat or anonymity guarantee. Known position packets are translated; packet types and payloads whose position data cannot be reliably transformed are suppressed. This includes some particle/explosion payloads and custom plugin payloads. Other plugin features may therefore be limited for the observing moderator, including voice-chat data.

At startup, the integration checks required ProtocolLib packet mappings. If a required mapping is unavailable, the spectator feature is disabled rather than started with incomplete packet coverage.

Before production use, test the exact Paper, ProtocolLib, client, SuperVanish and anti-cheat versions together. A real two-client test with packet capture/inspection is required to independently verify coordinate separation.

---

## Account-network limitations

The network shows accounts that used the same IP HMACs during the retention period. It does not establish identity.

- Dynamic IP addresses can cause links to be missed.
- Families, shared homes, schools, universities, CGNAT, mobile networks and VPN exits can connect unrelated players.
- Indirect links across several accounts are less reliable than direct matches.
- Bedrock players may appear in the network view when collection is enabled, but remain exempt from `/offend` and Layer 2 actions.
- `thatsaban.exempt` excludes an account from `/offend` and IP linking.
- Only staff with `thatsaban.view-linked-accounts` see account names. Others see UUIDs.
- There is no automatic ban feature based on a network match.

The defaults `enabled: false`, `auto-apply: false` and `retroactive: false` are intentionally conservative.

---

## Privacy and GDPR

These technical safeguards do not automatically make a server GDPR-compliant. The server operator remains responsible for purpose, legal basis, necessity, retention, access control and player notices. See [docs/PRIVACY.md](docs/PRIVACY.md) for a template and sample processing record.

- GeoIP checks use a local database. That'sABan does not send player IPs to an online GeoIP service.
- The account network stores HMAC values instead of raw IP addresses. HMAC values, UUIDs, names and Layer 2 records may still be personal or pseudonymous data.
- Store the HMAC key securely and separately. A backup containing both the database and key reduces the practical protection.
- The plugin does not create full data backups. Old `data.yml.bak-*` and `data.yml.corrupt-*` files are removed at startup. A damaged `data.yml` is not overwritten unsafely; the LinkStore blocks writes in that state.
- IP addresses may still appear in proxy, firewall, host, Paper or other plugin logs. Those systems need their own retention and deletion policies.
- Deleting a That'sABan record does not delete the entire LuckPerms user record, because it may contain unrelated permissions.

### Older plugin versions

Versions before commit `b34c644` used the external ipwho.is service for GeoIP checks. The raw IP was transmitted as part of the HTTPS URL and could also appear in earlier error logs. Do not use versions before `b34c644`.

The older GitHub Actions artifact from run #43 was built from an earlier commit and must not be used as the current version: [Workflow run #43](https://github.com/Christian8799/that-is-a-ban/actions/runs/45642064635).

---

## Install the GeoIP database

1. Download the **IP2Location LITE DB3 BIN** database from [IP2Location LITE](https://lite.ip2location.com/database/db3-ip-country-region-city).
2. Review the current IP2Location license. The database is not included in this repository or plugin JAR.
3. By default, place the IPv6 BIN file here:

   ```text
   plugins/ThatsABan/geoip/IP2LOCATION-LITE-DB3.IPV6.BIN
   ```

4. Ensure `country-filter.database-file` points to the actual file.
5. Restart the server and check the startup log for confirmation that the local database loaded.
6. IP2Location requires attribution. Include this in publicly accessible server information:

   **This server uses the IP2Location LITE database for IP geolocation.**  
   https://www.ip2location.com

The IP2Location Java library is included under the MIT license. The database has separate IP2Location license terms.

---

## Proxy and network setup

Behind BungeeCord or Velocity, the real player IP must be forwarded correctly to Paper. Without correct forwarding, That'sABan may only see the proxy IP. This affects both GeoIP region checks and the IP-based account network.

Verify that:

- Paper and proxy forwarding are configured correctly and securely.
- The backend server is not directly exposed to the public internet without protection.
- The IP received by Paper's `AsyncPlayerPreLoginEvent` is the actual client IP.
- Proxy, firewall and hosting logs have their own retention periods.

---

## Build

Requirements: Java 21 and Java 25 toolchains plus Gradle 9.9.3.

```bash
gradle build
```

The plugin artifact is created in `build/libs/`. GitHub Actions builds the shared plugin/core with Java 21 and both NMS adapters (1.21.11 and 26.3) with their matching Paperweight toolchains.

---

## Troubleshooting

| Symptom / log message | Possible cause and action |
|---|---|
| Country/region filter disabled | BIN file is missing, `country-filter.database-file` is wrong, or the file is unreadable. Place the database and restart. |
| Login denied because GeoIP lookup failed | Database loaded, but the individual lookup returned no usable result. Check the database file/license version and IP mapping. |
| Layer 2 or `/offend` disabled | LuckPerms is missing, disabled or its API failed to initialize. Check LuckPerms startup logs and compatibility. |
| `/adminspec` disabled | ProtocolLib is missing, disabled, incompatible, or a required packet mapping is unavailable. Install a compatible Paper 1.21.11 server JAR and inspect the log. |
| `/adminspec observe ... vanish` fails | SuperVanish is missing or its API is incompatible. Omit `vanish` to use normal observation. |
| Account network unavailable | `alt-detection.enabled` is false or the LinkStore is read-only. Check the configuration and `data.yml`. |
| Shared IP connects multiple accounts | This is a possible false positive. Do not treat it as proof of identity. |
