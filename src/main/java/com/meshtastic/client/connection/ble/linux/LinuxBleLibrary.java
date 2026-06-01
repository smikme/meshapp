package com.meshtastic.client.connection.ble.linux;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.meshtastic.client.utils.NativeResourceLoader;

import java.nio.file.Path;

/**
 * JNA mapping for the native libmeshapp-ble.so library, which implements BlueZ BLE through sd-bus.
 * <p>
 * The shared object exposes a flat C API for Linux Bluetooth LE operations:
 * scanning, connecting, and GATT read/write through file descriptors
 * obtained with AcquireNotify/AcquireWrite.
 * <p>
 * All callbacks are invoked from a worker thread, so callers must provide their
 * own thread-safety boundary.
 *
 * @see LinuxBle
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface LinuxBleLibrary extends Library {

    static LinuxBleLibrary loadIsolated() {
        Path libraryPath = NativeResourceLoader.extractLibraryResource("meshapp-ble");
        return Native.load(libraryPath.toAbsolutePath().toString(), LinuxBleLibrary.class);
    }

    static LinuxBleLibrary loadShared() {
        return Native.load("meshapp-ble", LinuxBleLibrary.class);
    }

    // ==================== Initialization ====================

    /** Initializes sd-bus and BlueZ. Call once. */
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
     * Connects to a BLE device. Blocks until GATT discovery, timeout, or a terminal error.
     * @return 0=OK, -1=timeout/disconnect, -2=not found, -3=GATT error,
     *         -4=access denied, -5=cancelled
     */
    int meshble_connect(String address, int timeoutMs);

    /** Disconnects from the BLE device. */
    void meshble_disconnect();

    /** @return 1 when connected, otherwise 0 */
    int meshble_is_connected();

    // ==================== Data Transfer ====================

    /**
     * Writes protobuf bytes to the toRadio GATT characteristic.
     * @return 0 on success, -4 if BLE bonding failed authentication/MITM, otherwise negative on error
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

    // ==================== Logging ====================

    /** Installs the callback for native log messages. */
    void meshble_set_log_callback(LogCallback callback);

    // ==================== Pairing ====================

    /** Installs the passkey-request callback used during pairing. */
    void meshble_set_passkey_request_callback(PasskeyRequestCallback callback);

    /** Responds to a passkey request. */
    void meshble_respond_passkey(int passkey);

    /** Cancels a passkey request after user refusal. */
    void meshble_cancel_passkey();

    // ==================== Info ====================

    /** @return 1 when notifications are active, 0 when polling is used */
    int meshble_notifications_active();

    // ==================== Callback Interfaces ====================

    /**
     * Callback invoked when a BLE device is discovered during scanning.
     */
    interface DeviceCallback extends Callback {
        void callback(String address, String name, int rssi);
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
     * Callback for native log messages, forwarded to SLF4J.
     */
    interface LogCallback extends Callback {
        void invoke(String message);
    }

    /**
     * Callback invoked when BLE pairing requires a passkey.
     */
    interface PasskeyRequestCallback extends Callback {
        void invoke(String deviceAddress);
    }
}
