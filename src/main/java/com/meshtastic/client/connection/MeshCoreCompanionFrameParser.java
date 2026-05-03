package com.meshtastic.client.connection;

import java.io.ByteArrayOutputStream;

/**
 * Эвристический parser для MeshCore Companion Protocol packets поверх byte-stream transport-ов.
 * <p>
 * Companion Protocol естественно работает с packet boundaries BLE write/notification.
 * TCP и Serial таких границ не сохраняют, поэтому packets фиксированной длины возвращаются
 * сразу, а variable-size packets завершаются по inter-byte silence через {@link #flushPartialFrame()}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class MeshCoreCompanionFrameParser implements StreamFrameParser {

    private static final int MAX_FRAME_SIZE = 512;

    private final boolean eagerVariableFrames;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);

    /**
     * Создаёт parser для обычного runtime-режима.
     * <p>
     * Variable-size packets будут возвращаться только после {@link #flushPartialFrame()},
     * чтобы не обрезать полезную нагрузку раньше окончания stream-паузы.
     */
    public MeshCoreCompanionFrameParser() {
        this(false);
    }

    /**
     * Создаёт parser с возможностью eager-выдачи variable-size packets.
     * <p>
     * Такой режим используется auto-detect-ом, где достаточно распознать тип ответа
     * и не требуется дождаться полного текстового хвоста packet-а.
     *
     * @param eagerVariableFrames {@code true}, если variable-size packet можно вернуть сразу после минимальной длины
     */
    MeshCoreCompanionFrameParser(boolean eagerVariableFrames) {
        this.eagerVariableFrames = eagerVariableFrames;
    }

    /**
     * Добавляет байт в текущий Companion packet.
     *
     * @param b очередной байт packet-а
     * @return готовый packet или {@code null}, если нужно дождаться дополнительных байтов/паузы
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

        if (eagerVariableFrames) {
            int minLength = minimumLength();
            if (minLength > 0 && buffer.size() >= minLength) {
                return take(buffer.size());
            }
        }
        return null;
    }

    /**
     * Проверяет, накоплен ли частичный Companion packet.
     *
     * @return {@code true}, если buffer не пуст
     */
    @Override
    public boolean hasPartialFrame() {
        return buffer.size() > 0;
    }

    /**
     * Завершает variable-size packet после read timeout или inter-byte silence.
     *
     * @return готовый packet или {@code null}, если накопленных данных недостаточно
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
     * Очищает накопленный packet.
     */
    @Override
    public void reset() {
        buffer.reset();
    }

    /**
     * Возвращает точную длину packet-а, если она известна по packet type и version.
     *
     * @return ожидаемая длина или {@code -1} для variable-size packets
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
     * Возвращает минимальную длину, достаточную для распознавания variable-size packet-а.
     *
     * @return минимальная длина или {@code -1}, если тип packet-а неизвестен
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
     * Определяет длину {@code DEVICE_INFO} packet-а по версии firmware protocol.
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
     * Забирает готовый packet из buffer-а и оставляет лишние байты для следующего packet-а.
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
     * Проверяет, может ли байт быть первым байтом известного Companion packet-а.
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
