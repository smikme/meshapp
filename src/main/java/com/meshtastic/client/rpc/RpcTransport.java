package com.meshtastic.client.rpc;

/**
 * Message transport used by the MeshApp RPC layer.
 * <p>
 * Implementations move complete JSON text messages between two RPC peers. The
 * transport can later be a direct socket, WebSocket router tunnel, or a test
 * in-memory link; request dispatch and method handling stay above this layer.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface RpcTransport extends AutoCloseable {

    /**
     * Registers the listener that receives complete JSON RPC messages and
     * transport lifecycle notifications.
     *
     * @param listener listener to install, or {@code null}
     */
    void setListener(RpcTransportListener listener);

    /**
     * Sends one complete JSON RPC message.
     *
     * @param message serialized RPC envelope
     */
    void send(String message);

    /**
     * @return {@code true} when the transport is open for writes
     */
    boolean isOpen();

    /** Closes the transport. Safe to call more than once. */
    @Override
    void close();
}
