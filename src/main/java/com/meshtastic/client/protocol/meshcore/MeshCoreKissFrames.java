package com.meshtastic.client.protocol.meshcore;

import com.meshtastic.client.connection.KissFrameParser;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Helpers for building KISS frames and MeshCore {@code SetHardware} constants.
 * <p>
 * Methods operate on the KISS frame body: the first byte is the KISS command,
 * and the second byte of {@code SetHardware} frames is the MeshCore sub-command.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class MeshCoreKissFrames {

    public static final int CMD_DATA = 0x00;
    public static final int CMD_SET_HARDWARE = 0x06;

    public static final int REQ_GET_IDENTITY = 0x01;
    public static final int REQ_GET_RADIO = 0x0B;
    public static final int REQ_GET_TX_POWER = 0x0C;
    public static final int REQ_GET_VERSION = 0x11;
    public static final int REQ_GET_STATS = 0x12;
    public static final int REQ_GET_BATTERY = 0x13;
    public static final int REQ_GET_DEVICE_NAME = 0x16;
    public static final int REQ_PING = 0x17;

    public static final int RESP_IDENTITY = 0x81;
    public static final int RESP_RADIO = 0x8B;
    public static final int RESP_TX_POWER = 0x8C;
    public static final int RESP_VERSION = 0x91;
    public static final int RESP_STATS = 0x92;
    public static final int RESP_BATTERY = 0x93;
    public static final int RESP_DEVICE_NAME = 0x96;
    public static final int RESP_PONG = 0x97;
    public static final int RESP_OK = 0xF0;
    public static final int RESP_ERROR = 0xF1;
    public static final int RESP_TX_DONE = 0xF8;
    public static final int RESP_RX_META = 0xF9;

    private MeshCoreKissFrames() {
    }

    /**
     * Creates a KISS {@code SetHardware} request without a payload.
     *
     * @param subCommand MeshCore request sub-command
     * @return escaped KISS frame ready for transport
     */
    public static byte[] setHardwareRequest(int subCommand) {
        return setHardwareRequest(subCommand, new byte[0]);
    }

    /**
     * Creates a KISS {@code SetHardware} request with a payload.
     *
     * @param subCommand MeshCore request sub-command
     * @param payload request data without command bytes
     * @return escaped KISS frame ready for transport
     */
    public static byte[] setHardwareRequest(int subCommand, byte[] payload) {
        byte[] body = new byte[2 + payload.length];
        body[0] = (byte) CMD_SET_HARDWARE;
        body[1] = (byte) (subCommand & 0xFF);
        System.arraycopy(payload, 0, body, 2, payload.length);
        return frame(body);
    }

    /**
     * Creates a KISS data frame carrying a MeshCore packet payload.
     *
     * @param payload raw MeshCore packet payload
     * @return escaped KISS data frame
     */
    public static byte[] dataFrame(byte[] payload) {
        byte[] body = new byte[1 + payload.length];
        body[0] = (byte) CMD_DATA;
        System.arraycopy(payload, 0, body, 1, payload.length);
        return frame(body);
    }

    /**
     * Wraps a body in a KISS frame and applies escape sequences.
     *
     * @param body KISS frame body without leading and trailing {@code FEND}
     * @return bytes ready for a TCP or Serial stream
     */
    public static byte[] frame(byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 2);
        out.write(KissFrameParser.FEND);
        for (byte b : body) {
            if (b == KissFrameParser.FEND) {
                out.write(KissFrameParser.FESC);
                out.write(KissFrameParser.TFEND);
            } else if (b == KissFrameParser.FESC) {
                out.write(KissFrameParser.FESC);
                out.write(KissFrameParser.TFESC);
            } else {
                out.write(b);
            }
        }
        out.write(KissFrameParser.FEND);
        return out.toByteArray();
    }

    /**
     * Returns whether a frame is a MeshCore {@code SetHardware} command or response.
     *
     * @param frame unescaped KISS frame body
     * @return {@code true} when the frame contains a KISS {@code SetHardware} command
     */
    public static boolean isSetHardwareFrame(byte[] frame) {
        return frame != null
                && frame.length >= 2
                && ((frame[0] & 0x0F) == CMD_SET_HARDWARE);
    }

    /**
     * Returns whether a frame is a recognized MeshCore KISS handshake or metadata response.
     *
     * @param frame unescaped KISS frame body
     * @return {@code true} when the sub-command is one of the supported responses
     */
    public static boolean isRecognizedResponseFrame(byte[] frame) {
        if (!isSetHardwareFrame(frame)) {
            return false;
        }
        int subCommand = frame[1] & 0xFF;
        return switch (subCommand) {
            case RESP_IDENTITY, RESP_RADIO, RESP_TX_POWER, RESP_VERSION,
                    RESP_STATS, RESP_BATTERY, RESP_DEVICE_NAME, RESP_PONG,
                    RESP_OK, RESP_ERROR, RESP_TX_DONE, RESP_RX_META -> true;
            default -> false;
        };
    }

    /**
     * Extracts the MeshCore sub-command from a {@code SetHardware} frame.
     *
     * @param frame unescaped KISS frame body
     * @return sub-command, or {@code -1} when the frame is not {@code SetHardware}
     */
    public static int subCommand(byte[] frame) {
        return isSetHardwareFrame(frame) ? frame[1] & 0xFF : -1;
    }

    /**
     * Extracts payload bytes from a {@code SetHardware} frame.
     *
     * @param frame unescaped KISS frame body
     * @return payload without KISS command and MeshCore sub-command bytes
     */
    public static byte[] setHardwarePayload(byte[] frame) {
        if (!isSetHardwareFrame(frame) || frame.length <= 2) {
            return new byte[0];
        }
        return Arrays.copyOfRange(frame, 2, frame.length);
    }

    /**
     * Encodes bytes as lowercase hexadecimal.
     *
     * @param data source bytes
     * @return hexadecimal string without separators
     */
    public static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
