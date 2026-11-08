package com.meshtastic.client.protocol.meshcore;

import java.util.*;

/** CayenneLPP 1.6.1 (the pinned firmware dependency). Multi-byte values are big endian. */
public final class MeshCoreTelemetry {
    private MeshCoreTelemetry() {}

    public record Reading(int channel, int type, List<Double> values, byte[] raw) {
        public Reading {
            values = List.copyOf(values);
            raw = raw.clone();
        }

        @Override
        public byte[] raw() {
            return raw.clone();
        }

        @Override
        public String toString() {
            return "channel="
                    + channel
                    + " type="
                    + type
                    + " "
                    + (values.isEmpty() ? HexFormat.of().formatHex(raw) : values);
        }
    }

    public static List<Reading> decode(byte[] data) {
        List<Reading> result = new ArrayList<>();
        for (int pos = 0; pos < data.length; ) {
            if (data.length - pos < 2)
                throw new IllegalArgumentException("Truncated CayenneLPP header");
            int channel = data[pos++] & 255, type = data[pos++] & 255;
            int size =
                    switch (type) {
                        case 0, 1, 102, 104, 120, 142 -> 1;
                        case 2, 3, 101, 103, 115, 116, 117, 121, 125, 128, 132 -> 2;
                        case 100, 118, 130, 131, 133 -> 4;
                        case 113, 134 -> 6;
                        case 135 -> 3;
                        case 136 -> 9;
                        case 240 -> pos < data.length ? data[pos] & 255 : 0;
                        default -> -1;
                    };
            if (size < 0) {
                result.add(
                        new Reading(
                                channel,
                                type,
                                List.of(),
                                Arrays.copyOfRange(data, pos, data.length)));
                break;
            }
            if (size == 0 || size > data.length - pos)
                throw new IllegalArgumentException("Truncated CayenneLPP value");
            List<Double> values = new ArrayList<>();
            if (type == 113 || type == 134) {
                for (int axis = 0; axis < 3; axis++)
                    values.add(
                            number(data, pos + axis * 2, 2, true) / (type == 113 ? 1000.0 : 100.0));
            } else if (type == 136) {
                values.add(number(data, pos, 3, true) / 10000.0);
                values.add(number(data, pos + 3, 3, true) / 10000.0);
                values.add(number(data, pos + 6, 3, true) / 100.0);
            } else if (type == 135) {
                for (int c = 0; c < 3; c++) values.add((double) (data[pos + c] & 255));
            } else if (type != 240) {
                boolean signed = type == 2 || type == 3 || type == 103 || type == 121;
                double scale =
                        switch (type) {
                            case 2, 3, 116 -> 100;
                            case 103, 115 -> 10;
                            case 104 -> 2;
                            case 117, 130, 131 -> 1000;
                            default -> 1;
                        };
                values.add(number(data, pos, size, signed) / scale);
            }
            result.add(
                    new Reading(channel, type, values, Arrays.copyOfRange(data, pos, pos + size)));
            pos += size;
        }
        return List.copyOf(result);
    }

    private static long number(byte[] b, int offset, int size, boolean signed) {
        long value = 0;
        for (int i = 0; i < size; i++) value = (value << 8) | (b[offset + i] & 255);
        if (signed && (b[offset] & 128) != 0) value -= 1L << (size * 8);
        return value;
    }
}
