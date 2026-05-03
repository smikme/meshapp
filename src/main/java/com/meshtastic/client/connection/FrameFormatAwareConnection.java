package com.meshtastic.client.connection;

/**
 * Transport-подключение, в котором можно переключить парсер потока перед запуском protocol runtime-а.
 * <p>
 * Интерфейс реализуют byte-stream transport-ы: TCP и Serial. Это позволяет сначала
 * открыть соединение, затем выполнить auto-detect и только после этого закрепить
 * Meshtastic, KISS или MeshCore Companion framing.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface FrameFormatAwareConnection {

    /**
     * Переключает формат фрейминга для последующих входящих байтов.
     *
     * @param frameFormat новый формат фрейминга
     */
    void setFrameFormat(FrameFormat frameFormat);

    /**
     * Возвращает текущий формат фрейминга transport-а.
     *
     * @return активный формат фрейминга
     */
    FrameFormat getFrameFormat();
}
