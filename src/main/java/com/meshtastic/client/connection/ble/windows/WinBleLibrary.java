package com.meshtastic.client.connection.ble.windows;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.meshtastic.client.utils.NativeResourceLoader;

import java.nio.file.Path;

/**
 * JNA mapping for the native meshapp-ble.dll library, which implements WinRT BLE.
 * <p>
 * The DLL exposes a flat C API for Windows Bluetooth LE operations: scanning,
 * connecting, GATT read/write, and notifications.
 * <p>
 * All callbacks are invoked from WinRT threads, so callers must provide their
 * own thread-safety boundary.
 *
 * @see WinBle
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface WinBleLibrary extends Library {

    static WinBleLibrary loadIsolated() {
        Path libraryPath = NativeResourceLoader.extractLibraryResource("meshapp-ble");
        return Native.load(libraryPath.toAbsolutePath().toString(), WinBleLibrary.class);
    }

    static WinBleLibrary loadShared() {
        return Native.load("meshapp-ble", WinBleLibrary.class);
    }

    // ==================== Initialization ====================

    /** Initializes the WinRT apartment. Call once. */
    int meshble_init();

    /** Releases all native resources. */
    void meshble_cleanup();

    // ==================== Adapter State ====================

    /**
     * Returns the BLE adapter state.
     * @return 0=UNKNOWN, 1=POWERED_OFF, 2=POWERED_ON, 3=UNSUPPORTED, 4=UNAUTHORIZED
     */
    int meshble_get_adapter_state();

    // ==================== Scanning ====================

    /** Selects the BLE profile: 0=Meshtastic, 1=MeshCore Companion. */
    void meshble_set_profile(int profile);

    /**
     * Starts BLE scanning with a service UUID filter for the selected profile.
     * @return 0 on success, negative on error
     */
    int meshble_start_scan(DeviceCallback callback);

    /** Stops BLE scanning. */
    void meshble_stop_scan();

    // ==================== Connection ====================

    /**
     * Connects to a BLE device. Blocks until GATT discovery or timeout.
     * @return 0=OK, -1=timeout, -2=not found, -3=GATT error, -4=access denied
     */
    int meshble_connect(String address, int timeoutMs);

    /** Returns the latest native BLE error detail, or {@code null}. */
    String meshble_get_last_error();

    /** Disconnects from the BLE device. */
    void meshble_disconnect();

    /** @return 1 when connected, otherwise 0 */
    int meshble_is_connected();

    // ==================== Data Transfer ====================

    /**
     * Writes protobuf bytes to the toRadio GATT characteristic.
     * @return 0 on success, negative on error
     */
    int meshble_write_to_radio(byte[] data, int length);

    /**
     * Reads fromRadio for the polling fallback.
     * @param buffer  output buffer
     * @param bufSize buffer size
     * @param outLen  int pointer receiving the number of bytes read
     * @return 0 on success
     */
    int meshble_read_from_radio(byte[] buffer, int bufSize, int[] outLen);

    /** Installs the listener for incoming fromRadio data. */
    void meshble_set_from_radio_listener(DataCallback callback);

    // ==================== Connection State ====================

    /** Installs the connection-state listener. */
    void meshble_set_state_listener(StateCallback callback);

    // ==================== Pairing ====================

    /** Installs the passkey request callback for app-managed BLE pairing. */
    void meshble_set_passkey_request_callback(PasskeyRequestCallback callback);

    /** Supplies the user-entered BLE passkey to the pending pairing request. */
    void meshble_respond_passkey(int passkey);

    /** Cancels the pending BLE pairing request. */
    void meshble_cancel_passkey();

    // ==================== Info ====================

    /** @return 1 when notifications are active, 0 when polling is used */
    int meshble_notifications_active();

    // ==================== Callback Interfaces ====================

    /**
     * Callback invoked when a BLE device is discovered during scanning.
     */
    interface DeviceCallback extends Callback {
        void callback(Pointer address, Pointer name, int rssi);
    }

    /**
     * Callback invoked when data arrives from the fromRadio characteristic.
     */
    interface DataCallback extends Callback {
        void callback(Pointer data, int length);
    }

    /**
     * Callback for BLE connection-state changes.
     * state: 0=connected, 1=disconnected, 2=error
     */
    interface StateCallback extends Callback {
        void callback(int state, String errorMsg);
    }

    /**
     * Callback for BLE passkey requests. Invoked only when WinRT pairing
     * actually requires the application to provide a PIN.
     */
    interface PasskeyRequestCallback extends Callback {
        void callback(String address);
    }
}
