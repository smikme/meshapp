package com.meshtastic.client.connection.rpc;

import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.connection.TransportConnection;
import com.meshtastic.client.rpc.DirectRpcClient;
import com.meshtastic.client.rpc.RpcAccessKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Transport adapter for direct MeshApp host RPC sessions.
 * <p>
 * This is intentionally not a byte stream for Meshtastic/MeshCore frames. It
 * lets {@code ConnectionManager} own the lifecycle while
 * {@code RemoteRpcProtocolRuntime} works with the RPC client.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class DirectRpcTransportConnection implements TransportConnection {

    private static final Logger log = LoggerFactory.getLogger(DirectRpcTransportConnection.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final String host;
    private final int port;
    private final String accessKey;

    private volatile DirectRpcClient client;
    private volatile ConnectionListener connectionListener;

    public DirectRpcTransportConnection(String host, int port, String accessKey) {
        this.host = Objects.requireNonNullElse(host, "").trim();
        this.port = port;
        this.accessKey = Objects.requireNonNullElse(accessKey, "").trim();
    }

    @Override
    public void connect() throws ConnectionException {
        if (host.isBlank()) {
            throw new ConnectionException("RPC host is required");
        }
        if (port < 1 || port > 65_535) {
            throw new ConnectionException("RPC port must be between 1 and 65535");
        }
        if (accessKey.isBlank()) {
            throw new ConnectionException("RPC access key is required");
        }

        try {
            client = DirectRpcClient.connect(host, port, RpcAccessKey.parse(accessKey), CONNECT_TIMEOUT);
            log.info("Connected to MeshApp RPC host {}:{}", host, port);
            ConnectionListener listener = connectionListener;
            if (listener != null) {
                listener.onConnected();
            }
        } catch (IllegalArgumentException | IOException e) {
            throw new ConnectionException("Failed to connect to MeshApp RPC host " + host + ":" + port, e);
        }
    }

    @Override
    public void disconnect() {
        DirectRpcClient current = client;
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
        return client != null;
    }

    @Override
    public void sendBytes(byte[] data) {
        log.debug("Ignoring byte send on direct RPC transport ({} bytes)", data != null ? data.length : 0);
    }

    @Override
    public void setDataListener(Consumer<byte[]> listener) {
        // Remote RPC does not deliver radio byte payloads through TransportConnection.
    }

    @Override
    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }

    /**
     * @return active direct RPC client
     */
    public DirectRpcClient getClient() {
        DirectRpcClient current = client;
        if (current == null) {
            throw new IllegalStateException("Direct RPC client is not connected");
        }
        return current;
    }
}
