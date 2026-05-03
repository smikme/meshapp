package com.meshtastic.client.connection;

/**
 * Состояний парсер, который восстанавливает протокольные сообщения из byte stream.
 * <p>
 * TCP и Serial отдают произвольные куски байтов, поэтому parser хранит промежуточное
 * состояние между вызовами и возвращает frame только после получения полной границы
 * сообщения.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface StreamFrameParser {

    /**
     * Обрабатывает один байт из нижележащего stream-а.
     *
     * @param b очередной байт
     * @return готовый payload frame-а или {@code null}, если frame ещё не завершён
     */
    byte[] processByte(byte b);

    /**
     * Проверяет, хранит ли parser незавершённый frame.
     *
     * @return {@code true}, если внутри parser-а есть частично полученные данные
     */
    boolean hasPartialFrame();

    /**
     * Даёт parser-у шанс завершить frame, граница которого определяется не delimiter-ом,
     * а паузой чтения или inter-byte silence.
     *
     * @return готовый payload frame-а или {@code null}, если сбрасывать нечего
     */
    default byte[] flushPartialFrame() {
        return null;
    }

    /** Сбрасывает состояние parser-а и отбрасывает незавершённый frame. */
    void reset();
}
