package com.meshtastic.client.connection.ble;

import com.meshtastic.client.connection.ConnectionException;

import java.util.function.Consumer;

/**
 * Platform-specific interface for BLE operations.
 * <p>
 * Implementations use native APIs through JNA:
 * <ul>
 *   <li>macOS: CoreBluetooth (CBCentralManager / CBPeripheral)</li>
 *   <li>Linux: BlueZ through D-Bus</li>
 *   <li>Windows: WinRT Bluetooth LE</li>
 * </ul>
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface BlePlatform {

    /** BLE adapter state. */
    enum AdapterState {
        UNKNOWN, POWERED_OFF, POWERED_ON, UNSUPPORTED, UNAUTHORIZED
    }

    /**
     * Starts BLE scanning. Devices with the current {@link BleProtocolProfile}
     * service UUID are passed to {@code onDeviceFound}; repeated discoveries
     * update RSSI.
 *
     * @param onDeviceFound callback for each discovered device
     */
    void startScan(Consumer<BleDevice> onDeviceFound);

    /** Stops scanning. */
    void stopScan();

    /**
     * Connects to a BLE device by address. Blocks until connection and
     * Meshtastic GATT characteristic discovery are complete.
 *
     * @param address device address, either MAC or CoreBluetooth UUID
     * @throws ConnectionException when connection fails
     */
    void connect(String address) throws ConnectionException;

    /**
     * Configures BLE service/characteristic UUIDs for the next scan/connect.
     * Default implementations remain in Meshtastic mode for compatibility.
 *
     * @param profile BLE protocol profile
     */
    default void setProfile(BleProtocolProfile profile) {}

    /**
     * @return current BLE backend profile
     */
    default BleProtocolProfile getProfile() {
        return BleProtocolProfile.MESHTASTIC;
    }

    /** Disconnects the current BLE connection. Safe to call repeatedly. */
    void disconnect();

    /** Checks whether the BLE connection is active and GATT characteristics are ready. */
    boolean isConnected();

    /**
     * Writes payload to the current BLE profile's outbound GATT characteristic.
     * Data is sent without serial framing, i.e. without the 0x94 0xC3 header.
 *
     * @param protobufPayload serialized protocol payload
     * @return true on successful write, false on error
     */
    boolean writeToRadio(byte[] protobufPayload);

    /**
     * Sets the listener for inbound characteristic payloads.
     * Invoked from the BLE notification or polling thread when data arrives.
 *
     * @param listener callback receiving protocol payloads
     */
    void setFromRadioListener(Consumer<byte[]> listener);

    /**
     * Sets the BLE connection-state listener.
 *
     * @param listener callback for state events
     */
    void setStateListener(Consumer<BleState> listener);

    /**
     * Returns the current BLE adapter state.
     */
    AdapterState getAdapterState();

    /**
     * Sets the passkey request handler for BLE pairing.
     * Called when a device requires a PIN for pairing. Platforms with
     * app-managed pairing then accept the response through
     * {@link #respondPasskey(int)} or {@link #cancelPasskey()}.
     * Default implementation is a noop for platforms without pairing support.
 *
     * @param handler callback with the device MAC address
     */
    default void setPasskeyRequestHandler(Consumer<String> handler) {}

    /**
     * Passes the user-entered BLE PIN to the platform backend.
     * Used only by implementations with app-managed pairing.
 *
     * @param passkey six-digit BLE passkey
     */
    default void respondPasskey(int passkey) {}

    /**
     * Cancels a pending BLE pairing request.
     * Default implementation is a noop for platforms without app-managed pairing.
     */
    default void cancelPasskey() {}

    /** Releases all native resources. */
    void dispose();
}
