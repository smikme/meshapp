package com.meshtastic.client.connection;

import com.meshtastic.client.protocol.meshcore.MeshCoreCompanionFrames;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshCoreCompanionFrameParserTest {

    @Test
    void flushesVariableSelfInfoPacketOnSilence() {
        MeshCoreCompanionFrameParser parser = new MeshCoreCompanionFrameParser();
        byte[] packet = selfInfo("meshcore-stream");

        byte[] emitted = null;
        for (byte b : packet) {
            emitted = parser.processByte(b);
        }

        assertNull(emitted);
        assertTrue(parser.hasPartialFrame());
        assertArrayEquals(packet, parser.flushPartialFrame());
        assertFalse(parser.hasPartialFrame());
    }

    @Test
    void emitsFixedDeviceInfoPacketImmediately() {
        MeshCoreCompanionFrameParser parser = new MeshCoreCompanionFrameParser();
        byte[] packet = new byte[80];
        packet[0] = (byte) MeshCoreCompanionFrames.PACKET_DEVICE_INFO;
        packet[1] = 3;

        byte[] emitted = null;
        for (byte b : packet) {
            emitted = parser.processByte(b);
        }

        assertArrayEquals(packet, emitted);
        assertFalse(parser.hasPartialFrame());
    }

    @Test
    void eagerModeEmitsSelfInfoAtMinimumLengthForAutodetect() {
        MeshCoreCompanionFrameParser parser = new MeshCoreCompanionFrameParser(true);
        byte[] packet = selfInfo("meshcore-stream");

        byte[] emitted = null;
        for (byte b : packet) {
            emitted = parser.processByte(b);
            if (emitted != null) {
                break;
            }
        }

        assertTrue(emitted.length >= 58);
        assertEqualsPacketType(MeshCoreCompanionFrames.PACKET_SELF_INFO, emitted);
    }

    private static void assertEqualsPacketType(int expected, byte[] packet) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, packet[0] & 0xFF);
    }

    private static byte[] selfInfo(String name) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] packet = new byte[58 + nameBytes.length];
        packet[0] = (byte) MeshCoreCompanionFrames.PACKET_SELF_INFO;
        packet[1] = 1;
        packet[2] = 10;
        packet[3] = 20;
        for (int i = 0; i < 32; i++) {
            packet[4 + i] = (byte) i;
        }
        System.arraycopy(nameBytes, 0, packet, 58, nameBytes.length);
        return packet;
    }
}
