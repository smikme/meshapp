package com.meshtastic.client.connection.ble;

/**
 * Обнаруженное BLE-устройство Meshtastic.
 *
 * @param address  адрес устройства (MAC на Linux/Windows, UUID на macOS)
 * @param name     рекламируемое имя (может быть {@code null})
 * @param rssi     уровень сигнала (dBm)
 */
public record BleDevice(String address, String name, int rssi) {

    /**
     * Возвращает отображаемое имя: рекламируемое имя, если есть, иначе адрес.
     */
    public String displayName() {
        return name != null && !name.isBlank() ? name : address;
    }
}
