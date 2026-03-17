package com.meshtastic.client.connection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * State machine for parsing Meshtastic serial/TCP frames.
 * Frame format: [0x94][0xC3][len_msb][len_lsb][payload...]
 */
public class FrameParser {

    private static final Logger log = LoggerFactory.getLogger(FrameParser.class);

    public static final byte START_BYTE_1 = (byte) 0x94;
    public static final byte START_BYTE_2 = (byte) 0xC3;
    public static final int MAX_PACKET_SIZE = 512;

    private enum State {
        WAIT_START1, WAIT_START2, READ_LEN_MSB, READ_LEN_LSB, READ_PAYLOAD
    }

    private State state = State.WAIT_START1;
    private int payloadLength;
    private int payloadIndex;
    private byte[] payloadBuffer;

    /**
     * Обрабатывает один байт из входного потока.
     * Продвигает конечный автомат по состояниям: WAIT_START1 → WAIT_START2 →
     * READ_LEN_MSB → READ_LEN_LSB → READ_PAYLOAD.
     * Пакеты с длиной {@code <= 0} или {@code > MAX_PACKET_SIZE} отбрасываются.
     *
     * @param b очередной байт
     * @return полный protobuf-payload при завершении фрейма, или {@code null} если фрейм не собран
     */
    public byte[] processByte(byte b) {
        switch (state) {
            case WAIT_START1:
                if (b == START_BYTE_1) {
                    state = State.WAIT_START2;
                }
                return null;

            case WAIT_START2:
                if (b == START_BYTE_2) {
                    state = State.READ_LEN_MSB;
                } else {
                    state = State.WAIT_START1;
                }
                return null;

            case READ_LEN_MSB:
                payloadLength = (b & 0xFF) << 8;
                state = State.READ_LEN_LSB;
                return null;

            case READ_LEN_LSB:
                payloadLength |= (b & 0xFF);
                if (payloadLength <= 0 || payloadLength > MAX_PACKET_SIZE) {
                    log.warn("Invalid packet length: {}, resetting parser", payloadLength);
                    state = State.WAIT_START1;
                    return null;
                }
                payloadBuffer = new byte[payloadLength];
                payloadIndex = 0;
                state = State.READ_PAYLOAD;
                return null;

            case READ_PAYLOAD:
                payloadBuffer[payloadIndex] = b;
                payloadIndex++;
                if (payloadIndex == payloadLength) {
                    state = State.WAIT_START1;
                    byte[] result = payloadBuffer;
                    payloadBuffer = null;
                    if (!isValidProtobufTag(result[0])) {
                        log.debug("Discarding false frame ({} bytes): invalid protobuf tag 0x{}",
                                result.length, String.format("%02X", result[0] & 0xFF));
                        return null;
                    }
                    return result;
                }
                return null;

            default:
                state = State.WAIT_START1;
                return null;
        }
    }

    /**
     * Проверяет что первый байт payload — валидный protobuf field tag.
     * Wire type (биты 0-2) должен быть 0-5, field number (биты 3+) должен быть > 0.
     * Отсеивает ложные фреймы от debug-вывода устройства на том же UART.
     */
    private static boolean isValidProtobufTag(byte firstByte) {
        int tag = firstByte & 0xFF;
        int wireType = tag & 0x07;
        int fieldNumber = tag >>> 3;
        return wireType <= 5 && fieldNumber > 0;
    }

    /**
     * Сбрасывает парсер в начальное состояние.
     * Используется при переподключении или обнаружении ошибки синхронизации.
     */
    public void reset() {
        state = State.WAIT_START1;
        payloadLength = 0;
        payloadIndex = 0;
        payloadBuffer = null;
    }
}
