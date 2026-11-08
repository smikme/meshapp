package com.meshtastic.client.protocol.meshcore;

public record MeshCoreChannel(int index, String name, byte[] secret) {
    public MeshCoreChannel {
        if (secret.length != 16) throw new IllegalArgumentException("Channel key must be 16 bytes");
        secret = secret.clone();
        name = name == null ? "" : name;
    }

    @Override
    public byte[] secret() {
        return secret.clone();
    }

    public boolean enabled() {
        return name != null && !name.isBlank();
    }

    @Override
    public String toString() {
        return index + ": " + (name == null ? "" : name);
    }
}
