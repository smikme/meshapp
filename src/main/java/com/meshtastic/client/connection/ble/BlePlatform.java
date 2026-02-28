package com.meshtastic.client.connection.ble;

import com.meshtastic.client.connection.ConnectionException;

import java.util.function.Consumer;

/**
 * Платформо-зависимый интерфейс BLE-операций.
 * <p>
 * Реализации используют нативные API через JNA:
 * <ul>
 *   <li>macOS — CoreBluetooth (CBCentralManager / CBPeripheral)</li>
 *   <li>Linux — BlueZ через D-Bus</li>
 *   <li>Windows — WinRT Bluetooth LE</li>
 * </ul>
 */
public interface BlePlatform {

    /** Состояние BLE-адаптера. */
    enum AdapterState {
        UNKNOWN, POWERED_OFF, POWERED_ON, UNSUPPORTED, UNAUTHORIZED
    }

    /**
     * Запускает BLE-сканирование. Найденные устройства с Meshtastic-сервисом
     * передаются в {@code onDeviceFound}. Повторные находки обновляют RSSI.
     *
     * @param onDeviceFound callback для каждого обнаруженного устройства
     */
    void startScan(Consumer<BleDevice> onDeviceFound);

    /** Останавливает сканирование. */
    void stopScan();

    /**
     * Подключается к BLE-устройству по адресу. Блокирует до завершения
     * подключения и обнаружения GATT-характеристик Meshtastic.
     *
     * @param address адрес устройства (MAC или CoreBluetooth UUID)
     * @throws ConnectionException если подключение не удалось
     */
    void connect(String address) throws ConnectionException;

    /** Отключает текущее BLE-соединение. Безопасен при повторном вызове. */
    void disconnect();

    /** Проверяет, активно ли BLE-соединение и готовы ли GATT-характеристики. */
    boolean isConnected();

    /**
     * Записывает protobuf-payload в toRadio-характеристику.
     * Данные передаются без serial-фрейминга (без заголовка 0x94 0xC3).
     *
     * @param protobufPayload сериализованный protobuf ToRadio
     */
    void writeToRadio(byte[] protobufPayload);

    /**
     * Устанавливает слушателя входящих FromRadio protobuf.
     * Вызывается из потока BLE-уведомлений при получении данных из fromRadio-характеристики.
     *
     * @param listener callback для приёма protobuf-данных
     */
    void setFromRadioListener(Consumer<byte[]> listener);

    /**
     * Устанавливает слушателя изменений состояния BLE-соединения.
     *
     * @param listener callback для событий состояния
     */
    void setStateListener(Consumer<BleState> listener);

    /**
     * Возвращает текущее состояние BLE-адаптера.
     */
    AdapterState getAdapterState();

    /** Освобождает все нативные ресурсы. */
    void dispose();
}
