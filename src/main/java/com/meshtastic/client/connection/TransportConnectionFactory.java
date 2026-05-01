package com.meshtastic.client.connection;

import com.meshtastic.client.connection.ble.BleConnection;
import com.meshtastic.client.connection.ble.BlePlatform;
import com.meshtastic.client.connection.ble.BleProtocolProfile;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;

import java.util.function.Supplier;

/**
 * Фабрика транспортных соединений из сохранённых профилей подключения.
 * <p>
 * Класс знает только о способе доставки байтов (TCP, Serial, BLE). Настройка
 * протокола, handshake и сервисы более высокого уровня остаются в реализациях
 * {@code CommunicationProtocol}.
 */
public final class TransportConnectionFactory {

    private TransportConnectionFactory() {
    }

    /**
     * Создаёт transport-реализацию для указанного профиля подключения.
     *
     * @param entry профиль подключения из {@code connections.json}
     * @param blePlatformSupplier поставщик платформенного BLE backend-а; вызывается
     *                            только для BLE-подключений
     * @return transport-объект, готовый к вызову {@link TransportConnection#connect()}
     */
    public static TransportConnection create(ConnectionEntry entry, Supplier<BlePlatform> blePlatformSupplier) {
        FrameFormat frameFormat = FrameFormat.forProtocol(entry.getEffectiveProtocol());
        return switch (entry.getEffectiveType()) {
            case TCP -> new TcpConnection(entry.getHost(), entry.getPort(), frameFormat);
            case SERIAL -> new SerialConnection(
                    entry.getPortName(),
                    entry.getBaudRate() > 0 ? entry.getBaudRate() : SerialConnection.DEFAULT_BAUD_RATE,
                    frameFormat
            );
            case BLE -> new BleConnection(
                    entry.getBleAddress(),
                    blePlatformSupplier.get(),
                    BleProtocolProfile.forProtocol(entry.getEffectiveProtocol())
            );
        };
    }

    /**
     * Формирует человекочитаемое описание параметров транспорта для логов.
     *
     * @param entry профиль подключения
     * @return строка с типом транспорта и ключевыми параметрами
     */
    public static String describe(ConnectionEntry entry) {
        ConnectionType type = entry.getEffectiveType();
        return switch (type) {
            case TCP -> String.format("type=TCP, host=%s, port=%d",
                    safeText(entry.getHost()), entry.getPort());
            case SERIAL -> String.format("type=SERIAL, port=%s, baud=%d",
                    safeText(entry.getPortName()),
                    entry.getBaudRate() > 0 ? entry.getBaudRate() : SerialConnection.DEFAULT_BAUD_RATE);
            case BLE -> String.format("type=BLE, address=%s, deviceName=%s",
                    safeText(entry.getBleAddress()), safeText(entry.getBleDeviceName()));
        };
    }

    private static String safeText(String value) {
        return value == null || value.isBlank() ? "?" : value.trim();
    }
}
