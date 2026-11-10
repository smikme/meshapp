# MeshCore support

**Language:** English | [Русский](meshcore-support.ru.md) | [Deutsch](meshcore-support.de.md)

MeshApp implements Companion messaging, editable settings, contacts, channels and
advanced operations. KISS is a separate modem mode. The implementation has software
tests; full hardware acceptance is still outstanding.

## Connect

Select `MeshCore Companion` for Companion firmware. USB/Serial and TCP use the
official stream framing: host → device `3C lengthLE16 payload`; device → host
`3E lengthLE16 payload`. Maximum payload: 176 bytes. BLE sends one raw Companion
payload per GATT write/notification, without the stream header. Service:
`6E400001-B5A3-F393-E0A9-E50E24DCCA9E`; RX ends in `0002`, TX in `0003`.
Notifications must be subscribed before handshake. Commands are not split into
separate GATT writes. Oversized writes and transport failures are surfaced.

Initialization waits for valid SELF_INFO, device capabilities, a complete contact
snapshot, every advertised channel slot and battery response. Readiness is distinct
from transport connection. The bounded command queue serializes untagged responses;
push events are processed separately. Reconnect creates a fresh session and sync.

## Use

- Chat: channel/DM text, offline queue, persistent history and retry of the same
  logical message. `DELIVERED` means accepted/sent by the radio; `CONFIRMED` means
  the matching recipient ACK. Channel OK is not subscriber confirmation. ACK timeout
  means unconfirmed/failed, not success. No `VERIFIED` status is inferred.
- Text limits: DM 160 UTF-8 bytes; channel 160 minus the sender's UTF-8 name and
  `: ` prefix. DM retries above attempt 3 allow at most 158 bytes. The composer and
  runtime validate the limit. Channel display names are unverified text.
- Settings: name, radio MHz/BW kHz/SF/CR/repeat, TX dBm, position, advert policies,
  telemetry permissions, time, BLE PIN and reboot. Writes read back where available.
- Channels: device capacity (including 1/8/40 slots), create/edit/clear, Public,
  hashtag SHA-256 keys, random private keys, official sharing links and local QR.
- Contacts: complete keys, types, flags, favourites, coordinates, timestamps and
  paths; add/edit/delete/import/export/share; live advert/path/delete updates.
  Remote login/logout/status/telemetry/CLI and path discovery are in Settings.
- Advanced API: tuning, auto-add, stats/custom variables, scopes/hash mode, trace,
  binary/channel/raw/control data, identity import/export/signing and factory reset.
  Identity/reset operations are explicit and require confirmation.
- Monitor: only real LOG_RX_DATA payloads are added as `MESHCORE_RADIO`, with RSSI
  and SNR. Companion host commands and channel secrets are not LoRa packets.
- KISS settings: modem metadata, radio/power edits, raw receive/transmit up to 255
  bytes, TxDone/busy/error handling and a SetHardware diagnostic console. KISS has
  no Companion chat/contacts/channel stack.

## Protocol reference and verification

Target: [official MeshCore firmware](https://github.com/meshcore-dev/MeshCore/blob/81dbae592ffad40b4212bc0b37e82b38da08f4bc/examples/companion_radio/MyMesh.cpp),
commit `81dbae592ffad40b4212bc0b37e82b38da08f4bc`, Companion protocol **14**.
The advertised client message-format version remains **3**: the firmware uses it
for V3 message envelopes; it is not a claim that firmware commands are limited
to version 3. Known version gates are checked, and optional disabled/unsupported
commands fail explicitly without disconnecting the session. Older firmware has
not been comprehensively validated.

[API and operation parameters](meshcore-api.md) ·
[CMD/RESP/PUSH matrix](meshcore-capabilities.md).

| Platform / transport | Software verification | Physical device |
|---|---|---|
| Linux TCP | Real local socket, independent length-prefixed emulator, reconnect tests | Not tested |
| Linux Serial | Adapter split-read tests and exact framing fixtures; no physical port | Not tested |
| Linux BLE | Java transport tests, native library builds, MTU/write-error checks | Not tested |
| Windows BLE | Java transport tests; native source updated, Windows build not run here | Not tested |
| macOS BLE | Existing native maximum-write check; Java integration tested | Not tested |
| KISS TCP/Serial | Parser, serialized handshake/metadata, raw DATA/TxDone scenarios | Not tested |
| JavaFX | Settings render and a real save action against the scripted Companion transport | Not tested with radio |

Hardware acceptance still requires two Companion radios: channel and DM both
ways, offline queue, official mobile client interoperability, long UTF-8 text,
settings changes and reconnect. Unit tests are not radio compatibility evidence.

## Data and compatibility limits

H2 schema 26 widens protocol/owner/contact identifiers to 80 characters without
rebuilding message history. MeshCore contacts use `mc:` + all 64 public-key hex
digits. Persistent positive local message IDs, protocol ACK hashes and database
row IDs are separate. Status writes include row ID and owner. Send attempts are
persisted, including retry number, ACK hash, timeout and result. Original message
envelopes and channel name/key fingerprints are available through `message.details`.

Legacy short IDs are rebound within the known owner when the contact prefix is
unambiguous. Colliding prefixes are not used to send DM; ambiguous incoming DM is
kept under `mc:pending:<prefix>`. Previously lost keys/messages cannot be recovered.
If legacy thread/read-count keys conflict with already migrated keys, those
auxiliary rows are retained rather than deleting history. Contact/channel secrets
are excluded from ordinary logs and status RPC. Explicit key exports expose them
only in the requested result. Local H2 storage is not an encrypted key vault.

The firmware dequeues a message before the host commits it. The client persists
before requesting the next message and stops on storage failure, but cannot
promise exactly-once recovery if the process dies in that interval. Two otherwise
identical incoming messages with the same sender, timestamp and content cannot
always be distinguished by this protocol. Channel slot history is retained when
the slot changes; incoming deduplication includes the channel key fingerprint.

Official stream framing replaces the previous raw-stream heuristic. Existing
Companion profiles keep their selected protocol, but custom bridges that emitted
unframed payloads must be updated to the official framing. Profiles without a
protocol field remain Meshtastic. KISS uses its own delimiters and cannot be
selected for Companion firmware.

Links and QR use the [official mobile format](https://github.com/meshcore-dev/MeshCore/blob/81dbae592ffad40b4212bc0b37e82b38da08f4bc/docs/qr_codes.md). Importing a
channel link with `region_scope` is rejected explicitly until that scope is
configured separately; it is never silently discarded. QR generation is local;
links can be pasted for import. Camera/image QR scanning is not implemented.

Advanced binary/trace/status/statistics results are available as original payloads
through the diagnostic console/API; they are not all presented as dedicated
charts or forms. Meshtastic protobuf replies/reactions are not sent over MeshCore.
