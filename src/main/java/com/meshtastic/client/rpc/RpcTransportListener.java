package com.meshtastic.client.rpc;

/**
 * Receives serialized RPC messages from a {@link RpcTransport}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface RpcTransportListener {

    /**
     * Called when the transport receives one complete JSON RPC message.
     *
     * @param message serialized RPC envelope
     */
    void onMessage(String message);

    /** Called when the transport closes. */
    default void onClosed() {
    }

    /**
     * Called when the transport fails.
     *
     * @param message human-readable error
     * @param cause root cause, if available
     */
    default void onError(String message, Throwable cause) {
    }
}
