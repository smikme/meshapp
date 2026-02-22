package com.meshtastic.client.connection;

public interface ConnectionListener {

    void onConnected();

    void onDisconnected();

    void onConnectionError(String message, Throwable cause);
}
