# Spieler-Reports und Replay-Clips

That'sABan enthält eine eingebettete, MIT-lizenzierte Flashback-Aufnahme-Engine. Der Aufnahme-Kern stammt aus [FlashbackServer](https://github.com/Zeffut/FlashbackServer) von Zeffut; der benötigte Paper-26.3-NMS-Adapter wird ebenfalls mit That'sABan ausgeliefert. Die Lizenzhinweise stehen unter [third_party](../third_party/NOTICE.md).

**Es muss kein separates FlashbackServer-Plugin installiert werden.** That'sABan erfasst die ausgehenden Minecraft-Pakete selbst, erzeugt den Snapshot und schreibt Flashback-Dateien im Format `.flashback`.

## Voraussetzungen

- Paper 26.3.
- Java 25.
- That'sABan mit `reports.enabled: true`.
- Auf dem Client des Staff-Mitglieds die [Flashback-Mod](https://modrinth.com/mod/flashback), um gespeicherte Replays zu öffnen.

Die eingebettete NMS-Implementierung ist derzeit gezielt für Minecraft 26.3 gebaut. Auf anderen Minecraft-Versionen bleiben die übrigen That'sABan-Funktionen verfügbar; die Replay-Aufnahme wird mit einer verständlichen Warnung deaktiviert.

## Konfiguration

In `plugins/ThatsABan/config.yml`:

```yaml
reports:
  enabled: true
  clip-window-seconds: 60
  minimum-clip-window-seconds: 60
```

- `clip-window-seconds`: Länge des Rolling-Clip-Puffers in Sekunden.
- `minimum-clip-window-seconds`: Mindestlänge, die That'sABan für einen Report akzeptiert. Dieser Wert darf nicht größer als das Aufnahmefenster sein.

Die Standardwerte betragen jeweils 60 Sekunden. Änderungen werden nach einem Serverneustart wirksam.

## Spieler-Befehl

```text
/report <Spieler> <Grund>
```

Das Ziel muss online sein. Spieler können sich nicht selbst melden. Der Grund ist standardmäßig auf 200 Zeichen begrenzt. Jeder Reporter hat standardmäßig einen Cooldown von 60 Sekunden.

That'sABan aktiviert für jeden Spieler nach dem Join automatisch einen Rolling-Clip-Puffer. Wird ein Report erstellt, speichert das Plugin das bereits aufgezeichnete Zeitfenster des Zielspielers. Die Aufnahme beginnt also nicht erst beim Absenden des Reports.

## Staff-Befehle

| Befehl | Wirkung |
|---|---|
| `/reports` oder `/reports list` | Öffnet das Staff-GUI beziehungsweise zeigt in der Konsole eine Textliste |
| `/reports view <ID>` | Öffnet die Report-Details beziehungsweise zeigt sie in der Konsole |
| `/reports offend <ID>` | Führt für den Staff-Absender den bestehenden `/offend <player>`-Befehl aus; normale Permissions und Bedrock-Prüfungen gelten weiterhin |
| `/reports resolve <ID>` | Markiert einen Report als gelöst |
| `/reports dismiss <ID>` | Verwirft einen Report |

Der Replay-Pfad in `/reports view` kann zum Kopieren angeklickt werden. Die `.flashback`-Datei liegt auf dem Server und muss über den normalen Server-Dateizugriff heruntergeladen werden. Das Replay wird nicht direkt an den Minecraft-Client des Staff-Mitglieds übertragen.

## Replay-Prüfung

Nach dem Schreiben prüft That'sABan die erzeugte Datei mit dem eingebetteten Flashback-Validator. Nur wenn Containerformat und Paketdekodierung erfolgreich sind, wird der Replay-Pfad als erfolgreich gespeichert. Fehler werden im Report als fehlgeschlagene Aufnahme ausgewiesen und in der Serverkonsole protokolliert.

Diese Prüfung garantiert nicht, dass jede mögliche Client-Mod-Kombination fehlerfrei abspielt. Sie verhindert aber, dass ein bereits beim Schreiben strukturell ungültiges oder nicht dekodierbares Replay stillschweigend als erfolgreich gemeldet wird.

## Permissions

- `thatsaban.report` (Standard: true): Spieler-Reports absenden.
- `thatsaban.reports` (Standard: op): Reports ansehen und verwalten.
- `thatsaban.reports.notify` (Standard: op): Benachrichtigungen über neue Reports erhalten.

## Gespeicherte Daten und Datenschutz

Report-Metadaten liegen in `plugins/ThatsABan/reports.yml`; Replay-Dateien liegen unter `plugins/ThatsABan/reports/`. Die Metadaten enthalten UUIDs und Namen von Reporter und Ziel, den Grund, Zeitstempel, Status und Replay-Pfad.

Replays enthalten aufgezeichnete Gameplay-Pakete und können damit auch im Spiel sichtbare Chat-Inhalte oder andere Spielinhalte enthalten. Beschränke den Zugriff auf den Report-Ordner und Backups auf vertrauenswürdige Staff-Mitglieder und lege eine Aufbewahrungs- und Löschfrist fest.

Ein Spieler, dessen Rolling-Puffer vor dem Report nicht aktiviert war, kann keine rückwirkende Aufnahme erhalten.
