package com.meshtastic.client.connection;

import java.io.ByteArrayOutputStream;

/**
 * Heuristic parser for MeshCore Companion Protocol packets carried over
 * byte-stream transports.
 * <p>
 * The Companion Protocol naturally has packet boundaries on BLE writes and
 * notifications. TCP and Serial do not preserve those boundaries, so fixed-size
 * packets are returned immediately, while variable-size packets are completed
 * after inter-byte silence via {@link #flushPartialFrame()}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class MeshCoreCompanionFrameParser implements StreamFrameParser {

    private static final int MAX_FRAME_SIZE = 512;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);

    /**
     * Creates a parser for normal runtime mode.
     * <p>
     * Variable-size packets are returned only after {@link #flushPartialFrame()}
     * so payloads are not truncated before the stream pause ends.
     */
    public MeshCoreCompanionFrameParser() {
    }

    /**
     * Adds one byte to the current Companion packet.
     *
     * @param b next packet byte
     * @return completed packet, or {@code null} while more bytes or a pause are required
     */
    @Override
    public byte[] processByte(byte b) {
        int value = b & 0xFF;
        if (buffer.size() == 0 && !isKnownPacketType(value)) {
            return null;
        }

        buffer.write(value);
        if (buffer.size() > MAX_FRAME_SIZE) {
            reset();
            return null;
        }

        int expectedLength = expectedLength();
        if (expectedLength > 0 && buffer.size() >= expectedLength) {
            return take(expectedLength);
        }

        return null;
    }

    /**
     * Returns whether a partial Companion packet is buffered.
     *
     * @return {@code true} when the buffer is not empty
     */
    @Override
    public boolean hasPartialFrame() {
        return buffer.size() > 0;
    }

    /**
     * Completes a variable-size packet after read timeout or inter-byte silence.
     *
     * @return completed packet, or {@code null} when buffered data is insufficient
     */
    @Override
    public byte[] flushPartialFrame() {
        if (buffer.size() == 0) {
            return null;
        }
        int minLength = minimumLength();
        if (minLength > 0 && buffer.size() >= minLength) {
            return take(buffer.size());
        }
        return null;
    }

    /**
     * Clears the buffered packet.
     */
    @Override
    public void reset() {
        buffer.reset();
    }

    /**
     * Returns the exact packet length when packet type and version define one.
     *
     * @return expected length, or {@code -1} for variable-size packets
     */
    private int expectedLength() {
        if (buffer.size() == 0) {
            return -1;
        }
        byte[] data = buffer.toByteArray();
        return switch (data[0] & 0xFF) {
            case 0x00 -> 1;  // PACKET_OK, optional value is ignored by current runtime
            case 0x06 -> 10; // PACKET_MSG_SENT
            case 0x0A -> 1;  // PACKET_NO_MORE_MSGS
            case 0x0D -> deviceInfoLength(data);
            case 0x12 -> 50; // PACKET_CHANNEL_INFO
            case 0x82 -> 7;  // PACKET_ACK
            case 0x83 -> 1;  // PACKET_MESSAGES_WAITING
            default -> -1;
        };
    }

    /**
     * Returns the minimum length needed to recognize a variable-size packet.
     *
     * @return minimum length, or {@code -1} when the packet type is unknown
     */
    private int minimumLength() {
        if (buffer.size() == 0) {
            return -1;
        }
        byte[] data = buffer.toByteArray();
        return switch (data[0] & 0xFF) {
            case 0x01 -> 1;  // PACKET_ERROR, optional error code
            case 0x03 -> 1;  // PACKET_CONTACT
            case 0x05 -> 58; // PACKET_SELF_INFO with optional trailing name
            case 0x07 -> 17; // PACKET_CONTACT_MSG_RECV with variable text
            case 0x08 -> 8;  // PACKET_CHANNEL_MSG_RECV with variable text
            case 0x0C -> 3;  // PACKET_BATTERY with optional storage fields
            case 0x10 -> 20; // PACKET_CONTACT_MSG_RECV_V3 with variable text
            case 0x11 -> 11; // PACKET_CHANNEL_MSG_RECV_V3 with variable text
            case 0x80 -> 1;  // PACKET_ADVERTISEMENT
            case 0x88 -> 1;  // PACKET_LOG_DATA
            default -> expectedLength();
        };
    }

    /**
     * Determines {@code DEVICE_INFO} packet length from firmware protocol version.
     */
    private int deviceInfoLength(byte[] data) {
        if (data.length < 2) {
            return -1;
        }
        int version = data[1] & 0xFF;
        if (version >= 10) {
            return 82;
        }
        if (version >= 9) {
            return 81;
        }
        if (version >= 3) {
            return 80;
        }
        return 2;
    }

    /**
     * Takes a completed packet from the buffer and keeps extra bytes for the next packet.
     */
    private byte[] take(int length) {
        byte[] data = buffer.toByteArray();
        byte[] frame = new byte[Math.min(length, data.length)];
        System.arraycopy(data, 0, frame, 0, frame.length);
        buffer.reset();
        if (data.length > frame.length) {
            buffer.write(data, frame.length, data.length - frame.length);
        }
        return frame;
    }

    /**
     * Returns whether a byte can start a known Companion packet.
     */
    private static boolean isKnownPacketType(int value) {
        return switch (value) {
            case 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
                    0x08, 0x09, 0x0A, 0x0C, 0x0D, 0x10, 0x11, 0x12,
                    0x80, 0x82, 0x83, 0x88 -> true;
            default -> false;
        };
    }
}
