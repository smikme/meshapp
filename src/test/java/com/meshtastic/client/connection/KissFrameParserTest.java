package com.meshtastic.client.connection;

import com.meshtastic.client.protocol.meshcore.MeshCoreKissFrames;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KissFrameParserTest {

    @Test
    void parsesEscapedKissFrame() {
        KissFrameParser parser = new KissFrameParser();
        byte[] body = {
                (byte) MeshCoreKissFrames.CMD_SET_HARDWARE,
                (byte) MeshCoreKissFrames.REQ_GET_DEVICE_NAME,
                KissFrameParser.FEND,
                KissFrameParser.FESC
        };

        byte[] result = feed(parser, MeshCoreKissFrames.frame(body));

        assertArrayEquals(body, result);
        assertFalse(parser.hasPartialFrame());
    }

    @Test
    void ignoresBytesBeforeDelimiterAndParsesMultipleFrames() {
        KissFrameParser parser = new KissFrameParser();
        byte[] first = {(byte) MeshCoreKissFrames.CMD_SET_HARDWARE, (byte) MeshCoreKissFrames.RESP_PONG};
        byte[] second = {(byte) MeshCoreKissFrames.CMD_SET_HARDWARE, (byte) MeshCoreKissFrames.RESP_OK};
        byte[] framedFirst = MeshCoreKissFrames.frame(first);
        byte[] framedSecond = MeshCoreKissFrames.frame(second);

        List<byte[]> frames = new ArrayList<>();
        assertNull(parser.processByte((byte) 0x55));
        for (byte b : framedFirst) {
            byte[] frame = parser.processByte(b);
            if (frame != null) {
                frames.add(frame);
            }
        }
        for (byte b : framedSecond) {
            byte[] frame = parser.processByte(b);
            if (frame != null) {
                frames.add(frame);
            }
        }

        assertArrayEquals(first, frames.get(0));
        assertArrayEquals(second, frames.get(1));
    }

    @Test
    void resetDropsPartialFrame() {
        KissFrameParser parser = new KissFrameParser();

        parser.processByte(KissFrameParser.FEND);
        parser.processByte((byte) MeshCoreKissFrames.CMD_SET_HARDWARE);

        assertTrue(parser.hasPartialFrame());
        parser.reset();
        assertFalse(parser.hasPartialFrame());
    }

    private static byte[] feed(KissFrameParser parser, byte[] data) {
        byte[] result = null;
        for (byte b : data) {
            byte[] frame = parser.processByte(b);
            if (frame != null) {
                result = frame;
            }
        }
        return result;
    }
}
