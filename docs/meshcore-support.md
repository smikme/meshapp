# MeshCore Support

**Language:** [Русский](meshcore-support.ru.md) | English

MeshApp supports MeshCore devices through two different protocol modes:

- **MeshCore KISS modem protocol** over a TCP/Serial byte stream.
- **MeshCore Companion Protocol** over BLE RX/TX GATT characteristics or a raw TCP/Serial byte stream.

These are separate protocol runtimes next to Meshtastic, not a replacement for Meshtastic logic. KISS and Companion are incompatible at the framing level: MeshCore BLE connections do not use KISS frames, and a TCP/Serial Companion endpoint must provide raw Companion packets without a KISS wrapper.

## What Changed

- New connection profiles use `ProtocolType.MESHTASTIC` by default; legacy profiles without a `protocol` field are treated as Meshtastic.
- Added `ProtocolType.MESHCORE_KISS`, `ProtocolType.MESHCORE_COMPANION`, `MeshCoreKissProtocolRuntime` and `MeshCoreCompanionProtocolRuntime`.
- Added KISS framing for TCP and Serial transports.
- Added `FrameFormat.MESHCORE_COMPANION` and a stream parser for MeshCore Companion packets over TCP/Serial.
- Added a BLE profile for MeshCore Companion service/RX/TX UUIDs.
- Protocol is selected explicitly before runtime startup: choose `MeshCore KISS` or `MeshCore Companion` in the connection profile.
- The connection card shows the selected/active protocol.
- MeshCore Companion now fills the shared UI state for Chat, Nodes, Dashboard, Settings and LoRa Monitor screens.
- Added channel-message and DM sending through MeshCore Companion Protocol.
- Added synchronization of MeshCore contacts, channel info and incoming messages from the Companion Protocol queue.
- Existing Meshtastic profiles remain compatible. Legacy profiles without a `protocol` field are still considered `MESHTASTIC`.

## Supported Transports

| Transport | MeshCore mode | Note |
|-----------|---------------|------|
| Serial / USB | KISS | Standard MeshCore KISS serial settings: 115200 baud, 8N1, no flow control. |
| TCP | KISS | Works with endpoints that provide the same KISS byte stream over TCP. |
| Serial / USB | Companion Protocol | Works with endpoints that provide raw Companion packets without KISS framing. |
| TCP | Companion Protocol | Works with bridge/server endpoints that transmit raw Companion packets over a byte stream. |
| BLE | Companion Protocol | Uses a separate BLE service/RX/TX UUID, TX notifications and raw Companion packets. |

## Protocol Selection

For new profiles, `ConnectionEntry.protocol` defaults to `MESHTASTIC`.

1. MeshApp opens the selected transport.
2. TCP/Serial transport immediately receives the `FrameFormat` that corresponds to the saved `ProtocolType`.
3. BLE transport immediately selects the saved `ProtocolType` GATT profile.
4. `ConnectionManager` starts the runtime from `ProtocolRegistry`: `MeshtasticProtocolRuntime`, `MeshCoreKissProtocolRuntime` or `MeshCoreCompanionProtocolRuntime`.
5. Legacy profiles without a `protocol` field use `MESHTASTIC`.

For MeshCore, explicitly select `MeshCore KISS` or `MeshCore Companion` in the protocol field when creating a connection.

## MeshCore Companion

MeshCore Companion Protocol uses its own binary packet protocol, not KISS framing. According to the MeshCore documentation, BLE companion devices advertise a Nordic UART-like service:

- Service UUID: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
- RX characteristic, App -> Firmware: `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`
- TX characteristic, Firmware -> App: `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`

MeshApp adds a separate BLE profile for these UUIDs:

- scan searches for the selected profile service UUID;
- connect immediately uses the selected profile;
- MeshCore Companion transport writes raw Companion packets to the RX characteristic;
- incoming data arrives from the TX characteristic through notifications;
- `MeshCoreCompanionProtocolRuntime` sends `APP_START` and parses `SELF_INFO`, `DEVICE_INFO`, `BATTERY`, contacts, channel info and queued messages.

TCP/Serial uses `FrameFormat.MESHCORE_COMPANION`: transport forwards raw Companion packets to the shared runtime. Because the official Companion transport is packet-boundary based BLE GATT, the byte stream parser does best-effort parsing: fixed-size responses are emitted immediately, while variable-size responses are completed by inter-byte silence/read timeout.

The Companion runtime creates a compatible `DeviceState`, so existing MeshApp screens can show MeshCore contacts as nodes, MeshCore channels as chats, battery voltage as dashboard telemetry and raw Companion packets as LoRa Monitor entries.

## MeshCore KISS Support Scope

The current MeshCore runtime performs the basic KISS modem handshake and reads device metadata through MeshCore `SetHardware` extensions.

Current collected state:

- device name
- identity public key, exposed to the UI as short `mc:<12 hex>`
- firmware version
- radio parameters: frequency, bandwidth, spreading factor, coding rate
- transmit power
- battery voltage
- packet statistics
- latest RX metadata: RSSI and SNR
- latest TX status
- latest MeshCore error code

KISS runtime remains a modem/metadata integration. Chat, DM, contacts and channel workflows are implemented through MeshCore Companion Protocol because it provides application commands for the Companion client.

## MeshCore Companion Support Scope

The current MeshCore Companion runtime performs the `APP_START` handshake and collects:

- self-info packet;
- public key, fully available in the runtime and exposed to the UI as short `mc:<12 hex>`;
- device name from self-info;
- contacts list from `CONTACTS_START` / `CONTACT` / `CONTACTS_END`;
- channel info from `CHANNEL_INFO`;
- incoming channel messages and contact messages, including V3 variants;
- outgoing channel messages and DMs;
- firmware protocol version;
- max contacts / max channels, if the device returns device-info v3+;
- BLE PIN, firmware build, model and firmware version from device-info v3+;
- battery voltage;
- storage usage, if present in the battery response;
- latest Companion error code.

Screen support:

- **Chat**: shows MeshCore channels, saves history in the shared H2 database, sends channel messages and DMs. Reactions, traceroute and Meshtastic bot commands are disabled for MeshCore or show local information.
- **Nodes**: shows MeshCore Companion contacts as nodes with public-key prefix, name, role, coordinates and last advert time when these fields are received from the device.
- **DM**: direct chats are created by MeshCore contact id `mc:<12 hex>` and sent through `SEND_TXT_MSG`.
- **Dashboard**: shows battery voltage as a telemetry entry for the local MeshCore device.
- **Settings**: shows a read-only tree of MeshCore metadata, radio parameters, storage and channels. Meshtastic Admin protobuf configuration is not written for MeshCore.
- **LoRa Monitor**: records incoming and outgoing raw MeshCore Companion packets with transport mechanism `MESHCORE_COMPANION`, dedicated packet types and HEX/ASCII preview.

## KISS Framing

MeshCore KISS uses standard KISS TNC framing:

| Byte | Value |
|------|-------|
| `0xC0` | `FEND`, frame delimiter |
| `0xDB` | `FESC`, escape byte |
| `0xDC` | escaped `FEND` |
| `0xDD` | escaped `FESC` |

MeshApp passes an already unescaped frame body to the protocol runtime:

```text
[type byte][payload...]
```

For MeshCore metadata, the type byte uses KISS command `SetHardware` (`0x06`), and the first payload byte is the MeshCore sub-command. Standard data frames (`0x00`) are accepted by the parser, but the current MeshCore runtime only logs them and does not yet decode MeshCore packet payload into application models.

## UI Behavior

- The user creates a normal TCP, Serial or BLE connection.
- Protocol defaults to `Meshtastic`; for MeshCore, the user explicitly selects `MeshCore KISS` or `MeshCore Companion`.
- After clicking **Connect**, MeshApp starts the selected protocol runtime.
- The connection card shows `Meshtastic`, `MeshCore KISS` or `MeshCore Companion`.
- For MeshCore Companion, Chat, Nodes, Dashboard, Settings and LoRa Monitor are available. Functions tied to Meshtastic Admin/Traceroute/Reaction protobuf remain unavailable and explicitly report this in the UI.
- For MeshCore KISS, the connection and modem runtime metadata are shown; application screens use Companion Protocol.

## Development Notes

Main classes:

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

Covered by tests:

- KISS escaping and parsing
- Companion packet parsing over byte stream transports
- protocol registration in the registry
- MeshCore KISS runtime startup after explicit protocol selection
- MeshCore Companion runtime startup after explicit protocol selection
- end-to-end TCP/BLE runtime selection through `ConnectionManager`
- MeshCore Companion bridge into `DeviceState` for Chat/Nodes/Dashboard
- MeshCore channel message and DM sending
- raw MeshCore Companion entries in LoRa Monitor

Specification links:

- MeshCore KISS modem protocol: <https://github.com/meshcore-dev/MeshCore/blob/main/docs/kiss_modem_protocol.md>
- MeshCore Companion Protocol: <https://docs.meshcore.io/companion_protocol/>
- MeshCore packet format: <https://docs.meshcore.io/packet_format/>
