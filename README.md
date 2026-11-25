# that-s-a-ban

Anti-Cheat-Connector für **Paper 1.21.11** (Java 21). Das Plugin bringt keine eigenen Cheat-Checks mit, sondern entscheidet pro Spieler, welche Anti-Cheat-Plugins greifen – über zwei Stufen.

| | Layer 1 (alle) | Layer 2 |
|---|---|---|
| CheatDetector | ✅ | ✅ |
| GrimAC | ✅ | ✅ |
| FoxGate (Anti-VPN) | – | ✅ |
| HandShaker | – | ✅ |

## Funktionsweise

- **`/offend <Spieler>`** versetzt einen Spieler dauerhaft in Layer 2.
- Layer 2 wird in LuckPerms gespeichert: Marker-Node `thatsaban.layer2` plus die negierten Bypass-Nodes `foxav.bypass` und `handshaker.bypass`.
- **IP-Kopplung:** Wenn aktiviert, werden bekannte IPs nur als gesalzene SHA-256-Hashes gespeichert. IPv6 wird auf /64 gekürzt. Bereits bekannte Accounts derselben IP können ebenfalls in Layer 2 versetzt werden. Diese Hashes sind pseudonyme personenbezogene Daten und bleiben datenschutzrechtlich relevant.
- **Regionssperre:** Standardmäßig werden nur IP-Adressen zugelassen, die die lokale Datenbank Deutschland/Bayern (Region Bayern oder Bavaria) zuordnet. Die Abfrage erfolgt ausschließlich lokal. Es gibt keine HTTP-Anfrage an ipwho.is oder einen anderen GeoIP-Anbieter, keine Übermittlung der Spieler-IP an einen GeoIP-Dienst und keinen IP-Cache. Roh-IP-Adressen werden von dieser Funktion nicht gespeichert oder protokolliert.
- **Bedrock-Spieler (Geyser/Floodgate) sind immun** gegen `/offend` und die IP-Kopplung.
- **`thatsaban.exempt`** nimmt einzelne Spieler von `/offend` und der IP-Kopplung aus.

## Lokale GeoIP-Datenbank einrichten

1. Lade die **IP2Location LITE DB3 BIN**-Datenbank direkt von [IP2Location LITE](https://lite.ip2location.com/database/db3-ip-country-region-city) herunter. Sie enthält Land, Region und Stadt.
2. Akzeptiere und beachte die aktuelle Lizenz von IP2Location. Die Datenbank wird absichtlich **nicht** im Plugin-Repository oder in der Plugin-JAR mitgeliefert.
3. Lege die IPv4/IPv6-fähige BIN-Datei unter `plugins/ThatsABan/geoip/IP2LOCATION-LITE-DB3.BIN` ab. Verwende die kombinierte IPv4/IPv6-Datenbank, damit beide Adressfamilien geprüft werden können.
4. Starte den Server neu. Fehlt die Datenbank oder kann sie nicht gelesen werden, lehnt die aktivierte Regionssperre Verbindungen ab (fail-closed).
5. IP2Location verlangt eine sichtbare Quellenangabe. Zeige diese in den öffentlich zugänglichen Serverinformationen, z. B. auf der Website oder einer /about-Seite:  
   **This server uses the IP2Location LITE database for IP geolocation.** https://www.ip2location.com

Die IP2Location-Java-Bibliothek wird unter MIT-Lizenz eingebunden. Für die GeoIP-Datenbank gelten separat die Lizenzbedingungen von IP2Location; prüfe diese vor dem Einsatz und halte die Datenbank aktuell. Die Datenbank ist nicht Teil des Plugin-Artefakts und wird nicht über dieses Repository weiterverteilt.

## Einrichtung

1. Voraussetzungen: **Paper 1.21.11**, **Java 21**, **LuckPerms**, GrimAC, CheatDetector, FoxGate und HandShaker. Geyser/Floodgate ist optional.
2. `ThatsABan-1.0.0.jar` in `plugins/` legen und den Server starten.
3. Die lokale GeoIP-Datenbank wie oben beschrieben installieren, bevor die Regionssperre aktiviert wird.
4. Die Layer-2-Plugins für alle anderen abschalten, indem die Standardgruppe deren Bypass bekommt:

```
/lp group default permission set foxav.bypass true
/lp group default permission set handshaker.bypass true
```

Layer-2-Spieler bekommen diese Nodes auf Spielerebene negiert.

## Permissions

| Permission | Standard | Bedeutung |
|---|---|---|
| `thatsaban.offend` | op | `/offend` benutzen |
| `thatsaban.notify` | op | Meldung bei automatischer IP-Kopplung |
| `thatsaban.layer2` | – | Marker-Node für Layer 2 |
| `thatsaban.exempt` | – | Ausnahme von `/offend` und IP-Kopplung |

## Datenschutz und DSGVO

Diese Änderungen sind technische Datenschutzmaßnahmen, aber **keine pauschale DSGVO-Konformitätsgarantie**. Der Serverbetreiber bleibt Verantwortlicher für die konkrete Verarbeitung und muss insbesondere Zweck, Rechtsgrundlage, Erforderlichkeit, Speicherdauer, Zugriffsschutz und Datenschutzhinweise für den gesamten Serverbetrieb festlegen.

- Die Regionsprüfung verarbeitet die beim Login ohnehin anfallende IP-Adresse nur flüchtig im Arbeitsspeicher und fragt eine lokale Datenbank ab. Das Plugin sendet diese IP nicht an einen GeoIP-Drittanbieter und schreibt sie nicht in eigene Logs oder einen GeoIP-Cache.
- **IP-Kopplung ist standardmäßig deaktiviert** (`alt-detection.enabled: false`). Wird sie aktiviert, speichert das Plugin gesalzene IP-Hashes sowie Spieler-UUIDs und Namen in `plugins/ThatsABan/data.yml`. Ein Hash ist nicht automatisch anonym; diese Daten können weiterhin personenbezogen sein. Aktivierung nur nach dokumentierter Rechtsgrundlage, Information der Spieler, festgelegter Löschfrist und Prüfung, ob die Funktion wirklich erforderlich ist.
- Die IP-Adresse kann unabhängig davon auch in Proxy-, Firewall-, Hoster-, Paper- oder anderen Plugin-Logs auftauchen. Diese Systeme müssen separat geprüft und mit passenden Aufbewahrungsfristen konfiguriert werden.
- Spieler müssen vor dem Login bzw. in den Serverinformationen verständlich über Verantwortlichen, Zwecke, Rechtsgrundlage, Empfänger, Speicherdauer und Betroffenenrechte informiert werden (Art. 13 DSGVO, soweit anwendbar).
- Bei Minderjährigen und öffentlich zugänglichen Servern sollte der Betreiber die Datenschutzhinweise und Rechtsgrundlage mit einer erwachsenen verantwortlichen Person bzw. einer Datenschutzfachperson prüfen.

## Hinweise

- Layer-2-Prüfungen greifen beim nächsten Login.
- Hinter BungeeCord/Velocity muss die echte Spieler-IP korrekt weitergereicht werden. Ohne korrektes Forwarding kann die Regionsprüfung die Proxy-IP statt der Spieler-IP sehen.
- GeoIP ist nur eine Näherung: VPNs, Mobilfunknetze, Proxys und falsch zugeordnete IP-Blöcke können zu Fehlentscheidungen führen.

## Bauen

```
gradle build
```

Die JAR liegt danach in `build/libs/`. Der GitHub-Workflow baut mit Java 21 gegen die Paper-1.21.11-API.
