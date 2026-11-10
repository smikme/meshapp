# MeshCore-Unterstützung

**Sprache:** [English](meshcore-support.md) | [Русский](meshcore-support.ru.md) | Deutsch

Companion bietet Nachrichten, bearbeitbare Einstellungen, Kontakte, Kanäle und
erweiterte Operationen. KISS ist ein eigener Modemmodus. Softwaretests sind
vorhanden; die vollständige Prüfung mit Funkgeräten steht noch aus.

## Verbindung

Für Companion-Firmware `MeshCore Companion` auswählen. USB/Serial und TCP verwenden
das offizielle Framing: Host → Gerät `3C lengthLE16 payload`, Gerät → Host
`3E lengthLE16 payload`, maximal 176 Nutzbytes. BLE überträgt einen Companion-Payload
pro GATT-Schreibvorgang/Notification ohne Stream-Header. Service-UUID:
`6E400001-B5A3-F393-E0A9-E50E24DCCA9E`; RX endet auf `0002`, TX auf `0003`.
Notifications werden vor dem Handshake abonniert. Ein Befehl wird nicht auf mehrere
GATT-Schreibvorgänge aufgeteilt; Schreibfehler und bekannte MTU-Grenzen werden gemeldet.

Die Sitzung ist erst nach gültiger SELF_INFO, Gerätegrenzen, vollständigen Kontakten,
allen Kanalplätzen und Batterieantwort bereit. Die begrenzte Warteschlange ordnet
ungetaggte Antworten einem aktiven Befehl zu; Push-Ereignisse laufen getrennt.
Eine neue Verbindung beginnt eine neue Sitzung mit Synchronisierung.

## Bedienung

- Kanal-/Direktnachrichten, Offline-Warteschlange, H2-Verlauf und Wiederholung
  derselben Nachricht. DELIVERED bedeutet vom Funkgerät angenommen/gesendet;
  CONFIRMED bedeutet passendes Empfänger-ACK. Kanal-OK bestätigt keine Teilnehmer.
- DM: 160 UTF-8-Bytes; Kanal: 160 minus Sendername und `: `. Ab Wiederholungsnummer
  4 sind DM auf 158 Bytes begrenzt. Kanalnamen der Absender sind unbestätigter Text.
- Einstellungen: Name, Frequenz MHz/BW kHz/SF/CR/repeat, Leistung dBm, Position,
  Ankündigungs-/Telemetrierichtlinien, Uhrzeit, BLE-PIN und Neustart mit Rücklesen.
- Kanäle: Gerätegrenzen (1/8/40 Plätze), Anlegen/Ändern/Leeren, Public, Hashtag-
  SHA-256-Schlüssel, zufällige private Schlüssel, offizielle Links und lokaler QR.
- Kontakte: vollständige Schlüssel, Typen, Flags/Favoriten, Koordinaten, Zeitstempel,
  Pfade und Live-Aktualisierungen; Hinzufügen/Bearbeiten/Löschen/Import/Export/Teilen.
  Remote-Login/Logout/Status/Telemetrie/CLI und Pfadsuche sind in den Einstellungen.
- Erweiterte API: Tuning, Auto-add, Statistik/Variablen, Scopes/Hash-Modus, Trace,
  Binär-/Kanal-/Raw-/Control-Daten, Identität und Signieren sowie Factory Reset.
- Monitor: echte LOG_RX_DATA mit RSSI/SNR unter MESHCORE_RADIO. Host-Befehle und
  Kanalschlüssel erscheinen nicht als Funkpakete.
- KISS: Modemeinstellungen, Radio/Leistung ändern, Raw-Pakete bis 255 Bytes,
  TxDone/busy/errors und SetHardware-Konsole. Keine Companion-Chats über KISS.


## Versionen und Prüfungen

Referenz: [offizielle MeshCore-Firmware](https://github.com/meshcore-dev/MeshCore/blob/81dbae592ffad40b4212bc0b37e82b38da08f4bc/examples/companion_radio/MyMesh.cpp),
Commit `81dbae592ffad40b4212bc0b37e82b38da08f4bc`, Companion-Protokoll **14**.
Die gemeldete Client-Nachrichtenformatversion bleibt **3**, damit die Firmware
V3-Nachrichten sendet. Das begrenzt nicht die verfügbaren Befehle auf Version 3.
Bekannte Versionsgrenzen werden vor dem Senden geprüft. Unsupported/disabled
beendet nur den jeweiligen Vorgang. Ältere Firmware wurde nicht vollständig geprüft.

[API-Operationen](meshcore-api.md) · [CMD/RESP/PUSH-Matrix](meshcore-capabilities.md).

| Plattform / Transport | Softwareprüfung | Physisches Gerät |
|---|---|---|
| Linux TCP | Lokaler Socket, unabhängiger Frame-Emulator und Reconnect | Nicht geprüft |
| Linux Serial | Adapter, geteilte Lesevorgänge und Referenzframes; kein physischer Port | Nicht geprüft |
| Linux BLE | Java-Tests, native Bibliothek gebaut, MTU/Schreibfehler | Nicht geprüft |
| Windows BLE | Java-Tests; nativer Quellcode geändert, kein Windows-Build | Nicht geprüft |
| macOS BLE | Vorhandene maximale Schreibgröße; Java-Integration | Nicht geprüft |
| KISS TCP/Serial | Parser, sequenzieller Handshake, DATA/TxDone/RxMeta | Nicht geprüft |
| JavaFX | Fünf Tabs gerendert; Name über Szenario-Transport gespeichert | Nicht mit Funkgerät geprüft |

Die Hardwareabnahme benötigt zwei Companion-Geräte: Kanal/DM in beide Richtungen,
Offline-Warteschlange, offiziellen Client, lange UTF-8-Texte, Einstellungsänderungen
und Reconnect. Softwaretests ersetzen diese Prüfung nicht.

## Daten und Kompatibilitätsgrenzen

H2-Schema 26 erweitert Kennungen auf 80 Zeichen, ohne den Nachrichtenverlauf neu
aufzubauen. MeshCore-Kontakte verwenden `mc:` und alle 64 Hex-Zeichen des öffentlichen
Schlüssels. Positive lokale Nachrichten-ID, ACK-Hash und Datenbankzeilen-ID sind
getrennt. Statusänderungen prüfen Zeile und Eigentümer. Sendeversuche speichern
Nummer, Hash, Zeitlimit und Ergebnis. `message.details` erhält ursprüngliche
Nachrichten sowie Kanalname und Schlüsselfingerprint.

Alte kurze IDs werden im Kontext des bekannten Eigentümers nur bei eindeutigem
Präfix zugeordnet. Bei Präfixkollision wird keine DM auf Verdacht gesendet;
mehrdeutige eingehende Nachrichten bleiben unter `mc:pending:<prefix>` erhalten.
Bereits verlorene Schlüssel/Nachrichten können nicht wiederhergestellt werden.
Konfligierende alte Thread-/Lesestatuszeilen bleiben erhalten. Normale Logs und
Status-RPC enthalten keine Schlüssel/PINs/Passwörter; Export ist explizit. H2 ist
kein verschlüsselter Schlüsseltresor.

Die Firmware entnimmt Nachrichten vor dem lokalen Commit. Der Client speichert
vor dem nächsten Abruf und stoppt bei Datenbankfehlern. Bei Prozessabsturz in
diesem Fenster ist exactly-once nicht garantiert. Gleiche Nachrichten mit gleichem
Absender und Zeitstempel sind nicht immer unterscheidbar. Kanalplatzverlauf bleibt
bei Änderungen erhalten; die Deduplizierung berücksichtigt den Schlüsselfingerprint.

Das offizielle Stream-Framing ersetzt die alte Raw-Stream-Heuristik. Gespeicherte
Companion-Profile bleiben erhalten; eigene Bridges mit ungerahmten Payloads müssen
aktualisiert werden. Profile ohne Protokollfeld bleiben Meshtastic. KISS ist auf
Frame-Ebene nicht mit Companion kompatibel.

Links und QR folgen dem [offiziellen Clientformat](https://github.com/meshcore-dev/MeshCore/blob/81dbae592ffad40b4212bc0b37e82b38da08f4bc/docs/qr_codes.md).
Kanallinks mit `region_scope` werden ausdrücklich abgelehnt; der Scope muss separat
konfiguriert werden. QR wird lokal erzeugt, Links können eingefügt werden.
Kamera-/Bild-QR-Erkennung ist noch nicht implementiert.

Erweiterte Binary-/Trace-/Status-/Statistikantworten sind als Originalpayload über
Diagnose/API verfügbar, nicht alle als eigene Formulare oder Diagramme. Einige
erweiterte Operationen benötigen noch unabhängige Fixtures, wie in der Matrix
angegeben. Meshtastic-Protobuf-Replies/Reaktionen werden nicht über MeshCore gesendet.
