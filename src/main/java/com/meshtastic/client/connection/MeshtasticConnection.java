package com.meshtastic.client.connection;

import java.util.function.Consumer;

public interface MeshtasticConnection {

    void connect() throws ConnectionException;

    void disconnect();

    boolean isConnected();

    void sendBytes(byte[] data);

    void setDataListener(Consumer<byte[]> listener);

    void setConnectionListener(ConnectionListener listener);
}
