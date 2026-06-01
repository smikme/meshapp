package com.meshtastic.client.connection.ble;

import com.meshtastic.client.model.ProtocolType;

/**
 * Discovered BLE device.
 *
 * @param address device address, MAC on Linux/Windows or UUID on macOS
 * @param name advertised name, or {@code null}
 * @param rssi signal level in dBm
 * @param protocolType protocol when known from scan mode
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record BleDevice(String address, String name, int rssi, ProtocolType protocolType) {

    public BleDevice(String address, String name, int rssi) {
        this(address, name, rssi, null);
    }

    /**
     * Returns the display name: advertised name when available, otherwise address.
     */
    public String displayName() {
        return name != null && !name.isBlank() ? name : address;
    }
}
