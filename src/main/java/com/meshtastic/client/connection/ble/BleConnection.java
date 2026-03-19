package com.meshtastic.client.connection.ble;

import com.meshtastic.client.components.PasskeyDialog;
import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.connection.MeshtasticConnection;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
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

    private final String address;
    private final BlePlatform platform;

    private volatile Consumer<byte[]> dataListener;
    private volatile ConnectionListener connectionListener;
    private volatile boolean connected;

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
    public synchronized void sendBytes(byte[] data) {
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

        if (platform.writeToRadio(payload)) {
            log.debug("Sent {} bytes to BLE device {}", payload.length, address);
        } else {
            log.warn("Failed to send {} bytes to BLE device {}", payload.length, address);
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
}
