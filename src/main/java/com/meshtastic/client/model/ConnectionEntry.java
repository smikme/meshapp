package com.meshtastic.client.model;

import java.util.UUID;

/**
 * Профиль подключения к Meshtastic-устройству (host + port).
 * <p>
 * Сериализуется в JSON ({@code ~/.meshapp/connections.json}) через Gson.
 * Поле {@code connected} помечено как {@code transient} — не сохраняется,
 * отражает текущее runtime-состояние. По умолчанию порт {@code 4403}
 * (стандартный TCP-порт Meshtastic).
 */
public class ConnectionEntry {

    private String id;
    private String name;
    private String host;
    private int port;
    private transient boolean connected;
    private transient boolean reconnecting;

    public ConnectionEntry() {
        this.id = UUID.randomUUID().toString();
        this.port = 4403;
    }

    public ConnectionEntry(String name, String host, int port) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.host = host;
        this.port = port;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public boolean isReconnecting() {
        return reconnecting;
    }

    public void setReconnecting(boolean reconnecting) {
        this.reconnecting = reconnecting;
    }
}
