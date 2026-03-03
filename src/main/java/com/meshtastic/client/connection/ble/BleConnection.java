package com.meshtastic.client.connection.ble;

import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.connection.MeshtasticConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    @Override
    public void connect() throws ConnectionException {
        log.info("Connecting to BLE device: {}", address);

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
                    ConnectionListener listener = connectionListener;
                    if (listener != null) { listener.onConnected(); }
                }
                case BleState.Disconnected ignored -> {
                    connected = false;
                    ConnectionListener listener = connectionListener;
                    if (listener != null) { listener.onDisconnected(); }
                }
                case BleState.Error e -> {
                    connected = false;
                    log.error("BLE error: {}", e.message(), e.cause());
                    ConnectionListener listener = connectionListener;
                    if (listener != null) { listener.onConnectionError(e.message(), e.cause()); }
                }
            }
        });

        platform.connect(address);
        connected = true;
        log.info("Connected to BLE device: {}", address);

        ConnectionListener listener = connectionListener;
        if (listener != null) {
            listener.onConnected();
        }
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

        platform.writeToRadio(payload);
        log.debug("Sent {} bytes to BLE device {}", payload.length, address);
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
