package com.meshtastic.client.connection;

import com.meshtastic.client.connection.ble.BleConnection;
import com.meshtastic.client.connection.ble.BlePlatform;
import com.meshtastic.client.connection.ble.BleProtocolProfile;
import com.meshtastic.client.connection.rpc.DirectRpcTransportConnection;
import com.meshtastic.client.connection.rpc.ExternalRouterRpcTransportConnection;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.model.RemoteRpcConnectionMode;

import java.util.function.Supplier;

/**
 * Factory for transport connections created from saved connection profiles.
 * <p>
 * This class knows only how bytes are delivered through TCP, Serial, or BLE.
 * Protocol selection, handshake, and higher-level services remain in
 * {@code CommunicationProtocol} implementations.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class TransportConnectionFactory {

    private TransportConnectionFactory() {
    }

    /**
     * Creates a transport implementation for a connection profile.
     *
     * @param entry connection profile from {@code connections.json}
     * @param blePlatformSupplier supplier for the platform BLE backend, invoked only for BLE connections
     * @return transport object ready for {@link TransportConnection#connect()}
     */
    public static TransportConnection create(ConnectionEntry entry, Supplier<BlePlatform> blePlatformSupplier) {
        return create(entry, blePlatformSupplier, false);
    }

    /**
     * Creates a transport implementation for a connection profile.
     *
     * @param entry connection profile from {@code connections.json}
     * @param blePlatformSupplier supplier for the platform BLE backend, invoked only for BLE connections
     * @param disposeBlePlatformOnDisconnect whether to dispose the BLE backend with the transport
     * @return transport object ready for {@link TransportConnection#connect()}
     */
    public static TransportConnection create(ConnectionEntry entry,
                                             Supplier<BlePlatform> blePlatformSupplier,
                                             boolean disposeBlePlatformOnDisconnect) {
        return switch (entry.getEffectiveType()) {
            case TCP -> new TcpConnection(
                    entry.getHost(),
                    entry.getPort(),
                    FrameFormat.forProtocol(entry.getEffectiveProtocol())
            );
            case SERIAL -> new SerialConnection(
                    entry.getPortName(),
                    entry.getBaudRate() > 0 ? entry.getBaudRate() : SerialConnection.DEFAULT_BAUD_RATE,
                    FrameFormat.forProtocol(entry.getEffectiveProtocol()),
                    entry.getEffectiveSerialModemLineMode()
            );
            case BLE -> new BleConnection(
                    entry.getBleAddress(),
                    blePlatformSupplier.get(),
                    BleProtocolProfile.forProtocol(entry.getEffectiveProtocol()),
                    disposeBlePlatformOnDisconnect
            );
            case REMOTE_RPC -> entry.getEffectiveRemoteRpcMode() == RemoteRpcConnectionMode.ROUTER
                    ? new ExternalRouterRpcTransportConnection(
                            entry.getHost(),
                            entry.getPort(),
                            entry.getRemoteAccessKey())
                    : new DirectRpcTransportConnection(
                            entry.getHost(),
                            entry.getPort(),
                            entry.getRemoteAccessKey());
        };
    }

    /**
     * Builds a readable transport description for logs.
     *
     * @param entry connection profile
     * @return transport type and key parameters
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
            case REMOTE_RPC -> String.format("type=REMOTE_RPC, mode=%s, host=%s, port=%d",
                    entry.getEffectiveRemoteRpcMode(), safeText(entry.getHost()), entry.getPort());
        };
    }

    private static String safeText(String value) {
        return value == null || value.isBlank() ? "?" : value.trim();
    }
}
