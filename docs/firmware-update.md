# Device firmware updates

Open **Settings → Firmware** while connected to the intended Meshtastic device. Select its application firmware, review the mode and warnings, and confirm the update. MeshCore flashing is not supported by this workflow.

| Connection | Image | Automatic mode |
| --- | --- | --- |
| ESP32 USB / Serial | application/update `.bin` | Serial, using esptool |
| ESP32 TCP | application/update `.bin` | Wi-Fi Unified OTA |
| ESP32 Bluetooth | application/update `.bin` | BLE Unified OTA |
| nRF52 Bluetooth | legacy Nordic DFU application `.zip` | DFU over BLE |
| nRF52840 or RP2040 USB / Serial | `.uf2` | UF2 mass storage |

An archive containing one application BIN or UF2 is also accepted. For an archive with multiple images, MeshApp tries to match the advertised hardware model; an ambiguous archive must be extracted and the correct image selected explicitly. Nordic ZIPs must contain `manifest.json` and an application BIN/DAT pair with a valid legacy CRC16. Combined SoftDevice/bootloader packages and Nordic Secure DFU packages are rejected.

## ESP32 over USB

Install the upstream [esptool](https://docs.espressif.com/projects/esptool/en/latest/esp32/installation.html) in the environment where MeshApp runs. MeshApp looks for `esptool`, `esptool.py`, `python3 -m esptool`, `python -m esptool`, and the Windows Python launcher. `MESHAPP_ESPTOOL` can specify an executable path (not a shell command). The utility is checked **before** the device connection is released.

MeshApp passes the selected port explicitly and updates the application at `0x10000`, following Meshtastic's [device-update script](https://github.com/meshtastic/firmware/blob/develop/bin/device-update.sh). It does not erase the whole flash or install a new partition table. Select the application/update binary for the board; factory images and bootloader binaries are rejected. Use the official installer for first installation or a partition-layout change. In Flatpak, the executable and its dependencies must be available inside the sandbox; installing esptool only on the host is insufficient. The native distribution can use the host installation.

## ESP32 OTA

Unified OTA needs firmware 2.7.17 or later, a compatible loader already installed on the device, and the matching application image. Wi-Fi requires a TCP profile whose host remains reachable on port 3232 after reboot. BLE uses the Unified OTA service and waits for a loader acknowledgment after each chunk.

Device refusals such as a missing loader are shown directly. Connection attempts can be retried before the OTA header is sent. Errors after the handshake, including a lost final response, stop the operation without replaying the image.

## Nordic DFU and UF2

For BLE DFU, select the application OTA ZIP intended for the board and its SoftDevice. MeshApp searches for the original BLE address or its incremented bootloader address. When the operating system exposes an unrelated identifier, select the intended bootloader by name and address from the discovered list; MeshApp never chooses an arbitrary nearby device.

For UF2, the device reboots into its USB bootloader. Select the root of its mounted drive, containing `INFO_UF2.TXT`. MeshApp checks the UF2 block structure, processor family, application address range, and drive identification before writing. The supported UF2 families are nRF52840 and RP2040. The OS must mount the drive and give MeshApp write access; sandboxed installations also need access to the selected mount.

## Validation and completion

The selected file is copied into a private temporary directory. Archive members are extracted to controlled filenames with size limits. A changed selection is rejected before issuing a reboot. ESP images are checked for an application header, segment bounds, checksum and optional SHA-256; Nordic images for the init-packet CRC; UF2 for complete, uniquely numbered blocks. Known board IDs are checked against the processor family. For unknown boards and different boards sharing a processor, the user must still select the matching board image.

Only one update can run at a time. Normal reconnect is suspended while the bootloader owns the transport. Cancellation closes the transfer and restores reconnect, but cannot undo bytes already written. A device interrupted during flashing may remain in its bootloader and need recovery with the official tools.

After transfer, MeshApp reconnects and waits for the original node's configuration and metadata. For ESP32 it also compares the advertised firmware version with the image descriptor. A successful file copy alone is not reported as a completed update. If application startup cannot be confirmed within two minutes, the result says so rather than automatically flashing again.

## Verification scope

Automated checks use local TCP servers, fake GATT devices, temporary UF2 volumes, and child processes. They cover checksums, archive selection, BLE flow control, device refusals, reconnect reservation and non-replay after a lost final response. They do not replace flashing representative physical boards on Linux, Windows and macOS. Native Windows and CoreBluetooth paths require their respective operating systems for device testing.
