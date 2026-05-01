package com.meshtastic.client.protocol.meshcore;

import com.meshtastic.client.connection.KissFrameParser;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Утилиты для сборки KISS frame-ов и константы MeshCore {@code SetHardware}.
 * <p>
 * Методы этого класса работают с телом KISS frame-а: первый byte является KISS command,
 * второй byte для {@code SetHardware} является MeshCore sub-command.
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
     * Создаёт KISS {@code SetHardware} request без payload-а.
     *
     * @param subCommand MeshCore request sub-command
     * @return полностью escaped KISS frame для отправки в transport
     */
    public static byte[] setHardwareRequest(int subCommand) {
        return setHardwareRequest(subCommand, new byte[0]);
    }

    /**
     * Создаёт KISS {@code SetHardware} request с payload-ом.
     *
     * @param subCommand MeshCore request sub-command
     * @param payload данные request-а без command bytes
     * @return полностью escaped KISS frame для отправки в transport
     */
    public static byte[] setHardwareRequest(int subCommand, byte[] payload) {
        byte[] body = new byte[2 + payload.length];
        body[0] = (byte) CMD_SET_HARDWARE;
        body[1] = (byte) (subCommand & 0xFF);
        System.arraycopy(payload, 0, body, 2, payload.length);
        return frame(body);
    }

    /**
     * Создаёт KISS data frame с MeshCore packet payload-ом.
     *
     * @param payload raw MeshCore packet payload
     * @return полностью escaped KISS data frame
     */
    public static byte[] dataFrame(byte[] payload) {
        byte[] body = new byte[1 + payload.length];
        body[0] = (byte) CMD_DATA;
        System.arraycopy(payload, 0, body, 1, payload.length);
        return frame(body);
    }

    /**
     * Оборачивает body в KISS frame и применяет escape-последовательности.
     *
     * @param body тело KISS frame-а без начального и конечного {@code FEND}
     * @return байты, готовые для записи в TCP/Serial stream
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
     * Проверяет, является ли frame ответом/командой MeshCore {@code SetHardware}.
     *
     * @param frame unescaped тело KISS frame-а
     * @return {@code true}, если frame содержит KISS command {@code SetHardware}
     */
    public static boolean isSetHardwareFrame(byte[] frame) {
        return frame != null
                && frame.length >= 2
                && ((frame[0] & 0x0F) == CMD_SET_HARDWARE);
    }

    /**
     * Проверяет, является ли frame распознанным ответом MeshCore KISS handshake/metadata.
     *
     * @param frame unescaped тело KISS frame-а
     * @return {@code true}, если sub-command входит в список поддерживаемых ответов
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
     * Возвращает MeshCore sub-command из {@code SetHardware} frame-а.
     *
     * @param frame unescaped тело KISS frame-а
     * @return sub-command или {@code -1}, если frame не является {@code SetHardware}
     */
    public static int subCommand(byte[] frame) {
        return isSetHardwareFrame(frame) ? frame[1] & 0xFF : -1;
    }

    /**
     * Извлекает payload из {@code SetHardware} frame-а.
     *
     * @param frame unescaped тело KISS frame-а
     * @return payload без KISS command и MeshCore sub-command
     */
    public static byte[] setHardwarePayload(byte[] frame) {
        if (!isSetHardwareFrame(frame) || frame.length <= 2) {
            return new byte[0];
        }
        return Arrays.copyOfRange(frame, 2, frame.length);
    }

    /**
     * Кодирует байты в lowercase HEX.
     *
     * @param data исходные байты
     * @return HEX-строка без разделителей
     */
    public static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
