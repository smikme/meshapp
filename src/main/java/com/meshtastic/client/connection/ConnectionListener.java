package com.meshtastic.client.connection;

/**
 * Listener for connection lifecycle events.
 * Callback methods are invoked from the connection-management thread, not the UI thread.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface ConnectionListener {

    /** Called after a connection is established successfully. */
    void onConnected();

    /** Called after a normal disconnection. */
    void onDisconnected();

    /**
     * Called when the connection fails.
     *
     * @param message error description
     * @param cause cause exception, or {@code null}
     */
    void onConnectionError(String message, Throwable cause);
}
