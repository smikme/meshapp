package com.meshtastic.client.connection.ble;

import com.meshtastic.client.model.ProtocolType;

/**
 * Обнаруженное BLE-устройство.
 *
 * @param address  адрес устройства (MAC на Linux/Windows, UUID на macOS)
 * @param name     рекламируемое имя (может быть {@code null})
 * @param rssi     уровень сигнала (dBm)
 * @param protocolType протокол, если он известен из режима сканирования
 */
public record BleDevice(String address, String name, int rssi, ProtocolType protocolType) {

    public BleDevice(String address, String name, int rssi) {
        this(address, name, rssi, ProtocolType.AUTO);
    }

    /**
     * Возвращает отображаемое имя: рекламируемое имя, если есть, иначе адрес.
     */
    public String displayName() {
        return name != null && !name.isBlank() ? name : address;
    }
}
