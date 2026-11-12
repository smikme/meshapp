# MeshApp über die Kommandozeile starten

**Sprache:** [Русский](command-line.ru.md) | [English](command-line.md) | Deutsch

Ohne weitere Optionen öffnet MeshApp das normale Anwendungsfenster. Mit `--terminal` starten Sie die Terminaloberfläche (TUI). Mit `--rpc-server` starten Sie einen Server, auf den Sie von einem anderen Computer mit MeshApp zugreifen können.

## Installierte Version starten

Öffnen Sie ein Terminal und wählen Sie den Befehl für Ihre Installation:

| Installation | TUI starten |
| --- | --- |
| Linux, `.deb`-Paket | `/opt/meshapp/bin/MeshApp --terminal` |
| Linux, AppImage | `./MeshApp-v2.4.0-linux-x86_64.AppImage --terminal` |
| Linux, Flatpak | `flatpak run app.privatepractice.meshapp --terminal` |
| macOS | `/Applications/MeshApp.app/Contents/MacOS/MeshApp --terminal` |

Verwenden Sie beim AppImage den Namen der heruntergeladenen Datei. Falls die Datei noch nicht ausführbar ist, führen Sie zuerst `chmod +x Dateiname.AppImage` aus.

Für den RPC-Server ersetzen Sie `--terminal` durch `--rpc-server`. Nach einer Installation per `.deb` lautet der Befehl zum Beispiel:

```bash
/opt/meshapp/bin/MeshApp --rpc-server --print-rpc-key
```

Mit `--print-rpc-key` wird der Zugangsschlüssel angezeigt, den Sie für die Verbindung benötigen.

### Windows

Öffnen Sie PowerShell im Installationsordner von MeshApp. Mit `.\MeshApp.exe` starten Sie die Desktop-Anwendung. Den RPC-Server starten Sie so:

```powershell
.\MeshAppRpcServer.exe --print-rpc-key
```

Diese Datei startet direkt den Server. Die zusätzliche Option `--rpc-server` ist daher nicht nötig.

Verwenden Sie für die TUI `runTerminal` oder `MeshApp.bat`, wie im nächsten Abschnitt beschrieben. Die Desktop-Anwendung `MeshApp.exe` gibt keinen Text in der Konsole aus, aus der sie gestartet wurde.

## Aus dem Quellcode starten

Installieren Sie die [für den Build benötigten Werkzeuge](../README.de.md#voraussetzungen) und öffnen Sie ein Terminal im Projektordner.

```bash
# Desktop-Anwendung
./gradlew run

# TUI
./gradlew --console=plain runTerminal

# RPC-Server
./gradlew --console=plain runRpcServer
```

In PowerShell verwenden Sie `.\gradlew.bat` statt `./gradlew`.

Optionen für MeshApp übergeben Sie mit `--args`. Dieser Befehl startet beispielsweise die TUI und verbindet sie über TCP mit einem Gerät:

```bash
./gradlew --console=plain runTerminal --args="--host 192.168.1.10"
```

`runTerminal` fügt `--terminal` selbst hinzu, `runRpcServer` entsprechend `--rpc-server`. Die Gradle-Option `--console=plain` verhindert, dass die laufend aktualisierte Fortschrittsanzeige die Terminaloberfläche stört.

### Ohne Gradle starten

Mit `./gradlew installDist` erstellen Sie eine ausführbare Version im Ordner `build/install/MeshApp`. Anschließend können Sie die Anwendung direkt starten:

```bash
./build/install/MeshApp/bin/MeshApp --terminal
./build/install/MeshApp/bin/MeshApp --rpc-server
```

Unter Windows erstellen Sie den Build mit `.\gradlew.bat installDist` und starten danach `MeshApp.bat`:

```powershell
.\build\install\MeshApp\bin\MeshApp.bat --terminal
.\build\install\MeshApp\bin\MeshApp.bat --rpc-server
```

Für diese Version muss Java 25 auf dem Computer installiert sein.

## TUI bedienen

In den folgenden Beispielen steht `MeshApp` als Kurzform für den Startbefehl Ihrer Installation. Ersetzen Sie den Namen durch den passenden Befehl aus den Abschnitten oben.

Nach dem Start mit `--terminal` sehen Sie Ihre gespeicherten Verbindungen. Wählen Sie mit den Pfeiltasten eine Verbindung und drücken Sie `c`, um sie herzustellen. Sie können das Gerät auch direkt beim Start angeben:

```bash
# TCP
MeshApp --terminal --host 192.168.1.10

# USB
MeshApp --terminal --serial /dev/ttyUSB0 --baud 115200

# Bluetooth mit einem MeshCore-Companion-Gerät
MeshApp --terminal --ble AA:BB:CC:DD:EE:FF --protocol meshcore-companion
```

Wählen Sie eine Verbindungsart: `--host`, `--serial` oder `--ble`. MeshApp legt dafür ein vorübergehendes Profil an und entfernt es beim normalen Beenden. USB-Anschlüsse heißen unter Windows meist `COM3` oder ähnlich, unter macOS etwa `/dev/cu.usbmodem…`. Für BLE benötigen Sie die Geräteadresse oder Kennung, die Ihr Betriebssystem verwendet.

| Option | Bedeutung |
| --- | --- |
| `--host HOST` | Geräteadresse für eine TCP-Verbindung. |
| `--port PORT` | TCP-Port des Geräts. Standard: `4403`. |
| `--serial PORT` | Serieller Anschluss, auch für USB-Verbindungen. |
| `--baud RATE` | Baudrate des seriellen Anschlusses. Standard: `115200`. |
| `--ble ADDRESS` | Geräteadresse für eine BLE-Verbindung. |
| `--protocol TYPE` | `meshtastic` (Standard), `meshcore-kiss` oder `meshcore-companion`. |
| `--name NAME` | Name des vorübergehenden Profils. Standard: `Terminal TCP`, `Terminal Serial` oder `Terminal BLE`. |
| `--help`, `-h` | Hilfe anzeigen und beenden. |

Mit `Tab` wechseln Sie zwischen den Bereichen, mit `Bild auf` und `Bild ab` blättern Sie im Chatverlauf. Wenn das Nachrichtenfeld nicht ausgewählt ist, öffnet `?` die Hilfe, `d` trennt die Verbindung und `q` beendet die Anwendung. Mit `/` können Sie dort einen Befehl eingeben, etwa `/connect 1`, `/channel 0`, `/dm !12345678` oder `/quit`.

## RPC-Server starten

Öffnen Sie zunächst die Desktop-Anwendung, richten Sie eine Verbindung zum Funkgerät ein und aktivieren Sie dafür die automatische Verbindung. Prüfen Sie, ob die Verbindung funktioniert, und beenden Sie die Anwendung anschließend vollständig.

Starten Sie den Server unter demselben Benutzerkonto:

```bash
MeshApp --rpc-server --print-rpc-key
```

Der Server lädt die gespeicherten Einstellungen und verbindet alle Profile, bei denen die automatische Verbindung eingeschaltet ist. Gibt es keine solchen Profile, läuft der Server ohne Verbindung zu einem Funkgerät. Die Optionen `--host`, `--serial` und `--ble` stehen im Servermodus nicht zur Verfügung.

Falls noch kein Zugangsschlüssel festgelegt wurde, erzeugt MeshApp einen, speichert ihn und zeigt ihn im Terminal an. Bei späteren Starts können Sie ihn mit `--print-rpc-key` erneut anzeigen. Kopieren Sie den vollständigen Schlüssel einschließlich `mra1_`; ein selbst gewähltes Passwort funktioniert hier nicht.

### Von einem anderen Computer verbinden

Standardmäßig ist der Server nur auf demselben Computer unter `127.0.0.1:44030` erreichbar. Für Verbindungen über das Netzwerk starten Sie ihn so:

```bash
MeshApp --rpc-server --rpc-bind 0.0.0.0 --rpc-port 44030 --print-rpc-key
```

Fügen Sie in MeshApp auf dem anderen Computer eine **Remote RPC**-Verbindung hinzu und wählen Sie **Direct**. Tragen Sie die IP-Adresse des Servercomputers, Port `44030` und den Zugangsschlüssel ein. Verwenden Sie dort die tatsächliche IP-Adresse, etwa `192.168.1.20`. Die Adresse `0.0.0.0` gehört nur in den Startbefehl des Servers. Falls keine Verbindung zustande kommt, prüfen Sie, ob die Firewall den Port freigibt.

Mit `Strg+C` im Terminal des Servers beenden Sie ihn.

### Serveroptionen

| Option | Bedeutung |
| --- | --- |
| `--rpc-bind ADDRESS`, `--bind ADDRESS` | Adresse, auf der Verbindungen angenommen werden. Ohne Angabe gilt die gespeicherte Adresse, sonst `127.0.0.1`. |
| `--rpc-port PORT`, `--port PORT` | Port zwischen `1` und `65535`. Ohne Angabe gilt der gespeicherte Port, sonst `44030`. |
| `--rpc-key KEY`, `--key KEY` | Diesen Zugangsschlüssel statt des gespeicherten verwenden. |
| `--rpc-router [SERVER]`, `--rpc-router-server [SERVER]` | Verbindung zu einem External RPC Router herstellen. Ohne Adresse wird die gespeicherte verwendet. |
| `--no-rpc-router` | Die Router-Verbindung für diesen Start ausschalten. |
| `--print-rpc-key` | Zugangsschlüssel im Terminal anzeigen. Neu erzeugte Schlüssel werden automatisch angezeigt. |
| `--no-autoconnect` | Beim Start keine automatischen Verbindungen zu Geräten herstellen. |
| `--help`, `-h` | Hilfe anzeigen und beenden. |

Adresse, Port und Schlüssel lassen sich auch über die Umgebungsvariablen `MESHAPP_RPC_BIND`, `MESHAPP_RPC_PORT` und `MESHAPP_RPC_KEY` festlegen:

```bash
MESHAPP_RPC_BIND=127.0.0.1 MESHAPP_RPC_PORT=44031 MeshApp --rpc-server
```

MeshApp prüft zuerst die Optionen in der Kommandozeile, dann die Umgebungsvariablen und zuletzt die gespeicherten Einstellungen. Ist kein Wert angegeben, verwendet es den Standardwert oder erzeugt einen Schlüssel.

Optionen und Umgebungsvariablen ändern die gespeicherten Einstellungen nicht. Ein von MeshApp neu erzeugter Schlüssel wird jedoch für spätere Starts gespeichert. `--rpc-server` startet den Server auch dann, wenn er in den Anwendungseinstellungen ausgeschaltet ist.

### Über einen Router verbinden

Wenn keine direkte Verbindung möglich ist, können Sie einen External RPC Router verwenden:

```bash
MeshApp --rpc-server --rpc-router --print-rpc-key
```

MeshApp verbindet sich mit der gespeicherten Router-Adresse. Solange Sie diese nicht geändert haben, lautet sie `wss://cloud.meshapp.privatepractice.app`. Einen eigenen Router geben Sie so an:

```bash
MeshApp --rpc-server --rpc-router wss://router.example.org/rpc --print-rpc-key
```

Wählen Sie auf dem Client **Router** und tragen Sie dieselbe Adresse und denselben Schlüssel ein. Der Router muss bereits laufen; der Befehl stellt nur die Verbindung zu ihm her. Direkte TCP-Verbindungen sind weiterhin möglich.

Ohne `--rpc-router` oder `--no-rpc-router` gilt die Router-Einstellung aus der Anwendung. Methoden und Nachrichtenformate sind in der [RPC-Dokumentation](rpc-protocol.de.md) beschrieben.

## Hilfe und Fehlerbehebung

Für die TUI und den Server gibt es jeweils eine eigene Hilfe:

```bash
MeshApp --terminal --help
MeshApp --rpc-server --help
```

Mit Gradle verwenden Sie `./gradlew runTerminal --args="--help"` oder `./gradlew runRpcServer --args="--help"`. `MeshApp --help` allein öffnet die Desktop-Anwendung.

Trennen Sie Optionen und Werte durch ein Leerzeichen: `--rpc-port 44030`. Die Schreibweise mit `=` wird nicht unterstützt. Namen mit Leerzeichen gehören in Anführungszeichen: `--name "Home radio"`. Bei Gradle lautet das beispielsweise `--args="--host 192.168.1.10 --name 'Home radio'"`.

| Problem | Was hilft? |
| --- | --- |
| `Unknown terminal option` oder `Unknown RPC server option` | Schreibweise prüfen und nachsehen, ob die Option im gewählten Modus verfügbar ist. |
| `Address already in use` | Ein anderes Programm belegt den Port. Beenden Sie es oder wählen Sie mit `--rpc-port` einen anderen Port. |
| Die Datenbank lässt sich nicht öffnen | Beenden Sie andere MeshApp-Instanzen, die unter demselben Benutzerkonto laufen. |
| Kein Zugriff auf den USB-Anschluss | Siehe [USB-Anleitung für Linux](../README.de.md#linux-zugriff-auf-usb-serial). |

Alle drei Modi verwenden dieselbe Datenbank in `~/.meshapp`. Sie kann nur von einem Prozess gleichzeitig geöffnet werden. Für Server und Client auf demselben Computer benötigen Sie getrennte Benutzerkonten.

Mit `--no-single-instance` oder dem gleichbedeutenden `--allow-multiple-instances` lässt sich ein weiteres Anwendungsfenster öffnen. Dasselbe bewirkt die Java-Einstellung `-Dmeshapp.singleInstance.disabled=true`. Eine eigene Datenbank wird dadurch nicht angelegt.

Die TUI und der RPC-Server liefern folgende Exit-Codes: `0` bei normalem Ende oder Anzeige der Hilfe, `1` bei einem Startfehler und `2` bei ungültigen Optionen.
