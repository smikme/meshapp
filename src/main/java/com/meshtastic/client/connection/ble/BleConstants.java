package com.meshtastic.client.connection.ble;

/**
 * Константы BLE-протоколов Meshtastic и MeshCore Companion.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class BleConstants {

    /** UUID основного GATT-сервиса Meshtastic. */
    public static final String SERVICE_UUID = "6ba1b218-15a8-461f-9fa8-5dcae273eafd";

    /** Характеристика для чтения FromRadio protobuf (Read, Notify). */
    public static final String FROM_RADIO_UUID = "2c55e69e-4993-11ed-b878-0242ac120002";

    /** Характеристика для записи ToRadio protobuf (Write). */
    public static final String TO_RADIO_UUID = "f75c76d2-129e-4dad-a1dd-7866124401e7";

    /** Характеристика-счётчик: уведомляет о наличии новых данных (Read, Notify). */
    public static final String FROM_NUM_UUID = "ed9da18c-a800-4f66-a670-aa7547e34453";

    /** UUID GATT-сервиса MeshCore Companion. */
    public static final String MESHCORE_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";

    /** MeshCore RX characteristic: приложение пишет команды, прошивка читает. */
    public static final String MESHCORE_RX_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e";

    /** MeshCore TX characteristic: прошивка шлёт notifications, приложение читает. */
    public static final String MESHCORE_TX_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e";

    /** Таймаут подключения к периферии (мс). */
    public static final int CONNECT_TIMEOUT_MS = 20_000;

    /** Таймаут обнаружения GATT-сервисов после подключения (мс). */
    public static final int SERVICE_DISCOVERY_TIMEOUT_MS = 20_000;

    /** Длина заголовка serial-фрейма [0x94][0xC3][len_msb][len_lsb], который BLE не использует. */
    public static final int SERIAL_FRAME_HEADER_SIZE = 4;

    private BleConstants() {}
}
