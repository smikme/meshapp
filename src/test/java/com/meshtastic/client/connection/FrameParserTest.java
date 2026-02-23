package com.meshtastic.client.connection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FrameParserTest {

    private FrameParser parser;

    @BeforeEach
    void setUp() {
        parser = new FrameParser();
    }

    // ═══════════════════════════════════════════════════════════
    //  Хелперы
    // ═══════════════════════════════════════════════════════════

    /** Собирает валидный фрейм: [0x94][0xC3][len_msb][len_lsb][payload...] */
    private static byte[] buildFrame(byte[] payload) {
        byte[] frame = new byte[4 + payload.length];
        frame[0] = FrameParser.START_BYTE_1;
        frame[1] = FrameParser.START_BYTE_2;
        frame[2] = (byte) ((payload.length >> 8) & 0xFF);
        frame[3] = (byte) (payload.length & 0xFF);
        System.arraycopy(payload, 0, frame, 4, payload.length);
        return frame;
    }

    /** Прогоняет все байты через processByte(), возвращает первый non-null результат или null. */
    private byte[] feedBytes(byte[] data) {
        for (byte b : data) {
            byte[] result = parser.processByte(b);
            if (result != null) return result;
        }
        return null;
    }

    /** Прогоняет все байты, собирает все non-null результаты (для нескольких фреймов). */
    private List<byte[]> feedAllFrames(byte[] data) {
        List<byte[]> frames = new ArrayList<>();
        for (byte b : data) {
            byte[] result = parser.processByte(b);
            if (result != null) frames.add(result);
        }
        return frames;
    }

    /** Конкатенирует несколько массивов байт. */
    private static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] a : arrays) out.writeBytes(a);
        return out.toByteArray();
    }

    // ═══════════════════════════════════════════════════════════
    //  Тесты
    // ═══════════════════════════════════════════════════════════

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
        byte[] payload = {0x01, 0x02, 0x03, 0x04, 0x05};
        byte[] frame = buildFrame(payload);

        byte[] result = feedBytes(frame);

        assertNotNull(result);
        assertArrayEquals(payload, result);
    }

    @Test
    void testParseMaxSizePayload() {
        byte[] payload = new byte[FrameParser.MAX_PACKET_SIZE]; // 512
        for (int i = 0; i < payload.length; i++) {
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
        // Фрейм с длиной 0 — невалидный
        byte[] invalidFrame = {
                FrameParser.START_BYTE_1, FrameParser.START_BYTE_2,
                0x00, 0x00 // length = 0
        };
        byte[] result = feedBytes(invalidFrame);
        assertNull(result, "Zero-length frame should be rejected");

        // Парсер должен восстановиться — подаём валидный фрейм
        byte[] payload = {0x0A};
        byte[] validFrame = buildFrame(payload);
        result = feedBytes(validFrame);
        assertNotNull(result, "Parser should recover after rejecting invalid frame");
        assertArrayEquals(payload, result);
    }

    @Test
    void testRejectOversizedPayload() {
        // Фрейм с длиной 513 (> MAX_PACKET_SIZE=512) — невалидный
        byte[] invalidFrame = {
                FrameParser.START_BYTE_1, FrameParser.START_BYTE_2,
                0x02, 0x01 // length = 513
        };
        byte[] result = feedBytes(invalidFrame);
        assertNull(result, "Oversized frame should be rejected");

        // Парсер должен восстановиться
        byte[] payload = {0x0B};
        byte[] validFrame = buildFrame(payload);
        result = feedBytes(validFrame);
        assertNotNull(result, "Parser should recover after rejecting oversized frame");
        assertArrayEquals(payload, result);
    }

    @Test
    void testResyncOnBadSecondStartByte() {
        // Первый start byte верный, второй — нет
        byte[] badStart = {FrameParser.START_BYTE_1, 0x00};
        byte[] result = feedBytes(badStart);
        assertNull(result);

        // Подаём валидный фрейм — должен распарситься
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
        byte[] payload = {0x0E, 0x0F};
        byte[] data = concat(garbage, buildFrame(payload));

        byte[] result = feedBytes(data);

        assertNotNull(result, "Valid frame should be parsed despite leading garbage");
        assertArrayEquals(payload, result);
    }

    @Test
    void testResetMethod() {
        // Начинаем парсить — подаём два start bytes (парсер в состоянии READ_LEN_MSB)
        parser.processByte(FrameParser.START_BYTE_1);
        parser.processByte(FrameParser.START_BYTE_2);

        // Reset сбрасывает состояние
        parser.reset();

        // Подаём полный валидный фрейм — должен распарситься с нуля
        byte[] payload = {0x10};
        byte[] frame = buildFrame(payload);
        byte[] result = feedBytes(frame);

        assertNotNull(result, "Parser should work after reset");
        assertArrayEquals(payload, result);
    }

    @Test
    void testStartByteInPayloadDoesNotConfuse() {
        // Payload содержит последовательность start bytes [0x94, 0xC3]
        byte[] payload = {0x01, FrameParser.START_BYTE_1, FrameParser.START_BYTE_2, 0x02};
        byte[] frame = buildFrame(payload);

        byte[] result = feedBytes(frame);

        assertNotNull(result);
        assertArrayEquals(payload, result, "Start bytes inside payload should not confuse parser");
    }

    @Test
    void testByteByByteReturnsNullUntilComplete() {
        byte[] payload = {0x0A, 0x0B, 0x0C};
        byte[] frame = buildFrame(payload); // 4 header + 3 payload = 7 bytes

        // Все байты кроме последнего должны вернуть null
        for (int i = 0; i < frame.length - 1; i++) {
            assertNull(parser.processByte(frame[i]),
                    "Byte " + i + " should return null (frame not complete)");
        }

        // Последний байт возвращает результат
        byte[] result = parser.processByte(frame[frame.length - 1]);
        assertNotNull(result, "Last byte should complete the frame");
        assertArrayEquals(payload, result);
    }
}
