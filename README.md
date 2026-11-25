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
- **IP-Kopplung ist standardmäßig deaktiviert** (`alt-detection.enabled: false`). Wird sie aktiviert, speichert das Plugin keine Roh-IP, sondern HMAC-SHA-256-Werte. IPv6 wird vor dem HMAC auf /64 gekürzt.
- Der geheime HMAC-Schlüssel liegt getrennt in `plugins/ThatsABan/ip-hmac.key`; `data.yml` und der Schlüssel werden mit owner-only-Dateirechten angelegt. Der Schlüssel darf nicht gelöscht oder mit anderen Servern geteilt werden. Geht er verloren, werden gespeicherte IP-Verknüpfungen beim nächsten Start verworfen.
- IP-Hashes und zugehörige Spielerreferenzen werden nach `alt-detection.retention-days` gelöscht (Standard: 30 Tage).
- Offender-Markierungen und die zugehörigen Layer-2-Nodes laufen nach `alt-detection.offender-retention-days` ab (Standard: **365 Tage seit dem letzten Login**, maximal 3650 Tage). Abgelaufene Layer-2-Nodes werden auch bei Offline-Spielern aus LuckPerms entfernt.
- **Shared-IP-Fehlalarme:** Familie, WG, Schule, CGNAT und Mobilfunk können dieselbe öffentliche IP verwenden. Deshalb sind `alt-detection.auto-apply` und `alt-detection.retroactive` standardmäßig `false`. Eine Übereinstimmung erzeugt zunächst nur einen Hinweis für Staff mit `thatsaban.notify`. Erst nach Prüfung kann Staff mit `/offend <Spieler>` bestätigen. Automatische oder rückwirkende Einstufung ist nur nach bewusster Konfigurationsänderung aktiv.
- **Bedrock-Spieler (Geyser/Floodgate) sind immun** gegen `/offend` und IP-Kopplung.
- **`thatsaban.exempt`** nimmt einzelne Spieler von `/offend` und der IP-Kopplung aus.

## Datenschutzbefehle

| Befehl | Zweck | Permission |
|---|---|---|
| `/thatsaban info <Spieler|UUID>` | Auskunft über Plugin-Datensatz und effektive Layer-2-LuckPerms-Nodes | `thatsaban.privacy.read` |
| `/thatsaban unoffend <Spieler|UUID>` | Layer 2 und Offender-Eintrag entfernen | `thatsaban.layer2.remove` |
| `/thatsaban forget <Spieler|UUID> confirm` | Plugin-Datensatz, Spielername, IP-Verknüpfungen und die Plugin-Layer-2-Nodes löschen | `thatsaban.privacy.erase` |

Die Löschung entfernt die UUID und den Namen aus dem eigenen `data.yml`, die Verknüpfung zu IP-Hashes und die vom Plugin gesetzten LuckPerms-Nodes. Ein HMAC-Hash, der weiterhin anderen Spielern zugeordnet ist, bleibt für diese übrigen Datensätze bestehen. Der LuckPerms-Benutzerdatensatz selbst wird nicht gelöscht, weil er auch unabhängige Berechtigungen anderer Plugins enthalten kann. Diese müssen bei einer umfassenderen Löschanfrage separat geprüft werden.

Namen von Accounts mit IP-Übereinstimmung werden nur Staff mit `thatsaban.notify` bzw. `thatsaban.view-linked-accounts` angezeigt. Das Plugin protokolliert standardmäßig keine Spieler- oder Staff-Namen. Optionales Audit-Logging (`privacy.audit-logging: true`) schreibt UUIDs, keine Namen oder IP-Adressen.

## Lokale GeoIP-Datenbank einrichten

1. Lade die **IP2Location LITE DB3 BIN**-Datenbank direkt von [IP2Location LITE](https://lite.ip2location.com/database/db3-ip-country-region-city) herunter.
2. Beachte die aktuelle Lizenz von IP2Location. Die Datenbank wird nicht im Plugin-Repository oder in der Plugin-JAR mitgeliefert.
3. Lege die IPv6-BIN-Datei unter `plugins/ThatsABan/geoip/IP2LOCATION-LITE-DB3.IPV6.BIN` ab.
4. Starte den Server neu. Fehlt die Datenbank oder kann sie nicht gelesen werden, lehnt die aktivierte Regionssperre Verbindungen ab (fail-closed).
5. IP2Location verlangt eine sichtbare Quellenangabe. Zeige diese in den öffentlich zugänglichen Serverinformationen: **This server uses the IP2Location LITE database for IP geolocation.** https://www.ip2location.com

Die IP2Location-Java-Bibliothek wird unter MIT-Lizenz eingebunden. Für die GeoIP-Datenbank gelten separat die Lizenzbedingungen von IP2Location. Die Datenbank ist nicht Teil des Plugin-Artefakts.

## Einrichtung

1. Voraussetzungen: **Paper 1.21.11**, **Java 21**, **LuckPerms**, GrimAC, CheatDetector, FoxGate und HandShaker. Geyser/Floodgate ist optional.
2. `ThatsABan-1.0.0.jar` in `plugins/` legen und den Server starten.
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
| `thatsaban.offend` | op | `/offend` benutzen |
| `thatsaban.privacy.read` | op | Plugin-Auskunft |
| `thatsaban.layer2.remove` | op | Layer 2 entfernen |
| `thatsaban.privacy.erase` | op | Plugin-Daten löschen |
| `thatsaban.notify` | op | IP-Übereinstimmungs-Hinweise |
| `thatsaban.view-linked-accounts` | op | Verknüpfte Account-Namen anzeigen |
| `thatsaban.layer2` | – | Marker-Node für Layer 2 |
| `thatsaban.exempt` | – | Ausnahme von `/offend` und IP-Kopplung |

## Datenschutz und DSGVO

Technische Schutzmaßnahmen sind keine pauschale DSGVO-Konformitätsgarantie. Der Serverbetreiber bleibt Verantwortlicher und muss insbesondere Zweck, Rechtsgrundlage, Erforderlichkeit, Speicherdauer, Zugriffe und Information der Spieler festlegen. Eine ausfüllbare Vorlage und ein Muster-Verarbeitungsverzeichnis stehen in [docs/PRIVACY.md](docs/PRIVACY.md).

- Die Regionsprüfung verwendet die lokale GeoIP-Datenbank. Das Plugin sendet dafür keine Spieler-IP an einen GeoIP-Drittanbieter und speichert keinen GeoIP-Cache.
- HMAC-Hashes, UUIDs, Namen und Layer-2-Zuordnungen können weiterhin personenbezogene oder pseudonyme Daten sein.
- `data.yml` und `ip-hmac.key` werden owner-only geschützt. Das Plugin erstellt keine vollständigen Daten-Backups. Beim Start werden alte `data.yml.bak-*`- und `data.yml.corrupt-*`-Dateien gelöscht. Eine beschädigte `data.yml` bleibt unverändert und blockiert Schreibzugriffe, statt ungesichert überschrieben zu werden.
- Die IP-Adresse kann unabhängig davon in Proxy-, Firewall-, Hoster-, Paper- oder anderen Plugin-Logs auftauchen. Diese Systeme müssen separat geprüft und mit passenden Aufbewahrungsfristen konfiguriert werden.
- Die Konfiguration begrenzt nur Daten, die dieses Plugin selbst verwaltet. Andere Plugins, LuckPerms, Hosting-Anbieter und Netzwerkkomponenten benötigen eigene Lösch- und Zugriffskonzepte.

## Hinweis zu älteren Versionen

Plugin-Stände vor **`b34c644`** verwendeten in der GeoIP-Prüfung den externen Dienst `ipwho.is`. Die Roh-IP wurde als Teil der HTTPS-URL übertragen. Der damalige Cache war ein In-Memory-Cache mit Roh-IP als Schlüssel; er wurde nicht als Cache-Datei gespeichert. In Fehlerpfaden wurde die Roh-IP zusätzlich in die Plugin-Logs geschrieben. Die Umstellung auf die lokale Datenbank erfolgte in `b34c644`, die datenschutzfreundlichen Standardwerte in `ae64e54`.

Verwende keine Plugin-Versionen vor `b34c644`. Prüfe alte Server-Logs auf frühere GeoIP-Fehlereinträge und lösche sie nach deinem dokumentierten Löschkonzept. Entferne außerdem vorhandene alte `data.yml.bak-*`- und `data.yml.corrupt-*`-Dateien sowie alte `data.yml`-IP-Hashdaten, falls du nicht auf die neue HMAC-Version migrierst. Der frühere GeoIP-Cache war flüchtig und verschwand beim Prozessende; eine persistente Cache-Datei wurde in den geprüften Commits nicht gefunden.

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
