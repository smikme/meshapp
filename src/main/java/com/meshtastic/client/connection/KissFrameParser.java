package com.meshtastic.client.connection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;

/**
 * Parser стандартных KISS TNC frame-ов.
 * <p>
 * На вход получает поток байтов с {@code FEND}/{@code FESC} delimiters и escape-последовательностями.
 * На выход отдаёт тело frame-а: первый byte является KISS command/type, остальные байты уже unescaped.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class KissFrameParser implements StreamFrameParser {

    private static final Logger log = LoggerFactory.getLogger(KissFrameParser.class);

    public static final byte FEND = (byte) 0xC0;
    public static final byte FESC = (byte) 0xDB;
    public static final byte TFEND = (byte) 0xDC;
    public static final byte TFESC = (byte) 0xDD;
    public static final int MAX_FRAME_SIZE = 512;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(MAX_FRAME_SIZE);
    private boolean inFrame;
    private boolean escaping;

    /**
     * Продвигает KISS state machine на один байт.
     *
     * @param b очередной байт из TCP/Serial stream-а
     * @return готовое тело KISS frame-а или {@code null}, если frame ещё не закрыт delimiter-ом
     */
    @Override
    public byte[] processByte(byte b) {
        if (b == FEND) {
            if (!inFrame) {
                inFrame = true;
                resetBuffer();
                return null;
            }

            if (buffer.size() == 0) {
                escaping = false;
                return null;
            }

            byte[] frame = buffer.toByteArray();
            resetBuffer();
            inFrame = true;
            return frame;
        }

        if (!inFrame) {
            return null;
        }

        if (escaping) {
            escaping = false;
            if (b == TFEND) {
                return append(FEND);
            }
            if (b == TFESC) {
                return append(FESC);
            }
            return append(b);
        }

        if (b == FESC) {
            escaping = true;
            return null;
        }

        return append(b);
    }

    /**
     * Проверяет, открыт ли KISS frame и есть ли уже накопленные данные.
     *
     * @return {@code true}, если parser находится между начальным и конечным {@code FEND}
     */
    @Override
    public boolean hasPartialFrame() {
        return inFrame && (buffer.size() > 0 || escaping);
    }

    /**
     * Возвращает parser в начальное состояние и очищает накопленный frame.
     */
    @Override
    public void reset() {
        inFrame = false;
        escaping = false;
        resetBuffer();
    }

    /**
     * Добавляет байт в текущий frame, контролируя максимальный размер frame-а.
     *
     * @param b байт после обработки escape-последовательностей
     * @return всегда {@code null}, так как завершение frame-а происходит только по {@code FEND}
     */
    private byte[] append(byte b) {
        if (buffer.size() >= MAX_FRAME_SIZE) {
            log.warn("KISS frame exceeded {} bytes, resetting parser", MAX_FRAME_SIZE);
            reset();
            return null;
        }
        buffer.write(b);
        return null;
    }

    /**
     * Очищает накопленный payload, не закрывая внешний KISS frame.
     */
    private void resetBuffer() {
        buffer.reset();
        escaping = false;
    }
}
