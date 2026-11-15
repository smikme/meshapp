/**
 * meshapp-ble: Windows BLE library for MeshApp (WinRT)
 *
 * Flat C API wrapping WinRT Bluetooth LE operations.
 * Called from Java via JNA. All functions are thread-safe.
 *
 * Return values: 0 = success, negative = error.
 * Callbacks fire from WinRT threads; caller handles synchronization.
 */

#ifndef MESHAPP_BLE_H
#define MESHAPP_BLE_H

#include <stdint.h>

#ifdef MESHBLE_EXPORTS
#define MESHBLE_API __declspec(dllexport)
#else
#define MESHBLE_API __declspec(dllimport)
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* ==================== Initialization ==================== */

/** Initialize WinRT apartment and Bluetooth subsystem. Call once before any other function. */
MESHBLE_API int meshble_init(void);

/** Release all resources and uninitialize WinRT. Safe to call multiple times. */
MESHBLE_API void meshble_cleanup(void);

/* ==================== Adapter State ==================== */

/**
 * Get current BLE adapter state.
 * @return 0=UNKNOWN, 1=POWERED_OFF, 2=POWERED_ON, 3=UNSUPPORTED, 4=UNAUTHORIZED
 */
MESHBLE_API int meshble_get_adapter_state(void);

/* ==================== Scanning ==================== */

/**
 * Select BLE profile before scanning/connecting; do not change it during a session.
 * Codes are a stable ABI shared with Java BleProtocolProfile and the Linux backend.
 * Firmware profiles require inbound notifications and do not fall back to polling.
 * @param profile 0=Meshtastic, 1=MeshCore Companion, 2=ESP32 OTA, 3=Nordic legacy DFU
 */
MESHBLE_API void meshble_set_profile(int profile);

/**
 * Callback for discovered BLE devices.
 * @param address  MAC address as "AA:BB:CC:DD:EE:FF"
 * @param name     advertised device name (may be NULL)
 * @param rssi     signal strength in dBm
 */
typedef void (*meshble_device_cb)(const char* address, const char* name, int rssi);

/**
 * Start scanning for BLE devices matching the selected profile service UUID.
 * @param callback  called for each discovered device (from WinRT thread)
 * @return 0 on success, negative on error
 */
MESHBLE_API int meshble_start_scan(meshble_device_cb callback);

/** Stop BLE scanning. */
MESHBLE_API void meshble_stop_scan(void);

/* ==================== Connection ==================== */

/**
 * Connect to a BLE device by MAC address. Blocks until GATT services and
 * characteristics are discovered, or timeout expires.
 *
 * @param address     MAC address as "AA:BB:CC:DD:EE:FF"
 * @param timeout_ms  connection timeout in milliseconds
 * @return 0 on success, -1 on timeout, -2 on device not found,
 *         -3 on GATT error, -4 on access denied (pairing required)
 */
MESHBLE_API int meshble_connect(const char* address, int timeout_ms);

/**
 * Return the latest native BLE error detail for diagnostics.
 * The returned pointer is owned by the library and remains valid until the
 * calling thread calls this function again.
 */
MESHBLE_API const char* meshble_get_last_error(void);

/** Disconnect from the current BLE device. Safe if not connected. */
MESHBLE_API void meshble_disconnect(void);

/** @return 1 if connected with GATT ready, 0 otherwise */
MESHBLE_API int meshble_is_connected(void);

/* ==================== Data Transfer ==================== */

/**
 * Write raw bytes to the active profile's outbound GATT characteristic.
 * Nordic DFU packet writes use Write Without Response; the Java uploader checks receipts.
 * @param data    protocol/firmware bytes without serial framing; copied before worker dispatch
 * @param length  number of bytes
 * @return 0 on success, -1 on error, -2 on access denied (device not paired)
 */
MESHBLE_API int meshble_write_to_radio(const unsigned char* data, int length);

/**
 * Write a Nordic legacy DFU opcode and parameters to its control point with response.
 * Requires a connected profile 3 session. Image/init packets use meshble_write_to_radio().
 * This waits for a bounded WinRT write; success acknowledges GATT delivery only. The DFU
 * operation result arrives separately through the inbound data callback.
 * @param data    caller-owned command bytes, copied before worker dispatch
 * @param length  positive number of command bytes
 * @return 0 on success, -1 for invalid state/arguments, timeout or write failure
 */
MESHBLE_API int meshble_write_firmware_control(const unsigned char* data, int length);

/**
 * Read fromRadio GATT characteristic (for polling fallback).
 * @param buffer    output buffer
 * @param buf_size  buffer capacity
 * @param out_len   actual bytes read (0 if empty)
 * @return 0 on success, negative on error
 */
MESHBLE_API int meshble_read_from_radio(unsigned char* buffer, int buf_size, int* out_len);

/**
 * Callback for incoming payloads from the active profile's inbound characteristic.
 * For OTA/DFU this delivers raw loader responses, without protobuf decoding.
 * @param data    borrowed payload bytes; copy before returning if needed later
 * @param length  number of bytes
 */
typedef void (*meshble_data_cb)(const unsigned char* data, int length);

/** Set listener for incoming FromRadio data (via notifications or polling). */
MESHBLE_API void meshble_set_from_radio_listener(meshble_data_cb callback);

/* ==================== Connection State ==================== */

/**
 * Callback for connection state changes.
 * @param state      0=connected, 1=disconnected, 2=error
 * @param error_msg  error description (NULL if state != 2)
 */
typedef void (*meshble_state_cb)(int state, const char* error_msg);

/** Set listener for connection state changes. */
MESHBLE_API void meshble_set_state_listener(meshble_state_cb callback);

/* ==================== Pairing ==================== */

/**
 * Callback when WinRT custom pairing needs a BLE passkey from the application.
 * @param device_address MAC address of the device requesting pairing
 */
typedef void (*meshble_passkey_request_cb)(const char* device_address);

/** Set callback for BLE passkey requests. */
MESHBLE_API void meshble_set_passkey_request_callback(meshble_passkey_request_cb callback);

/** Respond to a pending BLE passkey request with the user-provided PIN. */
MESHBLE_API void meshble_respond_passkey(uint32_t passkey);

/** Cancel a pending BLE passkey request (user declined pairing). */
MESHBLE_API void meshble_cancel_passkey(void);

/* ==================== Info ==================== */

/**
 * Check if fromRadio notifications are active (vs polling fallback).
 * @return 1 if notifications active, 0 if polling
 */
MESHBLE_API int meshble_notifications_active(void);

#ifdef __cplusplus
}
#endif

#endif /* MESHAPP_BLE_H */
