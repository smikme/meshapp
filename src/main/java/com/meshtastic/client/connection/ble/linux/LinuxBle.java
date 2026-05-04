package com.meshtastic.client.connection.ble.linux;

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
 * Linux-реализация BLE через BlueZ (нативная SO libmeshapp-ble.so + JNA).
 * <p>
 * Делегирует все BLE-операции в {@link LinuxBleLibrary}, которая загружает
 * {@code libmeshapp-ble.so} — C-модуль с sd-bus + AcquireNotify/AcquireWrite.
 * <p>
 * Паттерн аналогичен {@link com.meshtastic.client.connection.ble.windows.WinBle}:
 * <ul>
 *   <li>Static JNA callbacks для защиты от GC</li>
 *   <li>Polling fallback (200ms) для вычитки fromRadio</li>
 *   <li>Drain chain после каждой записи</li>
 * </ul>
 *
 * @see LinuxBleLibrary
 * @see BlePlatform
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class LinuxBle implements BlePlatform {

    private static final Logger log = LoggerFactory.getLogger(LinuxBle.class);

    private static final int POLL_INTERVAL_MS = 200;
    private static final int POST_WRITE_DRAIN_ATTEMPTS = 10;
    private static final int POST_WRITE_DRAIN_INTERVAL_MS = 300;
    private static final int READ_BUFFER_SIZE = 512;

    private final LinuxBleLibrary lib;

    // Static JNA callbacks — prevent GC (same pattern as WinBle/MacOsBle)
    private static LinuxBleLibrary.LogCallback logCallback;
    private static LinuxBleLibrary.DeviceCallback scanCallback;
    private static LinuxBleLibrary.DataCallback dataCallback;
    private static LinuxBleLibrary.StateCallback stateCallback;
    private static LinuxBleLibrary.PasskeyRequestCallback passkeyCallback;

    private volatile Consumer<BleDevice> scanConsumer;
    private volatile Consumer<byte[]> fromRadioListener;
    private volatile Consumer<BleState> stateListener;
    private volatile Consumer<String> passkeyRequestHandler;
    private volatile boolean connected;
    private volatile BleProtocolProfile profile = BleProtocolProfile.MESHTASTIC;

    // Polling (fromRadio data also comes via fd notifications in native code,
    // but polling ensures nothing is missed — same approach as Windows/macOS)
    private final ScheduledExecutorService pollScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ble-linux-poll");
                t.setDaemon(true);
                return t;
            });
    private volatile ScheduledFuture<?> pollFuture;
    private volatile boolean drainInProgress;

    public LinuxBle() {
        try {
            lib = LinuxBleLibrary.INSTANCE;
        } catch (UnsatisfiedLinkError e) {
            log.error("Не удалось загрузить libmeshapp-ble.so: {}", e.getMessage());
            throw new UnsupportedOperationException(
                    "libmeshapp-ble.so не найден. BLE на Linux недоступен.", e);
        }

        int result = lib.meshble_init();
        if (result != 0) {
            throw new RuntimeException("BlueZ BLE инициализация не удалась: error=" + result);
        }

        // Forward native log_msg() to SLF4J (static callback = GC protection)
        logCallback = msg -> log.debug("[native] {}", msg);
        lib.meshble_set_log_callback(logCallback);

        // Passkey request callback (static, prevent GC)
        passkeyCallback = address -> {
            Consumer<String> handler = passkeyRequestHandler;
            if (handler != null && address != null) {
                handler.accept(address);
            }
        };
        lib.meshble_set_passkey_request_callback(passkeyCallback);

        log.info("BlueZ BLE инициализирован (нативная библиотека)");
    }

    // ==================== BlePlatform: Scanning ====================

    @Override
    public void startScan(Consumer<BleDevice> onDeviceFound) {
        this.scanConsumer = onDeviceFound;
        lib.meshble_set_profile(profile.nativeCode());

        // Static callback — prevent GC
        scanCallback = (address, name, rssi) -> {
            Consumer<BleDevice> consumer = scanConsumer;
            if (consumer != null && address != null) {
                String deviceName = (name != null) ? name : "Unknown";
                consumer.accept(new BleDevice(address, deviceName, rssi, profile.protocolType()));
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
        lib.meshble_set_profile(profile.nativeCode());

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
                if (lib.meshble_notifications_active() == 0) {
                    log.info("Запуск polling fromRadio ({}ms)", POLL_INTERVAL_MS);
                    startPolling();
                } else {
                    log.info("BLE notifications активны; polling не запускается");
                }
            }
            case -1 -> throw new ConnectionException("BLE таймаут или разрыв подключения: " + address);
            case -2 -> throw new ConnectionException("BLE устройство не найдено: " + address);
            case -3 -> throw new ConnectionException("GATT ошибка при подключении к: " + address);
            case -4 -> throw new ConnectionException(
                    "Доступ запрещён. Выполните сопряжение: bluetoothctl pair " + address);
            case -5 -> throw new ConnectionException("BLE подключение отменено: " + address);
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
    public boolean writeToRadio(byte[] protobufPayload) {
        if (!isConnected()) {
            log.warn("writeToRadio: не подключено");
            return false;
        }
        int result = lib.meshble_write_to_radio(protobufPayload, protobufPayload.length);
        if (result != 0) {
            log.error("writeToRadio failed: error={}", result);
            return false;
        } else {
            log.debug("Отправлено {} байт в toRadio", protobufPayload.length);
            scheduleDrainAfterWrite();
            return true;
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

    // ==================== BlePlatform: Pairing ====================

    @Override
    public void setPasskeyRequestHandler(Consumer<String> handler) {
        this.passkeyRequestHandler = handler;
    }

    public void respondPasskey(int passkey) {
        log.info("Responding to passkey request with PIN");
        lib.meshble_respond_passkey(passkey);
    }

    public void cancelPasskey() {
        log.info("Cancelling passkey request");
        lib.meshble_cancel_passkey();
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
    public void setProfile(BleProtocolProfile profile) {
        this.profile = profile == null ? BleProtocolProfile.MESHTASTIC : profile;
        lib.meshble_set_profile(this.profile.nativeCode());
    }

    @Override
    public BleProtocolProfile getProfile() {
        return profile;
    }

    @Override
    public void dispose() {
        stopPolling();
        pollScheduler.shutdownNow();
        lib.meshble_cleanup();
        log.info("LinuxBle disposed");
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
     * Guard drainInProgress предотвращает конкурентные чтения (как в WinBle/MacOsBle).
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
                log.debug("Linux BLE drained {} bytes from fromRadio", outLen[0]);

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
     * После каждого writeToRadio — короткий drain burst.
     * На BlueZ fromNum notifications могут не прийти, хотя WriteValue уже завершился успешно.
     */
    private void scheduleDrainAfterWrite() {
        for (int i = 1; i <= POST_WRITE_DRAIN_ATTEMPTS; i++) {
            long delayMs = (long) i * POST_WRITE_DRAIN_INTERVAL_MS;
            pollScheduler.schedule(this::pollFromRadio, delayMs, TimeUnit.MILLISECONDS);
        }
    }
}
