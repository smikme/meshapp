package com.meshtastic.client.connection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class FrameParserTest {

    private FrameParser parser;

    @BeforeEach
    void setUp() {
        parser = new FrameParser();
    }

    // Helpers

    /** Builds a valid frame: [0x94][0xC3][len_msb][len_lsb][payload...]. */
    private static byte[] buildFrame(byte[] payload) {
        byte[] frame = new byte[4 + payload.length];
        frame[0] = FrameParser.START_BYTE_1;
        frame[1] = FrameParser.START_BYTE_2;
        frame[2] = (byte) ((payload.length >> 8) & 0xFF);
        frame[3] = (byte) (payload.length & 0xFF);
        System.arraycopy(payload, 0, frame, 4, payload.length);
        return frame;
    }

    /** Feeds all bytes through processByte() and returns the first non-null result, or null. */
    private byte[] feedBytes(byte[] data) {
        for (byte b : data) {
            byte[] result = parser.processByte(b);
            if (result != null) return result;
        }
        return null;
    }

    /** Feeds all bytes and collects every non-null result, for multi-frame streams. */
    private List<byte[]> feedAllFrames(byte[] data) {
        List<byte[]> frames = new ArrayList<>();
        for (byte b : data) {
            byte[] result = parser.processByte(b);
            if (result != null) frames.add(result);
        }
        return frames;
    }

    /** Concatenates multiple byte arrays. */
    private static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] a : arrays) out.writeBytes(a);
        return out.toByteArray();
    }

    // Tests

    @Test
    void testParseValidSingleBytePayload() {
        byte[] payload = {0x42};
        byte[] frame = buildFrame(payload);

        byte[] result = feedBytes(frame);

        assertNotNull(result);
        assertArrayEquals(payload, result);
    }

    @Test
    void testParseValidMultiBytePayload() {
        byte[] payload = {0x08, 0x02, 0x03, 0x04, 0x05}; // 0x08 = field 1, varint
        byte[] frame = buildFrame(payload);

        byte[] result = feedBytes(frame);

        assertNotNull(result);
        assertArrayEquals(payload, result);
    }

    @Test
    void testParseMaxSizePayload() {
        byte[] payload = new byte[FrameParser.MAX_PACKET_SIZE]; // 512
        payload[0] = 0x08; // valid protobuf tag: field 1, varint
        for (int i = 1; i < payload.length; i++) {
            payload[i] = (byte) (i & 0xFF);
        }
        byte[] frame = buildFrame(payload);

        byte[] result = feedBytes(frame);

        assertNotNull(result);
        assertEquals(512, result.length);
        assertArrayEquals(payload, result);
    }

    @Test
    void testRejectZeroLengthPayload() {
        // A frame with length 0 is invalid.
        byte[] invalidFrame = {
                FrameParser.START_BYTE_1, FrameParser.START_BYTE_2,
                0x00, 0x00 // length = 0
        };
        byte[] result = feedBytes(invalidFrame);
        assertNull(result, "Zero-length frame should be rejected");

        // The parser must recover; feed a valid frame next.
        byte[] payload = {0x0A};
        byte[] validFrame = buildFrame(payload);
        result = feedBytes(validFrame);
        assertNotNull(result, "Parser should recover after rejecting invalid frame");
        assertArrayEquals(payload, result);
    }

    @Test
    void testRejectOversizedPayload() {
        // A frame with length 513 (> MAX_PACKET_SIZE=512) is invalid.
        byte[] invalidFrame = {
                FrameParser.START_BYTE_1, FrameParser.START_BYTE_2,
                0x02, 0x01 // length = 513
        };
        byte[] result = feedBytes(invalidFrame);
        assertNull(result, "Oversized frame should be rejected");

        // The parser must recover.
        byte[] payload = {0x0B};
        byte[] validFrame = buildFrame(payload);
        result = feedBytes(validFrame);
        assertNotNull(result, "Parser should recover after rejecting oversized frame");
        assertArrayEquals(payload, result);
    }

    @Test
    void testResyncOnBadSecondStartByte() {
        // First start byte is correct; the second one is not.
        byte[] badStart = {FrameParser.START_BYTE_1, 0x00};
        byte[] result = feedBytes(badStart);
        assertNull(result);

        // A valid frame after that must parse successfully.
        byte[] payload = {0x0C, 0x0D};
        byte[] validFrame = buildFrame(payload);
        result = feedBytes(validFrame);
        assertNotNull(result, "Parser should resync after bad second start byte");
        assertArrayEquals(payload, result);
    }

    @Test
    void testMultipleConsecutiveFrames() {
        byte[] payload1 = {0x11, 0x22};
        byte[] payload2 = {0x33, 0x44, 0x55};
        byte[] combined = concat(buildFrame(payload1), buildFrame(payload2));

        List<byte[]> results = feedAllFrames(combined);

        assertEquals(2, results.size(), "Should parse both frames");
        assertArrayEquals(payload1, results.get(0));
        assertArrayEquals(payload2, results.get(1));
    }

    @Test
    void testGarbageBytesBeforeValidFrame() {
        byte[] garbage = {0x01, (byte) 0xFF, 0x00, 0x55, 0x7F};
        byte[] payload = {0x0A, 0x0F}; // 0x0A = field 1, length-delimited
        byte[] data = concat(garbage, buildFrame(payload));

        byte[] result = feedBytes(data);

        assertNotNull(result, "Valid frame should be parsed despite leading garbage");
        assertArrayEquals(payload, result);
    }

    @Test
    void testResetMethod() {
        // Start parsing by feeding both start bytes, leaving the parser at READ_LEN_MSB.
        parser.processByte(FrameParser.START_BYTE_1);
        parser.processByte(FrameParser.START_BYTE_2);

        // Reset clears parser state.
        parser.reset();

        // A complete valid frame must then parse from scratch.
        byte[] payload = {0x10};
        byte[] frame = buildFrame(payload);
        byte[] result = feedBytes(frame);

        assertNotNull(result, "Parser should work after reset");
        assertArrayEquals(payload, result);
    }

    @Test
    void testStartByteInPayloadDoesNotConfuse() {
        // Payload contains the start-byte sequence [0x94, 0xC3].
        byte[] payload = {0x08, FrameParser.START_BYTE_1, FrameParser.START_BYTE_2, 0x02};
        byte[] frame = buildFrame(payload);

        byte[] result = feedBytes(frame);

        assertNotNull(result);
        assertArrayEquals(payload, result, "Start bytes inside payload should not confuse parser");
    }

    @Test
    void testDiscardFrameWithInvalidProtobufTag() {
        // First byte 0x00 is an invalid protobuf tag (fieldNumber=0).
        byte[] badPayload = {0x00, 0x01, 0x02};
        byte[] badFrame = buildFrame(badPayload);
        byte[] result = feedBytes(badFrame);
        assertNull(result, "Frame with invalid protobuf tag should be discarded");

        // The parser must recover.
        byte[] goodPayload = {0x08, 0x01}; // field 1, varint
        byte[] goodFrame = buildFrame(goodPayload);
        result = feedBytes(goodFrame);
        assertNotNull(result, "Parser should recover after discarding invalid frame");
        assertArrayEquals(goodPayload, result);
    }

    @Test
    void testDiscardFrameWithInvalidWireType() {
        // First byte 0x0E has wireType=6, invalid because only 0-5 are allowed.
        byte[] badPayload = {0x0E, 0x01};
        byte[] badFrame = buildFrame(badPayload);
        byte[] result = feedBytes(badFrame);
        assertNull(result, "Frame with invalid wire type should be discarded");
    }

    @Test
    void testByteByByteReturnsNullUntilComplete() {
        byte[] payload = {0x0A, 0x0B, 0x0C};
        byte[] frame = buildFrame(payload); // 4 header + 3 payload = 7 bytes

        // Every byte except the last one should return null.
        for (int i = 0; i < frame.length - 1; i++) {
            assertNull(parser.processByte(frame[i]),
                    "Byte " + i + " should return null (frame not complete)");
        }

        // The last byte completes the frame.
        byte[] result = parser.processByte(frame[frame.length - 1]);
        assertNotNull(result, "Last byte should complete the frame");
        assertArrayEquals(payload, result);
    }
}
