package com.meshtastic.client.model;

import java.util.UUID;

/**
 * Saved connection profile for a device or supported protocol endpoint.
 * <p>
 * Profiles are serialized to {@code ~/.meshapp/connections.json} through Gson
 * and support TCP, Serial, and BLE transports. Legacy entries may not contain
 * {@code type} or {@code protocol}; effective accessors provide TCP and
 * Meshtastic defaults for those cases. Runtime flags such as {@code connected}
 * and {@code reconnecting} are transient and are not persisted.
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
    private boolean autoconnect;
    private transient boolean connected;
    private transient boolean reconnecting;

    public ConnectionEntry() {
        this.id = UUID.randomUUID().toString();
        this.protocol = ProtocolType.MESHTASTIC;
        this.port = 4403;
    }

    /** Constructor for TCP connections. */
    public ConnectionEntry(String name, String host, int port) {
        this.id = UUID.randomUUID().toString();
        this.protocol = ProtocolType.MESHTASTIC;
        this.type = ConnectionType.TCP;
        this.name = name;
        this.host = host;
        this.port = port;
    }

    /** Constructor for Serial connections over USB or Bluetooth SPP. */
    public ConnectionEntry(String name, String portName, int baudRate, ConnectionType type) {
        this.id = UUID.randomUUID().toString();
        this.protocol = ProtocolType.MESHTASTIC;
        this.type = type;
        this.name = name;
        this.portName = portName;
        this.baudRate = baudRate;
    }

    /** Constructor for BLE connections. */
    public ConnectionEntry(String name, String bleAddress, String bleDeviceName) {
        this.id = UUID.randomUUID().toString();
        this.protocol = ProtocolType.MESHTASTIC;
        this.type = ConnectionType.BLE;
        this.name = name;
        this.bleAddress = bleAddress;
        this.bleDeviceName = bleDeviceName;
    }

    /**
     * Returns the effective connection type.
     * Legacy entries with {@code type == null} are treated as TCP.
     */
    public ConnectionType getEffectiveType() {
        return type != null ? type : ConnectionType.TCP;
    }

    /**
     * Returns the effective connection protocol.
     * Legacy entries with {@code protocol == null} are treated as Meshtastic.
     */
    public ProtocolType getEffectiveProtocol() {
        return protocol != null ? protocol : ProtocolType.MESHTASTIC;
    }

    /**
     * Returns the DTR/RTS mode for Serial connections.
     * Legacy entries without the field use {@link SerialModemLineMode#AUTO}.
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
     * Returns the protocol value stored in the profile.
     * <p>
     * Older JSON entries may contain {@code null}; business logic should usually
     * use {@link #getEffectiveProtocol()} instead.
     *
     * @return stored protocol type, or {@code null}
     */
    public ProtocolType getProtocol() {
        return protocol;
    }

    /**
     * Sets the protocol that will run over the selected transport.
     *
     * @param protocol protocol type to store in the connection profile
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
     * Sets the DTR/RTS mode for Serial connections.
     *
     * @param serialModemLineMode stored mode, or {@code null} for legacy-compatible {@code AUTO}
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

    public boolean isAutoconnect() {
        return autoconnect;
    }

    public void setAutoconnect(boolean autoconnect) {
        this.autoconnect = autoconnect;
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
