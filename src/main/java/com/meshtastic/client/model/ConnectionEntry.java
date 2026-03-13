package com.meshtastic.client.model;

import java.util.UUID;

/**
 * Профиль подключения к Meshtastic-устройству.
 * <p>
 * Сериализуется в JSON ({@code ~/.meshapp/connections.json}) через Gson.
 * Поддерживает два типа транспорта: TCP (host + port) и Serial (portName + baudRate).
 * <p>
 * Поле {@code type} может быть {@code null} для legacy-записей —
 * в этом случае {@link #getEffectiveType()} возвращает {@link ConnectionType#TCP}.
 * Поля {@code connected} и {@code reconnecting} помечены как {@code transient} —
 * не сохраняются, отражают текущее runtime-состояние.
 */
public class ConnectionEntry {

    private String id;
    private String name;
    private ConnectionType type;
    private String host;
    private int port;
    private String portName;
    private int baudRate;
    private String bleAddress;
    private String bleDeviceName;
    private String nodeId;
    private transient boolean connected;
    private transient boolean reconnecting;

    public ConnectionEntry() {
        this.id = UUID.randomUUID().toString();
        this.port = 4403;
    }

    /** Конструктор для TCP-подключения. */
    public ConnectionEntry(String name, String host, int port) {
        this.id = UUID.randomUUID().toString();
        this.type = ConnectionType.TCP;
        this.name = name;
        this.host = host;
        this.port = port;
    }

    /** Конструктор для Serial-подключения (USB / Bluetooth SPP). */
    public ConnectionEntry(String name, String portName, int baudRate, ConnectionType type) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.name = name;
        this.portName = portName;
        this.baudRate = baudRate;
    }

    /** Конструктор для BLE-подключения. */
    public ConnectionEntry(String name, String bleAddress, String bleDeviceName) {
        this.id = UUID.randomUUID().toString();
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
