<p align="center">
  <img src="docs/logo/MeshApp.png" width="128" alt="MeshApp Logo"/>
</p>

<h1 align="center">MeshApp</h1>

<p align="center">
  Cross-platform desktop client for the
  <a href="https://meshtastic.org">Meshtastic</a> and MeshCore mesh networks
  <br/>
  <b>Java 25 &nbsp;·&nbsp; JavaFX &nbsp;·&nbsp; Protobuf &nbsp;·&nbsp; LoRa</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-25-orange?logo=openjdk" alt="Java 25"/>
  <img src="https://img.shields.io/badge/JavaFX-25.0.3-blue?logo=java" alt="JavaFX"/>
  <img src="https://img.shields.io/badge/Platform-Win%20%7C%20macOS%20%7C%20Linux-brightgreen" alt="Platform"/>
  <img src="https://img.shields.io/badge/License-GPL--3.0-blue" alt="License"/>
  <a href="https://t.me/+SRaOd1gftoo5MWRi">
  <img src="https://img.shields.io/badge/Telegram-@MeshAppClient-blue?logo=telegram" alt="Telegram">
</a>
</p>

<p align="center">
  Main development platform:
  <a href="https://git.privatepractice.app/covox/meshapp">git.privatepractice.app/covox/meshapp</a>
</p>

<div align="right">

<strong>English</strong> | <a href="README.ru.md">Русский</a>

</div>

---

## About

**MeshApp** is a full-featured cross-platform desktop client for [Meshtastic](https://meshtastic.org) and MeshCore. It works over **TCP**, **Serial / USB** and **BLE** and is designed for device management, messaging, network monitoring and radio module configuration from Windows, macOS and Linux PCs.

The codebase supports multiple communication protocols: the transport layer is separated from protocol runtimes. MeshApp currently includes **Meshtastic**, the **MeshCore KISS** runtime over TCP/Serial byte streams, and the **MeshCore Companion Protocol** runtime for BLE and raw TCP/Serial byte streams. New connections use Meshtastic by default; MeshCore is selected explicitly in the connection form.

Meshtastic is an open project that turns inexpensive LoRa modules into decentralized mesh-network nodes. Messages can travel from hundreds of meters to tens of kilometers without internet access, cellular towers or any other infrastructure.

MeshCore is a lightweight mesh protocol for LoRa and other packet-radio devices. MeshApp supports **MeshCore KISS modem** mode for TCP/Serial and **MeshCore Companion Protocol** for BLE, TCP and Serial endpoints that provide raw Companion packets.

```text
                     +------------------------------------------------------+
                     |                       MeshApp                         |
                     |                                                      |
 TCP (IP:4403) ----->|  +----------------+  +----------------+ +---------+ |
 USB Serial -------->|  |   Transport    |->| Protocol       | | UI /    | |
 BLE / GATT -------->|  | TCP/Serial/BLE |  | Runtime        | | Forms   | |
                     |  +----------------+  | Meshtastic /   | +---------+ |
                     |                      | MeshCore        |             |
                     |           |          +----------------+      |       |
                     |           v                  |               v       |
                     |   Native serial / BLE        v            H2 / Logs  |
                     |                         DeviceState / services       |
                     +------------------------------------------------------+
```

---

## What's New

- **Multi-protocol architecture**: the transport layer (`TCP`, `Serial`, `BLE`) is separated from protocol adapters; Meshtastic, MeshCore KISS and MeshCore Companion have independent runtimes.
- **Explicit protocol selection**: new connections use Meshtastic by default; MeshCore KISS and MeshCore Companion are selected in the connection profile.
- **MeshCore Companion in main screens**: Chat, Nodes, direct messages, Telemetry, Settings and LoRa Packets use a shared `DeviceState` bridge for MeshCore Companion Protocol.
- **Connection profile autostart**: selected connections can be established automatically when the application starts.
- **Maps and trace history**: saved traceroute results are available from the node tab and can be opened on the map.
- **MeshApp IDE**: Lua scripts, script store, import/export, autostart, KV storage, editor and debugger are built into the app.
- **Input commands**: `@tracebot` and `@infobot` include node autocompletion for quick `Traceroute` and `NodeInfo` requests.
- **Chat notifications**: mute/unmute per channel and DM, saved locally.
- **Crash / problem reporting**: MeshApp can offer to send a log after a crash or send a manual report from the Help window.
- **LoRa packet monitoring**: a live capture window with direction/type/date-time filters, search, HEX / ASCII preview and JSON/CSV export.
- **PC time synchronization**: set the current radio time from the PC clock and update GMT when needed.
- **Local database cleanup**: reset messages, reactions, node cache, telemetry and the LoRa packet journal from the UI.
- **Advanced configuration editor**: human-readable editors for IPv4, node IDs, hex values and bitmask fields, plus per-item editing for repeated fields.

---

## Features

### Chat and Messaging

<p align="center">
  <img src="docs/screenshots/chat-b.png" width="49%" alt="Chat - dark theme"/>
  <img src="docs/screenshots/chat-w.png" width="49%" alt="Chat - light theme"/>
</p>

- **Multi-channel chat**: send and receive messages in several mesh channels.
- **Direct messages**: private conversations with individual nodes.
- **Replies**: quote messages with context.
- **Message reactions**: quick emoji reactions with persistence and delivery tracking.
- **Delivery status**: ACK/NAK tracking for sent messages.
- **Traceroute**: visualize routes to network nodes and save successful results.
- **NodeInfo request**: request current node data on demand.
- **`@tracebot` and `@infobot` commands**: run traceroute and node-info requests directly from the input line with suggestions by name and `!nodeid`.
- **Channel management**: create secondary channels and edit name, PSK, uplink/downlink and position precision.
- **Emoji**: built-in emoji picker.
- **Unread counter**: badges for every chat.
- **Chat notifications**: enable or disable notifications per channel and DM through the bell icon and context menu.
- **History**: searchable full message history stored in the local database.
- **Local history cleanup**: delete individual messages and entire chats from the embedded database.

---

### Network Nodes

<p align="center">
  <img src="docs/screenshots/nodes-b.png" width="49%" alt="Nodes - dark theme"/>
  <img src="docs/screenshots/nodes-w.png" width="49%" alt="Nodes - light theme"/>
</p>

- **Flexible sorting and filters**: last heard time, distance, SNR, hops, channel, favorites, ignored nodes, direct nodes and offline nodes.
- **Search** by name, short name, ID or numeric address.
- **Detailed node card**: hardware, role, coordinates, firmware, SNR/RSSI and telemetry chart.
- **Quick actions**: open private chat, run traceroute, request fresh NodeInfo or remove a node from the local list.
- **Traces tab**: traceroute history for the selected node with creation date, date filter and lazy loading on scroll.
- **Open trace on map**: each saved trace can be opened in the map form through the map icon.
- **Favorite and ignored nodes**: local storage and synchronization with the device.
- **Node caching**: local database with pagination.

---

### Maps

- **OSM node map**: display current and cached nodes with coordinates.
- **Online and offline tiles**: network OSM tiles, local cache and selected `z/x/y.png|jpg|jpeg` directory.
- **Search and filters**: search nodes, filter unknown/offline/favorite/direct/ignored nodes.
- **Navigation**: jump to own node, fit all nodes with coordinates, zoom and use night map mode.
- **Measurements**: measure distance between points and select a rectangular area.
- **Area download**: download tiles for a selected area with progress, pause and cancel.
- **Trace visualization**: select recent saved traceroute results and overlay one or more routes on the map.

---

### Telemetry and Monitoring

<p align="center">
  <img src="docs/screenshots/telemetry-b.png" width="49%" alt="Telemetry - dark theme"/>
  <img src="docs/screenshots/telemetry-w.png" width="49%" alt="Telemetry - light theme"/>
</p>

- **Device dashboard**: a dedicated screen with Charts and Data tabs.
- **Real-time charts**: battery, voltage, channel utilization and Air Util TX.
- **Period filters**: from 1 hour to the full history.
- **Data aggregation**: automatic averaging for smoother curves.
- **Telemetry table**: detailed records with timestamps.
- **Advanced metrics**: Good RX, Bad RX, Dupe RX, TX, Dropped, Relayed, RSSI, SNR and hop data.
- **Lazy log loading**: long histories are loaded while scrolling.

---

### Connections

<p align="center">
  <img src="docs/screenshots/connections-b.png" width="49%" alt="Connections - dark theme"/>
  <img src="docs/screenshots/connections-w.png" width="49%" alt="Connections - light theme"/>
</p>

- **TCP, Serial / USB and BLE**: connect over the network, a COM/tty port or Bluetooth LE.
- **Separate transport and protocol layers**: a connection opens a low-level transport and starts the runtime of the protocol selected in the profile.
- **Device discovery**: serial-port discovery and BLE scanning for Meshtastic/MeshCore devices.
- **Connection profiles**: store addresses, ports and BLE devices for quick reconnects.
- **Autoconnect**: profile flag that starts the connection automatically when the app launches; disabled by default.
- **Single active connection**: the app works with one selected device at a time.
- **BLE pairing**: passkey/pairing flow when required by the device or platform.
- **Automatic configuration exchange**: device parameters are fetched automatically on connect.
- **Automatic reconnect**: retry recovery after disconnects.
- **Serial DTR/RTS setup**: select modem-line mode for USB-UART adapters.
- **Reliable Serial for USB-UART bridges**: works with CH340/CP210x/FTDI without causing unnecessary ESP32 resets; includes separate compatibility handling for Windows + Silicon Labs / CP210x.

#### MeshCore

The current MeshCore integration in MeshApp supports **MeshCore KISS modem protocol** over **Serial / USB** or **TCP**, and **MeshCore Companion Protocol** over **BLE**, **TCP** and **Serial**. New profiles use Meshtastic by default; to use MeshCore, explicitly select `MeshCore KISS` or `MeshCore Companion` in the protocol field.

MeshCore Companion does not use KISS framing. BLE uses service `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`, RX characteristic `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` and TX notifications `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`. TCP/Serial endpoints are supported when they provide raw Companion packets without a KISS wrapper.

Currently supported:

- standard KISS framing (`FEND`, `FESC`, escape sequences) for TCP/Serial byte streams
- explicit `MESHCORE_KISS` selection in the connection form
- basic metadata through MeshCore `SetHardware`: device name, version, identity, radio parameters, TX power, battery, stats, RSSI/SNR metadata and TX status
- BLE MeshCore Companion profile with separate RX/TX UUIDs, subscription to TX notifications and `APP_START` handshake
- `FrameFormat.MESHCORE_COMPANION` for TCP/Serial raw Companion packets
- Companion metadata: self-info, public key, device name, device info and battery/storage packet
- MeshCore contacts and channel info synchronized into the shared Nodes/Chat state
- incoming channel messages and DMs from the Companion Protocol queue
- outgoing channel messages and DMs through MeshCore Companion Protocol
- read-only MeshCore metadata in Settings
- raw MeshCore Companion packets in LoRa Packets
- active protocol display in the connection card

Current limitations:

- MeshCore KISS is TCP/Serial only; MeshCore Companion over TCP/Serial requires an endpoint that really provides raw Companion packets.
- MeshCore KISS remains a modem/metadata integration; application workflows are implemented through MeshCore Companion Protocol.
- MeshCore Companion does not support Meshtastic-only features: Admin protobuf save-flow, reactions, traceroute and Meshtastic bot commands.

Details are available in [docs/meshcore-support.md](docs/meshcore-support.md).

---

### Device Configuration

<p align="center">
  <img src="docs/screenshots/settings-b.png" width="49%" alt="Settings - dark theme"/>
  <img src="docs/screenshots/settings-w.png" width="49%" alt="Settings - light theme"/>
</p>

- **Parameter editing**: full LoRa module configuration through a tree interface.
- **Node name**: set Long Name (40 characters) and Short Name (4 characters).
- **Atomic save**: transactional begin/commit mechanism for grouped changes.
- **All modules**: configure Device, LoRa, Position, Power, Network, Bluetooth, Display and other modules.
- **Human-readable field editors**: IPv4 addresses, node IDs, hex values and bitmask fields can be edited without manually recalculating protobuf values.
- **Repeated fields as slots**: lists such as `admin_key` and `ignore_incoming` can be added, removed and edited item by item.
- **PC time synchronization**: set node time from the computer clock, update GMT and resynchronize after reconnect when reboot is required.
- **Configuration snapshots**: export and import full configuration as `.mcf`.
- **Configuration templates**: export and import depersonalized `.mtp` templates without personal or secret fields.
- **Local database cleanup**: reset the embedded H2 database, deleting messages, reactions, telemetry, node cache and packet journal.
- **Device power management**: restart and shut down hardware from the UI.

---

### Diagnostics and Logging

<p align="center">
  <img src="docs/screenshots/logs-b.png" width="49%" alt="Logs - dark theme"/>
  <img src="docs/screenshots/logs-w.png" width="49%" alt="Logs - light theme"/>
</p>

- **Built-in logs**: view debug information with level coloring.
- **Log controls**: pause/resume autoscroll, copy, clear and export to `.log`.
- **LoRa packet monitor**: separate live-capture window for incoming and outgoing mesh packets.
- **Capture controls**: start, stop and clear the accumulated packet journal.
- **Filters and search**: filter by direction, type, date/time range, nodes and payload, with pagination for long logs.
- **HEX / ASCII and packet tree**: inspect raw bytes, protobuf structure and highlighted selected fields.
- **Packet export**: copy or save the selected packet as text or protobuf-style JSON, and export the whole filtered set as JSON or CSV.
- **Crash and problem reports**: send technical logs to developers after a crash or manually from the Help window.

#### LoRa Debug

<p align="center">
  <img src="docs/screenshots/loradebug-b.png" width="49%" alt="LoRa Debug - dark theme"/>
  <img src="docs/screenshots/loradebug-w.png" width="49%" alt="LoRa Debug - light theme"/>
</p>

`LoRa Debug` helps inspect real mesh traffic at the individual-packet level. The window shows incoming, outgoing and internal `MeshPacket` records, lets you filter the journal by direction, message type and content, and keeps the selected type filter when the available type list is refreshed.

The tool is useful for delivery diagnostics, checking `NodeInfo` / `Telemetry` / `Position` packets, analyzing `MQTT proxy` traffic and viewing raw protobuf data through HEX / ASCII preview and a packet-structure tree. Large journals use dynamic page loading, and JSON/CSV export is performed in batches with a progress indicator.

---

### MeshApp IDE and Lua Scripts

- **Script list**: cards with name, emoji icon, author, version, type, run status and last-modified time without exposing the internal ID.
- **Script settings**: name, author, description, icon, autostart, bot type and binding to a node or automation name.
- **Code versioning**: version is incremented only when Lua code changes; settings changes do not increment it.
- **Air and automation bots**: scripts can listen to chat through `on_message` or start from the input line command through `on_command`.
- **Script autostart**: scripts with autostart enabled start for the selected node after the connection is ready.
- **Lua editor**: separate window with syntax highlighting, line numbers, auto-indent and `mesh.*` completion.
- **Debugger**: breakpoints, debug run, continue, step execution and local/global variable inspection.
- **KV storage**: isolated key-value storage per script with a dedicated editor.
- **Import and export**: move scripts in `.meshapp-script.json` JSON files with metadata.
- **Script store**: load catalog from MeshApp Store, filter by type, display author, install, update and remove local copies.
- **API documentation**: the built-in sandbox API is documented separately with a short Lua reference and examples.

---

### Interface

- **Dark and light themes**: AtlantaFX Cupertino.
- **Native window appearance**: Mica effect on Windows 11 and vibrancy on macOS.
- **Custom titlebar**: platform-style window buttons.
- **Sidebar**: fast navigation between sections.
- **System tray / status item**: minimize to tray, restore the window and quit the app.
- **System notifications**: native OS notifications for incoming messages, suppressed when the active chat is already open.
- **Sidebar toggles**: quickly switch theme and notifications without opening Settings.
- **Update check**: optional new-version check on startup.
- **Application settings**: disable visual effects, use software rendering and choose minimize-to-tray mode.
- **Window state persistence**: restore size, position, maximized state and splitters between sessions.
- **Toast notifications**: non-disruptive event feedback.
- **Terminal mode**: Lanterna TUI client for connecting and using chats without the JavaFX UI.

---

### Cache and Integrations

- **OneMesh import**: load a public node cache into the local H2 database for a faster start and richer node cards.
- **MQTT proxy bridge**: when `MQTT proxy_to_client` is enabled, MeshApp starts a desktop-side bridge to the broker and proxies messages between the device and MQTT.
- **Broker parameters from the device**: address, root topic, TLS, login/password and retained publications are taken from the MQTT module configuration, with local loopback suppression.
- **Local persistence**: messages, reactions, unread chats, favorite/ignored nodes, telemetry, scripts, KV data, traces and the LoRa packet journal are preserved between sessions.

---

## Lua API for Scripts

MeshApp supports custom Lua scripts and bots in a LuaJ sandbox. Scripts can use the `mesh` namespace for chats, local KV storage, limited HTTP(S) requests, node selection, traceroute and NodeInfo. Unsafe global APIs such as `io`, `os`, `debug`, `package`, `require`, `dofile`, `loadfile` and `luajava` are disabled.

Scripts can react to new messages through `on_message(msg)`, handle bot commands through `on_command(command)` and receive asynchronous operation results through `on_node_selected(event)`, `on_traceroute(event)` and `on_node_info(event)`.

Full Lua API documentation, object fields and working examples are available in [docs/lua-api.md](docs/lua-api.md).

---

## Quick Start

### Requirements

To build and run from source:

- **JDK 25 toolchain** (downloaded automatically by Gradle Toolchain)
- **Git** for cloning the repository
- **macOS**: Xcode Command Line Tools (`cc`) to build `libmeshapp-serial.dylib` and `libmeshapp-tray.dylib`
- **Windows**: CMake + MSVC Build Tools to build `meshapp-ble.dll`
- **Linux**: CMake + C/C++ toolchain + `libsystemd-dev` / `systemd-devel` to build `libmeshapp-ble.so`

Release packages (`.dmg`, `.msi`, `.deb`, `.AppImage`, `.flatpak`) do not require these build dependencies.

### Build and Run

```bash
# Clone the repository
git clone https://git.privatepractice.app/covox/meshapp.git
cd meshapp

# Run the application
./gradlew run

# Run terminal mode
./gradlew runTerminal

# Terminal mode with a temporary TCP profile
./gradlew runTerminal --args="--host 192.168.1.10 --protocol meshtastic"

# Run with local JMX for VisualVM/JConsole/JMC
./gradlew run -PjmxDebugEnabled=true

# Run VisualVM memory profiler mode
./gradlew run -PvisualVmProfilerEnabled=true

# Build .app/.dmg with VisualVM memory profiler mode
./gradlew jpackage -PvisualVmProfilerEnabled=true

# JMX on a different local port
./gradlew run -PjmxDebugEnabled=true -PjmxDebugPort=9011

# Build native installer (.dmg / .msi / .deb)
./gradlew jpackage

# Linux: build portable AppImage
./gradlew appImage -Pappimagetool=/path/to/appimagetool.AppImage

# Linux: build Flatpak bundle
./gradlew flatpak
```

When JMX is enabled, the app listens on `127.0.0.1` only; connection address:
`service:jmx:rmi:///jndi/rmi://127.0.0.1:9010/jmxrmi`. For another port, replace
`9010` with the `jmxDebugPort` value. The same can be enabled through
`MESHAPP_JMX_DEBUG=true` and `MESHAPP_JMX_PORT=9011`.

For memory analysis in VisualVM, use `Sampler > Memory` over a local JMX connection. `Profiler > Memory` is an instrumentation profiler; on Java 25/GraalVM/JavaFX/macOS its native agent may crash together with the target JVM.

If you still need to test `Profiler > Memory`, start the app with `-PvisualVmProfilerEnabled=true`. This mode also enables local JMX, disables class data sharing (`-Xshare:off`) and disables the Graal/JVMCI JIT compiler during profiling. For another port use `-PvisualVmProfilerEnabled=true -PjmxDebugPort=9011`. The mode can also be enabled with the `MESHAPP_VISUALVM_PROFILER=true` environment variable.

If profiling a packaged macOS `.app`, rebuild it with `-PvisualVmProfilerEnabled=true`: an already existing `.app` does not receive new JVM options automatically.

Software-rendering flags for bypassing macOS `CVDisplayLink` are not enabled by default because they can make the UI unusable. For a one-off test, add:
`-PvisualVmSoftwareRenderingEnabled=true`.

If VisualVM shows `Provided Memory settings are invalid` when starting `Profiler > Memory`, open memory profiler settings and replace the placeholder in `Profile classes` with a valid filter, for example `com.meshtastic.client.**` for application code or `**` for all classes.

### Connecting to a Device

1. Connect a Meshtastic or MeshCore device over USB/TCP/BLE.
2. In **Connections**, add a new profile and choose type: **TCP**, **Serial / USB** or **BLE**.
3. Select the protocol. **Meshtastic** is selected by default; for MeshCore choose **MeshCore KISS** or **MeshCore Companion**.
4. For **Serial / USB**, select the detected port; for **BLE**, start scanning and select a device from the list.
5. If the platform or device requires pairing, confirm pairing / enter passkey.
6. Click **Connect**. Meshtastic starts config exchange; MeshCore KISS performs the SetHardware handshake; MeshCore Companion performs the `APP_START` handshake.
7. For Meshtastic, switch to **Chats**, **Nodes** or **Settings**. For MeshCore Companion, **Chats**, **Nodes**, direct messages, **Telemetry**, **Settings** and **LoRa Packets** are available; MeshCore KISS shows modem metadata.

### Linux: USB Serial Access

If the USB port is visible in the list but connection fails with `Permission denied`, the current user has no access to `/dev/ttyUSB*` or `/dev/ttyACM*`. Check the device-node group and add the user to it:

```bash
ls -l /dev/ttyUSB0
sudo usermod -aG dialout "$USER"
```

On some distributions the group is named `uucp` or `lock`; use the group shown by `ls -l`. After changing groups, log out and back in. The MeshApp `.deb` package also installs udev rules for common USB-UART Meshtastic boards so the active local user receives a `uaccess` ACL and ModemManager does not claim the port.

If the error looks like `Device or resource busy`, the port is already open in another process. Usually it is another serial monitor/CLI or ModemManager.

---

## Technologies

| Component | Technology | Purpose |
|-----------|------------|---------|
| UI | JavaFX 25.0.3 + AtlantaFX | Interface with native appearance |
| Protocol runtime | `CommunicationProtocol` + `ProtocolRuntime` | Run protocol adapters over an open transport |
| Protocol selection | `ProtocolRegistry` + `ProtocolType` | Start the runtime selected in the connection profile |
| Meshtastic protocol | Protobuf 4.33.4 + Meshtastic schemas | Serialize `ToRadio` / `FromRadio` and process mesh packets |
| MeshCore KISS protocol | KISS framing + MeshCore `SetHardware` | Basic handshake and MeshCore KISS modem metadata |
| MeshCore Companion protocol | MeshCore Companion Protocol + BLE RX/TX or raw TCP/Serial packets | Handshake, metadata, contacts, channels, Chat/DM and raw packet monitor |
| Transport layer | `TransportConnection` | Common contract for TCP, Serial, BLE and future transport implementations |
| Database | H2 (embedded) | Local storage for messages, telemetry, scripts, traces and journals |
| Maps | JavaFX `TileMapView` + OSM tiles | Online/offline node map, tile cache and trace visualization |
| Lua runtime | LuaJ 3.0.1 | Sandbox scripts, bots, KV storage and `mesh.*` API |
| Lua editor | RichTextFX | Code editor with highlighting, lines, completion and debugger |
| Terminal mode | Lanterna | TUI client for running without the JavaFX interface |
| MQTT bridge | Eclipse Paho MQTT | Desktop-side proxy to an external MQTT broker for `proxy_to_client` |
| TCP | `java.net.Socket` | Meshtastic TCP API, MeshCore KISS endpoint or raw MeshCore Companion endpoint |
| Serial | Native JNA backends + jSerialComm discovery | Native COM/tty access without jSerialComm I/O; Meshtastic, MeshCore KISS and MeshCore Companion framing |
| BLE | CoreBluetooth / WinRT / BlueZ through JNA | BLE scanning, GATT and pairing on supported platforms |
| Native integrations | JNA + platform bridges | Mica (Win), vibrancy (macOS), tray/status item and system bridges |
| Build | Gradle 9.4.1 + Protobuf + CMake + jpackage | Java/native compilation and installer packaging |

---

## Protocol Architecture

Connections in MeshApp are now split into two independent layers:

- **Transport**: only delivers bytes by opening/closing the connection, writing data and forwarding incoming payloads upward. The common contract is `TransportConnection`; transport factory is `TransportConnectionFactory`.
- **Protocol runtime**: gives meaning to those bytes: framing, parsing, handshake/config exchange, runtime state and protocol services. Common contracts are `CommunicationProtocol`, `ProtocolRuntime`, `ProtocolRuntimeContext` and `ProtocolRegistry`.

Currently registered protocols:

| ProtocolType | Runtime | Purpose |
|--------------|---------|---------|
| `MESHTASTIC` | `MeshtasticProtocolRuntime` | `ProtocolHandler`, `DeviceState`, config exchange, incoming mesh packets, MQTT proxy |
| `MESHCORE_KISS` | `MeshCoreKissProtocolRuntime` | KISS SetHardware handshake, device name/version/identity/radio/battery/stats metadata |
| `MESHCORE_COMPANION` | `MeshCoreCompanionProtocolRuntime` | MeshCore Companion `APP_START`, self-info/device-info/battery, contacts, channel info, Chat/DM |

The protocol runtime is selected from the saved `ProtocolType`. TCP/Serial immediately receive the matching `FrameFormat`; BLE connects to the selected protocol's GATT profile. Old profiles without a `protocol` field use Meshtastic.

To add a new protocol:

1. Add a value to `ProtocolType`.
2. Implement `CommunicationProtocol<S>` and `ProtocolRuntime<S>`.
3. Register the adapter in `ProtocolRegistry`.
4. Add UI/services that work with the new runtime state.
5. Extend `ConnectionEntry` and `TransportConnectionFactory` if the protocol requires a new transport type.

The existing UI still uses compatible Meshtastic accessors from `ConnectionManager` (`getDeviceState`, `getProtocolHandler`, `getConfigFuture`). New protocols should access their state through the runtime abstraction or dedicated typed accessors.

---

## Project Structure

```text
meshapp/
|-- src/main/java/com/meshtastic/client/
|   |-- MeshAppLauncher.java      # Entry point: JavaFX or terminal mode
|   |-- MeshApp.java              # JavaFX Application
|   |-- connection/               # TransportConnection, TCP/Serial/BLE transport layer
|   |   |-- ble/                  # BLE transport + platform backends
|   |   \-- serial/               # Native serial I/O (Win/macOS/Linux)
|   |-- lua/                      # Lua runtime, sandbox API, script store/import/export
|   |-- protocol/                 # Shared protocol runtime API and registry
|   |   |-- meshcore/             # MeshCore KISS and Companion protocol adapters/runtimes
|   |   \-- meshtastic/           # Meshtastic protocol adapter/runtime
|   |-- terminal/                 # Lanterna TUI
|   |-- model/                    # Data models and runtime state
|   |-- service/                  # Persistence, discovery, reconnect, config exchange
|   |-- forms/                    # Main application screens
|   |-- components/               # Reusable UI components
|   |   \-- map/                  # OSM tile map components
|   |-- notification/             # System notifications
|   |-- platform/                 # OS-specific UI / system integration
|   |-- system/                   # App framework (FormManager, RootPane)
|   |-- tray/                     # System tray / status item
|   \-- themes/                   # Theme management
|-- native/
|   |-- windows-ble/              # WinRT BLE DLL
|   |-- linux-ble/                # BlueZ BLE shared library
|   |-- macos-serial/             # macOS serial helper dylib
|   \-- macos-tray/               # macOS native tray/status item bridge
|-- src/main/proto/meshtastic/    # Meshtastic protobuf schemas
|-- src/main/resources/           # CSS, fonts, icons, logos
\-- build.gradle                  # Build configuration
```

---

## Building Installers

MeshApp uses `jpackage` for native packages and additionally supports portable `AppImage` and sandboxed `Flatpak` on Linux:

| Platform | Format | Command |
|----------|--------|---------|
| Windows | `.msi` | `./gradlew jpackage` |
| macOS | `.dmg` | `./gradlew jpackage` |
| Linux | `.deb` | `./gradlew jpackage` |
| Linux | `.AppImage` | `./gradlew appImage -Pappimagetool=/path/to/appimagetool.AppImage` |
| Linux | `.flatpak` | `./gradlew flatpak` |

`AppImage` requires `appimagetool`: either in `PATH` or provided through `-Pappimagetool=...` / `APPIMAGETOOL=...`. If using the `.AppImage` version of `appimagetool`, `APPIMAGE_EXTRACT_AND_RUN=1` may be required.

`Flatpak` requires `flatpak` and `flatpak-builder`, plus installed runtime/SDK. By default the task uses `org.freedesktop.Platform//24.08` and `org.freedesktop.Sdk//24.08`:

```bash
flatpak --user remote-add --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
flatpak --user install -y flathub org.freedesktop.Platform//24.08 org.freedesktop.Sdk//24.08
./gradlew flatpak
```

When needed, override the runtime through `-PflatpakRuntime=...`, `-PflatpakRuntimeVersion=...`, `-PflatpakSdk=...` and `-PflatpakBranch=...`.

For `jpackage`, explicitly set the JDK used for the bundled runtime with `-PpackagingJavaHome=/path/to/jdk` or `PACKAGING_JAVA_HOME=/path/to/jdk`. On macOS the build additionally validates the `.app` with `otool -L` and fails if external dependencies such as `/opt/homebrew/...` or `/usr/local/...` remain inside the bundle.

During `processResources`, Gradle automatically builds platform native components:

- **Windows**: `meshapp-ble.dll` for BLE through WinRT.
- **Linux**: `libmeshapp-ble.so` for BLE through BlueZ.
- **macOS**: `libmeshapp-serial.dylib` for safe serial modem-line control.
- **macOS**: `libmeshapp-tray.dylib` for the native status item / tray bridge.

### macOS Signing and Notarization

By default, `./gradlew jpackage` on macOS performs only an ad-hoc `.app` signature. Such a `.dmg` is fine for local testing, but not enough for browser downloads: Gatekeeper may show **"App is damaged and can't be opened"**.

For a release build, pass credentials for `Developer ID` signing:

- `MAC_SIGNING_KEY_USER_NAME` or `-PmacSigningKeyUserName=...`: Team/User name from the Apple Developer certificate.
- `MAC_SIGNING_KEYCHAIN` or `-PmacSigningKeychain=...`: optional keychain with the certificate.
- `MAC_PACKAGE_SIGNING_PREFIX` or `-PmacPackageSigningPrefix=...`: optional signing prefix, defaults to `com.meshtastic`.

For a Gitea runner in daemon mode, prefer importing the certificate into a temporary keychain from secrets instead of relying on the user's `login.keychain`:

- `MAC_SIGNING_CERTIFICATE_P12`: base64 of the `.p12` with the `Developer ID Application` certificate.
- `MAC_SIGNING_CERTIFICATE_PASSWORD`: password for the `.p12`.
- `MAC_SIGNING_KEYCHAIN_PASSWORD`: password for the temporary keychain.

An `Apple Development` certificate is not suitable for a release DMG: it is intended for development. Downloadable builds require `Developer ID Application`.

Then use one of the notarization options:

- `MAC_NOTARY_KEYCHAIN_PROFILE` or `-PmacNotaryKeychainProfile=...`
- `MAC_NOTARY_APPLE_ID` + `MAC_NOTARY_TEAM_ID` + `MAC_NOTARY_PASSWORD`
- `MAC_NOTARY_KEY_FILE` + `MAC_NOTARY_KEY_ID` + `MAC_NOTARY_ISSUER`
- In CI, `MAC_NOTARY_KEY_FILE_BASE64` can be used instead of `MAC_NOTARY_KEY_FILE`; the workflow creates the `.p8` file itself.

After that, a normal `./gradlew jpackage` builds a signed `.app`, a signed `.dmg` and runs `notarytool submit --wait` plus `stapler`.

If the Gitea runner has no `Developer ID Application`, the workflow still builds the macOS artifact with the previous name, but skips `spctl`/notarization checks.

### Installing on macOS

If the build was made without `Developer ID` and notarization, macOS may show an **"unidentified developer"** or **"App is damaged and can't be opened"** warning. This is expected for a local ad-hoc build.

**Method 1** - Finder:

1. Open Applications, or the folder where MeshApp is installed.
2. Right-click, or Control-click, MeshApp and choose **Open**.
3. Confirm opening in the dialog. This is required only once.

**Method 2** - Terminal:

```bash
xattr -cr /Applications/MeshApp.app
```

---

## License

Distributed under the [GPL-3.0](LICENSE).

---

<p align="center">
  Created by Konstantin A. Smirnov
  <br>
<a href="https://t.me/coVox">
  <img src="https://img.shields.io/badge/Telegram-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white" alt="Telegram">
</a>
</p>
