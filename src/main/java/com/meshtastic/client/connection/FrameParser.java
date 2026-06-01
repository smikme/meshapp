package com.meshtastic.client.connection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * State machine for parsing Meshtastic serial and TCP frames.
 * <p>
 * Frame format: {@code [0x94][0xC3][len_msb][len_lsb][payload...]}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class FrameParser implements StreamFrameParser {

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
     * Processes one byte from the incoming stream.
     * Advances the state machine through start bytes, length bytes, and payload.
     * Frames with length {@code <= 0} or greater than {@code MAX_PACKET_SIZE} are discarded.
     *
     * @param b next byte
     * @return complete protobuf payload when a frame is finished, otherwise {@code null}
     */
    @Override
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
     * Returns {@code true} when the parser is inside a partial frame and waiting
     * for the remaining header or payload bytes.
     */
    @Override
    public boolean hasPartialFrame() {
        return state != State.WAIT_START1;
    }

    /**
     * Checks that the first payload byte is a valid protobuf field tag.
     * Wire type bits must be 0-5 and the field number must be positive. This
     * filters false frames from device debug output on the same UART.
     */
    private static boolean isValidProtobufTag(byte firstByte) {
        int tag = firstByte & 0xFF;
        int wireType = tag & 0x07;
        int fieldNumber = tag >>> 3;
        return wireType <= 5 && fieldNumber > 0;
    }

    /**
     * Resets the parser to its initial state after reconnect or sync errors.
     */
    @Override
    public void reset() {
        state = State.WAIT_START1;
        payloadLength = 0;
        payloadIndex = 0;
        payloadBuffer = null;
    }
}
