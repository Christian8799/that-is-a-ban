# Datenschutz-Vorlage für Betreiber von that-s-a-ban

> **Vor Verwendung ausfüllen und prüfen.** Dies ist eine technische Dokumentationsvorlage, keine Rechtsberatung und keine Bestätigung der DSGVO-Konformität. Platzhalter in eckigen Klammern müssen durch zutreffende Angaben ersetzt werden. Nicht benötigte Verarbeitungen sind zu entfernen.

## 1. Muster für Datenschutzhinweise (Art. 13 DSGVO)

### Verantwortlicher

- Betreiber/Verantwortlicher: [vollständiger Name bzw. Organisation]
- Anschrift: [ladungsfähige Anschrift]
- Kontakt: [E-Mail-Adresse]
- Datenschutzbeauftragter, falls benannt oder erforderlich: [Name/Kontakt]

### Zwecke und Datenkategorien

Beim Betrieb des Minecraft-Servers können folgende Daten verarbeitet werden:

- Minecraft-UUID und aktueller bzw. zuletzt bekannter Spielername zur Zuordnung von Konten und zur Verwaltung von Moderationsentscheidungen.
- IP-Adresse während des Verbindungsaufbaus für die lokale Regionsprüfung und – nur bei ausdrücklich aktivierter IP-Kopplung – zur Bildung eines HMAC-SHA-256-Werts.
- HMAC-IP-Hashes und Zuordnungen zu UUIDs für die Erkennung möglicher Mehrfachkonten.
- LuckPerms-Berechtigungsnodes zur Umsetzung bzw. Entfernung der Layer-2-Einstufung.
- Technische Ereignisse und Fehlerdaten, soweit sie in Paper-, Proxy-, Hoster- oder Plugin-Logs anfallen.

**Zweck 1 – Anti-Cheat und Mehrfachkonten:** [konkreten Schutz vor Umgehung serverseitiger Anti-Cheat-Maßnahmen und die betroffenen Serverregeln beschreiben]. Eine bloße allgemeine Sicherheitsbehauptung reicht nicht; den konkreten Zweck und die Erforderlichkeit beschreiben.

**Zweck 2 – Regionsbeschränkung auf Bayern:** Die IP-Adresse wird bereits beim Verbindungsaufbau flüchtig verarbeitet, um sie mit einer lokal gespeicherten GeoIP-Datenbank abzugleichen und Verbindungen außerhalb der freigegebenen Region abzulehnen. Der Betreiber muss diesen Zweck separat festlegen und prüfen. Die GeoIP-Zuordnung ist nur eine Näherung: VPNs, Mobilfunk, Proxys und fehlerhafte oder veraltete Datenbankeinträge können Spieler fälschlich ausschließen. Dokumentiere, warum eine Regionsbeschränkung erforderlich ist, warum mildere Mittel (z. B. freiwillige Auswahl oder manuelle Prüfung) nicht ausreichen und wie Fehlzuordnungen angefochten bzw. korrigiert werden können.

### Rechtsgrundlage und Interessenabwägung

Als mögliche Rechtsgrundlage kommt – nach individueller Prüfung – Art. 6 Abs. 1 lit. f DSGVO in Betracht. Der Betreiber muss dokumentieren:

1. **Berechtigtes Interesse:** [konkret beschreiben, wessen Interesse geschützt wird und welches Risiko besteht].
2. **Erforderlichkeit:** [erklären, weshalb die konkrete Verarbeitung notwendig ist und weshalb mildere Mittel nicht ausreichen].
3. **Abwägung:** [Erwartungen der Spieler, Alter der Spieler, Eingriffsintensität, Fehlerrisiko gemeinsamer IP-Adressen, Transparenz und Schutzmaßnahmen bewerten].
4. **Ergebnis:** [begründetes Ergebnis und Datum der Prüfung].

Art. 6 Abs. 1 lit. f DSGVO darf nicht pauschal als Rechtsgrundlage eingetragen werden. Bei Minderjährigen ist die vernünftige Erwartung der betroffenen Personen besonders sorgfältig zu berücksichtigen.

Für die **Regionsbeschränkung** ist eine eigene Interessenabwägung zu dokumentieren: berechtigtes Interesse an einer regional begrenzten Community bzw. einem regionalen Angebot, Erforderlichkeit einer IP-basierten GeoIP-Prüfung, mögliche mildere Mittel, Auswirkungen eines falschen Ausschlusses, Genauigkeit/Aktualität der Datenbank, VPN-/Mobilfunk-/Proxy-Nutzung und ein praktikabler Kontaktweg zur Überprüfung. Die Abwägung für Anti-Cheat deckt diesen eigenständigen Zweck nicht automatisch ab.

### Empfänger und Übermittlungen

- Hosting-/Serveranbieter: [Name, Rolle, Standort]
- Proxy-/Netzwerkdienst: [Name, Rolle, Standort]
- LuckPerms und weitere Plugins: [konkret beschreiben, welche Daten sie erhalten]
- GeoIP: Die aktuelle Plugin-Version verwendet die lokal bereitgestellte IP2Location-Datenbank. Ältere Versionen vor Commit `b34c644` übermittelten IP-Adressen an `ipwho.is`; diese alten Versionen sollen nicht mehr betrieben werden.
- Drittlandübermittlungen: [Empfänger, Land und geeignete Garantien eintragen oder „keine“ nach Prüfung].

### Speicherdauer

- IP-HMAC-Hashes und zugehörige Spielerreferenzen: `alt-detection.retention-days` (Standard 30 Tage).
- Offender-Einträge und zugehörige Layer-2-Marker: `alt-detection.offender-retention-days` (Standard 365 Tage seit letztem Login; maximal 3650 Tage).
- Paper-, Proxy-, Firewall-, Hoster- und andere Plugin-Logs: [eigene Frist des Betreibers eintragen].
- LuckPerms-Daten außerhalb der von diesem Plugin gesetzten Nodes: [eigene Regelung eintragen].

Die Fristen müssen zum tatsächlichen Betrieb passen. Konfigurationswerte sind regelmäßig zu überprüfen.

### Betroffenenrechte

Betroffene können – soweit die gesetzlichen Voraussetzungen vorliegen – Auskunft, Berichtigung, Löschung, Einschränkung der Verarbeitung und Widerspruch verlangen. Kontakt für Anfragen: [Kontakt]. Beschwerden können bei der zuständigen Datenschutzaufsichtsbehörde eingereicht werden: [zuständige Behörde/Kontakt].

Für die Plugin-Daten stehen Staff-Befehle zur Verfügung:

- `/thatsaban info <Spieler|UUID>`
- `/thatsaban unoffend <Spieler|UUID>`
- `/thatsaban forget <Spieler|UUID> confirm`

Diese Befehle decken nicht automatisch Daten in Paper-Logs, Proxy-Logs, Hosting-Systemen, anderen Plugins oder unabhängigen LuckPerms-Daten ab. Eine Anfrage muss daher systemübergreifend geprüft werden.

### Zeitpunkt der Information

Da die IP-Adresse bereits beim Verbindungsaufbau für die Regionsprüfung verarbeitet wird, müssen die erforderlichen Informationen den Spielern **vor dem Verbindungsaufbau bzw. vor dem Login** zugänglich sein. Ein bloßer Hinweis nach dem Login reicht für diese Verarbeitung nicht aus. Platziere die Datenschutzhinweise z. B. in der Serverliste (MOTD mit Link/kurzem Hinweis), auf der Website oder in einer anderen vor dem Beitritt erreichbaren Informationsquelle. Prüfe, ob der vollständige Hinweis ohne vorherige Datenverarbeitung erreichbar ist.

### Pflichtinformationen ergänzen

- Kontaktdaten und konkrete Serveridentität
- konkrete Zwecke und Rechtsgrundlage
- Empfänger/Auftragsverarbeiter und Drittlandübermittlungen
- konkrete Löschfristen für alle Systeme
- Beschwerderecht und Kontaktweg
- ggf. Datenschutzbeauftragter und automatisierte Entscheidungsfindung

## 2. Muster-Verzeichnis von Verarbeitungstätigkeiten (Art. 30 DSGVO)

| Feld | Vom Betreiber auszufüllen |
|---|---|
| Verantwortlicher | [Name, Kontaktdaten] |
| Bezeichnung | Sicherheits- und Anti-Cheat-Verarbeitung des Minecraft-Servers |
| Zweck | [konkreter Zweck und legitimes Interesse] |
| Betroffene Personen | Spieler, ggf. Teammitglieder |
| Datenkategorien | UUID, Spielername, IP-Adresse im Verbindungsprozess, HMAC-IP-Hash, Layer-2-Status, Berechtigungsnodes, technische Logs |
| Empfänger | [Hoster, Proxy, LuckPerms, weitere Plugins und Dienstleister] |
| Drittlandübermittlung | [Nein / Empfänger, Land, Garantie] |
| Löschfristen | IP-Verknüpfungen: Konfigurationswert; Offender: Konfigurationswert; Logs/LuckPerms/Hoster: separat eintragen |
| TOM | HMAC-SHA-256 mit separatem Schlüssel, owner-only-Dateirechte, begrenzte Aufbewahrung, Staff-Permissions, deaktivierte automatische Einstufung als Standard |
| Überprüfung | [Verantwortliche Person, Datum, nächster Prüftermin] |

## 3. DSFA-Prüfung (Art. 35 DSGVO)

Eine Datenschutz-Folgenabschätzung ist nicht automatisch allein deshalb erforderlich, weil das Plugin IP-Hashes oder Moderationsmarker verwendet. Der Betreiber muss aber vor dem Einsatz prüfen, ob die geplante Verarbeitung voraussichtlich ein hohes Risiko für Rechte und Freiheiten natürlicher Personen zur Folge hat.

Besonders zu prüfen sind:

- systematische oder umfangreiche Überwachung des Spielerverhaltens;
- Profilbildung über mehrere Konten anhand gemeinsamer IP-Adressen;
- automatische oder rückwirkende Einstufungen und deren Auswirkungen;
- gemeinsame IP-Adressen in Familien, Wohngemeinschaften, Schulen, Mobilfunknetzen und CGNAT;
- Minderjährige als betroffene Personen;
- Umfang, Dauer, Reichweite und mögliche Folgen einer Fehlentscheidung;
- Verknüpfung mit Daten aus anderen Plugins, Logs oder Diensten.

Dokumentiere mindestens Verarbeitung, Notwendigkeit/Verhältnismäßigkeit, Risiken, Schutzmaßnahmen und das Ergebnis der DSFA-Vorprüfung. Wenn nach den Schutzmaßnahmen ein hohes Restrisiko verbleibt, ist die Konsultation der zuständigen Aufsichtsbehörde nach Art. 36 DSGVO zu prüfen.

## 4. Technische Betriebs-Checkliste

- [ ] `alt-detection.enabled` nur nach dokumentierter Prüfung aktivieren.
- [ ] `alt-detection.auto-apply: false` und `alt-detection.retroactive: false` beibehalten, sofern keine begründete Einzelfallentscheidung etwas anderes rechtfertigt.
- [ ] Speicherdauern und Zugriffsberechtigungen regelmäßig prüfen.
- [ ] `plugins/ThatsABan/ip-hmac.key` bzw. den konfigurierten externen HMAC-Schlüsselpfad sicher aufbewahren und nicht veröffentlichen oder in Backups mit breitem Zugriff ablegen.
- [ ] Regionsbeschränkung als eigenen Zweck mit eigener Interessenabwägung dokumentieren und Spieler vor dem Verbindungsaufbau informieren.
- [ ] Zugriffsrechte auf `data.yml`, Schlüsseldatei, LuckPerms-Speicher und Logs prüfen.
- [ ] Betroffenenanfragen auch bei Hoster, Proxy, Paper, LuckPerms und weiteren Plugins bearbeiten.
- [ ] Alte Plugin-Versionen vor Commit `b34c644` nicht mehr einsetzen; historische Logs und alte Backup-Dateien nach dem Löschkonzept prüfen.
- [ ] Fehlalarme durch gemeinsame IP-Adressen manuell untersuchen; eine IP-Übereinstimmung ist kein Identitätsnachweis.
