package com.meshtastic.client.model;

import java.util.UUID;

/**
 * Профиль подключения к устройству или endpoint-у поддерживаемого протокола.
 * <p>
 * Сериализуется в JSON ({@code ~/.meshapp/connections.json}) через Gson.
 * Поддерживает три типа транспорта: TCP (host + port), Serial (portName + baudRate)
 * и BLE (address + deviceName).
 * <p>
 * Поле {@code type} может быть {@code null} для legacy-записей —
 * в этом случае {@link #getEffectiveType()} возвращает {@link ConnectionType#TCP}.
 * Новые профили по умолчанию используют {@link ProtocolType#MESHTASTIC}.
 * Поле {@code protocol} может быть {@code null} для legacy-записей —
 * в этом случае {@link #getEffectiveProtocol()} возвращает {@link ProtocolType#MESHTASTIC}.
 * Поля {@code connected} и {@code reconnecting} помечены как {@code transient} —
 * не сохраняются, отражают текущее runtime-состояние.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class ConnectionEntry {

    private String id;
    private String name;
    private ProtocolType protocol;
    private ConnectionType type;
    private String host;
    private int port;
    private String portName;
    private int baudRate;
    private SerialModemLineMode serialModemLineMode;
    private String bleAddress;
    private String bleDeviceName;
    private String nodeId;
    private transient boolean connected;
    private transient boolean reconnecting;

    public ConnectionEntry() {
        this.id = UUID.randomUUID().toString();
        this.protocol = ProtocolType.MESHTASTIC;
        this.port = 4403;
    }

    /** Конструктор для TCP-подключения. */
    public ConnectionEntry(String name, String host, int port) {
        this.id = UUID.randomUUID().toString();
        this.protocol = ProtocolType.MESHTASTIC;
        this.type = ConnectionType.TCP;
        this.name = name;
        this.host = host;
        this.port = port;
    }

    /** Конструктор для Serial-подключения (USB / Bluetooth SPP). */
    public ConnectionEntry(String name, String portName, int baudRate, ConnectionType type) {
        this.id = UUID.randomUUID().toString();
        this.protocol = ProtocolType.MESHTASTIC;
        this.type = type;
        this.name = name;
        this.portName = portName;
        this.baudRate = baudRate;
    }

    /** Конструктор для BLE-подключения. */
    public ConnectionEntry(String name, String bleAddress, String bleDeviceName) {
        this.id = UUID.randomUUID().toString();
        this.protocol = ProtocolType.MESHTASTIC;
        this.type = ConnectionType.BLE;
        this.name = name;
        this.bleAddress = bleAddress;
        this.bleDeviceName = bleDeviceName;
    }

    /**
     * Возвращает эффективный тип подключения.
     * Для legacy-записей (type == null) возвращает {@link ConnectionType#TCP}.
     */
    public ConnectionType getEffectiveType() {
        return type != null ? type : ConnectionType.TCP;
    }

    /**
     * Возвращает эффективный протокол подключения.
     * Для legacy-записей (protocol == null) возвращает {@link ProtocolType#MESHTASTIC}.
     */
    public ProtocolType getEffectiveProtocol() {
        return protocol != null ? protocol : ProtocolType.MESHTASTIC;
    }

    /**
     * Возвращает режим DTR/RTS для serial-подключения.
     * Для legacy-записей без поля возвращает {@link SerialModemLineMode#AUTO}.
     */
    public SerialModemLineMode getEffectiveSerialModemLineMode() {
        return serialModemLineMode != null ? serialModemLineMode : SerialModemLineMode.AUTO;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * Возвращает явно сохранённый протокол подключения.
     * <p>
     * Для старых JSON-записей может быть {@code null}; в бизнес-логике обычно
     * нужно использовать {@link #getEffectiveProtocol()}.
     *
     * @return сохранённый тип протокола или {@code null}
     */
    public ProtocolType getProtocol() {
        return protocol;
    }

    /**
     * Задаёт протокол, который будет поднят поверх выбранного транспорта.
     *
     * @param protocol тип протокола для сохранения в профиле подключения
     */
    public void setProtocol(ProtocolType protocol) {
        this.protocol = protocol;
    }

    public ConnectionType getType() {
        return type;
    }

    public void setType(ConnectionType type) {
        this.type = type;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getPortName() {
        return portName;
    }

    public void setPortName(String portName) {
        this.portName = portName;
    }

    public int getBaudRate() {
        return baudRate;
    }

    public void setBaudRate(int baudRate) {
        this.baudRate = baudRate;
    }

    public SerialModemLineMode getSerialModemLineMode() {
        return serialModemLineMode;
    }

    /**
     * Задаёт режим DTR/RTS для serial-подключения.
     *
     * @param serialModemLineMode сохранённый режим или {@code null} для legacy-compatible {@code AUTO}
     */
    public void setSerialModemLineMode(SerialModemLineMode serialModemLineMode) {
        this.serialModemLineMode = serialModemLineMode;
    }

    public String getBleAddress() {
        return bleAddress;
    }

    public void setBleAddress(String bleAddress) {
        this.bleAddress = bleAddress;
    }

    public String getBleDeviceName() {
        return bleDeviceName;
    }

    public void setBleDeviceName(String bleDeviceName) {
        this.bleDeviceName = bleDeviceName;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public boolean isReconnecting() {
        return reconnecting;
    }

    public void setReconnecting(boolean reconnecting) {
        this.reconnecting = reconnecting;
    }
}
