package com.meshtastic.client.connection.ble.windows;

import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ble.*;
import com.meshtastic.client.system.AppUi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Windows BLE implementation backed by WinRT through {@code meshapp-ble.dll}
 * and JNA.
 * <p>
 * All BLE operations are delegated to {@link WinBleLibrary}, which loads a
 * small C++/WinRT module with a flat C API. The structure mirrors the macOS BLE
 * implementation: each instance gets an isolated native library copy, JNA
 * callbacks are instance-owned to avoid GC and cross-connection clashes,
 * polling is used as a fallback when notifications are unavailable, and a drain
 * pass follows each write.
 *
 * @see WinBleLibrary
 * @see BlePlatform
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class WinBle implements BlePlatform {

    private static final Logger log = LoggerFactory.getLogger(WinBle.class);

    private static final int POLL_INTERVAL_MS = 200;
    private static final int READ_BUFFER_SIZE = 512;
    private static final long WRITE_WARN_THRESHOLD_MS = 2_000;

    private final WinBleLibrary lib;

    // Instance callbacks must stay strongly reachable while this isolated DLL copy is loaded.
    private WinBleLibrary.DeviceCallback scanCallback;
    private WinBleLibrary.DataCallback dataCallback;
    private WinBleLibrary.StateCallback stateCallback;
    private WinBleLibrary.PasskeyRequestCallback passkeyCallback;

    private volatile Consumer<BleDevice> scanConsumer;
    private volatile Consumer<byte[]> fromRadioListener;
    private volatile Consumer<BleState> stateListener;
    private volatile Consumer<String> passkeyRequestHandler;
    private volatile boolean connected;
    private volatile BleProtocolProfile profile = BleProtocolProfile.MESHTASTIC;

    // Polling (always active — fromRadio notifications unreliable on Windows)
    private final ScheduledExecutorService pollScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ble-win-poll");
                t.setDaemon(true);
                return t;
            });
    private volatile ScheduledFuture<?> pollFuture;
    private volatile boolean drainInProgress;
    private volatile int consecutiveWriteFailures;

    public WinBle() {
        try {
            lib = WinBleLibrary.loadIsolated();
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            log.error("Не удалось загрузить meshapp-ble.dll: {}", e.getMessage());
            throw new UnsupportedOperationException(
                    "meshapp-ble.dll не найден. BLE на Windows недоступен.", e);
        }

        int result = lib.meshble_init();
        if (result != 0) {
            throw new RuntimeException("WinRT BLE инициализация не удалась: error=" + result);
        }

        // The passkey callback lifts BLE pairing into the Java/UI layer, as Linux does.
        // WinRT knows when a PIN is required; the app answers through a dialog.
        passkeyCallback = address -> {
            try {
                Consumer<String> handler = passkeyRequestHandler;
                if (handler != null && address != null) {
                    handler.accept(address);
                }
            } catch (Throwable t) {
                log.warn("Windows BLE passkey callback failed", t);
            }
        };
        lib.meshble_set_passkey_request_callback(passkeyCallback);
        log.info("WinRT BLE инициализирован");
    }

    // ==================== BlePlatform: Scanning ====================

    @Override
    public void startScan(Consumer<BleDevice> onDeviceFound) {
        this.scanConsumer = onDeviceFound;
        lib.meshble_set_profile(profile.nativeCode());

        // Static callback — prevent GC
        scanCallback = (addressPtr, namePtr, rssi) -> {
            try {
                Consumer<BleDevice> consumer = scanConsumer;
                String address = utf8String(addressPtr);
                if (consumer != null && address != null) {
                    String name = utf8String(namePtr);
                    String deviceName = (name != null && !name.isBlank()) ? name : "Unknown";
                    consumer.accept(new BleDevice(address, deviceName, rssi, profile.protocolType()));
                }
            } catch (Throwable t) {
                log.warn("Windows BLE scan callback failed", t);
            }
        };

        int result = lib.meshble_start_scan(scanCallback);
        if (result != 0) {
            String message = "BLE scan не удалось запустить: error=" + result;
            log.error(message);
            throw new IllegalStateException(message);
        } else {
            log.info("BLE сканирование запущено");
        }
    }

    private static String utf8String(com.sun.jna.Pointer pointer) {
        return pointer == null ? null : pointer.getString(0, StandardCharsets.UTF_8.name());
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
            try {
                Consumer<byte[]> listener = fromRadioListener;
                if (listener != null && dataPtr != null && length > 0) {
                    byte[] bytes = dataPtr.getByteArray(0, length);
                    listener.accept(bytes);
                }
            } catch (Throwable t) {
                log.warn("Windows BLE data callback failed", t);
            }
        };

        // Setup state callback (static, prevent GC)
        stateCallback = (state, errorMsg) -> {
            try {
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
            } catch (Throwable t) {
                log.warn("Windows BLE state callback failed", t);
            }
        };
        lib.meshble_set_from_radio_listener(dataCallback);
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
            case -1 -> throw new ConnectionException("BLE таймаут подключения: " + address);
            case -2 -> throw new ConnectionException("BLE устройство не найдено: " + address);
            case -3 -> throw new ConnectionException("GATT ошибка при подключении к: " + address);
            case -4 -> throw new ConnectionException(
                    "BLE сопряжение не завершено или отклонено: " + address);
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
        long startedAt = System.nanoTime();
        String threadName = Thread.currentThread().getName();
        log.debug("writeToRadio: start {} bytes on thread {}", protobufPayload.length, threadName);
        int result = lib.meshble_write_to_radio(protobufPayload, protobufPayload.length);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        if (result != 0) {
            if (result == -2) {
                // AccessDenied means the current GATT session did not receive pairing/auth.
                // Do not stay connected, or writes would keep targeting a broken session.
                connected = false;
                stopPolling();
                lib.meshble_disconnect();
                log.error("writeToRadio: AccessDenied — BLE pairing is required or incomplete");
                AppUi.showStatus(AppUi.StatusType.ERROR,
                        "BLE: сопряжение не завершено. Подключитесь заново и подтвердите pairing");
                Consumer<BleState> sl = stateListener;
                if (sl != null) {
                    sl.accept(new BleState.Error(
                            "BLE сопряжение не завершено. Подключитесь заново и подтвердите pairing", null));
                }
                log.error("writeToRadio: AccessDenied after {} ms on thread {}", durationMs, threadName);
                return false;
            }
            int failures = ++consecutiveWriteFailures;
            if (failures == 5) {
                log.warn("writeToRadio: {} consecutive failures (lastDuration={} ms, thread={})",
                        failures, durationMs, threadName);
            } else if (failures <= 5) {
                log.error("writeToRadio failed: error={}, duration={} ms, thread={}",
                        result, durationMs, threadName);
            }
            return false;
        } else {
            consecutiveWriteFailures = 0;
            if (durationMs >= WRITE_WARN_THRESHOLD_MS) {
                log.warn("writeToRadio: slow success in {} ms ({} bytes, thread={})",
                        durationMs, protobufPayload.length, threadName);
            }
            log.debug("Отправлено {} байт в toRadio за {} мс", protobufPayload.length, durationMs);
            if (lib.meshble_notifications_active() == 0) {
                scheduleDrainAfterWrite();
            }
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

    @Override
    public void setPasskeyRequestHandler(Consumer<String> handler) {
        this.passkeyRequestHandler = handler;
    }

    @Override
    public void respondPasskey(int passkey) {
        log.info("Responding to Windows BLE passkey request");
        lib.meshble_respond_passkey(passkey);
    }

    @Override
    public void cancelPasskey() {
        log.info("Cancelling Windows BLE passkey request");
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
     * Polling path: read fromRadio until the response is empty.
     * The drainInProgress guard prevents concurrent reads, as in MacOsBle.
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
     * Runs an additional drain 200 ms after each writeToRadio call.
     * This mirrors MacOsBle and speeds up responses to outgoing requests.
     */
    private void scheduleDrainAfterWrite() {
        pollScheduler.schedule(this::pollFromRadio, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }
}
