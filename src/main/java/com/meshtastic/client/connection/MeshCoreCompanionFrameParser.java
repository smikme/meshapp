package com.meshtastic.client.connection;

import java.util.Arrays;
import java.util.function.LongSupplier;

/** Length-delimited radio-to-host Companion stream (MeshCore 81dbae5). BLE has no header. */
public final class MeshCoreCompanionFrameParser implements StreamFrameParser {
    public static final int MAX_FRAME_SIZE = 176;
    private static final long PARTIAL_TIMEOUT_MS = 5_000;
    private final LongSupplier clock;
    private final byte[] payload = new byte[MAX_FRAME_SIZE];
    private int phase, length, received;
    private long lastByteAt;

    public MeshCoreCompanionFrameParser() {
        this(System::currentTimeMillis);
    }

    public MeshCoreCompanionFrameParser(LongSupplier clock) {
        this.clock = clock;
    }

    @Override
    public byte[] processByte(byte b) {
        expire();
        lastByteAt = clock.getAsLong();
        int value = b & 255;
        switch (phase) {
            case 0 -> {
                if (value == 0x3e) phase = 1;
            }
            case 1 -> {
                length = value;
                phase = 2;
            }
            case 2 -> {
                length |= value << 8;
                if (length == 0 || length > MAX_FRAME_SIZE) reset();
                else {
                    received = 0;
                    phase = 3;
                }
            }
            case 3 -> {
                payload[received++] = b;
                if (received == length) {
                    byte[] result = Arrays.copyOf(payload, length);
                    reset();
                    return result;
                }
            }
            default -> throw new IllegalStateException();
        }
        return null;
    }

    @Override
    public boolean hasPartialFrame() {
        return phase != 0;
    }

    @Override
    public byte[] flushPartialFrame() {
        expire();
        return null;
    }

    @Override
    public void reset() {
        phase = length = received = 0;
    }

    private void expire() {
        if (phase != 0 && clock.getAsLong() - lastByteAt >= PARTIAL_TIMEOUT_MS) reset();
    }

    /** Host-to-radio frame; callers must not apply it to a BLE payload. */
    public static byte[] encode(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAX_FRAME_SIZE)
            throw new IllegalArgumentException("Companion payload must contain 1..176 bytes");
        byte[] framed = new byte[payload.length + 3];
        framed[0] = 0x3c;
        framed[1] = (byte) payload.length;
        framed[2] = (byte) (payload.length >>> 8);
        System.arraycopy(payload, 0, framed, 3, payload.length);
        return framed;
    }
}
