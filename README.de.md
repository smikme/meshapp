<p align="center">
  <img src="docs/logo/MeshApp.png" width="128" alt="MeshApp Logo"/>
</p>

<h1 align="center">MeshApp</h1>

<p align="center">
  Desktop-Client für
  <a href="https://meshtastic.org">Meshtastic</a>- und MeshCore-Netzwerke
  <br/>
  <b>Java 25 &nbsp;·&nbsp; JavaFX &nbsp;·&nbsp; Protobuf &nbsp;·&nbsp; LoRa</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-25-orange?logo=openjdk" alt="Java 25"/>
  <img src="https://img.shields.io/badge/JavaFX-25.0.3-blue?logo=java" alt="JavaFX"/>
  <img src="https://img.shields.io/badge/Platform-Win%20%7C%20macOS%20%7C%20Linux-brightgreen" alt="Plattform"/>
  <img src="https://img.shields.io/badge/License-AGPL--3.0-blue" alt="Lizenz"/>
  <a href="https://t.me/+SRaOd1gftoo5MWRi">
    <img src="https://img.shields.io/badge/Telegram-@MeshAppClient-blue?logo=telegram" alt="Telegram">
  </a>
</p>

<p align="center">
  Primäre Entwicklungsplattform:
  <a href="https://git.privatepractice.app/covox/meshapp">git.privatepractice.app/covox/meshapp</a>
</p>

<p align="center">
  <a href="#installation">Installation</a>
</p>

<div align="right">

<a href="README.md">English</a> | <a href="README.ru.md">Русский</a> | <strong>Deutsch</strong>

</div>

---

## Über MeshApp

MeshApp ist eine Desktop-Anwendung für Meshtastic- und MeshCore-Geräte. Sie verbindet sich per Netzwerk, USB oder BLE mit einem Gerät.

Die Anwendung kann Nachrichten austauschen, Netzwerkknoten auf einer Karte anzeigen, Telemetrie darstellen, Geräteeinstellungen ändern, LoRa-Pakete untersuchen und Lua-Skripte ausführen.

Neue Verbindungsprofile verwenden standardmäßig Meshtastic. Für MeshCore wählen Sie beim Erstellen des Profils den Betriebsmodus aus:

- `MeshCore KISS` - für TCP und Serial / USB
- `MeshCore Companion` - für BLE, TCP und Serial / USB

Verbindungstyp und Protokoll werden getrennt ausgewählt. Sie können sich zum Beispiel per TCP mit einem Meshtastic-Gerät, per USB mit einem MeshCore-KISS-Modem oder per BLE mit einem Gerät mit MeshCore Companion verbinden.

![MeshApp-Architektur](docs/meshapp-architecture.jpg)

---

## Installation

Fertige Pakete werden auf der [Gitea-Release-Seite](https://git.privatepractice.app/covox/meshapp/releases) veröffentlicht.

- macOS: `.dmg` herunterladen, öffnen und MeshApp in den Programme-Ordner verschieben.
- Windows: `.msi`-Installer herunterladen und ausführen.
- Debian / Ubuntu: `.deb`-Paket herunterladen und mit `apt` oder dem Paketmanager installieren.
- Linux AppImage: `.AppImage` herunterladen, ausführbar machen und starten.

Flatpak-Nutzer können MeshApp über die veröffentlichte Flatpak-Referenz installieren:

```bash
flatpak install --user https://flatpak.privatepractice.app/app.privatepractice.meshapp.flatpakref
flatpak run app.privatepractice.meshapp
```

Wenn das Repository `meshapp` früher mit dem alten direkten `/repo/`-Befehl hinzugefügt wurde und Flatpak `public key not found` meldet, entfernen Sie das alte Remote und fügen Sie es erneut hinzu:

```bash
flatpak remote-delete --user --force meshapp
flatpak install --user https://flatpak.privatepractice.app/app.privatepractice.meshapp.flatpakref
```

So aktualisieren Sie die Flatpak-Installation:

```bash
flatpak update app.privatepractice.meshapp
```

---

## Funktionsumfang

MeshApp umfasst:

- Chats in Netzwerkkanälen und Direktunterhaltungen.
- Eine Netzwerkknotenliste mit Suche, Filtern, Favoriten und ignorierten Geräten.
- Eine Karte mit Online- und Offline-Kacheln, Netzwerkknoten, Messungen und gespeicherten Routen.
- Geräte- und Netzwerktelemetrie: aktuelle Werte, Verlauf, Diagramme und Tabellenansicht.
- Meshtastic-Geräteeinstellungen: Konfiguration, Kanäle und Editoren für komplexe Felder.
- Verbindungen über TCP, USB/Serial und BLE; Meshtastic, MeshCore KISS und MeshCore Companion.
- Anwendungslogs und LoRa-Paketansicht mit Filtern, Suche und Export.
- Lua-Skripte: Editor, Debugger, Autostart, Bots, Datenspeicher und Skript-Store.
- Eine lokale Datenbank für Nachrichten, Telemetrie, Netzwerkknoten, Routen, Skripte und Paketlog.
- Terminalmodus ohne JavaFX.

---

## Chats

<p align="center">
  <img src="docs/screenshots/chat-b.jpg" width="49%" alt="Chat - dunkles Theme"/>
  <img src="docs/screenshots/chat-w.jpg" width="49%" alt="Chat - helles Theme"/>
</p>

<p align="center">
  <img src="docs/screenshots/chat-node-info-b.jpg" width="49%" alt="Knoteninformationen im Chat - dunkles Theme"/>
  <img src="docs/screenshots/chat-node-info-w.jpg" width="49%" alt="Knoteninformationen im Chat - helles Theme"/>
</p>

Chats umfassen Netzwerkkanäle und Direktunterhaltungen. Nachrichten werden lokal gespeichert; Antworten, Reaktionen, Zustellstatus und Ungelesen-Zähler werden unterstützt.

Die Eingabezeile kann `@tracebot`- und `@infobot`-Befehle ausführen. Sie prüfen die Route zum ausgewählten Knoten und fragen Informationen dazu ab; während der Eingabe schlägt die App Knotennamen und Adressen vor.

Kanäle können erstellt und bearbeitet werden: Name, Zugriffsschlüssel, Positionsfreigabe und Positionsgenauigkeit. Benachrichtigungen lassen sich für jeden Kanal und jede Direktunterhaltung separat aktivieren oder deaktivieren.

---

## Knoten

<p align="center">
  <img src="docs/screenshots/nodes-b.jpg" width="49%" alt="Knoten - dunkles Theme"/>
  <img src="docs/screenshots/nodes-w.jpg" width="49%" alt="Knoten - helles Theme"/>
</p>

Der Knotenbildschirm zeigt Geräte, die aktuell im Netzwerk sichtbar sind, sowie Geräte, die bereits in der lokalen Datenbank gespeichert wurden. Die Suche funktioniert nach Name, Kurzname, Kennung und numerischer Adresse. Filter gibt es nach letzter Sichtung, Entfernung, Signalqualität, Hop-Anzahl, Kanal, Favoriten, ignorierten Knoten, direkten Knoten und nicht verfügbaren Knoten.

Die Knotenkarte zeigt Rolle, Hardwaremodell, Koordinaten, Firmware-Version, Signalpegel und ein Telemetriediagramm. Von der Karte aus können Sie einen Direktchat öffnen, die Route zum Knoten prüfen, Informationen aktualisieren oder den Knoten aus der lokalen Liste entfernen.

Der Verlauf der Routenprüfungen wird für jeden Knoten separat gespeichert. Eine gespeicherte Route kann auf der Karte geöffnet werden.

---

## Karte

<p align="center">
  <img src="docs/screenshots/map-b.jpg" width="49%" alt="Karte - dunkles Theme"/>
  <img src="docs/screenshots/map-w.jpg" width="49%" alt="Karte - helles Theme"/>
</p>

Die Karte zeigt Knoten mit Koordinaten und gespeicherte Routen. Unterstützt werden Online-Kacheln von OpenStreetMap, ein lokaler Cache und ein Offline-Kachelverzeichnis im Format `z/x/y.png|jpg|jpeg`.

Die Karte bietet Suche, Filter, Sprung zum eigenen Gerät, Übersicht aller Knoten mit Koordinaten, Nachtmodus, Entfernungsmessung und rechteckige Bereichsauswahl. Der ausgewählte Bereich kann für die Nutzung ohne Internetzugang heruntergeladen werden; der Download lässt sich pausieren oder abbrechen.

---

## Telemetrie

<p align="center">
  <img src="docs/screenshots/telemetry-b.jpg" width="49%" alt="Telemetrie - dunkles Theme"/>
  <img src="docs/screenshots/telemetry-w.jpg" width="49%" alt="Telemetrie - helles Theme"/>
</p>

Telemetrie zeigt Geräte- und Netzwerkzustand: Akkuladung, Spannung, Kanalauslastung, für Übertragung genutzte Airtime, Empfangsqualität, Anzahl gesendeter, verlorener und weitergeleiteter Pakete, Signalpegel und Hop-Daten.

Daten können in Diagrammen oder als Tabelle angezeigt werden. Der Zeitraum reicht von 1 Stunde bis zum vollständigen Verlauf. Für lange Zeiträume werden Werte gemittelt, damit die Diagramme lesbar bleiben.

---

## Verbindungen

<p align="center">
  <img src="docs/screenshots/connections-b.jpg" width="49%" alt="Verbindungen - dunkles Theme"/>
  <img src="docs/screenshots/connections-w.jpg" width="49%" alt="Verbindungen - helles Theme"/>
</p>

MeshApp kann mit mehreren Verbindungen parallel arbeiten. Verbindungsprofile speichern Adresse, Port, ausgewähltes BLE-Gerät, USB-/Serial-Einstellungen und Protokoll. Für das gewünschte Profil kann eine automatische Verbindung beim Start der Anwendung aktiviert werden.

Unterstützte Verbindungstypen:

- TCP
- Serial / USB
- BLE

Für Serial / USB stehen Port-Erkennung und DTR/RTS-Leitungseinstellungen zur Verfügung. Gängige USB-UART-Chips CH340, CP210x und FTDI werden unterstützt; Windows mit Silicon Labs / CP210x hat eine eigene Verbindungsbehandlung.

Für BLE werden Gerätesuche, GATT-Verbindung und Passkey-Pairing unterstützt, wenn Gerät oder Betriebssystem dies verlangen.

Nach dem Verbinden führt MeshApp den initialen Austausch mit dem Gerät aus:

- Meshtastic: Austausch der Einstellungen
- MeshCore KISS: Aushandlung über `SetHardware`
- MeshCore Companion: Startaustausch über `APP_START`

### MeshCore

MeshCore wird in zwei Varianten unterstützt:

- `MeshCore KISS` funktioniert über TCP oder Serial / USB.
- `MeshCore Companion` funktioniert über BLE, TCP oder Serial / USB.

MeshCore Companion verwendet kein KISS-Format. Für BLE nutzt es:

- service `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
- RX characteristic `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`
- TX notifications `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`

Bei Verbindungen über TCP oder Serial muss das Gerät Companion-Pakete ohne zusätzlichen KISS-Wrapper senden.

Derzeit unterstützt:

- KISS-Format (`FEND`, `FESC` und Escape-Sequenzen) für TCP und Serial
- Lesen von Geräteinformationen über MeshCore `SetHardware`: Name, Version, Kennung, LoRa-Parameter, TX-Leistung, Akku, Statistiken, RSSI/SNR-Daten und TX-Status
- MeshCore-Companion-BLE-Profil mit RX/TX-UUIDs, Abonnement von TX-Benachrichtigungen und `APP_START`
- `FrameFormat.MESHCORE_COMPANION` für Companion-Pakete über TCP und Serial
- Informationen über lokales Gerät, öffentlichen Schlüssel, Gerätedaten, Akku und Speicher
- Kontakte und Kanalinformationen in den gemeinsamen Knoten- und Chat-Bildschirmen
- eingehende und ausgehende Kanalnachrichten und Direktnachrichten über das Companion Protocol
- Anzeige von MeshCore-Informationen in den Einstellungen ohne Bearbeitung
- MeshCore-Companion-Pakete in LoRa Debug
- Anzeige des aktiven Protokolls in der Verbindungskarte

Einschränkungen:

- MeshCore KISS funktioniert nur über TCP und Serial.
- MeshCore Companion über TCP/Serial funktioniert nur mit Geräten, die Companion-Pakete ohne KISS-Wrapper senden.
- MeshCore KISS wird derzeit für Modembetrieb und das Lesen von Geräteinformationen genutzt; Chats, Direktnachrichten und die wichtigsten Benutzerabläufe sind über MeshCore Companion umgesetzt.
- MeshCore Companion enthält keine Funktionen, die nur zu Meshtastic gehören: Speichern von Einstellungen über Admin-Protobuf, Reaktionen, Routenprüfungen und Meshtastic-Botbefehle.

Details: [docs/meshcore-support.de.md](docs/meshcore-support.de.md).

---

## Geräteeinstellungen

<p align="center">
  <img src="docs/screenshots/settings-b.jpg" width="49%" alt="Einstellungen - dunkles Theme"/>
  <img src="docs/screenshots/settings-w.jpg" width="49%" alt="Einstellungen - helles Theme"/>
</p>

Meshtastic-Geräteeinstellungen werden als Abschnittsbaum geöffnet: Gerät, LoRa, Position, Stromversorgung, Netzwerk, Bluetooth, Anzeige und weitere Einstellungen.

Die Oberfläche erlaubt das Ändern des langen und kurzen Gerätenamens, das Bearbeiten von Konfigurationsfeldern, das Speichern mehrerer Änderungen in einem Vorgang, das Synchronisieren der Gerätezeit mit dem Computer, Neustart und Herunterfahren.

Für Felder, die manuell unpraktisch zu bearbeiten sind, gibt es eigene Editoren: IPv4-Adressen, Knotenkennungen, Hexwerte, Bitmasken und Wertelisten wie `admin_key` und `ignore_incoming`.

Konfigurationen können exportiert und importiert werden:

- `.mcf` - vollständige Konfigurationskopie
- `.mtp` - Vorlage ohne persönliche und geheime Daten

Die Oberfläche kann außerdem die lokale H2-Datenbank leeren: Nachrichten, Reaktionen, Telemetrie, Knotencache und Paketlog.

---

## Logs und LoRa-Pakete

<p align="center">
  <img src="docs/screenshots/logs-b.jpg" width="49%" alt="Logs - dunkles Theme"/>
  <img src="docs/screenshots/logs-w.jpg" width="49%" alt="Logs - helles Theme"/>
</p>

<p align="center">
  <img src="docs/screenshots/loradebug-b.jpg" width="49%" alt="LoRa Debug - dunkles Theme"/>
  <img src="docs/screenshots/loradebug-w.jpg" width="49%" alt="LoRa Debug - helles Theme"/>
</p>

Der eingebaute Log-Viewer unterstützt farbige Log-Level, pausierbares Autoscroll, Kopieren, Leeren und Export nach `.log`.

LoRa Debug zeigt eingehende, ausgehende und interne `MeshPacket`-Einträge. Pakete können nach Richtung, Typ, Zeit, Knoten und Inhalt gefiltert werden. Für das ausgewählte Paket stehen HEX- und ASCII-Ansicht, Protobuf-Baum und Feldhervorhebung zur Verfügung.

Das ausgewählte Paket kann kopiert oder gespeichert werden. Die gefilterte Menge kann als JSON oder CSV exportiert werden.

Wenn die Anwendung abstürzt oder ein Problem an die Entwickler gesendet werden soll, kann ein Bericht nach dem Absturz oder manuell aus dem Hilfefenster gesendet werden.

---

## Lua-Skripte und MeshApp IDE

<p align="center">
  <img src="docs/screenshots/luascripts-b.jpg" width="49%" alt="Lua-Skripte - dunkles Theme"/>
  <img src="docs/screenshots/luascripts-w.jpg" width="49%" alt="Lua-Skripte - helles Theme"/>
</p>

<p align="center">
  <img src="docs/screenshots/ide-b.jpg" width="49%" alt="Lua-Editor - dunkles Theme"/>
  <img src="docs/screenshots/ide-w.jpg" width="49%" alt="Lua-Editor - helles Theme"/>
</p>

<p align="center">
  <img src="docs/screenshots/shop-b.jpg" width="49%" alt="Skript-Store - dunkles Theme"/>
  <img src="docs/screenshots/shop-w.jpg" width="49%" alt="Skript-Store - helles Theme"/>
</p>

MeshApp IDE ist eine eingebaute Umgebung für Lua-Skripte und Bots. Skripte werden direkt in der Anwendung erstellt und ausgeführt, in der lokalen Datenbank gespeichert und können nach `.meshapp-script.json` exportiert werden.

Die IDE umfasst:

- Skriptkarten mit Name, Symbol, Autor, Version, Typ und Status
- Skripteinstellungen: Beschreibung, Autostart, Bot-Typ, Bindung an einen Knoten oder Automationsnamen
- Lua-Editor mit Highlighting, Zeilennummern, automatischer Einrückung und `mesh.*`-Vervollständigung
- Syntaxprüfung und Ausgabe von Laufzeitfehlern
- Debugger mit Breakpoints, schrittweiser Ausführung und Ansichten für lokale/globale Variablen
- isolierter Datenspeicher für jedes Skript
- Skript-Store mit Installation, Update und Entfernen lokaler Kopien

Skripte laufen in einer isolierten LuaJ-Umgebung. Gefährliche APIs sind deaktiviert: `io`, `os`, `debug`, `package`, `require`, `dofile`, `loadfile`, `luajava`.

Einstiegspunkte:

- `on_message(msg)` - Reaktion auf eine neue Nachricht
- `on_command(command)` - Befehlsverarbeitung
- `on_node_selected(event)`, `on_traceroute(event)`, `on_node_info(event)`, `on_admin(event)` - asynchrone Event-Handler

API-Dokumentation: [docs/lua-api.de.md](docs/lua-api.de.md).

---

## Oberfläche und lokale Daten

<p align="center">
  <img src="docs/screenshots/info-b.jpg" width="49%" alt="Hilfe und Informationen - dunkles Theme"/>
  <img src="docs/screenshots/info-w.jpg" width="49%" alt="Hilfe und Informationen - helles Theme"/>
</p>

Die Oberfläche enthält dunkle und helle AtlantaFX-Cupertino-Themes, eine Seitenleiste, Tray-/Statusleisten-Integration, Toast-Benachrichtigungen und Schnellschalter für Theme und Benachrichtigungen. Windows 11 nutzt Mica, macOS nutzt Vibrancy. Fenstergröße, Fensterposition und Splitterpositionen werden zwischen Sitzungen gespeichert.

Systembenachrichtigungen werden bei neuen Nachrichten angezeigt, wenn der entsprechende Chat nicht geöffnet ist. Update-Prüfungen beim Start können in den Einstellungen aktiviert oder deaktiviert werden.

Nachrichten, Reaktionen, ungelesene Chats, Favoriten und ignorierte Knoten, Telemetrie, Skripte, Skriptdaten, Verlauf der Routenprüfungen und das LoRa-Paketlog werden lokal gespeichert.

Import des öffentlichen OneMesh-Caches und eine lokale MQTT-Bridge für `MQTT proxy_to_client` werden ebenfalls unterstützt. Broker-Parameter werden aus der MQTT-Konfiguration des Geräts übernommen.

Für die Nutzung ohne JavaFX steht ein Terminalmodus auf Basis von Lanterna zur Verfügung.

---

## Schnelleinstieg

### Voraussetzungen

Zum Bauen und Starten aus dem Quellcode werden benötigt:

- Git
- JDK 25; Gradle kann die benötigte Toolchain automatisch herunterladen
- macOS: Xcode Command Line Tools (`cc`) für `libmeshapp-serial.dylib` und `libmeshapp-tray.dylib`
- Windows: CMake + MSVC Build Tools für `meshapp-ble.dll`
- Linux: CMake + C/C++-Toolchain + `libsystemd-dev` / `systemd-devel` für `libmeshapp-ble.so`

Fertige Pakete (`.dmg`, `.msi`, `.deb`, `.AppImage`, `.flatpak`) benötigen diese Build-Abhängigkeiten nicht.

### Start aus dem Quellcode

```bash
git clone https://git.privatepractice.app/covox/meshapp.git
cd meshapp

# JavaFX-Anwendung
./gradlew run

# Terminalmodus
./gradlew runTerminal

# Terminalmodus mit temporärem TCP-Profil
./gradlew runTerminal --args="--host 192.168.1.10 --protocol meshtastic"
```

### Verbindung mit einem Gerät

1. Verbinden Sie das Gerät über USB, TCP oder BLE.
2. Öffnen Sie **Verbindungen**.
3. Fügen Sie ein Profil hinzu: **TCP**, **Serial / USB** oder **BLE**.
4. Wählen Sie das Protokoll. Neue Profile verwenden standardmäßig **Meshtastic**; für MeshCore wählen Sie **MeshCore KISS** oder **MeshCore Companion**.
5. Wählen Sie bei **Serial / USB** den Port. Starten Sie bei **BLE** die Suche und wählen Sie das Gerät aus.
6. Wenn Gerät oder Betriebssystem einen Passkey anfordert, bestätigen Sie das Pairing.
7. Klicken Sie auf **Verbinden**.

Nach der Verbindung mit Meshtastic sind Chats, Knoten, Karte, Einstellungen und die weiteren Hauptbildschirme verfügbar. Für MeshCore Companion stehen Chats, Knoten, Direktnachrichten, Telemetrie, Einstellungen und LoRa Debug zur Verfügung. Für MeshCore KISS werden Modeminformationen angezeigt.

### Linux: Zugriff auf USB-Serial

Wenn der USB-Port sichtbar ist, die Verbindung aber mit `Permission denied` fehlschlägt, hat der Benutzer keinen Zugriff auf `/dev/ttyUSB*` oder `/dev/ttyACM*`.

```bash
ls -l /dev/ttyUSB0
sudo usermod -aG dialout "$USER"
```

Auf manchen Distributionen heißt die Gruppe `uucp` oder `lock`; verwenden Sie die von `ls -l` angezeigte Gruppe. Melden Sie sich nach der Gruppenänderung ab und wieder an.

Das MeshApp-`.deb`-Paket installiert udev-Regeln für gängige USB-UART-Meshtastic-Boards. Der aktive lokale Benutzer erhält eine `uaccess`-ACL, und ModemManager übernimmt den Port nicht.

Wenn der Fehler wie `Device or resource busy` aussieht, ist der Port bereits in einem anderen Prozess geöffnet: Serial Monitor, CLI oder ModemManager.

---

## Debug-Start und Profiling

Lokales JMX für VisualVM, JConsole oder JMC:

```bash
./gradlew run -PjmxDebugEnabled=true
./gradlew run -PjmxDebugEnabled=true -PjmxDebugPort=9011
```

Wenn JMX aktiviert ist, lauscht die Anwendung nur auf `127.0.0.1`. Verbindungsadresse:
`service:jmx:rmi:///jndi/rmi://127.0.0.1:9010/jmxrmi`. Für einen anderen Port ersetzen Sie `9010` durch den Wert von `jmxDebugPort`.

Dasselbe kann über Umgebungsvariablen aktiviert werden:

```bash
MESHAPP_JMX_DEBUG=true
MESHAPP_JMX_PORT=9011
```

Für VisualVM reicht `Sampler > Memory` über eine lokale JMX-Verbindung normalerweise aus. `Profiler > Memory` instrumentiert Klassen; unter Java 25/GraalVM/JavaFX/macOS kann dessen nativer Agent die Ziel-JVM zum Absturz bringen.

Wenn Sie ausdrücklich `Profiler > Memory` benötigen, starten Sie die Anwendung so:

```bash
./gradlew run -PvisualVmProfilerEnabled=true
./gradlew run -PvisualVmProfilerEnabled=true -PjmxDebugPort=9011
```

Dieser Modus aktiviert JMX, deaktiviert Class Data Sharing (`-Xshare:off`) und deaktiviert während des Profilings den Graal/JVMCI-JIT. Er kann über die Umgebungsvariable `MESHAPP_VISUALVM_PROFILER=true` aktiviert werden.

Wenn eine gebaute macOS-`.app` profiliert werden soll, bauen Sie sie mit demselben Flag neu:

```bash
./gradlew jpackage -PvisualVmProfilerEnabled=true
```

Software-Rendering zum Prüfen von macOS-`CVDisplayLink`-Problemen ist standardmäßig deaktiviert. Für eine einmalige Prüfung:

```bash
./gradlew run -PvisualVmSoftwareRenderingEnabled=true
```

Wenn VisualVM `Provided Memory settings are invalid` anzeigt, öffnen Sie die Einstellungen des Memory-Profilers und ersetzen Sie die Vorlage in `Profile classes` durch einen gültigen Filter, zum Beispiel `com.meshtastic.client.**` oder `**`.

---

## Technologien

| Komponente | Technologie | Zweck |
|-----------|------------|---------|
| Oberfläche | JavaFX 25.0.3 + AtlantaFX | Hauptoberfläche |
| Meshtastic | Protobuf 4.33.4 + Meshtastic-Schemas | `ToRadio` / `FromRadio` und Mesh-Pakete |
| MeshCore KISS | KISS-Format + MeshCore `SetHardware` | Initialer Austausch und MeshCore-KISS-Modeminformationen |
| MeshCore Companion | Companion Protocol + BLE RX/TX oder TCP/Serial ohne KISS-Wrapper | Initialer Austausch, Geräteinformationen, Kontakte, Kanäle, Chats und Paketansicht |
| Transport | `TransportConnection` | Gemeinsamer Vertrag für TCP, Serial, BLE und künftige Verbindungsmethoden |
| Datenbank | Eingebettetes H2 | Nachrichten, Telemetrie, Skripte, Routen und Logs |
| Karten | JavaFX `TileMapView` + OpenStreetMap-Kacheln | Online- und Offline-Karte, gespeicherte Routen |
| Lua-Umgebung | LuaJ 3.0.1 | Isolierte Skripte, Bots, Datenspeicher und `mesh.*`-API |
| Lua-Editor | RichTextFX | Highlighting, Zeilennummern, Vervollständigung und Debugger |
| Terminalmodus | Lanterna | Textoberfläche ohne JavaFX |
| MQTT-Bridge | Eclipse Paho MQTT | Lokaler Proxy für `proxy_to_client` |
| TCP | `java.net.Socket` | Meshtastic-TCP-API, MeshCore KISS oder Companion-Verbindung ohne KISS-Wrapper |
| Serial | Native JNA-Backends + jSerialComm-Erkennung | Nativer COM-/tty-Zugriff |
| BLE | CoreBluetooth / WinRT / BlueZ über JNA | BLE-Gerätesuche, GATT und Pairing |
| Native Integrationen | JNA + OS-Integrationen | Mica, Vibrancy, Tray-/Statusleisten-Integration und Plattformschichten |
| Build | Gradle 9.4.1 + Protobuf + CMake + jpackage | Java-Code, native Bibliotheken und Installer-Builds |

---

## Protokollarchitektur

Dieser Abschnitt richtet sich an Entwickler. Für die Nutzung der Anwendung genügt es, Verbindungstyp und Protokoll im Profil auszuwählen.

Verbindungen in MeshApp sind in zwei Ebenen aufgeteilt:

- **Transport** öffnet die Verbindung, schreibt Bytes und gibt eingehende Daten an die Protokollebene weiter. Gemeinsamer Vertrag: `TransportConnection`; Factory: `TransportConnectionFactory`.
- **Protokollumgebung** verarbeitet Frame-Format, Paketparser, initialen Austausch, Protokollzustand und Dienste für ein bestimmtes Protokoll. Gemeinsame Verträge: `CommunicationProtocol`, `ProtocolRuntime`, `ProtocolRuntimeContext` und `ProtocolRegistry`.

Registrierte Protokolle:

| ProtocolType | Umgebung | Zweck |
|--------------|-------------|---------|
| `MESHTASTIC` | `MeshtasticProtocolRuntime` | `ProtocolHandler`, `DeviceState`, Einstellungsaustausch, eingehende Mesh-Pakete, MQTT-Proxy |
| `MESHCORE_KISS` | `MeshCoreKissProtocolRuntime` | Initialer KISS-`SetHardware`-Austausch, Gerätename, Version, Kennung, LoRa, Akku und Statistik |
| `MESHCORE_COMPANION` | `MeshCoreCompanionProtocolRuntime` | Companion-`APP_START`, lokale Geräteinformationen, Akku, Kontakte, Kanäle, Chats und Direktnachrichten |

Die Umgebung wird aus dem gespeicherten `ProtocolType` ausgewählt. TCP/Serial erhält das passende `FrameFormat`; BLE verbindet sich mit dem GATT-Profil des ausgewählten Protokolls. Alte Profile ohne `protocol`-Feld werden als Meshtastic-Profile behandelt.

So fügen Sie ein neues Protokoll hinzu:

1. Einen Wert zu `ProtocolType` hinzufügen
2. `CommunicationProtocol<S>` und `ProtocolRuntime<S>` implementieren
3. Den Adapter in `ProtocolRegistry` registrieren
4. Oberfläche und Dienste für den neuen Protokollzustand ergänzen
5. Bei Bedarf `ConnectionEntry` und `TransportConnectionFactory` erweitern

Teile der Oberfläche verwenden weiterhin Meshtastic-kompatible Zugriffsmethoden aus `ConnectionManager` (`getDeviceState`, `getProtocolHandler`, `getConfigFuture`). Neue Protokolle sollten ihren Zustand über die Abstraktion der Protokollumgebung oder typisierte Zugriffsmethoden beziehen.

---

## Projektstruktur

```text
meshapp/
|-- src/main/java/com/meshtastic/client/
|   |-- MeshAppLauncher.java      # Einstiegspunkt: JavaFX oder Terminalmodus
|   |-- MeshApp.java              # JavaFX-Anwendung
|   |-- connection/               # TransportConnection und TCP-/Serial-/BLE-Transport
|   |   |-- ble/                  # BLE-Transport und Plattformimplementierungen
|   |   \-- serial/               # Native Serial-I/O für Windows/macOS/Linux
|   |-- lua/                      # Lua-Umgebung, isolierte API, Skript-Store, Import/Export
|   |-- protocol/                 # Gemeinsame Protokollumgebungs-APIs und Registry
|   |   |-- meshcore/             # MeshCore-KISS- und Companion-Adapter/Umgebungen
|   |   \-- meshtastic/           # Meshtastic-Adapter und -Umgebung
|   |-- terminal/                 # Textoberfläche auf Lanterna
|   |-- model/                    # Datenmodelle und Laufzeitzustand
|   |-- service/                  # Datenspeicher, Gerätesuche, Wiederverbindung, Einstellungsaustausch
|   |-- forms/                    # Hauptbildschirme der Anwendung
|   |-- components/               # Wiederverwendbare Oberflächenkomponenten
|   |   \-- map/                  # OpenStreetMap-Kartenkachelkomponenten
|   |-- notification/             # Systembenachrichtigungen
|   |-- platform/                 # OS-Schnittstelle und Systemintegrationen
|   |-- system/                   # Anwendungsframework: FormManager, RootPane
|   |-- tray/                     # Tray-/Statusleisten-Element
|   \-- themes/                   # Theme-Verwaltung
|-- native/
|   |-- windows-ble/              # WinRT-BLE-DLL
|   |-- linux-ble/                # BlueZ-BLE-Shared-Library
|   |-- macos-serial/             # macOS-Serial-Hilfsbibliothek
|   \-- macos-tray/               # Native macOS-Tray-/Statusleisten-Integration
|-- src/main/proto/meshtastic/    # Meshtastic-Protobuf-Schemas
|-- src/main/resources/           # CSS, Schriftarten, Symbole, Logos
\-- build.gradle                  # Build-Einstellungen
```

---

## Installer bauen

MeshApp wird mit `jpackage` gebaut. Für Linux werden zusätzlich AppImage und Flatpak unterstützt.

| Plattform | Format | Befehl |
|----------|--------|---------|
| Windows | `.msi` | `./gradlew jpackage` |
| macOS | `.dmg` | `./gradlew jpackage` |
| Linux | `.deb` | `./gradlew jpackage` |
| Linux | `.AppImage` | `./gradlew appImage -Pappimagetool=/path/to/appimagetool.AppImage` |
| Linux | `.flatpak` | `./gradlew flatpak` |

`AppImage` benötigt `appimagetool`: entweder in `PATH` oder über `-Pappimagetool=...` / `APPIMAGETOOL=...`. Wenn die `.AppImage`-Version von `appimagetool` selbst verwendet wird, kann `APPIMAGE_EXTRACT_AND_RUN=1` nötig sein.

`Flatpak` benötigt `flatpak`, `flatpak-builder`, Runtime und SDK. Standardmäßig werden `org.freedesktop.Platform//25.08` und `org.freedesktop.Sdk//25.08` verwendet.

```bash
flatpak --user remote-add --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
flatpak --user install -y flathub org.freedesktop.Platform//25.08 org.freedesktop.Sdk//25.08
./gradlew flatpak
```

Die Runtime kann mit `-PflatpakRuntime=...`, `-PflatpakRuntimeVersion=...`, `-PflatpakSdk=...` und `-PflatpakBranch=...` überschrieben werden.

Die Veröffentlichung für Flathub verwendet `app.privatepractice.meshapp.yml`. Nach Änderungen an Gradle-Abhängigkeiten muss die Maven-Quellenliste für Offline-Builds neu erstellt werden:

```bash
scripts/update-flatpak-sources.sh
scripts/update-flatpak-sources.sh aarch64
```

`flatpak-sources-x86_64.json`, `flatpak-sources-aarch64.json` und `flatpak-sources-foojay.json` müssen in git committed werden. Das Verzeichnis `offline-repository/` ist nur ein lokaler Cache.

Lokale Prüfung mit Flathub Builder:

```bash
flatpak install -y flathub org.flatpak.Builder org.freedesktop.Sdk.Extension.openjdk25//25.08
flatpak run --command=flathub-build org.flatpak.Builder --install app.privatepractice.meshapp.yml
```

Für `jpackage` kann das JDK der gebündelten Runtime explizit gesetzt werden: `-PpackagingJavaHome=/path/to/jdk` oder `PACKAGING_JAVA_HOME=/path/to/jdk`. Unter macOS prüft der Build die `.app` mit `otool -L` und schlägt fehl, wenn externe Abhängigkeiten wie `/opt/homebrew/...` oder `/usr/local/...` im Bundle verbleiben.

Während `processResources` baut Gradle native Komponenten:

- Windows: `meshapp-ble.dll` für BLE über WinRT
- Linux: `libmeshapp-ble.so` für BLE über BlueZ
- macOS: `libmeshapp-serial.dylib` für serielle Modem-Steuerleitungen
- macOS: `libmeshapp-tray.dylib` für Tray-/Statusleisten-Element

### Signierung und Notarisierung unter macOS

Standardmäßig versieht `./gradlew jpackage` unter macOS die `.app` mit einer Ad-hoc-Signatur. Das reicht für lokale Tests, aber eine aus dem Browser heruntergeladene `.dmg` kann die Gatekeeper-Meldung **"The application is damaged and can't be opened."** erhalten.

Release-Builds benötigen Zugangsdaten für die `Developer ID`-Signierung:

- `MAC_SIGNING_KEY_USER_NAME` oder `-PmacSigningKeyUserName=...`
- `MAC_SIGNING_KEYCHAIN` oder `-PmacSigningKeychain=...`
- `MAC_PACKAGE_SIGNING_PREFIX` oder `-PmacPackageSigningPrefix=...`, Standardwert ist `com.meshtastic`

Für einen Gitea-Runner im Daemon-Modus ist es besser, das Zertifikat aus Secrets in einen temporären Keychain zu importieren:

- `MAC_SIGNING_CERTIFICATE_P12`
- `MAC_SIGNING_CERTIFICATE_PASSWORD`
- `MAC_SIGNING_KEYCHAIN_PASSWORD`

Herunterladbare Builds benötigen `Developer ID Application`. `Apple Development` eignet sich für Entwicklung, aber nicht für Release-DMG-Builds.

Notarisierung kann auf eine der folgenden Arten aktiviert werden:

- `MAC_NOTARY_KEYCHAIN_PROFILE` oder `-PmacNotaryKeychainProfile=...`
- `MAC_NOTARY_APPLE_ID` + `MAC_NOTARY_TEAM_ID` + `MAC_NOTARY_PASSWORD`
- `MAC_NOTARY_KEY_FILE` + `MAC_NOTARY_KEY_ID` + `MAC_NOTARY_ISSUER`
- In CI kann `MAC_NOTARY_KEY_FILE_BASE64` statt `MAC_NOTARY_KEY_FILE` übergeben werden

Danach baut `./gradlew jpackage` signierte `.app` und `.dmg` und führt anschließend `notarytool submit --wait` und `stapler` aus.

Wenn der Gitea-Runner kein `Developer ID Application` hat, baut der Workflow das macOS-Artefakt weiterhin unter demselben Namen, überspringt aber `spctl`- und Notarisierungsprüfungen.

### Installation unter macOS

Wenn der Build ohne `Developer ID` und Notarisierung erstellt wurde, kann macOS **"from an unidentified developer"** oder **"The application is damaged and can't be opened."** anzeigen. Das ist bei einem lokalen Ad-hoc-Build erwartbar.

Über Finder:

1. Öffnen Sie Programme oder das Verzeichnis, in dem MeshApp installiert ist.
2. Klicken Sie mit der rechten Maustaste oder mit Control-Klick auf MeshApp.
3. Wählen Sie **Öffnen** und bestätigen Sie den Start. Das muss nur einmal gemacht werden.

Über das Terminal:

```bash
xattr -cr /Applications/MeshApp.app
```

---

## Lizenz

Veröffentlicht unter der Lizenz [AGPL-3.0](LICENSE).

---

<p align="center">
  Erstellt von Konstantin A. Smirnov
  <br>
  <a href="https://t.me/coVox">
    <img src="https://img.shields.io/badge/Telegram-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white" alt="Telegram">
  </a>
</p>
