package com.meshtastic.client.connection.ble;

/**
 * BLE connection state event.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public sealed interface BleState {

    /** Connection is established and GATT characteristics are ready. */
    record Connected() implements BleState {}

    /** Connection has been closed. */
    record Disconnected() implements BleState {}

    /** Connection error. */
    record Error(String message, Throwable cause) implements BleState {}
}
