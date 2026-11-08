package com.meshtastic.client.connection;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

class MeshCoreCompanionFrameParserTest {
    @Test
    void officialHeaderAndBackToBackPacketsNeedNoSilence() {
        byte[] wire = {
            0x3e,
            5,
            0,
            2,
            1,
            0,
            0,
            0,
            0x3e,
            9,
            0,
            (byte) 0x82,
            1,
            2,
            3,
            4,
            5,
            6,
            0,
            0,
            0x3e,
            5,
            0,
            4,
            1,
            0,
            0,
            0
        };
        for (int split = 0; split <= wire.length; split++) {
            var p = new MeshCoreCompanionFrameParser();
            var out = new ArrayList<byte[]>();
            feed(p, Arrays.copyOfRange(wire, 0, split), out);
            assertNull(p.flushPartialFrame());
            feed(p, Arrays.copyOfRange(wire, split, wire.length), out);
            assertEquals(List.of(5, 9, 5), out.stream().map(b -> b.length).toList());
            assertFalse(p.hasPartialFrame());
            assertArrayEquals(new byte[] {(byte) 0x82, 1, 2, 3, 4, 5, 6, 0, 0}, out.get(1));
        }
    }

    @Test
    void unknownPayloadDoesNotChangeFraming() {
        var p = new MeshCoreCompanionFrameParser();
        var out = new ArrayList<byte[]>();
        feed(p, new byte[] {0x3e, 3, 0, (byte) 0xfe, 0x3e, 0, 0x3e, 1, 0, 10}, out);
        assertEquals(2, out.size());
        assertArrayEquals(new byte[] {(byte) 0xfe, 0x3e, 0}, out.getFirst());
    }

    @Test
    void noiseInvalidLengthAndTimedOutPartialRecover() {
        var now = new AtomicLong();
        var p = new MeshCoreCompanionFrameParser(now::get);
        var out = new ArrayList<byte[]>();
        feed(
                p,
                new byte[] {0x55, 0x66, 0x3e, 0, 0, 0x3e, (byte) 255, (byte) 255, 0x3e, 4, 0, 2},
                out);
        assertTrue(p.hasPartialFrame());
        now.set(5001);
        assertNull(p.flushPartialFrame());
        assertFalse(p.hasPartialFrame());
        feed(p, new byte[] {0x3e, 1, 0, 10}, out);
        assertEquals(1, out.size());
        assertArrayEquals(new byte[] {10}, out.getFirst());
    }

    @Test
    void outgoingUsesHostDirectionAndLittleEndianLength() {
        assertArrayEquals(
                new byte[] {0x3c, 2, 0, 0x16, 3},
                MeshCoreCompanionFrameParser.encode(new byte[] {0x16, 3}));
        assertThrows(
                IllegalArgumentException.class,
                () -> MeshCoreCompanionFrameParser.encode(new byte[177]));
    }

    private static void feed(MeshCoreCompanionFrameParser p, byte[] bytes, List<byte[]> out) {
        for (byte b : bytes) {
            byte[] frame = p.processByte(b);
            if (frame != null) out.add(frame);
        }
    }
}
