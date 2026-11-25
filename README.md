# that-s-a-ban

Anti-Cheat-Connector für **Paper 1.21.11** (Java 21). Das Plugin bringt keine eigenen Cheat-Checks mit, sondern steuert über LuckPerms, welche Anti-Cheat-Plugins für einen Spieler greifen.

| | Layer 1 (alle) | Layer 2 |
|---|---|---|
| CheatDetector | ✅ | ✅ |
| GrimAC | ✅ | ✅ |
| FoxGate (Anti-VPN) | – | ✅ |
| HandShaker | – | ✅ |

## Funktionsweise

- **`/offend <Spieler>`** ist eine bewusste Staff-Entscheidung und setzt den Spieler in Layer 2.
- Layer 2 verwendet den LuckPerms-Marker `thatsaban.layer2` sowie die in `layer2.negated-permissions` konfigurierten Nodes.
- **Alt-Account-Netzwerk ist standardmäßig deaktiviert** (`alt-detection.enabled: false`). Nach ausdrücklicher Aktivierung werden alle erfassten Java- und Bedrock-Accounts (außer `thatsaban.exempt`) anhand gemeinsamer IP-HMACs verknüpft. Es werden keine Roh-IPs gespeichert; IPv6 wird vor dem HMAC auf /64 gekürzt. Bedrock-Accounts erscheinen nur im Netzwerk und bleiben von Layer-2-Aktionen/`/offend` ausgenommen.
- **Account-Netzwerk:** `/thatsaban network <Spieler|UUID> [1-3]` zeigt direkte und indirekte Verbindungen bis zur angegebenen Tiefe (Standard 2), maximal 50 weitere Accounts. Namen werden nur mit `thatsaban.view-linked-accounts` angezeigt; andernfalls erscheinen UUIDs. `thatsaban.network` oder `thatsaban.admin` erlaubt die Netzwerkansicht.
- **Interpretation:** Das Netzwerk ist eine begrenzte Darstellung gemeinsam beobachteter IP-HMACs, kein Beweis gemeinsamer Identität. Dynamische IPs können Verbindungen übersehen; Familien, Schulen, CGNAT, Mobilfunk und VPN-Ausgänge können unbeteiligte Accounts verbinden. Indirekte Verbindungen sind noch unsicherer. Es gibt keinen automatischen Bann oder eine automatische Einstufung aus diesem Netzwerk.
- Der geheime HMAC-Schlüssel liegt getrennt in `plugins/ThatsABan/ip-hmac.key`. Über `privacy.hmac-key-path` oder vorrangig `THATSABAN_HMAC_KEY_PATH` kann ein **absoluter Pfad außerhalb des Plugin-Ordners** festgelegt werden. `data.yml` und Schlüsseldatei erhalten owner-only-Dateirechte. Der Schlüssel darf nicht gelöscht oder mit anderen Servern geteilt werden. Geht er verloren, werden gespeicherte IP-Verknüpfungen beim nächsten Start verworfen. Bei der erstmaligen Umstellung den bisherigen Schlüssel sicher an den neuen Pfad verschieben, bevor der Server startet; ein neu erzeugter Schlüssel kann alte HMAC-Zuordnungen nicht mehr auflösen. Der externe Pfad trennt die Dateien organisatorisch, schützt aber nicht vor einem kompromittierten Serverkonto oder vor Backups, die beide Pfade gemeinsam erfassen.
- IP-Hashes und zugehörige Spielerreferenzen werden nach `alt-detection.retention-days` gelöscht (Standard: 30 Tage).
- Plugin-verwaltete Layer-2-Markierungen (einschließlich Offender) laufen nach `alt-detection.offender-retention-days` ab (Standard: **365 Tage seit dem letzten Login**, maximal 3650 Tage). Abgelaufene Layer-2-Nodes werden auch bei Offline-Spielern aus LuckPerms entfernt.
- **Shared-IP-Fehlalarme:** Familie, WG, Schule, CGNAT und Mobilfunk können dieselbe öffentliche IP verwenden. Deshalb sind `alt-detection.auto-apply` und `alt-detection.retroactive` standardmäßig `false`. Eine Übereinstimmung erzeugt zunächst nur einen Hinweis für Staff mit `thatsaban.notify`. Erst nach Prüfung kann Staff mit `/offend <Spieler>` bestätigen. Automatische oder rückwirkende Einstufung ist nur nach bewusster Konfigurationsänderung aktiv.
- **Bedrock-Spieler (Geyser/Floodgate) sind immun** gegen `/offend` und Layer-2-Aktionen; bei aktivierter Netzwerkerkennung werden ihre HMAC-IP-Verknüpfungen ausschließlich für die Netzwerkansicht erfasst.
- **`thatsaban.exempt`** nimmt einzelne Spieler von `/offend` und der IP-Kopplung aus.

## Datenschutzbefehle

| Befehl | Zweck | Permission |
|---|---|---|
| `/thatsaban info <Spieler|UUID>` | Auskunft über Plugin-Datensatz und effektive Layer-2-LuckPerms-Nodes | `thatsaban.privacy.read` |
| `/thatsaban network <Spieler|UUID> [1-3]` | Zeigt direkte/indirekte Account-Verbindungen und gemeinsame HMAC-Indizien | `thatsaban.network` |
| `/thatsaban unoffend <Spieler|UUID>` | Layer 2 und Offender-Eintrag entfernen | `thatsaban.layer2.remove` |
| `/thatsaban forget <Spieler|UUID> confirm` | Plugin-Datensatz, Spielername, IP-Verknüpfungen und die Plugin-Layer-2-Nodes löschen | `thatsaban.privacy.erase` |

Die Löschung entfernt die UUID und den Namen aus dem eigenen `data.yml`, die Verknüpfung zu IP-Hashes und die vom Plugin direkt gesetzten LuckPerms-Nodes. Vererbte Marker aus Gruppen oder kontextgebundene Nodes müssen separat in LuckPerms geprüft werden. Ein HMAC-Hash, der weiterhin anderen Spielern zugeordnet ist, bleibt für diese übrigen Datensätze bestehen. Der LuckPerms-Benutzerdatensatz selbst wird nicht gelöscht, weil er auch unabhängige Berechtigungen anderer Plugins enthalten kann. Diese müssen bei einer umfassenderen Löschanfrage separat geprüft werden.

Namen von Accounts mit IP-Übereinstimmung werden nur Staff mit `thatsaban.notify` bzw. `thatsaban.view-linked-accounts` angezeigt. Staff-Hinweise werden ausschließlich an online Spieler mit `thatsaban.notify` gesendet, nicht an die Konsole. Das Plugin protokolliert standardmäßig keine Spieler- oder Staff-Namen. Optionales Audit-Logging (`privacy.audit-logging: true`) schreibt UUIDs, keine Namen oder IP-Adressen.
- `thatsaban.admin` ist eine übergeordnete Permission für alle drei Datenschutzbefehle; alternativ können die engeren Permissions einzeln vergeben werden.
- Auf Dateisystemen ohne POSIX-Rechte verwendet das Plugin einen Java-`File`-Fallback zum Einschränken der Lese-/Schreibrechte. Kann dieser Fallback die Rechte nicht setzen, werden LinkStore-Schreibzugriffe blockiert. Die tatsächliche Zugriffskontrolle des Betriebssystems muss der Betreiber zusätzlich prüfen.

## Lokale GeoIP-Datenbank einrichten

1. Lade die **IP2Location LITE DB3 BIN**-Datenbank direkt von [IP2Location LITE](https://lite.ip2location.com/database/db3-ip-country-region-city) herunter.
2. Beachte die aktuelle Lizenz von IP2Location. Die Datenbank wird nicht im Plugin-Repository oder in der Plugin-JAR mitgeliefert.
3. Lege die IPv6-BIN-Datei unter `plugins/ThatsABan/geoip/IP2LOCATION-LITE-DB3.IPV6.BIN` ab.
4. Starte den Server neu. Fehlt die Datenbank oder kann sie nicht gelesen werden, wird ausschließlich der Country-/Region-Filter deaktiviert und im Serverlog gemeldet. Andere Plugin-Funktionen und Logins bleiben verfügbar; die Regionssperre greift in diesem Zustand nicht.
5. IP2Location verlangt eine sichtbare Quellenangabe. Zeige diese in den öffentlich zugänglichen Serverinformationen: **This server uses the IP2Location LITE database for IP geolocation.** https://www.ip2location.com

Die IP2Location-Java-Bibliothek wird unter MIT-Lizenz eingebunden. Für die GeoIP-Datenbank gelten separat die Lizenzbedingungen von IP2Location. Die Datenbank ist nicht Teil des Plugin-Artefakts.

## Einrichtung

1. Basisvoraussetzung: **Paper 1.21.11** und **Java 21**. LuckPerms, ProtocolLib, GrimAC, CheatDetector, FoxGate, HandShaker und Geyser/Floodgate sind optionale Integrationen. LuckPerms wird für Layer 2, `/offend` und die LuckPerms-abhängigen `/thatsaban`-Aktionen benötigt; ProtocolLib wird nur für `/adminspec` benötigt.
2. `ThatsABan-1.1.0.jar` in `plugins/` legen und den Server starten.
3. Die lokale GeoIP-Datenbank installieren, bevor die Regionssperre aktiviert wird.
4. Die Layer-2-Plugins für die Standardgruppe umgehen lassen:
   
```
/lp group default permission set foxav.bypass true
/lp group default permission set handshaker.bypass true
```

Layer-2-Spieler erhalten diese Nodes auf Spielerebene negiert.

## Permissions

| Permission | Standard | Bedeutung |
|---|---|---|
| `thatsaban.admin` | op | Übergeordnete Berechtigung für alle Datenschutzbefehle |
| `thatsaban.offend` | op | `/offend` benutzen |
| `thatsaban.privacy.read` | op | Plugin-Auskunft |
| `thatsaban.layer2.remove` | op | Layer 2 entfernen |
| `thatsaban.privacy.erase` | op | Plugin-Daten löschen |
| `thatsaban.network` | op | Account-Netzwerk einsehen |
| `thatsaban.notify` | op | IP-Übereinstimmungs-Hinweise |
| `thatsaban.view-linked-accounts` | op | Verknüpfte Account-Namen anzeigen |
| `thatsaban.spectator` | op | Geschützte Admin-Beobachtung und `/adminspec off` |
| `thatsaban.spectator.permanent` | op | Permanente Spectator-Accounts verwalten |
| `thatsaban.layer2` | – | Marker-Node für Layer 2 |
| `thatsaban.exempt` | – | Ausnahme von `/offend` und IP-Kopplung |

## Datenschutz und DSGVO

Technische Schutzmaßnahmen sind keine pauschale DSGVO-Konformitätsgarantie. Der Serverbetreiber bleibt Verantwortlicher und muss insbesondere Zweck, Rechtsgrundlage, Erforderlichkeit, Speicherdauer, Zugriffe und Information der Spieler festlegen. Eine ausfüllbare Vorlage und ein Muster-Verarbeitungsverzeichnis stehen in [docs/PRIVACY.md](docs/PRIVACY.md).

- Die Regionsprüfung verwendet die lokale GeoIP-Datenbank. Das Plugin sendet dafür keine Spieler-IP an einen GeoIP-Drittanbieter und speichert keinen GeoIP-Cache.
- HMAC-Hashes, UUIDs, Namen und Layer-2-Zuordnungen können weiterhin personenbezogene oder pseudonyme Daten sein.
- `data.yml` und `ip-hmac.key` werden owner-only geschützt. Das Plugin erstellt keine vollständigen Daten-Backups. Beim Start werden alte `data.yml.bak-*`- und `data.yml.corrupt-*`-Dateien gelöscht. Eine beschädigte `data.yml` bleibt unverändert und blockiert Schreibzugriffe, statt ungesichert überschrieben zu werden. In diesem Zustand melden `/offend`, `/thatsaban unoffend` und `/thatsaban forget` einen Fehler, statt eine nicht persistierbare Änderung als erfolgreich auszugeben.
- Die IP-Adresse kann unabhängig davon in Proxy-, Firewall-, Hoster-, Paper- oder anderen Plugin-Logs auftauchen. Diese Systeme müssen separat geprüft und mit passenden Aufbewahrungsfristen konfiguriert werden.
- Die Konfiguration begrenzt nur Daten, die dieses Plugin selbst verwaltet. Andere Plugins, LuckPerms, Hosting-Anbieter und Netzwerkkomponenten benötigen eigene Lösch- und Zugriffskonzepte.

## Hinweis zu älteren Versionen

Plugin-Stände vor **`b34c644`** verwendeten in der GeoIP-Prüfung den externen Dienst `ipwho.is`. Die Roh-IP wurde als Teil der HTTPS-URL übertragen. Der damalige Cache war ein In-Memory-Cache mit Roh-IP als Schlüssel; er wurde nicht als Cache-Datei gespeichert. In Fehlerpfaden wurde die Roh-IP zusätzlich in die Plugin-Logs geschrieben. Die Umstellung auf die lokale Datenbank erfolgte in `b34c644`, die datenschutzfreundlichen Standardwerte in `ae64e54`.

Verwende keine Plugin-Versionen vor `b34c644`. Ein [GitHub-Actions-Artefakt `ThatsABan-Paper-1.21.11` aus Run #43](https://github.com/Christian8799/that-is-a-ban/actions/runs/45642064635) wurde für den älteren Commit `d62c76a` gefunden (Ablaufdatum: 23.02.2027); dieses Artefakt basiert noch auf dem Stand mit externer GeoIP-Abfrage und darf nicht eingesetzt werden. Prüfe alte Server-Logs auf frühere GeoIP-Fehlereinträge und lösche sie nach deinem dokumentierten Löschkonzept. Entferne außerdem vorhandene alte `data.yml.bak-*`- und `data.yml.corrupt-*`-Dateien sowie alte `data.yml`-IP-Hashdaten, falls du nicht auf die neue HMAC-Version migrierst. Der frühere GeoIP-Cache war flüchtig und verschwand beim Prozessende; eine persistente Cache-Datei wurde in den geprüften Commits nicht gefunden.

## Hinweise

- Layer-2-Prüfungen greifen spätestens beim nächsten Login.
- Hinter BungeeCord/Velocity muss die echte Spieler-IP korrekt weitergereicht werden. Ohne korrektes Forwarding kann die Regionsprüfung die Proxy-IP statt der Spieler-IP sehen.
- GeoIP ist nur eine Näherung. VPNs, Mobilfunknetze, Proxys und falsch zugeordnete IP-Blöcke können zu Fehlentscheidungen führen.
- Bei einer gemeinsamen IP ist eine Übereinstimmung kein Beweis für dieselbe Person.

## Bauen

```
gradle build
```

Die JAR liegt danach in `build/libs/`. Der GitHub-Workflow baut mit Java 21 gegen die Paper-1.21.11-API.

## Protected Admin Spectator

The optional staff-observation workflow is provided by `/adminspec`:

- `/adminspec observe <player>` starts an observation session.
- `/adminspec off` ends the session and restores the observer's previous location, game mode, flight state, movement speeds and view distances.
- `/adminspec permanent add <player|uuid>` permanently enforces Spectator for an account.
- `/adminspec permanent remove <player|uuid>` removes that enforcement.

Optional runtime plugins: **LuckPerms** and **ProtocolLib 5.5.0-SNAPSHOT**. If ProtocolLib is missing or incompatible, only `/adminspec` is disabled. If LuckPerms is missing or incompatible, only Layer 2, `/offend` and the LuckPerms-dependent `/thatsaban` actions are disabled. The plugin itself, the LinkStore and an available Country-/Region-Filter continue to run. ProtocolLib is a separate dependency and is not bundled into the ThatsABan JAR. CI downloads the official Paper plugin asset from ProtocolLib's GitHub dev-build release; the Maven artifact is only the platform-neutral library and must not be installed as the server plugin. During an observation session, the observer's view and sending distances are reduced to four chunks. The observer is hidden from all currently online players and players who join during the session. Switching observation targets keeps the same coordinate offset for the entire session; targets too close to the client coordinate limit are rejected instead of changing coordinate space mid-session. Visibility is restored only after the observer has been returned to the saved location.

The coordinate-offset implementation is still under packet-coverage testing. It translates chunk lifecycle, block update, multi-block section, selected block-event, entity spawn/teleport/position-sync, player-position, world-border, vehicle and client movement/interaction packets. Payloads with nested or currently unparsed coordinates (including particles, biome/debug data, look-at hints, minecart movement and waypoint/test-instance hints) are suppressed for the observer rather than forwarded unchanged. Server-to-client custom plugin payloads are also suppressed while observing because their binary formats cannot be audited generically; this can temporarily disable plugin features such as voice-chat data for the observer. Entity metadata block positions and standard block-entity NBT x/z identification fields are translated. Block/entity NBT query requests are denied. Respawn packets have their last-death location removed. Explosion packets are suppressed because embedded particle options may contain additional position data. This is a conservative compatibility trade-off, not a guarantee that every coordinate-bearing packet is covered: plugins can send custom payloads or other position data, and packet layouts can change between versions. Keep the feature disabled on production servers until it has been tested with the exact Paper build, client versions and anticheat configuration in use.
