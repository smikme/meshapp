package com.meshtastic.client.protocol;

import com.google.protobuf.MessageLite;

/**
 * Creates framed serial packets for Meshtastic devices.
 * Frame format: [0x94][0xC3][len_msb][len_lsb][protobuf payload]
 */
public class PacketFramer {

    private PacketFramer() {} // utility class

    private static final byte START_BYTE_1 = (byte) 0x94;
    private static final byte START_BYTE_2 = (byte) 0xC3;

    /**
     * Оборачивает protobuf-сообщение в фрейм Meshtastic.
     *
     * @param message protobuf-сообщение для фреймирования
     * @return байтовый массив с заголовком (4 байта) и payload
     */
    public static byte[] frame(MessageLite message) {
        byte[] payload = message.toByteArray();
        byte[] frame = new byte[4 + payload.length];
        frame[0] = START_BYTE_1;
        frame[1] = START_BYTE_2;
        frame[2] = (byte) ((payload.length >> 8) & 0xFF);
        frame[3] = (byte) (payload.length & 0xFF);
        System.arraycopy(payload, 0, frame, 4, payload.length);
        return frame;
    }
}
