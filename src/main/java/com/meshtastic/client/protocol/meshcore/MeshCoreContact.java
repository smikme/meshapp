package com.meshtastic.client.protocol.meshcore;

import java.util.Arrays;

/** Lossless contact snapshot. Path-length encoding is retained for multi-byte path hashes. */
public record MeshCoreContact(byte[] packet) {
    public MeshCoreContact {
        if (packet.length < 148) throw new IllegalArgumentException("Contact requires 148 bytes");
        packet = packet.clone();
    }

    @Override
    public byte[] packet() {
        return packet.clone();
    }

    public static MeshCoreContact create(
            byte[] key, String name, int type, int flags, double lat, double lon) {
        if (key.length != 32 || type < 1 || type > 4 || flags < 0 || flags > 255)
            throw new IllegalArgumentException("Invalid contact key, type or flags");
        byte[] n = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (n.length > 31 || name.indexOf(0) >= 0)
            throw new IllegalArgumentException("Contact name exceeds 31 UTF-8 bytes");
        byte[] position = MeshCoreCommands.position(lat, lon), packet = new byte[148];
        packet[0] = 3;
        System.arraycopy(key, 0, packet, 1, 32);
        packet[33] = (byte) type;
        packet[34] = (byte) flags;
        packet[35] = (byte) 255;
        System.arraycopy(n, 0, packet, 100, n.length);
        System.arraycopy(position, 1, packet, 136, 8);
        MeshCoreCompanionFrames.writeIntLe(packet, 144, System.currentTimeMillis() / 1000);
        return new MeshCoreContact(packet);
    }

    public MeshCoreContact withFavorite(boolean favorite) {
        byte[] copy = packet();
        copy[34] = (byte) (favorite ? flags() | 1 : flags() & ~1);
        return new MeshCoreContact(copy);
    }

    public byte[] publicKey() {
        return Arrays.copyOfRange(packet, 1, 33);
    }

    public String id() {
        return "mc:" + MeshCoreCompanionFrames.hex(publicKey());
    }

    public int type() {
        return packet[33] & 255;
    }

    public int flags() {
        return packet[34] & 255;
    }

    public int encodedPathLength() {
        return packet[35] & 255;
    }

    public byte[] path() {
        return Arrays.copyOfRange(packet, 36, 100);
    }

    public String name() {
        return MeshCoreCompanionFrames.nullTerminatedText(packet, 100, 32);
    }

    public long lastAdvert() {
        return MeshCoreCompanionFrames.unsignedIntLe(packet, 132);
    }

    public long lastModified() {
        return MeshCoreCompanionFrames.unsignedIntLe(packet, 144);
    }

    public double latitude() {
        return MeshCoreCompanionFrames.signedIntLe(packet, 136) / 1_000_000.0;
    }

    public double longitude() {
        return MeshCoreCompanionFrames.signedIntLe(packet, 140) / 1_000_000.0;
    }
}
