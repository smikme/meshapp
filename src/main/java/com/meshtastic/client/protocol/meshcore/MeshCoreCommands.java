package com.meshtastic.client.protocol.meshcore;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/** Validated Companion command encoders; layouts from firmware 81dbae5. */
public final class MeshCoreCommands {
    private MeshCoreCommands() {}

    public static byte[] bytes(int command, int... values) {
        byte[] b = new byte[values.length + 1];
        b[0] = (byte) command;
        for (int i = 0; i < values.length; i++) b[i + 1] = (byte) values[i];
        return b;
    }

    public static byte[] withBody(int command, byte[] body) {
        if (body.length > 175) throw new IllegalArgumentException("Command body exceeds 175 bytes");
        byte[] b = new byte[body.length + 1];
        b[0] = (byte) command;
        System.arraycopy(body, 0, b, 1, body.length);
        return b;
    }

    public static byte[] key(String text) {
        String hex = text == null ? "" : text.trim().replaceFirst("^mc:", "");
        if (!hex.matches("[0-9a-fA-F]{64}"))
            throw new IllegalArgumentException("Expected a full 32-byte public key");
        return HexFormat.of().parseHex(hex);
    }

    public static byte[] keyCommand(int command, byte[] key) {
        if (key.length != 32) throw new IllegalArgumentException("Expected a full public key");
        return withBody(command, key);
    }

    public static byte[] text(int command, String text, int maxBytes) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        if (b.length == 0 || b.length > maxBytes || text.indexOf(0) >= 0)
            throw new IllegalArgumentException(
                    "Expected 1.." + maxBytes + " UTF-8 bytes without NUL");
        return withBody(command, b);
    }

    public static byte[] time(long seconds) {
        if (seconds < 0 || seconds > 0xffff_ffffL)
            throw new IllegalArgumentException("Time outside uint32 range");
        byte[] b = new byte[5];
        b[0] = 6;
        MeshCoreCompanionFrames.writeIntLe(b, 1, seconds);
        return b;
    }

    public static byte[] position(double lat, double lon) {
        if (!Double.isFinite(lat)
                || !Double.isFinite(lon)
                || Math.abs(lat) > 90
                || Math.abs(lon) > 180) throw new IllegalArgumentException("Invalid coordinates");
        byte[] b = new byte[9];
        b[0] = 14;
        MeshCoreCompanionFrames.writeIntLe(b, 1, Math.round(lat * 1e6));
        MeshCoreCompanionFrames.writeIntLe(b, 5, Math.round(lon * 1e6));
        return b;
    }

    public static byte[] radio(double mhz, double bandwidthKhz, int sf, int cr, boolean repeat) {
        if (!Double.isFinite(mhz)
                || mhz < 150
                || mhz > 2500
                || !Double.isFinite(bandwidthKhz)
                || bandwidthKhz < 7
                || bandwidthKhz > 500
                || sf < 5
                || sf > 12
                || cr < 5
                || cr > 8) throw new IllegalArgumentException("Invalid radio parameters");
        byte[] b = new byte[12];
        b[0] = 11;
        MeshCoreCompanionFrames.writeIntLe(b, 1, Math.round(mhz * 1000));
        MeshCoreCompanionFrames.writeIntLe(b, 5, Math.round(bandwidthKhz * 1000));
        b[9] = (byte) sf;
        b[10] = (byte) cr;
        b[11] = (byte) (repeat ? 1 : 0);
        return b;
    }

    public static byte[] pin(int pin) {
        if (pin != 0 && (pin < 100000 || pin > 999999))
            throw new IllegalArgumentException("PIN must be zero or six digits");
        byte[] b = new byte[5];
        b[0] = 37;
        MeshCoreCompanionFrames.writeIntLe(b, 1, pin);
        return b;
    }

    public static byte[] channel(int index, String name, byte[] secret) {
        byte[] n = (name == null ? "" : name).getBytes(StandardCharsets.UTF_8);
        if (index < 0
                || index > 255
                || n.length > 31
                || secret.length != 16
                || name != null && name.indexOf(0) >= 0)
            throw new IllegalArgumentException(
                    "Invalid channel slot, name (max 31 UTF-8 bytes), or key");
        if (n.length > 0 && Arrays.equals(secret, new byte[16]))
            throw new IllegalArgumentException("Named channels require a nonzero 16-byte key");
        byte[] b = new byte[50];
        b[0] = 32;
        b[1] = (byte) index;
        System.arraycopy(n, 0, b, 2, n.length);
        System.arraycopy(secret, 0, b, 34, 16);
        return b;
    }

    public static byte[] channelKey(String name, String key) {
        if (key != null && !key.isBlank()) {
            byte[] secret =
                    key.matches("[0-9a-fA-F]{32}")
                            ? HexFormat.of().parseHex(key)
                            : Base64.getDecoder().decode(key);
            if (secret.length != 16)
                throw new IllegalArgumentException("Channel secret must contain 16 bytes");
            return secret;
        }
        if ("Public".equals(name)) return Base64.getDecoder().decode("izOH6cXN6mrJ5e26oRXNcg==");
        if (name != null && name.startsWith("#"))
            return Arrays.copyOf(HexFormat.of().parseHex(MeshCoreStorage.digest(name)), 16);
        byte[] secret = new byte[16];
        new java.security.SecureRandom().nextBytes(secret);
        return secret;
    }

    public static byte[] contact(MeshCoreContact contact) {
        byte[] b = contact.packet();
        b[0] = 9;
        return b;
    }

    public static byte[] login(byte[] key, String password) {
        byte[] p = password.getBytes(StandardCharsets.UTF_8);
        if (key.length != 32 || p.length > 140 || password.indexOf(0) >= 0)
            throw new IllegalArgumentException("Invalid login key/password");
        byte[] body = Arrays.copyOf(key, 32 + p.length);
        System.arraycopy(p, 0, body, 32, p.length);
        return withBody(26, body);
    }
}
