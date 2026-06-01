package com.meshtastic.client.connection;

import java.util.function.Consumer;

/**
 * Low-level transport connection used by protocol adapters.
 * <p>
 * Implementations own only the byte-stream lifecycle: open, close, write bytes
 * prepared by the active protocol, and deliver inbound payloads upward. Business
 * logic for a specific radio network or protocol does not belong here.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface TransportConnection {

    /**
     * Opens the transport connection.
     *
     * @throws ConnectionException when the transport cannot be opened
     */
    void connect() throws ConnectionException;

    /** Closes the transport. The method is safe to call more than once. */
    void disconnect();

    /**
     * @return {@code true} when the transport is open and writable
     */
    boolean isConnected();

    /**
     * Sends bytes already prepared by the active protocol through the transport.
     *
     * @param data bytes prepared by the active protocol adapter
     */
    void sendBytes(byte[] data);

    /**
     * Sends bytes and tells transports with receive watchdogs whether this write
     * should expect inbound activity.
     *
     * @param data bytes prepared by the active protocol adapter
     * @param expectResponseAfterWrite {@code true} for ordinary requests,
     *                                 {@code false} for keepalive or heartbeat writes
     */
    default void sendBytes(byte[] data, boolean expectResponseAfterWrite) {
        sendBytes(data);
    }

    /**
     * Registers a listener for protocol payloads extracted by the transport
     * framing layer from TCP, Serial, or BLE input.
     *
     * @param listener callback for received payload bytes, or {@code null}
     */
    void setDataListener(Consumer<byte[]> listener);

    /**
     * Registers a listener for transport lifecycle events.
     *
     * @param listener connection, disconnection, and error listener, or {@code null}
     */
    void setConnectionListener(ConnectionListener listener);
}
