package com.meshtastic.client.model;

/**
 * Transport type used to connect to a Meshtastic device.
 * <p>
 * Gson serializes enum values by name, preserving compatibility with existing JSON.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public enum ConnectionType {
    TCP,
    SERIAL,
    BLE,
    REMOTE_RPC
}
