# That's a Ban

**Dokumentation:** [English](README.md) · [Deutsch](README.de.md) · [Spieler-Reports und Replay-Clips](docs/REPORTS.md)

**That's a Ban** ist ein Anti-Cheat-Connector und ein Werkzeug für Datenschutz- und Moderationsabläufe auf **Paper 1.21.11**. Das Plugin implementiert keine eigenen Cheat-Erkennungsregeln. Es verbindet vorhandene Plugins und steuert über LuckPerms, welche Anti-Cheat-Integrationen für Spieler in Layer 1 beziehungsweise Layer 2 greifen.

Neben der Layer-2-Verwaltung bietet das Plugin eine optionale, paketbasierte Admin-Beobachtung, eine lokale GeoIP-Regionsprüfung und – wenn ausdrücklich aktiviert – ein pseudonymisiertes Alt-Account-Netzwerk.

## Inhaltsverzeichnis

- [Kompatibilität und optionale Plugins](#kompatibilität-und-optionale-plugins)
- [Spieler-Reports und Replay-Clips](docs/REPORTS.md)
- [Schnellstart](#schnellstart)
- [Funktionsweise: Layer 1 und Layer 2](#funktionsweise-layer-1-und-layer-2)
- [Konfiguration vollständig](#konfiguration-vollständig)
  - [Konfigurationsdatei](#vollständige-standardkonfiguration)
  - [Layer 2 und Offend](#layer-2-und-offend)
  - [Datenschutz und HMAC-Schlüssel](#datenschutz-und-hmac-schlüssel)
  - [Bedrock-Erkennung](#bedrock-erkennung)
  - [Country-/Region-Filter](#countryregion-filter)
  - [Alt-Account-Erkennung](#alt-account-erkennung)
  - [Integrationslisten](#integrationslisten)
  - [Nachrichten](#nachrichten)
  - [Automatisch verwaltete Spectator-Daten](#automatisch-verwaltete-spectator-daten)
- [Befehle](#befehle)
- [Permissions](#permissions)
- [Geschützte Admin-Beobachtung](#geschützte-admin-beobachtung)
- [Alt-Account-Netzwerk: Grenzen und Fehlalarme](#alt-account-netzwerk-grenzen-und-fehlalarme)
- [Datenschutz und DSGVO](#datenschutz-und-dsgvo)
- [GeoIP-Datenbank installieren](#geoip-datenbank-installieren)
- [Proxy- und Netzwerkbetrieb](#proxy--und-netzwerkbetrieb)
- [Build](#build)
- [Fehlerbehebung](#fehlerbehebung)

---

## Kompatibilität und optionale Plugins

| Komponente | Status | Zweck |
|---|---|---|
| Paper 1.21.11 | Erforderlich | Serverplattform |
| Java 21 | Erforderlich | Laufzeit und Build |
| LuckPerms | Optional, für Layer 2 erforderlich | Berechtigungsmarker, Layer-2-Nodes, /offend und LuckPerms-abhängige Datenschutzaktionen |
| ProtocolLib 5.5.0-SNAPSHOT | Optional, für /adminspec erforderlich | Paketübersetzung für die geschützte Admin-Beobachtung |
| SuperVanish | Optional | Expliziter Vanish-Modus und Vanish-Durchsetzung permanenter Admin-Accounts |
| FlashbackServer | Optional; für `/report`-Clips erforderlich | Rolling-Flashback-Replay-Puffer für Spieler-Reports; siehe [Report-Einrichtung](docs/REPORTS.md) |
| IP2Location LITE DB3 BIN (IPv6) | Externe Datei, optional für den restlichen Pluginbetrieb | Lokaler Country-/Region-Filter |
| GrimAC, CheatDetector | Optionale Integrationen | In der Standardkonfiguration für Layer 1 aufgeführt |
| FoxGate, HandShaker | Optionale Integrationen | In der Standardkonfiguration für Layer 2 aufgeführt |
| Geyser/Floodgate | Optional | Zusätzliche Erkennung von Bedrock-Spielern |

Die optionalen Plugins werden nicht in die That'sABan-JAR eingebettet. Fehlt eine optionale Integration, wird nach Möglichkeit nur die davon abhängige Funktion deaktiviert:

- Ohne LuckPerms bleiben der LinkStore, die lokale GeoIP-Prüfung und die Store-basierten Teile von /thatsaban info und /thatsaban network verfügbar. Layer 2, /offend, /thatsaban unoffend und /thatsaban forget benötigen LuckPerms.
- Ohne ProtocolLib oder bei inkompatiblen Paket-Mappings wird /adminspec deaktiviert.
- Ohne SuperVanish ist die normale Admin-Beobachtung weiterhin verfügbar. Der explizite Befehl mit dem Argument vanish benötigt SuperVanish. Permanente Admin-Accounts werden ohne SuperVanish über Bukkit-Spieler-Sichtbarkeit verborgen.
- Fehlt die lokale GeoIP-Datenbank, wird nur der Country-/Region-Filter deaktiviert. Ist die Datenbank vorhanden, schlägt aber eine einzelne GeoIP-Abfrage fehl, wird der Login aus Sicherheitsgründen abgelehnt.

## Schnellstart

1. Paper 1.21.11 mit Java 21 installieren.
2. Die That'sABan-JAR in den Ordner plugins legen und den Server einmal starten. Dadurch wird plugins/ThatsABan/config.yml angelegt.
3. Server stoppen und config.yml nach Bedarf anpassen.
4. Für Layer 2 LuckPerms installieren. Für /adminspec ProtocolLib 5.5.0-SNAPSHOT installieren.
5. Wenn der Country-/Region-Filter verwendet werden soll, die IP2Location-Datenbank wie unter [GeoIP-Datenbank installieren](#geoip-datenbank-installieren) beschrieben ablegen.
6. Server starten und die Startmeldungen auf deaktivierte optionale Funktionen oder fehlende Integrationen prüfen.
7. Permissions gezielt an Staff vergeben. Die vollständige Liste steht unter [Permissions](#permissions).

Nach Änderungen an config.yml den Server neu starten. Das Plugin stellt keinen eigenen /reload-Befehl bereit. Die Konfiguration sollte nicht während des Betriebs manuell editiert werden.

### Layer-2-Bypass für die Standardgruppe

Die in der Standardkonfiguration verwendeten Layer-2-Integrationen sind FoxGate und HandShaker. Damit diese Plugins für normale Spieler umgehen, während That'sABan den Bypass für Layer-2-Spieler entzieht, müssen die Bypass-Nodes in LuckPerms für die Standardgruppe gesetzt sein:

~~~text
/lp group default permission set foxav.bypass true
/lp group default permission set handshaker.bypass true
~~~

Die konkreten Permission-Nodes müssen zu den tatsächlich installierten Versionen der jeweiligen Anti-Cheat-Plugins passen. Wenn du andere Plugins verwendest, passe sowohl die Nodes unter layer2.negated-permissions als auch die Integrationsliste an.

---

## Funktionsweise: Layer 1 und Layer 2

Die Integrationen werden über die LuckPerms-Berechtigungen gesteuert:

| Ebene | Bedeutung |
|---|---|
| Layer 1 | Standardzustand. Die unter integrations.layer1-plugins aufgeführten Anti-Cheat-Plugins sollen für normale Spieler aktiv sein. |
| Layer 2 | Zusätzliche Staff-Einstufung. /offend setzt den Marker thatsaban.layer2 und entzieht die unter layer2.negated-permissions konfigurierten Bypass-Nodes. |

That'sABan führt keine eigenen Cheat-Checks aus und bannt Spieler nicht automatisch aufgrund eines Netzwerk-Treffers.

Bedrock-Spieler werden von /offend und Layer-2-Aktionen ausgenommen. Die Erkennung nutzt Floodgate-/Geyser-APIs, die Floodgate-UUID-Eigenschaft und zusätzlich die konfigurierbaren Namenspräfixe.

---

## Konfiguration vollständig

Die aktive Konfigurationsdatei liegt unter:

~~~text
plugins/ThatsABan/config.yml
~~~

Alle vom Betreiber vorgesehenen Konfigurationsoptionen der ausgelieferten config.yml sind in den folgenden Abschnitten aufgeführt. Der vollständige Standardinhalt steht hier ebenfalls als Vorlage.

### Vollständige Standardkonfiguration

~~~yaml
# that-s-a-ban - Konfiguration
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
  minimum-clip-window-seconds: 60

privacy:
  # Optionaler absoluter Pfad außerhalb des Plugin-Ordners; leer = plugins/ThatsABan/ip-hmac.key.
  # Umgebungsvariable THATSABAN_HMAC_KEY_PATH hat Vorrang.
  hmac-key-path: ""
  # Standardmäßig keine spielerbezogenen Audit-Einträge in Paper-Logs schreiben.
  # Bei Aktivierung werden nur UUIDs (keine Spielernamen/IP-Adressen) protokolliert.
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

# Datenschutzfreundliche Voreinstellungen: IP-Kopplung bleibt ausgeschaltet.
alt-detection:
  enabled: false
  retention-days: 30
  offender-retention-days: 365 # Endliche Frist seit letzter Aktivität; maximal 3650 Tage
  auto-apply: false # false = nur Staff-Vorschläge; /offend erfordert eine bewusste Bestätigung
  retroactive: false # Bereits bekannte IP-Übereinstimmungen nicht automatisch rückwirkend einstufen
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
~~~

### Allgemeine Regeln

- YAML-Einrückungen sind relevant. Verwende Leerzeichen, keine Tabs.
- Bei Listen müssen die Einträge unter dem jeweiligen Schlüssel eingerückt und mit einem Bindestrich begonnen werden.
- Strings mit Sonderzeichen, Doppelpunkten oder MiniMessage-Tags sollten in Anführungszeichen stehen.
- config-version ist eine interne Migrationsnummer. Nicht manuell erhöhen oder zurücksetzen.
- Die Werte in der Tabelle beschreiben die ausgelieferten Standardwerte. Bei älteren Konfigurationsdateien können automatische Migrationen einzelne Werte ergänzen oder auf datenschutzfreundliche Defaults setzen.
- Änderungen werden beim nächsten Plugin-/Serverstart eingelesen.

### Layer 2 und Offend

| Schlüssel | Standard | Bedeutung und zulässige Werte |
|---|---:|---|
| config-version | 3 | Interne Versionsnummer für Konfigurationsmigrationen. Nicht manuell ändern. |
| layer2.marker-permission | thatsaban.layer2 | LuckPerms-Marker, der einen Spieler als Layer 2 kennzeichnet. Muss mit der für den Server gewünschten Marker-Node übereinstimmen. |
| layer2.negated-permissions | foxav.bypass, handshaker.bypass | Liste der Bypass-Permissions, die That'sABan einem Layer-2-Spieler entzieht beziehungsweise negiert. Passe die Nodes an deine Anti-Cheat-Plugins an. |
| layer2.exempt-permission | thatsaban.exempt | Spieler mit dieser Permission sind von /offend und der IP-Kopplung ausgenommen. |
| offend.kick-online-target | false | Bei true wird ein online Zielspieler nach erfolgreicher /offend-Einstufung mit der konfigurierten Nachricht kick-relogin getrennt. Bei false bleibt er online. |
| reports.enabled | true | Aktiviert Spieler-Reports. Für Replay-Clips wird das separate FlashbackServer-Plugin benötigt. |
| reports.cooldown-seconds | 60 | Mindestwartezeit zwischen Reports desselben Spielers. |
| reports.max-reason-length | 200 | Maximale Länge des Report-Grunds in Zeichen. |
| reports.minimum-clip-window-seconds | 60 | Mindestwert für FlashbackServer `clips.window-seconds`; gespeicherte Clips können durch Keyframe-Rotation länger sein. |

Die Marker- und Exempt-Permissions müssen in LuckPerms entsprechend deiner Gruppenstruktur vergeben werden. That'sABan legt keine Anti-Cheat-Regeln für die externen Plugins fest.

### Datenschutz und HMAC-Schlüssel

| Schlüssel | Standard | Bedeutung und zulässige Werte |
|---|---|---|
| privacy.hmac-key-path | leer | Optionaler absoluter Dateipfad für den geheimen HMAC-Schlüssel. Leer bedeutet plugins/ThatsABan/ip-hmac.key. |
| THATSABAN_HMAC_KEY_PATH | nicht gesetzt | Umgebungsvariable, die privacy.hmac-key-path übersteuert. Verwende einen absoluten Pfad außerhalb des Plugin-Ordners, wenn du den Schlüssel getrennt ablegen möchtest. |
| privacy.audit-logging | false | Bei true protokolliert das Plugin erfolgreiche /offend-Einstufungen mit der UUID des ausführenden Staff-Accounts (oder CONSOLE) und der Ziel-UUID. Spielernamen und IP-Adressen werden dabei nicht in diese Audit-Einträge geschrieben. |

**Wichtig für den HMAC-Schlüssel:**

- Der Schlüssel darf nicht gelöscht oder zwischen Servern geteilt werden.
- Bei Verlust des Schlüssels können gespeicherte IP-HMAC-Verknüpfungen nicht mehr mit neu erfassten Adressen verglichen werden.
- Bei einem erstmaligen Wechsel des Speicherorts muss der bisherige Schlüssel bei gestopptem Server sicher an den neuen Pfad verschoben werden, bevor der Server wieder startet. Ein neu erzeugter Schlüssel kann die alten HMAC-Zuordnungen nicht auflösen.
- Der getrennte Speicherort schützt nicht vor einem kompromittierten Serverkonto oder Backups, die sowohl Datenbankdatei als auch Schlüssel enthalten.
- data.yml und die Schlüsseldatei werden mit restriktiven Dateirechten angelegt. Die tatsächlichen Rechte und Server-Backups müssen vom Betreiber zusätzlich kontrolliert werden.

### Bedrock-Erkennung

| Schlüssel | Standard | Bedeutung |
|---|---|---|
| bedrock.name-prefixes | ["."] | Liste von Namenspräfixen, die als zusätzliche Bedrock-Erkennung gelten. Ein Name, der mit einem nichtleeren Eintrag beginnt, wird als Bedrock erkannt. |

Die Standardkonfiguration erkennt zusätzlich Floodgate-UUIDs sowie Spieler über die Floodgate- und Geyser-APIs, sofern diese verfügbar sind. Ändere die Präfixliste nur, wenn sie zu deiner Geyser-/Floodgate-Konfiguration passt. Ein zu allgemeines Präfix kann versehentlich Java-Spieler als Bedrock einstufen.

### Country-/Region-Filter

| Schlüssel | Standard | Bedeutung und zulässige Werte |
|---|---|---|
| country-filter.enabled | true | Aktiviert oder deaktiviert die GeoIP-Regionsprüfung beim Login. Bei false wird keine Regionssperre angewendet. |
| country-filter.database-file | geoip/IP2LOCATION-LITE-DB3.IPV6.BIN | Pfad zur lokalen IP2Location-BIN-Datei relativ zum Plugin-Datenordner. |
| country-filter.allowed-region-names | Bayern, Bavaria | Liste erlaubter Regionsnamen. Die Prüfung akzeptiert nur Deutschland (DE) und eine passende Region aus dieser Liste. Groß-/Kleinschreibung und diakritische Zeichen werden bei der Regionsnormalisierung vereinheitlicht. |
| country-filter.allow-private-addresses | false | Bei true werden lokale/private, Loopback-, Link-Local- und Any-Local-Adressen ohne GeoIP-Abfrage zugelassen. Bei false werden sie normal geprüft. |

Verhalten bei Fehlern:

- Ist country-filter.enabled false, wird die Regionsprüfung übersprungen.
- Ist die Datenbankdatei beim Start nicht vorhanden oder nicht lesbar, wird der Country-/Region-Filter vollständig deaktiviert und im Log gemeldet. Andere Funktionen laufen weiter.
- Ist die Datenbank geladen, schlägt aber eine einzelne GeoIP-Abfrage fehl oder liefert kein verwertbares Ergebnis, wird der Login abgelehnt. Das ist ein Fail-closed-Verhalten.
- Die GeoIP-Zuordnung ist nur eine Näherung. Sie kann VPNs, Mobilfunknetze, Proxys und falsch zugeordnete IP-Blöcke nicht zuverlässig erkennen.

### Alt-Account-Erkennung

Die IP-basierte Erkennung ist standardmäßig ausgeschaltet. Sie muss bewusst aktiviert werden.

| Schlüssel | Standard | Bedeutung und zulässige Werte |
|---|---:|---|
| alt-detection.enabled | false | Hauptschalter. Bei false werden keine neuen IP-Verknüpfungen erfasst und die Netzwerkfunktion ist deaktiviert. |
| alt-detection.retention-days | 30 | Aufbewahrungsdauer für IP-HMACs und zugehörige Verknüpfungen in Tagen. Effektiver Wertebereich: 1 bis 3650 Tage. |
| alt-detection.offender-retention-days | 365 | Aufbewahrungsdauer für vom Plugin verwaltete Layer-2-/Offender-Markierungen seit der letzten Aktivität. Effektiver Wertebereich: 1 bis 3650 Tage. |
| alt-detection.auto-apply | false | Bei true können erkannte Accounts mit einer bereits als Layer 2 markierten IP automatisch in Layer 2 eingestuft werden. Bei false erhalten Staff nur einen Hinweis. |
| alt-detection.retroactive | false | Bei true werden bereits gespeicherte IP-Übereinstimmungen bei der Login-Prüfung berücksichtigt. Bei false werden nur neue Übereinstimmungen verarbeitet. |
| alt-detection.ignore-private-addresses | true | Ignoriert Loopback-, private, Link-Local- und Any-Local-Adressen für die IP-Kopplung. |
| alt-detection.ignored-ips | [] | Liste exakter IP-Textwerte, die von der IP-Kopplung ausgeschlossen werden. Die Einträge müssen der von Java gelieferten Host-Adressdarstellung entsprechen. |

### Empfohlene Aktivierung mit manueller Staff-Prüfung

Wenn du das Netzwerk bewusst nutzen möchtest, aber keine automatische Einstufung willst, ändere nur:

~~~yaml
alt-detection:
  enabled: true
  retention-days: 30
  offender-retention-days: 365
  auto-apply: false
  retroactive: false
  ignore-private-addresses: true
  ignored-ips: []
~~~

Damit werden erkannte Übereinstimmungen zunächst nur Staff-Mitgliedern mit thatsaban.notify gemeldet. Staff kann den Sachverhalt prüfen und bei ausreichender Grundlage /offend <Spieler> verwenden.

### Automatische und rückwirkende Einstufung

Setze auto-apply nur dann auf true, wenn du die Konsequenzen bewusst akzeptierst. Setze retroactive nur dann auf true, wenn bereits gespeicherte IP-Übereinstimmungen ebenfalls geprüft werden sollen. retroactive allein aktiviert keine automatische Einstufung: Bei auto-apply false werden weiterhin nur Hinweise versendet.

**Wichtig:** Gemeinsame IP-Adressen sind kein Identitätsnachweis. Familie, Wohngemeinschaft, Schule, CGNAT, Mobilfunk und VPN-Ausgänge können unbeteiligte Accounts verbinden. Das Netzwerk ist ein Hinweis für Staff, kein automatischer Bann-Grund.

IPv6-Adressen werden vor der HMAC-Bildung auf /64 gekürzt. Es werden keine Roh-IP-Adressen in der LinkStore-Datenbank gespeichert. Die HMAC-Werte bleiben trotzdem pseudonyme, potenziell personenbezogene Daten.

### Integrationslisten

| Schlüssel | Standard | Bedeutung |
|---|---|---|
| integrations.layer1-plugins | GrimAC, CheatDetector | Plugin-Namen, die beim Start auf Vorhandensein geprüft und als Layer-1-Integrationen gemeldet werden. |
| integrations.layer2-plugins | FoxGate, HandShaker | Plugin-Namen, die beim Start auf Vorhandensein geprüft und als Layer-2-Integrationen gemeldet werden. |

Die Listen sind Diagnose-/Integrationshinweise. Sie installieren, konfigurieren oder aktivieren die genannten Plugins nicht selbst. Die tatsächliche Layer-2-Berechtigungssteuerung erfolgt über layer2.negated-permissions und LuckPerms.

### Nachrichten

Alle Einträge unter messages können angepasst werden. Die Nachrichten werden mit Adventure MiniMessage verarbeitet. Du kannst daher MiniMessage-Formatierungen wie <red>, <green>, <yellow>, <gray>, <white>, <bold> und weitere unterstützte Tags verwenden.

| Schlüssel | Verwendung | Platzhalter |
|---|---|---|
| messages.prefix | Präfix für Chat-Nachrichten, die über den Nachrichten-Helper gesendet werden | keine |
| messages.usage | Benutzungshinweis für /offend | keine |
| messages.player-not-found | Zielspieler wurde nicht gefunden | <player> |
| messages.bedrock-immune | Ziel ist ein Bedrock-Spieler | <player> |
| messages.exempt | Ziel besitzt die Exempt-Permission | <player> |
| messages.already | Ziel ist bereits in Layer 2 | <player> |
| messages.done | Layer-2-Einstufung erfolgreich | <player> |
| messages.done-alts | Zusätzlich eingestufte Accounts | <alts> |
| messages.error | Layer-2-Aktion fehlgeschlagen | keine |
| messages.kick-relogin | Kick-Nachricht bei offend.kick-online-target | keine |
| messages.alt-suggestion-staff | Staff-Hinweis bei IP-Übereinstimmung ohne Auto-Apply | <player> |
| messages.alt-auto-applied-staff | Staff-Hinweis bei automatischer Einstufung | <player> |
| messages.country-denied | Login außerhalb der erlaubten Region | keine |
| messages.country-lookup-failed | GeoIP-Abfrage nicht verfügbar/fehlgeschlagen | keine |

Der Prefix wird bei den Chat-Nachrichten ergänzt. Die beiden Country-Filter-Nachrichten werden ohne Prefix als Login-Ablehnungsgrund verwendet. Platzhalter sollten unverändert in der Nachricht bleiben, damit das Plugin sie mit den tatsächlichen Werten ersetzen kann.

### Automatisch verwaltete Spectator-Daten

Die Admin-Spectator-Funktion speichert ihren Zustand bei Bedarf zusätzlich in config.yml. Diese Schlüssel sind **keine manuellen Konfigurationsoptionen**:

| Pfad | Zweck |
|---|---|
| admin-spectator.permanent-players | UUID-Liste der dauerhaft festgelegten Spectator-Accounts |
| admin-spectator.permanent-snapshots | Gespeicherter ursprünglicher Zustand permanenter Accounts |
| admin-spectator.pending-permanent-restores | Wiederherstellungen, die für später/offline vorgemerkt wurden |

Diese Abschnitte werden vom Plugin verwaltet. Nicht manuell bearbeiten, löschen oder umbenennen. Ein manueller Eingriff kann dazu führen, dass der ursprüngliche Spielmodus oder Vanish-Zustand nicht mehr wiederhergestellt werden kann.

Für Admin-Spectator gibt es derzeit keine weiteren Betreiber-Settings in config.yml. Die Beobachtungsdistanz (vier Chunks), Paketbehandlung und Wiederherstellungslogik sind fest im Plugin implementiert.

---

## Befehle

### Layer 2

| Befehl | Beschreibung | Berechtigung |
|---|---|---|
| /offend <Spieler> | Setzt einen Java-Spieler nach bewusster Staff-Entscheidung in Layer 2 | thatsaban.offend |
| /report <Spieler> <Grund> | Meldet einen Spieler und speichert seinen Replay-Clip | thatsaban.report |
| /reports | Öffnet das klickbare Staff-Menü mit Filtern, Seitenwechsel und Report-Details | thatsaban.reports |
| /reports view <ID> | Öffnet die Detailansicht eines Reports | thatsaban.reports |
| /reports offend <ID> | Führt nach Prüfung die bestehende Offend-Funktion aus | thatsaban.reports und thatsaban.offend |
| /reports resolve <ID> | Markiert einen Report als erledigt | thatsaban.reports |
| /reports dismiss <ID> | Verwirft einen Report | thatsaban.reports |
| /thatsaban unoffend <Spieler\|UUID> | Entfernt die vom Plugin verwaltete Layer-2-/Offender-Einstufung | thatsaban.layer2.remove oder thatsaban.admin |

### Datenschutz- und Netzwerkbefehle

| Befehl | Beschreibung | Berechtigung |
|---|---|---|
| /thatsaban info <Spieler\|UUID> | Zeigt den gespeicherten Plugin-Datensatz und – wenn LuckPerms verfügbar ist – relevante Layer-2-Informationen | thatsaban.privacy.read oder thatsaban.admin |
| /thatsaban network <Spieler\|UUID> [1-3] | Zeigt direkte/indirekte IP-HMAC-Verbindungen. Standardtiefe 2, maximal 3; maximal 50 weitere Accounts | thatsaban.network oder thatsaban.admin |
| /thatsaban forget <Spieler\|UUID> confirm | Löscht Plugin-Datensatz, gespeicherte Spielerreferenzen, IP-Verknüpfungen und die direkt vom Plugin verwalteten Layer-2-Nodes | thatsaban.privacy.erase oder thatsaban.admin |

Wenn LuckPerms fehlt, bleiben /thatsaban info und /thatsaban network als Store-basierte Auskunft verfügbar. /thatsaban network setzt weiterhin voraus, dass alt-detection.enabled true ist und der LinkStore lesbar ist. Ohne LuckPerms können /thatsaban unoffend und /thatsaban forget nicht ausgeführt werden.

Die Löschung entfernt keine unabhängigen LuckPerms-Berechtigungen, die von anderen Plugins oder Gruppen vergeben wurden. Vererbte oder kontextgebundene Nodes müssen separat geprüft werden.

---

## Permissions

Die folgenden Standardwerte stammen aus plugin.yml. op bedeutet, dass die Berechtigung standardmäßig Operatoren gewährt wird. false bedeutet, dass sie standardmäßig niemandem gewährt wird.

| Permission | Standard | Zweck |
|---|---|---|
| thatsaban.admin | op | Zusätzliche Sammelberechtigung, die die Datenschutzbefehle als Alternative zu ihren Einzel-Permissions akzeptieren |
| thatsaban.offend | op | /offend verwenden |
| thatsaban.privacy.read | op | /thatsaban info verwenden |
| thatsaban.layer2.remove | op | /thatsaban unoffend verwenden |
| thatsaban.privacy.erase | op | /thatsaban forget verwenden |
| thatsaban.network | op | /thatsaban network verwenden |
| thatsaban.notify | op | Staff-Hinweise zu IP-Übereinstimmungen erhalten |
| thatsaban.view-linked-accounts | op | Namen verknüpfter Accounts sehen; ohne diese Permission werden UUIDs angezeigt |
| thatsaban.spectator | op | /adminspec verwenden |
| thatsaban.spectator.permanent | op | Permanente Spectator-Accounts verwalten; diese Permission erbt in plugin.yml thatsaban.spectator |
| thatsaban.layer2 | false | LuckPerms-Marker für Layer 2 |
| thatsaban.exempt | false | Ausnahme von /offend und IP-Kopplung |

Die Berechtigungen für Datenschutzbefehle können einzeln vergeben werden. thatsaban.admin ist keine automatische Bukkit-Permission-Vererbung für sämtliche anderen Plugin-Permissions; der Datenschutz-Befehlscode akzeptiert sie ausdrücklich als Alternative.

Für permanente Spectator-Verwaltung werden sowohl thatsaban.spectator als auch thatsaban.spectator.permanent geprüft. Durch die definierte Child-Permission wird thatsaban.spectator beim Vergeben von thatsaban.spectator.permanent mitgewährt.

---

## Geschützte Admin-Beobachtung

Die Admin-Beobachtung wird über ProtocolLib bereitgestellt. Sie verschiebt die dem beobachtenden Client übermittelten Koordinaten in einen getrennten Koordinatenraum und filtert beziehungsweise unterdrückt nicht sicher übersetzbare Positionsdaten. Das Ziel selbst wird nicht teleportiert.

### Befehle

| Befehl | Wirkung |
|---|---|
| /adminspec observe <Spieler> | Startet die geschützte Beobachtung ohne SuperVanish |
| /adminspec observe <Spieler> vanish | Startet die Beobachtung und aktiviert zusätzlich SuperVanish |
| /adminspec off | Beendet die Beobachtung und stellt den gespeicherten Zustand des Moderators wieder her |
| /adminspec permanent add <Spieler\|UUID> | Erzwingt dauerhaft Spectator für den Account und speichert dessen vorherigen Zustand |
| /adminspec permanent remove <Spieler\|UUID> | Entfernt die dauerhafte Erzwingung und stellt den gespeicherten Zustand wieder her |

Der Zielspieler muss bei observe online sein. Permanente Accounts können über einen bekannten Spielernamen oder eine UUID verwaltet werden; die Verwaltung funktioniert auch über die Serverkonsole.

### Abhängigkeiten und Verhalten

- ProtocolLib ist für /adminspec zwingend erforderlich. Die verwendete Paper-Server-Plugin-Version ist ProtocolLib 5.5.0-SNAPSHOT aus dem offiziellen ProtocolLib-Dev-Build. Das plattformneutrale Maven-Artefakt allein ist kein installierbares Server-Plugin.
- SuperVanish wird nur für den expliziten vanish-Modus und die Vanish-Durchsetzung permanenter Accounts verwendet.
- Ohne SuperVanish bleibt der normale Beobachtungsmodus verfügbar. Permanente Accounts werden dann über Bukkit-Spieler-Sichtbarkeit verborgen.
- Der Beobachter wird vor Spielern verborgen, die bereits online sind oder während der Sitzung beitreten.
- Während einer Beobachtung werden Sicht- und Sende-Sichtweite des Beobachters auf vier Chunks reduziert.
- Beim Wechsel des beobachteten Ziels bleibt der Koordinatenoffset gleich. Ziele, für die dieser Offset außerhalb des sicheren Client-Koordinatenbereichs läge, werden abgelehnt.
- /adminspec off stellt den gespeicherten Ort, Spielmodus, Flugzustand, Invulnerable-Zustand, Bewegungsgeschwindigkeiten, Sichtweiten und gegebenenfalls den vorherigen Vanish-Zustand wieder her.
- Permanente Accounts werden beim Login und regelmäßig erneut auf Spectator/Unsichtbarkeit geprüft.

### Paket- und Kompatibilitätshinweise

Die Funktion ist versionssensibel und darf nicht als vollständige Anti-Cheat- oder Anonymitätsgarantie verstanden werden. Die Implementierung übersetzt bekannte Positionspakete, unterdrückt aber Pakettypen und Payloads, deren Positionsdaten nicht zuverlässig transformiert werden können. Dazu gehören unter anderem bestimmte Partikel-/Explosionsdaten und benutzerdefinierte Plugin-Payloads. Das kann Funktionen anderer Plugins für den beobachtenden Moderator einschränken, beispielsweise Voice-Chat-Daten.

Beim Start prüft die Integration die benötigten ProtocolLib-Paket-Mappings. Fehlt ein erforderliches Mapping, wird die Spectator-Funktion deaktiviert, statt mit unvollständiger Paketabdeckung zu starten.

Vor einem Produktionseinsatz müssen die exakte Paper-Version, ProtocolLib-Version, Client-Versionen, SuperVanish und die eingesetzten Anti-Cheat-Plugins gemeinsam getestet werden. Insbesondere ein echter Zwei-Client-Test mit Paketaufzeichnung ist erforderlich, um die Koordinatentrennung unabhängig zu prüfen.

---

## Alt-Account-Netzwerk: Grenzen und Fehlalarme

Das Netzwerk zeigt Accounts, die im Beobachtungszeitraum dieselben IP-HMACs verwendet haben. Es ist keine Identitätsfeststellung.

- Dynamische IP-Adressen können Verbindungen übersehen.
- Familien, Wohngemeinschaften, Schulen, Universitäten, CGNAT, Mobilfunk und VPN-Ausgänge können unbeteiligte Spieler verbinden.
- Indirekte Verbindungen über mehrere Accounts sind noch weniger aussagekräftig als direkte Übereinstimmungen.
- Bedrock-Spieler können bei aktivierter Netzwerkerkennung in der Netzwerkansicht auftauchen, bleiben aber von /offend und Layer-2-Aktionen ausgenommen.
- thatsaban.exempt nimmt einen Account von /offend und der IP-Kopplung aus.
- Namen werden nur Staff mit thatsaban.view-linked-accounts angezeigt. Sonst zeigt das Netzwerk UUIDs.
- Es gibt keine automatische Bann-Funktion aufgrund eines Netzwerk-Treffers.

Die Standardwerte enabled: false, auto-apply: false und retroactive: false sind absichtlich konservativ.

---

## Datenschutz und DSGVO

Die technischen Schutzmaßnahmen sind keine pauschale DSGVO-Konformitätsgarantie. Der Serverbetreiber bleibt verantwortlich für Zweck, Rechtsgrundlage, Erforderlichkeit, Speicherdauer, Zugriffsrechte und Spielerinformationen. Eine Vorlage und ein Muster-Verarbeitungsverzeichnis befinden sich in [docs/PRIVACY.md](docs/PRIVACY.md).

- Die GeoIP-Prüfung verwendet eine lokale Datenbank. That'sABan sendet die Spieler-IP dafür nicht an einen GeoIP-Webdienst.
- Das Alt-Account-Netzwerk speichert HMAC-Werte statt Roh-IP-Adressen. HMAC-Werte, UUIDs, Namen und Layer-2-Zuordnungen können trotzdem personenbezogene oder pseudonyme Daten sein.
- Der HMAC-Schlüssel muss getrennt und sicher gesichert werden. Schlüssel und Datenbank gemeinsam in einem ungeschützten Backup abzulegen, hebt den praktischen Schutz auf.
- Das Plugin erstellt keine vollständigen Daten-Backups. Alte data.yml.bak-* und data.yml.corrupt-* Dateien werden beim Start entfernt. Eine beschädigte data.yml wird nicht ungesichert überschrieben; der LinkStore blockiert in diesem Zustand Schreibzugriffe.
- IP-Adressen können unabhängig davon in Proxy-, Firewall-, Hoster-, Paper- oder anderen Plugin-Logs auftauchen. Diese Systeme benötigen eigene Aufbewahrungs- und Löschkonzepte.
- Das Löschen eines That'sABan-Datensatzes löscht nicht den gesamten LuckPerms-Benutzerdatensatz, da dieser auch unabhängige Berechtigungen enthalten kann.

### Ältere Plugin-Versionen

Plugin-Stände vor Commit b34c644 verwendeten für die GeoIP-Prüfung den externen Dienst ipwho.is. Dabei wurde die Roh-IP als Teil der HTTPS-URL übertragen; in früheren Fehlerpfaden konnte sie außerdem in Logs auftauchen. Verwende keine Versionen vor b34c644.

Das frühere GitHub-Actions-Artefakt aus Run #43 basierte auf einem älteren Commit und darf nicht als aktuelle Version eingesetzt werden: [Workflow Run #43](https://github.com/Christian8799/that-is-a-ban/actions/runs/45642064635).

---

## GeoIP-Datenbank installieren

1. Lade die **IP2Location LITE DB3 BIN**-Datenbank von [IP2Location LITE](https://lite.ip2location.com/database/db3-ip-country-region-city) herunter.
2. Beachte die aktuelle IP2Location-Lizenz. Die Datenbank wird weder im Repository noch in der Plugin-JAR ausgeliefert.
3. Lege die IPv6-BIN-Datei standardmäßig unter folgendem Pfad ab:

   ~~~text
   plugins/ThatsABan/geoip/IP2LOCATION-LITE-DB3.IPV6.BIN
   ~~~

4. Stelle sicher, dass country-filter.database-file auf den tatsächlichen Dateipfad zeigt.
5. Starte den Server neu und prüfe das Startlog auf die Meldung, dass die lokale GeoIP-Datenbank geladen wurde.
6. IP2Location verlangt eine sichtbare Quellenangabe. Zeige in den öffentlich zugänglichen Serverinformationen:

   **This server uses the IP2Location LITE database for IP geolocation.**  
   https://www.ip2location.com

Die IP2Location-Java-Bibliothek wird unter MIT-Lizenz eingebunden. Für die GeoIP-Datenbank gelten separat die Lizenzbedingungen von IP2Location.

---

## Proxy- und Netzwerkbetrieb

Hinter BungeeCord oder Velocity muss die echte Spieler-IP korrekt an Paper weitergereicht werden. Ohne korrektes Forwarding kann That'sABan nur die Proxy-IP sehen. Das verfälscht sowohl die GeoIP-Regionsprüfung als auch das IP-basierte Account-Netzwerk.

Prüfe daher:

- Paper- und Proxy-Forwarding sind korrekt und sicher konfiguriert.
- Der Backend-Server ist nicht ungeschützt direkt aus dem Internet erreichbar.
- Die IP, die Paper in AsyncPlayerPreLoginEvent erhält, ist tatsächlich die Client-IP.
- Proxy-, Firewall- und Hoster-Logs besitzen eigene Aufbewahrungsfristen.

---

## Build

Voraussetzungen: Java 21 und Gradle.

~~~bash
gradle build
~~~

Das Plugin-Artefakt liegt anschließend unter build/libs/. Der GitHub-Actions-Workflow baut gegen die Paper-1.21.11-API mit Java 21.

---

## Fehlerbehebung

| Symptom / Loghinweis | Mögliche Ursache und Maßnahme |
|---|---|
| Country-/Region-Filter wurde deaktiviert | BIN-Datei fehlt, Pfad in country-filter.database-file stimmt nicht oder Datei ist nicht lesbar. GeoIP-Datei ablegen und Server neu starten. |
| Login wegen GeoIP-Abfrage abgelehnt | Datenbank ist geladen, aber die konkrete Abfrage lieferte kein verwertbares Ergebnis. Datenbankdatei/Lizenzversion und IP-Zuordnung prüfen. |
| Layer 2 oder /offend deaktiviert | LuckPerms fehlt, ist nicht aktiviert oder seine API konnte nicht initialisiert werden. LuckPerms-Startlog und Plugin-Kompatibilität prüfen. |
| /adminspec deaktiviert | ProtocolLib fehlt, ist nicht aktiviert, inkompatibel oder ein erforderliches Paket-Mapping fehlt. Passende ProtocolLib-Server-JAR für Paper 1.21.11 installieren und Log prüfen. |
| /adminspec observe ... vanish funktioniert nicht | SuperVanish fehlt oder dessen API ist inkompatibel. Für Beobachtung ohne Vanish das Argument vanish weglassen. |
| Alt-Account-Netzwerk nicht verfügbar | alt-detection.enabled ist false oder der LinkStore ist schreibgeschützt. Konfiguration und data.yml prüfen. |
| Gemeinsame IP führt zu mehreren Accounts | Das ist ein erwartbarer möglicher Fehlalarm. Nicht automatisch als Identitätsnachweis interpretieren. |
| Layer-2-Plugins reagieren nicht | LuckPerms-Bypass-Nodes, layer2.negated-permissions und die tatsächlichen Plugin-Permissions kontrollieren. |

Bei einer beschädigten data.yml nicht einfach eine neue Datei darüberkopieren. Der schreibgeschützte Zustand soll verhindern, dass vorhandene Daten ohne Prüfung überschrieben werden. Sichere zuerst die Dateien und untersuche den Fehler.

