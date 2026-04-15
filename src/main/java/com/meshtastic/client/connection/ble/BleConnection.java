package com.meshtastic.client.connection.ble;

import com.meshtastic.client.components.PasskeyDialog;
import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.connection.MeshtasticConnection;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * BLE-транспорт для Meshtastic-устройств.
 * <p>
 * Реализует {@link MeshtasticConnection}, делегируя BLE-операции
 * платформо-зависимому {@link BlePlatform}. В отличие от TCP/Serial,
 * BLE не использует serial-фрейминг ({@code [0x94][0xC3][len][payload]}) —
 * GATT-характеристики передают protobuf напрямую.
 * <p>
 * {@link #sendBytes(byte[])} ожидает фреймированные данные от
 * {@link com.meshtastic.client.protocol.PacketFramer} и автоматически
 * вырезает 4-байтный заголовок перед записью в toRadio-характеристику.
 */
public class BleConnection implements MeshtasticConnection {

    private static final Logger log = LoggerFactory.getLogger(BleConnection.class);
    private static final int SEND_QUEUE_CAPACITY = 256;
    private static final long WRITE_WARN_THRESHOLD_MS = 2_000;
    private static final long WRITE_ERROR_THRESHOLD_MS = 10_000;

    private final String address;
    private final BlePlatform platform;
    private final Object sendInfrastructureLock = new Object();
    private final AtomicLong writeSequence = new AtomicLong();
    private final AtomicLong connectionGeneration = new AtomicLong();
    private final AtomicReference<BleWriteDiagnostics> inFlightWrite = new AtomicReference<>();

    private volatile Consumer<byte[]> dataListener;
    private volatile ConnectionListener connectionListener;
    private volatile boolean connected;
    private volatile ThreadPoolExecutor writeExecutor;
    private volatile ScheduledExecutorService writeWatchdog;

    public BleConnection(String address, BlePlatform platform) {
        this.address = address;
        this.platform = platform;
    }

    /**
     * Подключает BLE transport, нормализуя различия платформенных backends:
     * часть реализаций шлёт {@link BleState.Connected} из native callbacks,
     * а часть считает успешным сам факт завершения {@link BlePlatform#connect(String)}.
     *
     * @throws ConnectionException если платформенный backend не смог завершить подключение
     */
    @Override
    public void connect() throws ConnectionException {
        log.info("Connecting to BLE device: {}", address);
        connectionGeneration.incrementAndGet();
        ensureSendInfrastructure();
        // Linux/Windows already emit BleState.Connected from native callbacks, while macOS
        // currently relies on connect() returning successfully. Keep one onConnected() for both.
        AtomicBoolean connectedEventDelivered = new AtomicBoolean(false);
        // If connect() synchronously surfaces Disconnected/Error, do not backfill success afterwards.
        AtomicBoolean terminalStateObserved = new AtomicBoolean(false);

        // Устанавливаем слушатели перед подключением — при reconnect они могут быть stale
        platform.setFromRadioListener(data -> {
            Consumer<byte[]> listener = dataListener;
            if (listener != null) {
                listener.accept(data);
            }
        });

        platform.setStateListener(state -> {
            switch (state) {
                case BleState.Connected ignored -> {
                    connected = true;
                    if (connectedEventDelivered.compareAndSet(false, true)) {
                        ConnectionListener listener = connectionListener;
                        if (listener != null) { listener.onConnected(); }
                    }
                }
                case BleState.Disconnected ignored -> {
                    connected = false;
                    terminalStateObserved.set(true);
                    ConnectionListener listener = connectionListener;
                    if (listener != null) { listener.onDisconnected(); }
                }
                case BleState.Error e -> {
                    connected = false;
                    terminalStateObserved.set(true);
                    log.error("BLE error: {}", e.message(), e.cause());
                    ConnectionListener listener = connectionListener;
                    if (listener != null) { listener.onConnectionError(e.message(), e.cause()); }
                }
            }
        });

        // Pairing UI поднимается в общий BLE-контракт: Linux/Windows могут запросить passkey
        // из native backend, а macOS просто никогда не вызовет этот handler.
        platform.setPasskeyRequestHandler(deviceAddress ->
                Platform.runLater(() ->
                        PasskeyDialog.show(deviceAddress,
                                platform::respondPasskey,
                                platform::cancelPasskey)));

        platform.connect(address);
        // Fallback for platforms that complete connect() successfully but do not emit Connected state.
        if (!terminalStateObserved.get() && connectedEventDelivered.compareAndSet(false, true)) {
            connected = true;
            ConnectionListener listener = connectionListener;
            if (listener != null) {
                listener.onConnected();
            }
        }
        log.info("Connected to BLE device: {}", address);
    }

    @Override
    public void disconnect() {
        connected = false;
        connectionGeneration.incrementAndGet();
        shutdownSendInfrastructure("disconnect");
        platform.setFromRadioListener(null);
        platform.setStateListener(null);
        platform.disconnect();
        log.info("Disconnected from BLE device: {}", address);

        ConnectionListener listener = connectionListener;
        if (listener != null) {
            listener.onDisconnected();
        }
    }

    @Override
    public boolean isConnected() {
        return connected && platform.isConnected();
    }

    /**
     * Отправляет данные на устройство. Входные данные содержат serial-фрейм
     * ({@code [0x94][0xC3][len_msb][len_lsb][payload]}), из которого
     * извлекается только payload для записи в toRadio-характеристику.
     *
     * @param data фреймированные данные (формат serial/TCP)
     */
    @Override
    public void sendBytes(byte[] data) {
        sendBytes(data, true);
    }

    @Override
    public void sendBytes(byte[] data, boolean expectResponseAfterWrite) {
        if (!isConnected()) {
            log.warn("Cannot send: BLE not connected to {}", address);
            return;
        }
        if (data.length <= BleConstants.SERIAL_FRAME_HEADER_SIZE) {
            log.warn("BLE send: data too short ({} bytes), expected > {} header bytes",
                    data.length, BleConstants.SERIAL_FRAME_HEADER_SIZE);
            return;
        }

        // Вырезаем 4-байтный serial-заголовок — BLE передаёт protobuf напрямую
        byte[] payload = new byte[data.length - BleConstants.SERIAL_FRAME_HEADER_SIZE];
        System.arraycopy(data, BleConstants.SERIAL_FRAME_HEADER_SIZE, payload, 0, payload.length);

        ensureSendInfrastructure();

        ThreadPoolExecutor executor = writeExecutor;
        if (executor == null || executor.isShutdown()) {
            log.warn("BLE send infrastructure is unavailable for {}", address);
            return;
        }

        long opId = writeSequence.incrementAndGet();
        long queuedAtNanos = System.nanoTime();
        long generation = connectionGeneration.get();
        String callerThread = Thread.currentThread().getName();
        int queueDepth = executor.getQueue().size();

        log.debug("Queued BLE write #{} ({} bytes, expectResponseAfterWrite={}, callerThread={}, queueDepth={})",
                opId, payload.length, expectResponseAfterWrite, callerThread, queueDepth);

        try {
            executor.execute(() -> performWrite(
                    opId, payload, expectResponseAfterWrite, callerThread, queuedAtNanos, generation));
        } catch (RejectedExecutionException e) {
            log.warn("BLE send queue rejected write #{} ({} bytes, queueDepth={})",
                    opId, payload.length, queueDepth, e);
        }
    }

    @Override
    public void setDataListener(Consumer<byte[]> listener) {
        this.dataListener = listener;
    }

    @Override
    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }

    private void ensureSendInfrastructure() {
        synchronized (sendInfrastructureLock) {
            if (writeExecutor == null || writeExecutor.isShutdown()) {
                writeExecutor = new ThreadPoolExecutor(
                        1,
                        1,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>(SEND_QUEUE_CAPACITY),
                        r -> {
                            Thread t = new Thread(r, "ble-send-" + threadSuffix());
                            t.setDaemon(true);
                            return t;
                        }
                );
            }
            if (writeWatchdog == null || writeWatchdog.isShutdown()) {
                writeWatchdog = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "ble-send-watchdog-" + threadSuffix());
                    t.setDaemon(true);
                    return t;
                });
            }
        }
    }

    private void shutdownSendInfrastructure(String reason) {
        BleWriteDiagnostics current = inFlightWrite.get();
        if (current != null) {
            log.warn("BLE write #{} is still in progress during {} (payload={} bytes, callerThread={}, writeThread={})",
                    current.id(), reason, current.payloadSize(), current.callerThread(), current.writeThread());
        }

        ThreadPoolExecutor executorToStop;
        ScheduledExecutorService watchdogToStop;
        synchronized (sendInfrastructureLock) {
            executorToStop = writeExecutor;
            watchdogToStop = writeWatchdog;
            writeExecutor = null;
            writeWatchdog = null;
        }

        if (executorToStop != null) {
            List<Runnable> dropped = executorToStop.shutdownNow();
            if (!dropped.isEmpty()) {
                log.info("Dropped {} queued BLE writes during {}", dropped.size(), reason);
            }
        }
        if (watchdogToStop != null) {
            watchdogToStop.shutdownNow();
        }
    }

    private void performWrite(long opId,
                              byte[] payload,
                              boolean expectResponseAfterWrite,
                              String callerThread,
                              long queuedAtNanos,
                              long generation) {
        if (generation != connectionGeneration.get()) {
            log.debug("Skipping stale BLE write #{} because connection generation changed", opId);
            return;
        }
        if (!isConnected()) {
            log.debug("Skipping BLE write #{} because transport is no longer connected", opId);
            return;
        }

        long startedAtNanos = System.nanoTime();
        long queueDelayMs = TimeUnit.NANOSECONDS.toMillis(startedAtNanos - queuedAtNanos);
        String writeThread = Thread.currentThread().getName();

        BleWriteDiagnostics diagnostics = new BleWriteDiagnostics(
                opId,
                payload.length,
                expectResponseAfterWrite,
                callerThread,
                writeThread,
                queuedAtNanos,
                startedAtNanos,
                generation
        );
        inFlightWrite.set(diagnostics);

        ScheduledFuture<?> warnFuture = scheduleWriteWatchdog(diagnostics, WRITE_WARN_THRESHOLD_MS, false);
        ScheduledFuture<?> errorFuture = scheduleWriteWatchdog(diagnostics, WRITE_ERROR_THRESHOLD_MS, true);

        log.debug("Starting BLE write #{} on {} after {} ms in queue (callerThread={}, expectResponseAfterWrite={})",
                opId, writeThread, queueDelayMs, callerThread, expectResponseAfterWrite);

        boolean success = false;
        Throwable failure = null;
        try {
            success = platform.writeToRadio(payload);
        } catch (Throwable t) {
            failure = t;
        } finally {
            if (warnFuture != null) {
                warnFuture.cancel(false);
            }
            if (errorFuture != null) {
                errorFuture.cancel(false);
            }
            inFlightWrite.compareAndSet(diagnostics, null);
        }

        long finishedAtNanos = System.nanoTime();
        long writeDurationMs = TimeUnit.NANOSECONDS.toMillis(finishedAtNanos - startedAtNanos);

        if (failure != null) {
            log.error("BLE write #{} crashed after {} ms (payload={} bytes, callerThread={}, writeThread={})",
                    opId, writeDurationMs, payload.length, callerThread, writeThread, failure);
            return;
        }

        if (success) {
            if (writeDurationMs >= WRITE_WARN_THRESHOLD_MS) {
                log.warn("BLE write #{} completed slowly in {} ms (payload={} bytes, callerThread={}, writeThread={})",
                        opId, writeDurationMs, payload.length, callerThread, writeThread);
            } else {
                log.debug("BLE write #{} completed in {} ms", opId, writeDurationMs);
            }
            log.debug("Sent {} bytes to BLE device {}", payload.length, address);
        } else {
            log.warn("BLE write #{} failed after {} ms (payload={} bytes, callerThread={}, writeThread={})",
                    opId, writeDurationMs, payload.length, callerThread, writeThread);
            log.warn("Failed to send {} bytes to BLE device {}", payload.length, address);
        }
    }

    private ScheduledFuture<?> scheduleWriteWatchdog(BleWriteDiagnostics diagnostics,
                                                     long thresholdMs,
                                                     boolean errorLevel) {
        ScheduledExecutorService watchdog = writeWatchdog;
        if (watchdog == null || watchdog.isShutdown()) {
            return null;
        }
        return watchdog.schedule(() -> {
            BleWriteDiagnostics current = inFlightWrite.get();
            if (current != diagnostics) {
                return;
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - diagnostics.startedAtNanos());
            if (errorLevel) {
                log.error("BLE write #{} is still blocked after {} ms (payload={} bytes, callerThread={}, writeThread={}, expectResponseAfterWrite={}, generation={})",
                        diagnostics.id(), elapsedMs, diagnostics.payloadSize(), diagnostics.callerThread(),
                        diagnostics.writeThread(), diagnostics.expectResponseAfterWrite(), diagnostics.generation());
            } else {
                log.warn("BLE write #{} is still in progress after {} ms (payload={} bytes, callerThread={}, writeThread={}, expectResponseAfterWrite={})",
                        diagnostics.id(), elapsedMs, diagnostics.payloadSize(), diagnostics.callerThread(),
                        diagnostics.writeThread(), diagnostics.expectResponseAfterWrite());
            }
        }, thresholdMs, TimeUnit.MILLISECONDS);
    }

    private String threadSuffix() {
        String sanitized = address == null ? "unknown" : address.replaceAll("[^A-Za-z0-9]+", "");
        if (sanitized.isEmpty()) {
            sanitized = "unknown";
        }
        return sanitized.length() > 12 ? sanitized.substring(sanitized.length() - 12) : sanitized;
    }

    private record BleWriteDiagnostics(long id,
                                       int payloadSize,
                                       boolean expectResponseAfterWrite,
                                       String callerThread,
                                       String writeThread,
                                       long queuedAtNanos,
                                       long startedAtNanos,
                                       long generation) {
    }
}
