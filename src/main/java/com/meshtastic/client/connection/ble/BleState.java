package com.meshtastic.client.connection.ble;

/**
 * Событие изменения состояния BLE-соединения.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public sealed interface BleState {

    /** Соединение установлено, GATT-характеристики готовы. */
    record Connected() implements BleState {}

    /** Соединение разорвано. */
    record Disconnected() implements BleState {}

    /** Ошибка соединения. */
    record Error(String message, Throwable cause) implements BleState {}
}
