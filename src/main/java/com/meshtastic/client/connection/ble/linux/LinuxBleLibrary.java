package com.meshtastic.client.connection.ble.linux;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * JNA-маппинг нативной библиотеки libmeshapp-ble.so (BlueZ BLE через sd-bus).
 * <p>
 * SO предоставляет плоский C API для работы с Linux Bluetooth LE:
 * сканирование, подключение, GATT чтение/запись через fd (AcquireNotify/AcquireWrite).
 * <p>
 * Все callbacks вызываются из worker thread — вызывающий код
 * должен обеспечить thread-safety.
 *
 * @see LinuxBle
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface LinuxBleLibrary extends Library {

    LinuxBleLibrary INSTANCE = loadLibrary();

    private static LinuxBleLibrary loadLibrary() {
        return Native.load("meshapp-ble", LinuxBleLibrary.class);
    }

    // ==================== Initialization ====================

    /** Инициализация sd-bus и BlueZ. Вызывать один раз. */
    int meshble_init();

    /** Освобождение всех ресурсов. */
    void meshble_cleanup();

    // ==================== Adapter State ====================

    /**
     * Состояние BLE-адаптера.
     * @return 0=UNKNOWN, 1=POWERED_OFF, 2=POWERED_ON, 3=UNSUPPORTED, 4=UNAUTHORIZED
     */
    int meshble_get_adapter_state();

    // ==================== Scanning ====================

    /** Настраивает BLE profile: -1=AUTO, 0=Meshtastic, 1=MeshCore Companion. */
    void meshble_set_profile(int profile);

    /**
     * Запуск сканирования BLE с фильтром по service UUID выбранного profile.
     * @return 0 при успехе, отрицательное при ошибке
     */
    int meshble_start_scan(DeviceCallback callback);

    /** Остановка сканирования. */
    void meshble_stop_scan();

    // ==================== Connection ====================

    /**
     * Подключение к BLE-устройству. Блокирует до обнаружения GATT или таймаута.
     * @return 0=OK, -1=timeout, -2=not found, -3=GATT error, -4=access denied
     */
    int meshble_connect(String address, int timeoutMs);

    /** Отключение от BLE-устройства. */
    void meshble_disconnect();

    /** @return 1 если подключено, 0 иначе */
    int meshble_is_connected();

    // ==================== Data Transfer ====================

    /**
     * Запись protobuf в toRadio GATT characteristic.
     * @return 0 при успехе, отрицательное при ошибке
     */
    int meshble_write_to_radio(byte[] data, int length);

    /**
     * Чтение fromRadio (для polling fallback).
     * @param buffer   выходной буфер
     * @param bufSize  размер буфера
     * @param outLen   указатель на int — количество прочитанных байт
     * @return 0 при успехе
     */
    int meshble_read_from_radio(byte[] buffer, int bufSize, int[] outLen);

    /** Установка слушателя входящих данных из fromRadio. */
    void meshble_set_from_radio_listener(DataCallback callback);

    // ==================== Connection State ====================

    /** Установка слушателя изменений состояния подключения. */
    void meshble_set_state_listener(StateCallback callback);

    // ==================== Logging ====================

    /** Установка callback для нативных лог-сообщений. */
    void meshble_set_log_callback(LogCallback callback);

    // ==================== Pairing ====================

    /** Установка callback для запроса passkey при pairing. */
    void meshble_set_passkey_request_callback(PasskeyRequestCallback callback);

    /** Ответить на запрос passkey. */
    void meshble_respond_passkey(int passkey);

    /** Отменить запрос passkey (пользователь отказался). */
    void meshble_cancel_passkey();

    // ==================== Info ====================

    /** @return 1 если notifications активны, 0 если polling */
    int meshble_notifications_active();

    // ==================== Callback Interfaces ====================

    /**
     * Callback обнаружения BLE-устройства при сканировании.
     */
    interface DeviceCallback extends Callback {
        void callback(String address, String name, int rssi);
    }

    /**
     * Callback получения данных из fromRadio characteristic.
     */
    interface DataCallback extends Callback {
        void callback(Pointer data, int length);
    }

    /**
     * Callback изменения состояния BLE-подключения.
     * state: 0=connected, 1=disconnected, 2=error
     */
    interface StateCallback extends Callback {
        void callback(int state, String errorMsg);
    }

    /**
     * Callback для нативных лог-сообщений (перенаправление в SLF4J).
     */
    interface LogCallback extends Callback {
        void invoke(String message);
    }

    /**
     * Callback запроса passkey при BLE pairing.
     */
    interface PasskeyRequestCallback extends Callback {
        void invoke(String deviceAddress);
    }
}
