package com.meshtastic.client.protocol.meshcore;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Utilities and constants for MeshCore Companion Protocol packets.
 * <p>
 * These packets are used the same way over BLE RX/TX characteristics and raw
 * TCP/Serial byte-stream endpoints.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class MeshCoreCompanionFrames {

    public static final int CMD_APP_START = 0x01;
    public static final int CMD_SEND_DIRECT_TEXT = 0x02;
    public static final int CMD_SEND_CHANNEL_TEXT = 0x03;
    public static final int CMD_GET_CONTACTS = 0x04;
    public static final int CMD_GET_MESSAGE = 0x0A;
    public static final int CMD_GET_BATTERY = 0x14;
    public static final int CMD_DEVICE_QUERY = 0x16;
    public static final int CMD_GET_CHANNEL = 0x1F;

    public static final int PACKET_OK = 0x00;
    public static final int PACKET_ERROR = 0x01;
    public static final int PACKET_CONTACTS_START = 0x02;
    public static final int PACKET_CONTACT = 0x03;
    public static final int PACKET_CONTACTS_END = 0x04;
    public static final int PACKET_SELF_INFO = 0x05;
    public static final int PACKET_MSG_SENT = 0x06;
    public static final int PACKET_CONTACT_MSG_RECV = 0x07;
    public static final int PACKET_CHANNEL_MSG_RECV = 0x08;
    public static final int PACKET_NO_MORE_MSGS = 0x0A;
    public static final int PACKET_BATTERY = 0x0C;
    public static final int PACKET_DEVICE_INFO = 0x0D;
    public static final int PACKET_CONTACT_MSG_RECV_V3 = 0x10;
    public static final int PACKET_CHANNEL_MSG_RECV_V3 = 0x11;
    public static final int PACKET_CHANNEL_INFO = 0x12;
    public static final int PACKET_ADVERTISEMENT = 0x80;
    public static final int PACKET_ACK = 0x82;
    public static final int PACKET_MESSAGES_WAITING = 0x83;
    public static final int PACKET_LOG_DATA = 0x88;

    private MeshCoreCompanionFrames() {
    }

    /**
     * Creates an {@code APP_START} command.
 *
     * @param appName application name written after the packet service header
     * @return raw Companion packet ready for transport
     */
    public static byte[] appStart(String appName) {
        byte[] nameBytes = appName == null || appName.isBlank()
                ? new byte[0]
                : appName.getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[8 + nameBytes.length];
        frame[0] = (byte) CMD_APP_START;
        System.arraycopy(nameBytes, 0, frame, 8, nameBytes.length);
        return frame;
    }

    /**
     * Creates a command that requests device information.
     *
     * @return raw Companion {@code DEVICE_QUERY} packet
     */
    public static byte[] deviceQuery() {
        return new byte[]{(byte) CMD_DEVICE_QUERY, 0x03};
    }

    /**
     * Creates a command that requests battery and storage metadata.
     *
     * @return raw Companion {@code GET_BATTERY} packet
     */
    public static byte[] getBattery() {
        return new byte[]{(byte) CMD_GET_BATTERY};
    }

    /**
     * Creates a command that requests the contact list.
     *
     * @return raw Companion {@code GET_CONTACTS} packet
     */
    public static byte[] getContacts() {
        return new byte[]{(byte) CMD_GET_CONTACTS};
    }

    /**
     * Creates a command that requests MeshCore channel information.
 *
     * @param channelIndex channel index in the {@code 0..7} range
     * @return raw Companion {@code GET_CHANNEL} packet
     */
    public static byte[] getChannel(int channelIndex) {
        return new byte[]{(byte) CMD_GET_CHANNEL, (byte) (channelIndex & 0xFF)};
    }

    /**
     * Creates a command that sends text to a MeshCore channel.
 *
     * @param channelIndex     channel index in the {@code 0..7} range
     * @param timestampSeconds sender Unix timestamp in seconds
     * @param text             message text
     * @return raw Companion {@code SEND_CHANNEL_TXT_MSG} packet
     */
    public static byte[] sendChannelText(int channelIndex, long timestampSeconds, String text) {
        byte[] textBytes = safeTextBytes(text);
        byte[] packet = new byte[7 + textBytes.length];
        packet[0] = (byte) CMD_SEND_CHANNEL_TEXT;
        packet[1] = 0x00;
        packet[2] = (byte) (channelIndex & 0xFF);
        writeIntLe(packet, 3, timestampSeconds);
        System.arraycopy(textBytes, 0, packet, 7, textBytes.length);
        return packet;
    }

    /**
     * Creates a command that sends a direct message to a contact public-key prefix.
 *
     * @param publicKeyPrefix  first 6 bytes of the recipient public key
     * @param timestampSeconds sender Unix timestamp in seconds
     * @param text             message text
     * @return raw Companion {@code SEND_TXT_MSG} packet
     */
    public static byte[] sendDirectText(byte[] publicKeyPrefix, long timestampSeconds, String text) {
        byte[] prefix = Arrays.copyOf(publicKeyPrefix == null ? new byte[0] : publicKeyPrefix, 6);
        byte[] textBytes = safeTextBytes(text);
        byte[] packet = new byte[13 + textBytes.length];
        packet[0] = (byte) CMD_SEND_DIRECT_TEXT;
        packet[1] = 0x00;
        packet[2] = 0x00;
        writeIntLe(packet, 3, timestampSeconds);
        System.arraycopy(prefix, 0, packet, 7, prefix.length);
        System.arraycopy(textBytes, 0, packet, 13, textBytes.length);
        return packet;
    }

    /**
     * Creates a command that requests the next message from the MeshCore queue.
     *
     * @return raw Companion {@code GET_MESSAGE} packet
     */
    public static byte[] getMessage() {
        return new byte[]{(byte) CMD_GET_MESSAGE};
    }

    /**
     * Extracts the public key from a {@code SELF_INFO} packet.
 *
     * @param selfInfoPacket raw {@code SELF_INFO} packet
     * @return 32 public-key bytes, or an empty array when the packet is invalid
     */
    public static byte[] publicKey(byte[] selfInfoPacket) {
        if (selfInfoPacket == null || selfInfoPacket.length < 36
                || (selfInfoPacket[0] & 0xFF) != PACKET_SELF_INFO) {
            return new byte[0];
        }
        return Arrays.copyOfRange(selfInfoPacket, 4, 36);
    }

    /**
     * Reads a variable-length UTF-8 string from a packet.
 *
     * @param packet raw Companion packet
     * @param offset start offset of the string
     * @return trimmed string, or {@code null} when no string is present
     */
    public static String text(byte[] packet, int offset) {
        if (packet == null || offset >= packet.length) {
            return null;
        }
        String text = new String(packet, offset, packet.length - offset, StandardCharsets.UTF_8)
                .replace("\u0000", "")
                .trim();
        return text.isBlank() ? null : text;
    }

    /**
     * Reads a UTF-8 string with a fixed maximum length.
 *
     * @param packet raw Companion packet
     * @param offset start offset of the string
     * @param length maximum field length in bytes
     * @return trimmed string, or {@code null} when no string is present
     */
    public static String fixedText(byte[] packet, int offset, int length) {
        if (packet == null || offset >= packet.length || length <= 0) {
            return null;
        }
        int available = Math.min(length, packet.length - offset);
        String text = new String(packet, offset, available, StandardCharsets.UTF_8)
                .replace("\u0000", "")
                .trim();
        return text.isBlank() ? null : text;
    }

    /**
     * Reads a fixed-length UTF-8 string up to the first {@code 0x00}.
 *
     * @param packet raw Companion packet
     * @param offset start offset of the string
     * @param length maximum field length in bytes
     * @return trimmed string, or {@code null} when no string is present
     */
    public static String nullTerminatedText(byte[] packet, int offset, int length) {
        if (packet == null || offset >= packet.length || length <= 0) {
            return null;
        }
        int end = Math.min(packet.length, offset + length);
        int zero = offset;
        while (zero < end && packet[zero] != 0) {
            zero++;
        }
        String text = new String(packet, offset, zero - offset, StandardCharsets.UTF_8).trim();
        return text.isBlank() ? null : text;
    }

    /**
     * Reads an unsigned 16-bit little-endian value.
 *
     * @param packet raw Companion packet
     * @param offset first-byte offset
     * @return value in the {@code 0..65535} range, or {@code 0} when data is insufficient
     */
    public static int unsignedShortLe(byte[] packet, int offset) {
        if (packet == null || packet.length < offset + 2) {
            return 0;
        }
        return (packet[offset] & 0xFF) | ((packet[offset + 1] & 0xFF) << 8);
    }

    /**
     * Reads an unsigned 32-bit little-endian value.
 *
     * @param packet raw Companion packet
     * @param offset first-byte offset
     * @return unsigned-int value, or {@code 0} when data is insufficient
     */
    public static long unsignedIntLe(byte[] packet, int offset) {
        if (packet == null || packet.length < offset + 4) {
            return 0L;
        }
        return Integer.toUnsignedLong(
                (packet[offset] & 0xFF)
                        | ((packet[offset + 1] & 0xFF) << 8)
                        | ((packet[offset + 2] & 0xFF) << 16)
                        | ((packet[offset + 3] & 0xFF) << 24));
    }

    /**
     * Reads a signed 32-bit little-endian value.
 *
     * @param packet raw Companion packet
     * @param offset first-byte offset
     * @return signed int, or {@code 0} when data is insufficient
     */
    public static int signedIntLe(byte[] packet, int offset) {
        if (packet == null || packet.length < offset + 4) {
            return 0;
        }
        return (packet[offset] & 0xFF)
                | ((packet[offset + 1] & 0xFF) << 8)
                | ((packet[offset + 2] & 0xFF) << 16)
                | ((packet[offset + 3] & 0xFF) << 24);
    }

    /**
     * Encodes bytes as lowercase HEX.
 *
     * @param data source bytes; {@code null} is treated as an empty array
     * @return HEX string without separators
     */
    public static String hex(byte[] data) {
        return MeshCoreKissFrames.hex(data == null ? new byte[0] : data);
    }

    /**
     * Returns a short MeshCore node id for a public key.
     * <p>
     * The full public key remains in runtime state; the UI and database receive
     * a stable short identifier, {@code mc:<12 hex>}, compatible with existing
     * chat-field constraints.
 *
     * @param publicKeyHex full or partial public key in HEX
     * @return node id in the {@code mc:abcdef123456} form, or {@code null}
     */
    public static String nodeIdFromPublicKeyHex(String publicKeyHex) {
        if (publicKeyHex == null) {
            return null;
        }
        String normalized = publicKeyHex.replaceAll("[^0-9a-fA-F]", "").toLowerCase();
        if (normalized.length() < 12) {
            return null;
        }
        return "mc:" + normalized.substring(0, 12);
    }

    /**
     * Returns the first 6 public-key bytes from a MeshCore node id.
 *
     * @param nodeId node id in the {@code mc:<12 hex>} form
     * @return 6-byte public-key prefix, or an empty array
     */
    public static byte[] publicKeyPrefixFromNodeId(String nodeId) {
        if (nodeId == null || !nodeId.startsWith("mc:") || nodeId.length() < 15) {
            return new byte[0];
        }
        return hexToBytes(nodeId.substring(3, 15));
    }

    /**
     * Decodes a HEX string into bytes.
 *
     * @param hex HEX string without separators
     * @return byte array; invalid input produces an empty array
     */
    public static byte[] hexToBytes(String hex) {
        if (hex == null) {
            return new byte[0];
        }
        String normalized = hex.replaceAll("[^0-9a-fA-F]", "");
        if (normalized.length() % 2 != 0) {
            normalized = "0" + normalized;
        }
        byte[] bytes = new byte[normalized.length() / 2];
        try {
            for (int i = 0; i < bytes.length; i++) {
                int pos = i * 2;
                bytes[i] = (byte) Integer.parseInt(normalized.substring(pos, pos + 2), 16);
            }
            return bytes;
        } catch (NumberFormatException e) {
            return new byte[0];
        }
    }

    /**
     * Checks whether a packet looks like a recognized MeshCore Companion Protocol response.
 *
     * @param packet raw Companion packet
     * @return {@code true} when the first byte matches a known packet type
     */
    public static boolean isRecognizedResponsePacket(byte[] packet) {
        if (packet == null || packet.length == 0) {
            return false;
        }
        return switch (packet[0] & 0xFF) {
            case PACKET_OK,
                    PACKET_ERROR,
                    PACKET_CONTACTS_START,
                    PACKET_CONTACT,
                    PACKET_CONTACTS_END,
                    PACKET_SELF_INFO,
                    PACKET_MSG_SENT,
                    PACKET_CONTACT_MSG_RECV,
                    PACKET_CHANNEL_MSG_RECV,
                    PACKET_NO_MORE_MSGS,
                    PACKET_BATTERY,
                    PACKET_DEVICE_INFO,
                    PACKET_CONTACT_MSG_RECV_V3,
                    PACKET_CHANNEL_MSG_RECV_V3,
                    PACKET_CHANNEL_INFO,
                    PACKET_ADVERTISEMENT,
                    PACKET_ACK,
                    PACKET_MESSAGES_WAITING,
                    PACKET_LOG_DATA -> true;
            default -> false;
        };
    }

    private static byte[] safeTextBytes(String text) {
        return text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
    }

    private static void writeIntLe(byte[] packet, int offset, long value) {
        long unsigned = value & 0xFFFF_FFFFL;
        packet[offset] = (byte) (unsigned & 0xFF);
        packet[offset + 1] = (byte) ((unsigned >>> 8) & 0xFF);
        packet[offset + 2] = (byte) ((unsigned >>> 16) & 0xFF);
        packet[offset + 3] = (byte) ((unsigned >>> 24) & 0xFF);
    }
}
