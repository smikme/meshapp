package com.meshtastic.client.protocol.meshcore;

/** Received protocol event; binary data remains available without inventing Meshtastic fields. */
public record MeshCoreEvent(long receivedAt, int type, byte[] payload) {
    public MeshCoreEvent {
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    public String name() {
        return switch (type) {
            case 0x1b -> "Channel data";
            case 0x84 -> "Raw data";
            case 0x85 -> "Login accepted";
            case 0x86 -> "Login rejected";
            case 0x87 -> "Remote status";
            case 0x88 -> "Radio RX";
            case 0x89 -> "Trace";
            case 0x8b -> "Telemetry";
            case 0x8c -> "Binary response";
            case 0x8d -> "Path discovery";
            case 0x8e -> "Control data";
            default -> "Event 0x%02X".formatted(type);
        };
    }

    @Override
    public String toString() {
        return java.time.Instant.ofEpochMilli(receivedAt)
                + " · "
                + name()
                + " · "
                + java.util.HexFormat.ofDelimiter(" ").formatHex(payload);
    }
}
