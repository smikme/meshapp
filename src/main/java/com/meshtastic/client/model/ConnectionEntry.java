package com.meshtastic.client.model;

import java.util.UUID;

public class ConnectionEntry {

    private String id;
    private String name;
    private String host;
    private int port;
    private transient boolean connected;

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
}
