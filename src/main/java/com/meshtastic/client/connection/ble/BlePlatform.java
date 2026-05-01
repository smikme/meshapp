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
     * Запускает BLE-сканирование. Найденные устройства с service UUID текущего
     * {@link BleProtocolProfile} передаются в {@code onDeviceFound}. Повторные
     * находки обновляют RSSI.
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

    /**
     * Настраивает BLE service/characteristic UUID для следующего scan/connect.
     * Реализации по умолчанию остаются в Meshtastic-режиме для совместимости.
     *
     * @param profile профиль BLE-протокола
     */
    default void setProfile(BleProtocolProfile profile) {}

    /**
     * @return текущий BLE-профиль backend-а
     */
    default BleProtocolProfile getProfile() {
        return BleProtocolProfile.MESHTASTIC;
    }

    /** Отключает текущее BLE-соединение. Безопасен при повторном вызове. */
    void disconnect();

    /** Проверяет, активно ли BLE-соединение и готовы ли GATT-характеристики. */
    boolean isConnected();

    /**
     * Записывает payload в outbound GATT characteristic текущего BLE-профиля.
     * Данные передаются без serial-фрейминга (без заголовка 0x94 0xC3).
     *
     * @param protobufPayload сериализованный протокольный payload
     * @return true при успешной записи, false при ошибке
     */
    boolean writeToRadio(byte[] protobufPayload);

    /**
     * Устанавливает слушателя входящих payload-ов из inbound characteristic.
     * Вызывается из потока BLE-уведомлений или polling при получении данных.
     *
     * @param listener callback для приёма протокольных payload-ов
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

    /**
     * Устанавливает обработчик запроса passkey при BLE pairing.
     * Вызывается когда устройство требует PIN-код для сопряжения.
     * Платформы с app-managed pairing затем принимают ответ через
     * {@link #respondPasskey(int)} или {@link #cancelPasskey()}.
     * По умолчанию — noop (платформы без поддержки pairing не реагируют).
     *
     * @param handler callback с MAC-адресом устройства
     */
    default void setPasskeyRequestHandler(Consumer<String> handler) {}

    /**
     * Передаёт платформенному backend введённый пользователем BLE PIN-код.
     * Используется только реализациями с app-managed pairing.
     *
     * @param passkey шестизначный BLE passkey
     */
    default void respondPasskey(int passkey) {}

    /**
     * Отменяет pending BLE pairing request.
     * По умолчанию — noop для платформ без app-managed pairing.
     */
    default void cancelPasskey() {}

    /** Освобождает все нативные ресурсы. */
    void dispose();
}
