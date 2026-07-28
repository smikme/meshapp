<p align="center">
  <img src="docs/logo/MeshApp.png" width="128" alt="MeshApp Logo"/>
</p>

<h1 align="center">MeshApp</h1>

<p align="center">
  Desktop client for
  <a href="https://meshtastic.org">Meshtastic</a> and MeshCore networks
  <br/>
  <b>Java 25 &nbsp;·&nbsp; JavaFX &nbsp;·&nbsp; Protobuf &nbsp;·&nbsp; LoRa</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-25-orange?logo=openjdk" alt="Java 25"/>
  <img src="https://img.shields.io/badge/JavaFX-25.0.3-blue?logo=java" alt="JavaFX"/>
  <img src="https://img.shields.io/badge/Platform-Win%20%7C%20macOS%20%7C%20Linux-brightgreen" alt="Platform"/>
  <img src="https://img.shields.io/badge/License-AGPL--3.0-blue" alt="License"/>
  <a href="https://t.me/+SRaOd1gftoo5MWRi">
    <img src="https://img.shields.io/badge/Telegram-@MeshAppClient-blue?logo=telegram" alt="Telegram">
  </a>
</p>

<p align="center">
  Main development platform:
  <a href="https://git.privatepractice.app/covox/meshapp">git.privatepractice.app/covox/meshapp</a>
</p>

<p align="center">
  <a href="#installation">Installation</a>
</p>

<div align="right">

<strong>English</strong> | <a href="README.ru.md">Русский</a> | <a href="README.de.md">Deutsch</a>

</div>

---

## About

MeshApp is a desktop application for Meshtastic and MeshCore devices. It connects to a device over the network, USB, or BLE.

The application can exchange messages, show network nodes on a map, display telemetry, change device settings, inspect LoRa packets, and run Lua scripts.

New connection profiles use Meshtastic by default. For MeshCore, select the operating mode when creating the profile:

- `MeshCore KISS` - for TCP and Serial / USB
- `MeshCore Companion` - for BLE, TCP, and Serial / USB

The connection type and protocol are selected separately. For example, you can connect over TCP to a Meshtastic device, over USB to a MeshCore KISS modem, or over BLE to a device with MeshCore Companion.

![MeshApp architecture](docs/meshapp-architecture.jpg)

---

## Installation

Ready-made packages are published on the [Gitea releases page](https://git.privatepractice.app/covox/meshapp/releases).

- macOS: download the `.dmg`, open it, and move MeshApp to Applications.
- Windows: download and run the `.msi` installer.
- Debian / Ubuntu: download the `.deb` package and install it with `apt` or your package manager.
- Linux AppImage: download the `.AppImage`, make it executable, and run it.

Flatpak users can install MeshApp through the published Flatpak ref:

```bash
flatpak install --user https://flatpak.privatepractice.app/app.privatepractice.meshapp.flatpakref
flatpak run app.privatepractice.meshapp
```

If the `meshapp` repository was previously added with the old direct `/repo/` command and Flatpak reports `public key not found`, remove the old remote and add it again:

```bash
flatpak remote-delete --user --force meshapp
flatpak install --user https://flatpak.privatepractice.app/app.privatepractice.meshapp.flatpakref
```

To update the Flatpak installation:

```bash
flatpak update app.privatepractice.meshapp
```

---

## What the App Can Do

MeshApp includes:

- Chats in network channels and direct conversations.
- A network node list with search, filters, favorite devices, and ignored devices.
- A map with online and offline tiles, network nodes, measurements, and saved routes.
- Device and network telemetry: current values, history, charts, and table view.
- Meshtastic device settings: configuration, channels, and editors for complex fields.
- Connections over TCP, USB/Serial, and BLE; Meshtastic, MeshCore KISS, and MeshCore Companion.
- Application logs and LoRa packet viewing with filters, search, and export.
- Lua scripts: editor, debugger, autostart, bots, data storage, and script store.
- A local database for messages, telemetry, network nodes, routes, scripts, and the packet log.
- Terminal mode without JavaFX.

---

## Chats

<p align="center">
  <img src="docs/screenshots/chat-b.jpg" width="49%" alt="Chat - dark theme"/>
  <img src="docs/screenshots/chat-w.jpg" width="49%" alt="Chat - light theme"/>
</p>

<p align="center">
  <img src="docs/screenshots/chat-node-info-b.jpg" width="49%" alt="Node information in chat - dark theme"/>
  <img src="docs/screenshots/chat-node-info-w.jpg" width="49%" alt="Node information in chat - light theme"/>
</p>

Chats include network channels and direct conversations. Messages are stored locally; replies, reactions, delivery status, and unread counters are supported.

The input line can run `@tracebot` and `@infobot` commands. They check the route to the selected node and request information about it; while typing, the app suggests node names and addresses.

Channels can be created and edited: name, access key, position sharing settings, and position precision. Notifications can be enabled or disabled separately for each channel and direct conversation.

---

## Nodes

<p align="center">
  <img src="docs/screenshots/nodes-b.jpg" width="49%" alt="Nodes - dark theme"/>
  <img src="docs/screenshots/nodes-w.jpg" width="49%" alt="Nodes - light theme"/>
</p>

The nodes screen shows devices currently visible in the network and devices already saved in the local database. Search is available by name, short name, identifier, and numeric address. Filters are available by last heard time, distance, signal quality, hop count, channel, favorite nodes, ignored nodes, direct nodes, and unavailable nodes.

The node card shows role, hardware model, coordinates, firmware version, signal level, and a telemetry chart. From the card you can open a direct chat, check the route to the node, refresh information about it, or remove the node from the local list.

Route check history is stored separately for each node. A saved route can be opened on the map.

---

## Map

<p align="center">
  <img src="docs/screenshots/map-b.jpg" width="49%" alt="Map - dark theme"/>
  <img src="docs/screenshots/map-w.jpg" width="49%" alt="Map - light theme"/>
</p>

The map shows nodes with coordinates and saved routes. Interactive OpenStreetMap tiles, an HTTP-compliant local cache, and a pre-existing offline tile directory in `z/x/y.png|jpg|jpeg` format are supported.

The map includes search, filters, jump to your own device, overview of all nodes with coordinates, night mode, distance measurement, and rectangular area selection. MeshApp requests only tiles visible in the current interactive viewport and does not bulk-download OpenStreetMap areas. For offline work, connect a tile directory obtained separately from a source whose terms permit offline use.

An alternative interactive provider can be selected at startup with the JVM properties `meshapp.map.tileSource.url`, `meshapp.map.tileSource.id`, `meshapp.map.tileSource.attribution`, `meshapp.map.tileSource.minZoom`, and `meshapp.map.tileSource.maxZoom`. The URL template must contain `{z}`, `{x}`, and `{y}`. Runtime configuration never enables bulk downloading.

---

## Telemetry

<p align="center">
  <img src="docs/screenshots/telemetry-b.jpg" width="49%" alt="Telemetry - dark theme"/>
  <img src="docs/screenshots/telemetry-w.jpg" width="49%" alt="Telemetry - light theme"/>
</p>

Telemetry shows device and network state: battery charge, voltage, channel load, airtime used for transmission, receive quality, sent, lost, and relayed packet counts, signal level, and hop data.

Data can be viewed on charts or in a table. The period can be selected from 1 hour to the full history. For long periods, values are averaged so the charts remain readable.

---

## Connections

<p align="center">
  <img src="docs/screenshots/connections-b.jpg" width="49%" alt="Connections - dark theme"/>
  <img src="docs/screenshots/connections-w.jpg" width="49%" alt="Connections - light theme"/>
</p>

MeshApp can work with several connections in parallel. Connection profiles store the address, port, selected BLE device, USB / Serial settings, and protocol. Automatic connection on application startup can be enabled for the desired profile.

Supported connection types:

- TCP
- Serial / USB
- BLE

For Serial / USB, port discovery and DTR/RTS line settings are available. Common USB-UART chips CH340, CP210x, and FTDI are supported; Windows with Silicon Labs / CP210x has separate connection handling.

For BLE, device discovery, GATT connection, and passkey pairing are supported when required by the device or operating system.

After connecting, MeshApp performs the initial exchange with the device:

- Meshtastic: settings exchange
- MeshCore KISS: negotiation through `SetHardware`
- MeshCore Companion: exchange startup through `APP_START`

For Meshtastic firmware 2.8.0 and newer, MeshApp supports the compact initial
node database followed by background position and telemetry replay. Older
firmware keeps the legacy connection flow.

### MeshCore

MeshCore is supported in two variants:

- `MeshCore KISS` works over TCP or Serial / USB.
- `MeshCore Companion` works over BLE, TCP, or Serial / USB.

MeshCore Companion does not use the KISS format. For BLE it uses:

- service `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
- RX characteristic `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`
- TX notifications `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`

When connecting over TCP or Serial, the device must send Companion packets without an additional KISS wrapper.

Currently supported:

- KISS format (`FEND`, `FESC`, and escape sequences) for TCP and Serial
- reading device information through MeshCore `SetHardware`: name, version, identifier, LoRa parameters, TX power, battery, statistics, RSSI/SNR data, and TX status
- MeshCore Companion BLE profile with RX/TX UUIDs, subscription to TX notifications, and `APP_START`
- `FrameFormat.MESHCORE_COMPANION` for Companion packets over TCP and Serial
- information about the local device, public key, device data, battery, and storage
- contacts and channel information in the shared nodes and chat screens
- incoming and outgoing channel messages and direct messages through Companion Protocol
- viewing MeshCore information in settings without editing
- MeshCore Companion packets in LoRa Debug
- active protocol display in the connection card

Limitations:

- MeshCore KISS works only over TCP and Serial.
- MeshCore Companion over TCP/Serial works only with devices that send Companion packets without a KISS wrapper.
- MeshCore KISS is currently used for modem mode and reading device information; chats, direct messages, and the main user workflows are implemented through MeshCore Companion.
- MeshCore Companion does not include functions that belong only to Meshtastic: saving settings through Admin protobuf, reactions, route checks, and Meshtastic bot commands.

Details: [docs/meshcore-support.md](docs/meshcore-support.md).

---

## Device Settings

<p align="center">
  <img src="docs/screenshots/settings-b.jpg" width="49%" alt="Settings - dark theme"/>
  <img src="docs/screenshots/settings-w.jpg" width="49%" alt="Settings - light theme"/>
</p>

Meshtastic device settings open as a tree of sections: device, LoRa, position, power, network, Bluetooth, display, and other settings.

The interface lets you change the long and short device name, edit configuration fields, save several changes in one operation, synchronize device time with the computer, reboot the device, and shut it down.

For fields that are inconvenient to edit manually, separate editors are available: IPv4 addresses, node identifiers, hexadecimal values, bitmasks, and value lists such as `admin_key` and `ignore_incoming`.

Configuration can be exported and imported:

- `.mcf` - full configuration copy
- `.mtp` - template without personal and secret data

Firmware 2.8 settings are enabled only when the target node reports version
2.8.0 or newer. This includes legal region/preset combinations, packet
signature policy, Mesh Beacon, and the 24-byte UTF-8 long-name limit.

The interface can also clear the local H2 database: messages, reactions, telemetry, node cache, and packet log.

---

## Logs and LoRa Packets

<p align="center">
  <img src="docs/screenshots/logs-b.jpg" width="49%" alt="Logs - dark theme"/>
  <img src="docs/screenshots/logs-w.jpg" width="49%" alt="Logs - light theme"/>
</p>

<p align="center">
  <img src="docs/screenshots/loradebug-b.jpg" width="49%" alt="LoRa Debug - dark theme"/>
  <img src="docs/screenshots/loradebug-w.jpg" width="49%" alt="LoRa Debug - light theme"/>
</p>

The built-in log viewer supports colored log levels, pausing autoscroll, copy, clear, and export to `.log`.

LoRa Debug shows incoming, outgoing, and internal `MeshPacket` entries. Packets can be filtered by direction, type, time, nodes, and content. For the selected packet, HEX and ASCII views, protobuf tree, and field highlighting are available.

The selected packet can be copied or saved. The filtered set can be exported to JSON or CSV.

If the application crashes or a problem needs to be sent to the developers, a report can be sent after the crash or manually from the Help window.

---

## Lua Scripts and MeshApp IDE

<p align="center">
  <img src="docs/screenshots/luascripts-b.jpg" width="49%" alt="Lua scripts - dark theme"/>
  <img src="docs/screenshots/luascripts-w.jpg" width="49%" alt="Lua scripts - light theme"/>
</p>

<p align="center">
  <img src="docs/screenshots/ide-b.jpg" width="49%" alt="Lua editor - dark theme"/>
  <img src="docs/screenshots/ide-w.jpg" width="49%" alt="Lua editor - light theme"/>
</p>

<p align="center">
  <img src="docs/screenshots/shop-b.jpg" width="49%" alt="Script store - dark theme"/>
  <img src="docs/screenshots/shop-w.jpg" width="49%" alt="Script store - light theme"/>
</p>

MeshApp IDE is a built-in environment for Lua scripts and bots. Scripts are created and run directly from the application, stored in the local database, and can be exported to `.meshapp-script.json`.

The IDE includes:

- script cards with name, icon, author, version, type, and status
- script settings: description, autostart, bot type, binding to a node or automation name
- Lua editor with highlighting, line numbers, auto indentation, and `mesh.*` completion
- syntax checking and runtime error output
- debugger with breakpoints, step-by-step execution, and local/global variable views
- isolated data storage for each script
- script store with install, update, and local copy removal

Scripts run in an isolated LuaJ environment. Dangerous APIs are disabled: `io`, `os`, `debug`, `package`, `require`, `dofile`, `loadfile`, `luajava`.

Entry points:

- `on_message(msg)` - reaction to a new message
- `on_reaction(reaction)` - message reaction handling
- `on_command(command)` - command handling
- `on_node_selected(event)`, `on_traceroute(event)`, `on_node_info(event)`, `on_admin(event)` - asynchronous event handlers

API documentation: [docs/lua-api.md](docs/lua-api.md).

---

## Interface and Local Data

<p align="center">
  <img src="docs/screenshots/info-b.jpg" width="49%" alt="Help and information - dark theme"/>
  <img src="docs/screenshots/info-w.jpg" width="49%" alt="Help and information - light theme"/>
</p>

The interface includes dark and light AtlantaFX Cupertino themes, a sidebar, a system tray/status item, toast notifications, and quick switches for theme and notifications. Windows 11 uses Mica, and macOS uses vibrancy. Window size and position, as well as splitter positions, are saved between sessions.

System notifications are shown for new messages if the corresponding chat is not open. Update checks on startup can be enabled or disabled in settings.

Messages, reactions, unread chats, favorite and ignored nodes, telemetry, scripts, script data, route check history, and the LoRa packet log are stored locally.

Importing the public OneMesh cache and a local MQTT bridge for `MQTT proxy_to_client` are also supported. Broker parameters are taken from the device MQTT configuration.

For use without JavaFX, a terminal mode based on Lanterna is available.

---

## Quick Start

### Requirements

Building and running from source requires:

- Git
- JDK 25; Gradle can download the required toolchain automatically
- macOS: Xcode Command Line Tools (`cc`) for `libmeshapp-serial.dylib` and `libmeshapp-tray.dylib`
- Windows: CMake + MSVC Build Tools for `meshapp-ble.dll`
- Linux: CMake + C/C++ toolchain + `libsystemd-dev` / `systemd-devel` for `libmeshapp-ble.so`

Ready-made packages (`.dmg`, `.msi`, `.deb`, `.AppImage`, `.flatpak`) do not need these build dependencies.

### Running from Source

```bash
git clone https://git.privatepractice.app/covox/meshapp.git
cd meshapp

# JavaFX application
./gradlew run

# Terminal mode
./gradlew runTerminal

# Terminal mode with a temporary TCP profile
./gradlew runTerminal --args="--host 192.168.1.10 --protocol meshtastic"
```

### Connecting to a Device

1. Connect the device over USB, TCP, or BLE.
2. Open **Connections**.
3. Add a profile: **TCP**, **Serial / USB**, or **BLE**.
4. Select the protocol. New profiles use **Meshtastic** by default; for MeshCore select **MeshCore KISS** or **MeshCore Companion**.
5. For **Serial / USB**, select the port. For **BLE**, start scanning and select the device.
6. If the device or operating system requests a passkey, confirm pairing.
7. Click **Connect**.

After connecting to Meshtastic, chats, nodes, map, settings, and the other main screens are available. For MeshCore Companion, chats, nodes, direct messages, telemetry, settings, and LoRa Debug are available. For MeshCore KISS, modem information is shown.

### Linux: USB Serial Access

If the USB port is visible but the connection fails with `Permission denied`, the user does not have access to `/dev/ttyUSB*` or `/dev/ttyACM*`.

```bash
ls -l /dev/ttyUSB0
sudo usermod -aG dialout "$USER"
```

On some distributions the group is named `uucp` or `lock`; use the group shown by `ls -l`. After changing groups, log out and log back in.

The MeshApp `.deb` package installs udev rules for common USB-UART Meshtastic boards. The active local user receives a `uaccess` ACL, and ModemManager does not take over the port.

If the error looks like `Device or resource busy`, the port is already open in another process: serial monitor, CLI, or ModemManager.

---

## Debug Run and Profiling

Local JMX for VisualVM, JConsole, or JMC:

```bash
./gradlew run -PjmxDebugEnabled=true
./gradlew run -PjmxDebugEnabled=true -PjmxDebugPort=9011
```

When JMX is enabled, the application listens only on `127.0.0.1`. Connection address:
`service:jmx:rmi:///jndi/rmi://127.0.0.1:9010/jmxrmi`. For another port, replace `9010` with the `jmxDebugPort` value.

The same can be enabled through environment variables:

```bash
MESHAPP_JMX_DEBUG=true
MESHAPP_JMX_PORT=9011
```

For VisualVM, `Sampler > Memory` over a local JMX connection is usually enough. `Profiler > Memory` instruments classes; on Java 25/GraalVM/JavaFX/macOS its native agent can crash the target JVM.

If you specifically need `Profiler > Memory`, run the application this way:

```bash
./gradlew run -PvisualVmProfilerEnabled=true
./gradlew run -PvisualVmProfilerEnabled=true -PjmxDebugPort=9011
```

This mode enables JMX, disables class data sharing (`-Xshare:off`), and disables the Graal/JVMCI JIT during profiling. It can be enabled through the `MESHAPP_VISUALVM_PROFILER=true` environment variable.

If profiling a built macOS `.app`, rebuild it with the same flag:

```bash
./gradlew jpackage -PvisualVmProfilerEnabled=true
```

Software rendering for checking macOS `CVDisplayLink` problems is disabled by default. For a one-time check:

```bash
./gradlew run -PvisualVmSoftwareRenderingEnabled=true
```

If VisualVM shows `Provided Memory settings are invalid`, open memory profiler settings and replace the template in `Profile classes` with a valid filter, for example `com.meshtastic.client.**` or `**`.

---

## Technologies

| Component | Technology | Purpose |
|-----------|------------|---------|
| Interface | JavaFX 25.0.3 + AtlantaFX | Main interface |
| Meshtastic | Protobuf 4.33.4 + Meshtastic schemas | `ToRadio` / `FromRadio` and mesh packets |
| MeshCore KISS | KISS format + MeshCore `SetHardware` | Initial exchange and MeshCore KISS modem information |
| MeshCore Companion | Companion Protocol + BLE RX/TX or TCP/Serial without KISS wrapper | Initial exchange, device information, contacts, channels, chats, and packet viewing |
| Transport | `TransportConnection` | Shared contract for TCP, Serial, BLE, and future connection methods |
| Database | Embedded H2 | Messages, telemetry, scripts, routes, and logs |
| Maps | JavaFX `TileMapView` + OpenStreetMap tiles | Online and offline map, saved routes |
| Lua environment | LuaJ 3.0.1 | Isolated scripts, bots, data storage, and `mesh.*` API |
| Lua editor | RichTextFX | Highlighting, line numbers, completion, and debugger |
| Terminal mode | Lanterna | Text interface without JavaFX |
| MQTT bridge | Eclipse Paho MQTT | Local proxy for `proxy_to_client` |
| TCP | `java.net.Socket` | Meshtastic TCP API, MeshCore KISS, or Companion connection without KISS wrapper |
| Serial | Native JNA backends + jSerialComm discovery | Native COM/tty access |
| BLE | CoreBluetooth / WinRT / BlueZ through JNA | BLE device discovery, GATT, and pairing |
| Native integrations | JNA + OS integrations | Mica, vibrancy, system tray/status item, and platform layers |
| Build | Gradle 9.4.1 + Protobuf + CMake + jpackage | Java code, native library, and installer builds |

---

## Protocol Architecture

This section is for developers. To use the application, it is enough to select the connection type and protocol in the profile.

Connections in MeshApp are split into two levels:

- **Transport** opens the connection, writes bytes, and passes incoming data to the protocol level. Shared contract: `TransportConnection`; factory: `TransportConnectionFactory`.
- **Protocol environment** handles frame format, packet parsing, initial exchange, protocol state, and services for a specific protocol. Shared contracts: `CommunicationProtocol`, `ProtocolRuntime`, `ProtocolRuntimeContext`, and `ProtocolRegistry`.

Registered protocols:

| ProtocolType | Environment | Purpose |
|--------------|-------------|---------|
| `MESHTASTIC` | `MeshtasticProtocolRuntime` | `ProtocolHandler`, `DeviceState`, settings exchange, incoming mesh packets, MQTT proxy |
| `MESHCORE_KISS` | `MeshCoreKissProtocolRuntime` | Initial KISS `SetHardware` exchange, device name, version, identifier, LoRa, battery, and statistics |
| `MESHCORE_COMPANION` | `MeshCoreCompanionProtocolRuntime` | Companion `APP_START`, local device information, battery, contacts, channels, chats, and direct messages |

The environment is selected from the stored `ProtocolType`. TCP/Serial receive the matching `FrameFormat`; BLE connects to the GATT profile of the selected protocol. Old profiles without a `protocol` field are treated as Meshtastic profiles.

To add a new protocol:

1. Add a value to `ProtocolType`
2. Implement `CommunicationProtocol<S>` and `ProtocolRuntime<S>`
3. Register the adapter in `ProtocolRegistry`
4. Add interface and services for the new protocol state
5. Extend `ConnectionEntry` and `TransportConnectionFactory` if needed

Some of the interface still uses Meshtastic-compatible access methods from `ConnectionManager` (`getDeviceState`, `getProtocolHandler`, `getConfigFuture`). New protocols should get their state through the protocol environment abstraction or typed access methods.

---

## Project Structure

```text
meshapp/
|-- src/main/java/com/meshtastic/client/
|   |-- MeshAppLauncher.java      # Entry point: JavaFX or terminal mode
|   |-- MeshApp.java              # JavaFX application
|   |-- connection/               # TransportConnection and TCP/Serial/BLE transport
|   |   |-- ble/                  # BLE transport and platform implementations
|   |   \-- serial/               # Native Serial I/O for Windows/macOS/Linux
|   |-- lua/                      # Lua environment, isolated API, script store, import/export
|   |-- protocol/                 # Shared protocol environment APIs and registry
|   |   |-- meshcore/             # MeshCore KISS and Companion adapters/environments
|   |   \-- meshtastic/           # Meshtastic adapter and environment
|   |-- terminal/                 # Text interface on Lanterna
|   |-- model/                    # Data models and runtime state
|   |-- service/                  # Data storage, device discovery, reconnect, settings exchange
|   |-- forms/                    # Main application screens
|   |-- components/               # Reusable interface components
|   |   \-- map/                  # OpenStreetMap tile map components
|   |-- notification/             # System notifications
|   |-- platform/                 # OS interface and system integrations
|   |-- system/                   # Application framework: FormManager, RootPane
|   |-- tray/                     # System tray/status item
|   \-- themes/                   # Theme management
|-- native/
|   |-- windows-ble/              # WinRT BLE DLL
|   |-- linux-ble/                # BlueZ BLE shared library
|   |-- macos-serial/             # macOS Serial helper library
|   \-- macos-tray/               # Native macOS system tray/status item integration
|-- src/main/proto/meshtastic/    # Meshtastic Protobuf schemas
|-- src/main/resources/           # CSS, fonts, icons, logos
\-- build.gradle                  # Build settings
```

---

## Building Installers

MeshApp is built through `jpackage`. AppImage and Flatpak are additionally supported for Linux.

| Platform | Format | Command |
|----------|--------|---------|
| Windows | `.msi` | `./gradlew jpackage` |
| macOS | `.dmg` | `./gradlew jpackage` |
| Linux | `.deb` | `./gradlew jpackage` |
| Linux | `.AppImage` | `./gradlew appImage -Pappimagetool=/path/to/appimagetool.AppImage` |
| Linux | `.flatpak` | `./gradlew flatpak` |

`AppImage` requires `appimagetool`: either in `PATH` or through `-Pappimagetool=...` / `APPIMAGETOOL=...`. If the `.AppImage` version of `appimagetool` itself is used, `APPIMAGE_EXTRACT_AND_RUN=1` may be needed.

`Flatpak` requires `flatpak`, `flatpak-builder`, runtime, and SDK. By default, `org.freedesktop.Platform//25.08` and `org.freedesktop.Sdk//25.08` are used.

```bash
flatpak --user remote-add --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
flatpak --user install -y flathub org.freedesktop.Platform//25.08 org.freedesktop.Sdk//25.08
./gradlew flatpak
```

The runtime can be overridden with `-PflatpakRuntime=...`, `-PflatpakRuntimeVersion=...`, `-PflatpakSdk=...`, and `-PflatpakBranch=...`.

Flathub publishing uses `app.privatepractice.meshapp.yml`. After changing Gradle dependencies, rebuild the Maven source list for offline builds:

```bash
scripts/update-flatpak-sources.sh
scripts/update-flatpak-sources.sh aarch64
```

`flatpak-sources-x86_64.json`, `flatpak-sources-aarch64.json`, and `flatpak-sources-foojay.json` must be committed to git. The `offline-repository/` directory is only a local cache.

Local verification through Flathub Builder:

```bash
flatpak install -y flathub org.flatpak.Builder org.freedesktop.Sdk.Extension.openjdk25//25.08
flatpak run --command=flathub-build org.flatpak.Builder --install app.privatepractice.meshapp.yml
```

For `jpackage`, the JDK for the bundled runtime can be set explicitly: `-PpackagingJavaHome=/path/to/jdk` or `PACKAGING_JAVA_HOME=/path/to/jdk`. On macOS, the build checks the `.app` with `otool -L` and fails if external dependencies such as `/opt/homebrew/...` or `/usr/local/...` remain inside the bundle.

During `processResources`, Gradle builds native components:

- Windows: `meshapp-ble.dll` for BLE through WinRT
- Linux: `libmeshapp-ble.so` for BLE through BlueZ
- macOS: `libmeshapp-serial.dylib` for serial modem control lines
- macOS: `libmeshapp-tray.dylib` for the system tray/status item

### Signing and Notarization on macOS

By default, `./gradlew jpackage` on macOS applies an ad-hoc signature to `.app`. This is enough for local testing, but a `.dmg` downloaded from a browser may receive the Gatekeeper message **"The application is damaged and can't be opened."**

Release builds need credentials for `Developer ID` signing:

- `MAC_SIGNING_KEY_USER_NAME` or `-PmacSigningKeyUserName=...`
- `MAC_SIGNING_KEYCHAIN` or `-PmacSigningKeychain=...`
- `MAC_PACKAGE_SIGNING_PREFIX` or `-PmacPackageSigningPrefix=...`, defaults to `com.meshtastic`

For a Gitea runner in daemon mode, it is better to import the certificate from secrets into a temporary keychain:

- `MAC_SIGNING_CERTIFICATE_P12`
- `MAC_SIGNING_CERTIFICATE_PASSWORD`
- `MAC_SIGNING_KEYCHAIN_PASSWORD`

Downloadable builds need `Developer ID Application`. `Apple Development` is suitable for development, but not for release DMG builds.

Notarization can be enabled in one of these ways:

- `MAC_NOTARY_KEYCHAIN_PROFILE` or `-PmacNotaryKeychainProfile=...`
- `MAC_NOTARY_APPLE_ID` + `MAC_NOTARY_TEAM_ID` + `MAC_NOTARY_PASSWORD`
- `MAC_NOTARY_KEY_FILE` + `MAC_NOTARY_KEY_ID` + `MAC_NOTARY_ISSUER`
- In CI, `MAC_NOTARY_KEY_FILE_BASE64` can be passed instead of `MAC_NOTARY_KEY_FILE`

After that, `./gradlew jpackage` builds signed `.app` and `.dmg`, then runs `notarytool submit --wait` and `stapler`.

If the Gitea runner does not have `Developer ID Application`, the workflow still builds the macOS artifact with the same name, but skips `spctl` and notarization checks.

### Installation on macOS

If the build is made without `Developer ID` and notarization, macOS may show **"from an unidentified developer"** or **"The application is damaged and can't be opened."** This is expected for a local ad-hoc build.

Through Finder:

1. Open Applications or the directory where MeshApp is installed.
2. Right-click or Control-click MeshApp.
3. Select **Open** and confirm launch. This only needs to be done once.

Through terminal:

```bash
xattr -cr /Applications/MeshApp.app
```

---

## License

Distributed under the [AGPL-3.0](LICENSE) license.

---

<p align="center">
  Created by Konstantin A. Smirnov
  <br>
  <a href="https://t.me/coVox">
    <img src="https://img.shields.io/badge/Telegram-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white" alt="Telegram">
  </a>
</p>
