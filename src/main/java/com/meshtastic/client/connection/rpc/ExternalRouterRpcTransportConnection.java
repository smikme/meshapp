package com.meshtastic.client.connection.rpc;

import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.rpc.ExternalRouterRpcClient;
import com.meshtastic.client.rpc.RpcAccessKey;
import com.meshtastic.client.rpc.RpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Transport adapter for MeshApp RPC sessions connected through ERPC Router.
 * <p>
 * This adapter plugs router-backed RPC into {@code ConnectionManager}. It does
 * not carry raw Meshtastic frames; higher layers use {@link #getRpcClient()} to
 * call the remote MeshApp Host.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ExternalRouterRpcTransportConnection implements RemoteRpcTransportConnection {

    private static final Logger log = LoggerFactory.getLogger(ExternalRouterRpcTransportConnection.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);

    private final String server;
    private final int port;
    private final String accessKey;
    private final AtomicBoolean disconnecting = new AtomicBoolean(false);

    private volatile ExternalRouterRpcClient client;
    private volatile ConnectionListener connectionListener;

    /**
     * Creates a router-backed MeshApp RPC transport connection.
     *
     * @param server router server address
     * @param port router fallback port when the server address has no port
     * @param accessKey shared MeshApp RPC access key
     */
    public ExternalRouterRpcTransportConnection(String server, int port, String accessKey) {
        this.server = Objects.requireNonNullElse(server, "").trim();
        this.port = port;
        this.accessKey = Objects.requireNonNullElse(accessKey, "").trim();
    }

    @Override
    public void connect() throws ConnectionException {
        if (server.isBlank()) {
            throw new ConnectionException("ERPC Router server is required");
        }
        if (port < 1 || port > 65_535) {
            throw new ConnectionException("ERPC Router port must be between 1 and 65535");
        }
        if (accessKey.isBlank()) {
            throw new ConnectionException("RPC access key is required");
        }

        try {
            disconnecting.set(false);
            client = ExternalRouterRpcClient.connect(
                    server,
                    port,
                    RpcAccessKey.parse(accessKey),
                    CONNECT_TIMEOUT,
                    this::notifyRemoteClosed,
                    this::notifyRemoteError);
            log.info("Connected to MeshApp RPC host through ERPC Router {}:{}", server, port);
            ConnectionListener listener = connectionListener;
            if (listener != null) {
                listener.onConnected();
            }
        } catch (IllegalArgumentException | IOException e) {
            throw new ConnectionException("Failed to connect through ERPC Router " + server + ":" + port, e);
        }
    }

    @Override
    public void disconnect() {
        disconnecting.set(true);
        ExternalRouterRpcClient current = client;
        client = null;
        if (current != null) {
            current.close();
        }
        ConnectionListener listener = connectionListener;
        if (listener != null) {
            listener.onDisconnected();
        }
    }

    @Override
    public boolean isConnected() {
        ExternalRouterRpcClient current = client;
        return current != null && current.isOpen();
    }

    @Override
    public void sendBytes(byte[] data) {
        log.debug("Ignoring byte send on ERPC Router RPC transport ({} bytes)", data != null ? data.length : 0);
    }

    @Override
    public void setDataListener(Consumer<byte[]> listener) {
        // Remote RPC does not deliver radio byte payloads through TransportConnection.
    }

    @Override
    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }

    @Override
    public RpcClient getRpcClient() {
        ExternalRouterRpcClient current = client;
        if (current == null) {
            throw new IllegalStateException("ERPC Router RPC client is not connected");
        }
        return current.rpcClient();
    }

    private void notifyRemoteClosed() {
        if (disconnecting.get()) {
            return;
        }
        client = null;
        ConnectionListener listener = connectionListener;
        if (listener != null) {
            listener.onDisconnected();
        }
    }

    private void notifyRemoteError(String message, Throwable error) {
        if (disconnecting.get()) {
            return;
        }
        client = null;
        ConnectionListener listener = connectionListener;
        if (listener != null) {
            listener.onConnectionError(message, error);
        }
    }
}
