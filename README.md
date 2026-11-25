# that-s-a-ban

Anti-Cheat-Connector für **Paper 1.21.11** (Java 21). Das Plugin bringt keine eigenen Cheat-Checks mit, sondern entscheidet pro Spieler, welche Anti-Cheat-Plugins greifen – über zwei Stufen.

| | Layer 1 (alle) | Layer 2 |
|---|---|---|
| CheatDetector | ✅ | ✅ |
| GrimAC | ✅ | ✅ |
| FoxGate (Anti-VPN) | – | ✅ |
| HandShaker | – | ✅ |

## Funktionsweise

- **`/offend <Spieler>`** versetzt einen Spieler **dauerhaft** in Layer 2 (Permission `thatsaban.offend`).
- Layer 2 wird in **LuckPerms** gespeichert: Marker-Node `thatsaban.layer2` (true) plus die negierten Bypass-Nodes `foxav.bypass` und `handshaker.bypass` direkt auf dem Spieler. Dadurch kann Layer 2 pro Spieler auch direkt mit LuckPerms gesetzt werden.
- **IP-Kopplung:** Beim `/offend` werden alle bekannten IPs des Spielers markiert und bereits bekannte Accounts dieser IPs ebenfalls in Layer 2 versetzt. Joint später ein Account von einer markierten IP, bekommt er automatisch Layer 2. Die Kopplung geht nur über die IP (IPv6: /64), nicht über den Standort, und wird nicht weitergereicht (ein automatisch versetzter Account markiert nicht seine eigenen IPs).
- **Bedrock-Spieler (Geyser/Floodgate) sind immun** gegen `/offend` und die IP-Kopplung.
- **`thatsaban.exempt`** nimmt einzelne Spieler (z. B. Team-Accounts) von `/offend` und der IP-Kopplung aus.

## Einrichtung

1. Voraussetzungen: **Paper 1.21.11**, **Java 21**, **LuckPerms**, GrimAC, CheatDetector, FoxGate und HandShaker. Geyser/Floodgate ist optional.
2. `ThatsABan-1.0.0.jar` in `plugins/` legen und den Server starten.
3. Einmalig die Layer-2-Plugins für alle anderen abschalten, indem die Standardgruppe deren Bypass bekommt:

```
/lp group default permission set foxav.bypass true
/lp group default permission set handshaker.bypass true
```

Layer-2-Spieler bekommen diese Nodes auf Spielerebene negiert, das überstimmt die Gruppe. FoxGate und HandShaker greifen dann nur noch bei ihnen.

## Permissions

| Permission | Standard | Bedeutung |
|---|---|---|
| `thatsaban.offend` | op | `/offend` benutzen |
| `thatsaban.notify` | op | Meldung bei automatischer IP-Kopplung |
| `thatsaban.layer2` | – | Marker-Node für Layer 2 (per LuckPerms) |
| `thatsaban.exempt` | – | Ausnahme von `/offend` und IP-Kopplung |

## Hinweise

- Die Layer-2-Prüfungen von FoxGate und HandShaker greifen **beim nächsten Login**. Mit `offend.kick-online-target: true` wird der Spieler nach `/offend` gekickt.
- Layer 2 ist dauerhaft. Zum Entfernen die Nodes in LuckPerms löschen; ein markierter IP-Eintrag in `data.yml` setzt Accounts dieser IP aber beim nächsten Join wieder in Layer 2. Für gemeinsam genutzte IPs (Familie, Schule, Mobilfunk) gibt es `alt-detection.ignored-ips` und `thatsaban.exempt`.
- Hinter BungeeCord/Velocity muss die echte Spieler-IP weitergereicht werden. Loopback-/LAN-Adressen werden standardmäßig ignoriert.
- **Datenschutz:** IPs werden nur als gesalzener SHA-256-Hash in `plugins/ThatsABan/data.yml` gespeichert. Das ist trotzdem ein personenbezogenes Datum (DSGVO), prüfe Rechtsgrundlage und Hinweis an die Spieler.
- Die Bypass-Nodes `foxav.bypass` und `handshaker.bypass` stammen aus der Dokumentation der Plugins und sind in `config.yml` änderbar.

## Bauen

```
gradle build
```

Die JAR liegt danach in `build/libs/`. Der GitHub-Workflow baut mit Java 21 gegen die Paper-1.21.11-API und lädt die JAR als Build-Artefakt hoch.
