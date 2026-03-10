package com.meshtastic.client.connection.ble.windows;

import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ble.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Windows-реализация BLE через WinRT (нативная DLL meshapp-ble.dll + JNA).
 * <p>
 * Делегирует все BLE-операции в {@link WinBleLibrary}, которая загружает
 * {@code meshapp-ble.dll} — маленький C++/WinRT модуль с плоским C API.
 * <p>
 * Паттерн аналогичен {@link com.meshtastic.client.connection.ble.macos.MacOsBle}:
 * <ul>
 *   <li>Static JNA callbacks для защиты от GC</li>
 *   <li>Polling fallback (200ms) при недоступности notifications</li>
 *   <li>Drain chain после каждой записи</li>
 * </ul>
 *
 * @see WinBleLibrary
 * @see BlePlatform
 */
public class WinBle implements BlePlatform {

    private static final Logger log = LoggerFactory.getLogger(WinBle.class);

    private static final int POLL_INTERVAL_MS = 200;
    private static final int READ_BUFFER_SIZE = 512;

    private final WinBleLibrary lib;

    // Static JNA callbacks — prevent GC (same pattern as MacOsBle)
    private static WinBleLibrary.DeviceCallback scanCallback;
    private static WinBleLibrary.DataCallback dataCallback;
    private static WinBleLibrary.StateCallback stateCallback;

    private volatile Consumer<BleDevice> scanConsumer;
    private volatile Consumer<byte[]> fromRadioListener;
    private volatile Consumer<BleState> stateListener;
    private volatile boolean connected;

    // Polling fallback (when notifications unavailable)
    private final ScheduledExecutorService pollScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ble-win-poll");
                t.setDaemon(true);
                return t;
            });
    private volatile ScheduledFuture<?> pollFuture;
    private volatile boolean drainInProgress;

    public WinBle() {
        try {
            lib = WinBleLibrary.INSTANCE;
        } catch (UnsatisfiedLinkError e) {
            log.error("Не удалось загрузить meshapp-ble.dll: {}", e.getMessage());
            throw new UnsupportedOperationException(
                    "meshapp-ble.dll не найден. BLE на Windows недоступен.", e);
        }

        int result = lib.meshble_init();
        if (result != 0) {
            throw new RuntimeException("WinRT BLE инициализация не удалась: error=" + result);
        }
        log.info("WinRT BLE инициализирован");
    }

    // ==================== BlePlatform: Scanning ====================

    @Override
    public void startScan(Consumer<BleDevice> onDeviceFound) {
        this.scanConsumer = onDeviceFound;

        // Static callback — prevent GC
        scanCallback = (address, name, rssi) -> {
            Consumer<BleDevice> consumer = scanConsumer;
            if (consumer != null && address != null) {
                String deviceName = (name != null) ? name : "Unknown";
                consumer.accept(new BleDevice(address, deviceName, rssi));
            }
        };

        int result = lib.meshble_start_scan(scanCallback);
        if (result != 0) {
            log.error("BLE scan не удалось запустить: error={}", result);
        } else {
            log.info("BLE сканирование запущено");
        }
    }

    @Override
    public void stopScan() {
        scanConsumer = null;
        lib.meshble_stop_scan();
        log.info("BLE сканирование остановлено");
    }

    // ==================== BlePlatform: Connection ====================

    @Override
    public void connect(String address) throws ConnectionException {
        log.info("Подключение к BLE устройству: {}", address);

        // Setup data callback (static, prevent GC)
        dataCallback = (dataPtr, length) -> {
            Consumer<byte[]> listener = fromRadioListener;
            if (listener != null && dataPtr != null && length > 0) {
                byte[] bytes = dataPtr.getByteArray(0, length);
                listener.accept(bytes);
            }
        };
        lib.meshble_set_from_radio_listener(dataCallback);

        // Setup state callback (static, prevent GC)
        stateCallback = (state, errorMsg) -> {
            Consumer<BleState> sl = stateListener;
            switch (state) {
                case 0 -> { // connected
                    connected = true;
                    if (sl != null) { sl.accept(new BleState.Connected()); }
                }
                case 1 -> { // disconnected
                    connected = false;
                    stopPolling();
                    if (sl != null) { sl.accept(new BleState.Disconnected()); }
                }
                case 2 -> { // error
                    connected = false;
                    stopPolling();
                    String msg = errorMsg != null ? errorMsg : "BLE error";
                    if (sl != null) { sl.accept(new BleState.Error(msg, null)); }
                }
                default -> { /* unknown state code */ }
            }
        };
        lib.meshble_set_state_listener(stateCallback);

        // Blocking connect
        int result = lib.meshble_connect(address, BleConstants.CONNECT_TIMEOUT_MS);

        switch (result) {
            case 0 -> {
                connected = true;
                log.info("BLE подключено: {}", address);

                // Start polling if notifications are not active
                if (lib.meshble_notifications_active() == 0) {
                    log.info("Notifications недоступны, запуск polling ({}ms)", POLL_INTERVAL_MS);
                    startPolling();
                }
            }
            case -1 -> throw new ConnectionException("BLE таймаут подключения: " + address);
            case -2 -> throw new ConnectionException("BLE устройство не найдено: " + address);
            case -3 -> throw new ConnectionException("GATT ошибка при подключении к: " + address);
            case -4 -> throw new ConnectionException(
                    "Доступ запрещён. Выполните сопряжение устройства в настройках Bluetooth Windows: " + address);
            default -> throw new ConnectionException("BLE ошибка подключения (code=" + result + "): " + address);
        }
    }

    @Override
    public void disconnect() {
        connected = false;
        stopPolling();
        lib.meshble_disconnect();
        log.info("BLE отключено");
    }

    @Override
    public boolean isConnected() {
        return connected && lib.meshble_is_connected() == 1;
    }

    // ==================== BlePlatform: Data ====================

    @Override
    public void writeToRadio(byte[] protobufPayload) {
        if (!isConnected()) {
            log.warn("writeToRadio: не подключено");
            return;
        }
        int result = lib.meshble_write_to_radio(protobufPayload, protobufPayload.length);
        if (result != 0) {
            log.error("writeToRadio failed: error={}", result);
        } else {
            log.debug("Отправлено {} байт в toRadio", protobufPayload.length);
            // Schedule extra drain after write (same pattern as MacOsBle)
            scheduleDrainAfterWrite();
        }
    }

    @Override
    public void setFromRadioListener(Consumer<byte[]> listener) {
        this.fromRadioListener = listener;
    }

    @Override
    public void setStateListener(Consumer<BleState> listener) {
        this.stateListener = listener;
    }

    // ==================== BlePlatform: State ====================

    @Override
    public AdapterState getAdapterState() {
        int state = lib.meshble_get_adapter_state();
        return switch (state) {
            case 1 -> AdapterState.POWERED_OFF;
            case 2 -> AdapterState.POWERED_ON;
            case 3 -> AdapterState.UNSUPPORTED;
            case 4 -> AdapterState.UNAUTHORIZED;
            default -> AdapterState.UNKNOWN;
        };
    }

    @Override
    public void dispose() {
        stopPolling();
        pollScheduler.shutdownNow();
        lib.meshble_cleanup();
        log.info("WinBle disposed");
    }

    // ==================== Polling Fallback ====================

    private void startPolling() {
        stopPolling();
        pollFuture = pollScheduler.scheduleAtFixedRate(
                this::pollFromRadio, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopPolling() {
        ScheduledFuture<?> f = pollFuture;
        if (f != null) {
            f.cancel(false);
            pollFuture = null;
        }
    }

    /**
     * Polling: читаем fromRadio до получения пустого ответа.
     * Guard drainInProgress предотвращает конкурентные чтения (как в MacOsBle).
     */
    private void pollFromRadio() {
        if (!connected || drainInProgress) { return; }
        drainInProgress = true;

        try {
            byte[] buffer = new byte[READ_BUFFER_SIZE];
            int[] outLen = new int[1];

            for (int i = 0; i < 100; i++) { // Safety limit
                int result = lib.meshble_read_from_radio(buffer, buffer.length, outLen);
                if (result != 0 || outLen[0] == 0) { break; }

                byte[] data = new byte[outLen[0]];
                System.arraycopy(buffer, 0, data, 0, outLen[0]);

                Consumer<byte[]> listener = fromRadioListener;
                if (listener != null) {
                    listener.accept(data);
                }
            }
        } catch (Exception e) {
            log.warn("Polling fromRadio error", e);
        } finally {
            drainInProgress = false;
        }
    }

    /**
     * После каждого writeToRadio — дополнительный drain через 200ms.
     * Паттерн из MacOsBle: ускоряет получение ответа на отправленный запрос.
     */
    private void scheduleDrainAfterWrite() {
        pollScheduler.schedule(this::pollFromRadio, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }
}
