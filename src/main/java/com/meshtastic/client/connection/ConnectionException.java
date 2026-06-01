package com.meshtastic.client.connection;

/**
 * Exception thrown when a connection cannot be established.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class ConnectionException extends Exception {

    public ConnectionException(String message) {
        super(message);
    }

    public ConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
