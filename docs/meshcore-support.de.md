# MeshCore-Unterstützung

**Sprache:** [Русский](meshcore-support.ru.md) | [English](meshcore-support.md) | Deutsch

MeshApp unterstützt MeshCore-Geräte über zwei unterschiedliche Protokollmodi:

- **MeshCore-KISS-Modemprotokoll** über einen TCP-/Serial-Byte-Stream.
- **MeshCore Companion Protocol** über BLE-RX/TX-GATT-Characteristics oder einen rohen TCP-/Serial-Byte-Stream.

Dies sind separate Protokoll-Runtimes neben Meshtastic, kein Ersatz für Meshtastic-Logik. KISS und Companion sind auf Framing-Ebene inkompatibel: MeshCore-BLE-Verbindungen verwenden keine KISS-Frames, und ein TCP-/Serial-Companion-Endpunkt muss rohe Companion-Pakete ohne KISS-Wrapper liefern.

## Was geändert wurde

- Neue Verbindungsprofile verwenden standardmäßig `ProtocolType.MESHTASTIC`; Legacy-Profile ohne `protocol`-Feld werden als Meshtastic behandelt.
- `ProtocolType.MESHCORE_KISS`, `ProtocolType.MESHCORE_COMPANION`, `MeshCoreKissProtocolRuntime` und `MeshCoreCompanionProtocolRuntime` wurden hinzugefügt.
- KISS-Framing für TCP- und Serial-Transporte wurde hinzugefügt.
- `FrameFormat.MESHCORE_COMPANION` und ein Stream-Parser für MeshCore-Companion-Pakete über TCP/Serial wurden hinzugefügt.
- Ein BLE-Profil für Service-/RX-/TX-UUIDs von MeshCore Companion wurde hinzugefügt.
- Das Protokoll wird vor dem Start der Runtime explizit ausgewählt: Wählen Sie `MeshCore KISS` oder `MeshCore Companion` im Verbindungsprofil.
- Die Verbindungskarte zeigt das ausgewählte/aktive Protokoll.
- MeshCore Companion füllt jetzt den gemeinsamen UI-Zustand für Chat, Knoten, Dashboard, Einstellungen und LoRa Monitor.
- Senden von Kanalnachrichten und DMs über MeshCore Companion Protocol wurde hinzugefügt.
- Synchronisation von MeshCore-Kontakten, Kanalinformationen und eingehenden Nachrichten aus der Companion-Protocol-Warteschlange wurde hinzugefügt.
- Bestehende Meshtastic-Profile bleiben kompatibel. Legacy-Profile ohne `protocol`-Feld gelten weiterhin als `MESHTASTIC`.

## Unterstützte Transporte

| Transport | MeshCore-Modus | Hinweis |
|-----------|---------------|------|
| Serial / USB | KISS | Standard-Serial-Einstellungen für MeshCore KISS: 115200 Baud, 8N1, keine Flusskontrolle. |
| TCP | KISS | Funktioniert mit Endpunkten, die denselben KISS-Byte-Stream über TCP bereitstellen. |
| Serial / USB | Companion Protocol | Funktioniert mit Endpunkten, die rohe Companion-Pakete ohne KISS-Framing bereitstellen. |
| TCP | Companion Protocol | Funktioniert mit Bridge-/Server-Endpunkten, die rohe Companion-Pakete über einen Byte-Stream übertragen. |
| BLE | Companion Protocol | Verwendet separate BLE-Service-/RX-/TX-UUIDs, TX-Benachrichtigungen und rohe Companion-Pakete. |

## Protokollauswahl

Für neue Profile ist `ConnectionEntry.protocol` standardmäßig `MESHTASTIC`.

1. MeshApp öffnet den ausgewählten Transport.
2. TCP-/Serial-Transport erhält sofort das `FrameFormat`, das zum gespeicherten `ProtocolType` passt.
3. BLE-Transport wählt sofort das GATT-Profil des gespeicherten `ProtocolType`.
4. `ConnectionManager` startet die Runtime aus `ProtocolRegistry`: `MeshtasticProtocolRuntime`, `MeshCoreKissProtocolRuntime` oder `MeshCoreCompanionProtocolRuntime`.
5. Legacy-Profile ohne `protocol`-Feld verwenden `MESHTASTIC`.

Für MeshCore wählen Sie beim Erstellen einer Verbindung im Protokollfeld explizit `MeshCore KISS` oder `MeshCore Companion`.

## MeshCore Companion

MeshCore Companion Protocol nutzt ein eigenes binäres Paketprotokoll und kein KISS-Framing. Laut MeshCore-Dokumentation kündigen BLE-Companion-Geräte einen Nordic-UART-ähnlichen Service an:

- Service UUID: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
- RX characteristic, App -> Firmware: `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`
- TX characteristic, Firmware -> App: `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`

MeshApp fügt für diese UUIDs ein separates BLE-Profil hinzu:

- die Suche sucht nach der Service-UUID des ausgewählten Profils;
- die Verbindung nutzt sofort das ausgewählte Profil;
- der MeshCore-Companion-Transport schreibt rohe Companion-Pakete in die RX-Characteristic;
- eingehende Daten kommen über Benachrichtigungen aus der TX-Characteristic;
- `MeshCoreCompanionProtocolRuntime` sendet `APP_START` und parst `SELF_INFO`, `DEVICE_INFO`, `BATTERY`, Kontakte, Kanalinformationen und gepufferte Nachrichten.

TCP/Serial verwendet `FrameFormat.MESHCORE_COMPANION`: Der Transport gibt rohe Companion-Pakete an die gemeinsame Runtime weiter. Da der offizielle Companion-Transport auf paketgrenzenbasiertem BLE GATT beruht, arbeitet der Byte-Stream-Parser nach Best-Effort: Antworten fester Größe werden sofort ausgegeben, Antworten variabler Größe werden durch Byte-Stille/Lese-Timeout abgeschlossen.

Die Companion-Runtime erzeugt einen kompatiblen `DeviceState`, sodass bestehende MeshApp-Bildschirme MeshCore-Kontakte als Knoten, MeshCore-Kanäle als Chats, Akkuspannung als Dashboard-Telemetrie und rohe Companion-Pakete als LoRa-Monitor-Einträge anzeigen können.

## Umfang der MeshCore-KISS-Unterstützung

Die aktuelle MeshCore-Runtime führt den grundlegenden KISS-Modem-Handshake aus und liest Gerätemetadaten über MeshCore-`SetHardware`-Erweiterungen.

Aktuell erfasster Zustand:

- Gerätename
- öffentlicher Identitätsschlüssel, in der UI als kurzes `mc:<12 hex>` angezeigt
- Firmware-Version
- Funkparameter: Frequenz, Bandbreite, Spreading Factor, Coding Rate
- Sendeleistung
- Akkuspannung
- Paketstatistik
- letzte RX-Metadaten: RSSI und SNR
- letzter TX-Status
- letzter MeshCore-Fehlercode

Die KISS-Runtime bleibt eine Modem-/Metadatenintegration. Chat-, DM-, Kontakt- und Kanalabläufe sind über MeshCore Companion Protocol implementiert, weil es Anwendungsbefehle für den Companion-Client bereitstellt.

## Umfang der MeshCore-Companion-Unterstützung

Die aktuelle MeshCore-Companion-Runtime führt den `APP_START`-Handshake aus und sammelt:

- Self-Info-Paket;
- öffentlichen Schlüssel, vollständig in der Runtime verfügbar und in der UI als kurzes `mc:<12 hex>` angezeigt;
- Gerätename aus Self-Info;
- Kontaktliste aus `CONTACTS_START` / `CONTACT` / `CONTACTS_END`;
- Kanalinformationen aus `CHANNEL_INFO`;
- eingehende Kanal- und Kontaktnachrichten einschließlich V3-Varianten;
- ausgehende Kanalnachrichten und DMs;
- Firmware-Protokollversion;
- maximale Kontakte / maximale Kanäle, wenn das Gerät device-info v3+ zurückgibt;
- BLE-PIN, Firmware-Build, Modell und Firmware-Version aus device-info v3+;
- Akkuspannung;
- Speichernutzung, falls in der Akkuantwort vorhanden;
- letzten Companion-Fehlercode.

Unterstützte Bildschirme:

- **Chat**: zeigt MeshCore-Kanäle, speichert Verlauf in der gemeinsamen H2-Datenbank, sendet Kanalnachrichten und DMs. Reaktionen, Traceroute und Meshtastic-Botbefehle sind für MeshCore deaktiviert oder zeigen lokale Informationen.
- **Knoten**: zeigt MeshCore-Companion-Kontakte als Knoten mit Public-Key-Präfix, Name, Rolle, Koordinaten und letzter Advert-Zeit, wenn diese Felder vom Gerät empfangen wurden.
- **DM**: Direktchats werden über die MeshCore-Kontakt-ID `mc:<12 hex>` erstellt und über `SEND_TXT_MSG` gesendet.
- **Dashboard**: zeigt die Akkuspannung als Telemetrieeintrag für das lokale MeshCore-Gerät.
- **Einstellungen**: zeigt einen schreibgeschützten Baum mit MeshCore-Metadaten, Funkparametern, Speicher und Kanälen. Meshtastic-Admin-Protobuf-Konfiguration wird für MeshCore nicht geschrieben.
- **LoRa Monitor**: zeichnet eingehende und ausgehende rohe MeshCore-Companion-Pakete mit Transportmechanismus `MESHCORE_COMPANION`, eigenen Pakettypen und HEX-/ASCII-Vorschau auf.

## KISS-Framing

MeshCore KISS verwendet standardmäßiges KISS-TNC-Framing:

| Byte | Wert |
|------|-------|
| `0xC0` | `FEND`, Frame-Trenner |
| `0xDB` | `FESC`, Escape-Byte |
| `0xDC` | escaped `FEND` |
| `0xDD` | escaped `FESC` |

MeshApp übergibt der Protokoll-Runtime einen bereits ent-escapten Frame-Body:

```text
[type byte][payload...]
```

Für MeshCore-Metadaten verwendet das Typ-Byte den KISS-Befehl `SetHardware` (`0x06`), und das erste Payload-Byte ist der MeshCore-Unterbefehl. Standard-Datenframes (`0x00`) werden vom Parser akzeptiert, aber die aktuelle MeshCore-Runtime loggt sie nur und decodiert MeshCore-Paket-Payload noch nicht in Anwendungsmodelle.

## UI-Verhalten

- Der Benutzer erstellt eine normale TCP-, Serial- oder BLE-Verbindung.
- Das Protokoll ist standardmäßig `Meshtastic`; für MeshCore wählt der Benutzer explizit `MeshCore KISS` oder `MeshCore Companion`.
- Nach Klick auf **Verbinden** startet MeshApp die ausgewählte Protokoll-Runtime.
- Die Verbindungskarte zeigt `Meshtastic`, `MeshCore KISS` oder `MeshCore Companion`.
- Für MeshCore Companion sind Chat, Knoten, Dashboard, Einstellungen und LoRa Monitor verfügbar. Funktionen, die an Meshtastic-Admin-/Traceroute-/Reaction-Protobuf gebunden sind, bleiben nicht verfügbar und melden dies ausdrücklich in der UI.
- Für MeshCore KISS werden Verbindung und Modem-Runtime-Metadaten angezeigt; Anwendungsbildschirme verwenden Companion Protocol.

## Entwicklungshinweise

Wichtige Klassen:

- `com.meshtastic.client.connection.KissFrameParser`
- `com.meshtastic.client.connection.MeshCoreCompanionFrameParser`
- `com.meshtastic.client.protocol.meshcore.MeshCoreKissProtocol`
- `com.meshtastic.client.protocol.meshcore.MeshCoreKissProtocolRuntime`
- `com.meshtastic.client.protocol.meshcore.MeshCoreKissState`
- `com.meshtastic.client.protocol.meshcore.MeshCoreKissFrames`
- `com.meshtastic.client.connection.ble.BleProtocolProfile`
- `com.meshtastic.client.protocol.meshcore.MeshCoreCompanionProtocol`
- `com.meshtastic.client.protocol.meshcore.MeshCoreCompanionProtocolRuntime`
- `com.meshtastic.client.protocol.meshcore.MeshCoreCompanionState`
- `com.meshtastic.client.protocol.meshcore.MeshCoreCompanionFrames`
- `com.meshtastic.client.forms.FormChatData`
- `com.meshtastic.client.forms.FormChatUi`
- `com.meshtastic.client.forms.FormSetting`
- `com.meshtastic.client.service.PacketMonitorService`

Durch Tests abgedeckt:

- KISS-Escaping und Parsing
- Companion-Paket-Parsing über Byte-Stream-Transporte
- Protokollregistrierung in der Registry
- Start der MeshCore-KISS-Runtime nach expliziter Protokollauswahl
- Start der MeshCore-Companion-Runtime nach expliziter Protokollauswahl
- End-to-End-Auswahl der TCP-/BLE-Runtime über `ConnectionManager`
- MeshCore-Companion-Bridge in `DeviceState` für Chat/Knoten/Dashboard
- Senden von MeshCore-Kanalnachrichten und DMs
- rohe MeshCore-Companion-Einträge im LoRa Monitor

Spezifikationslinks:

- MeshCore-KISS-Modemprotokoll: <https://github.com/meshcore-dev/MeshCore/blob/main/docs/kiss_modem_protocol.md>
- MeshCore Companion Protocol: <https://docs.meshcore.io/companion_protocol/>
- MeshCore-Paketformat: <https://docs.meshcore.io/packet_format/>
