package com.meshtastic.client.connection;

import java.util.function.Consumer;

/**
 * Транспортный интерфейс для связи с Meshtastic-устройством.
 * <p>
 * Реализации обеспечивают отправку/приём сырых байтов через конкретный транспорт
 * (TCP, Serial). Полученные данные передаются через callback {@code dataListener}
 * в виде декодированных protobuf-фреймов (после разбора через {@link FrameParser}).
 *
 * @see TcpConnection
 */
public interface MeshtasticConnection {

    /**
     * Устанавливает соединение с устройством.
     *
     * @throws ConnectionException если соединение не удалось установить
     */
    void connect() throws ConnectionException;

    /** Разрывает соединение. Безопасен при повторном вызове. */
    void disconnect();

    /**
     * Проверяет, активно ли соединение.
     *
     * @return {@code true} если соединение установлено и готово к передаче данных
     */
    boolean isConnected();

    /**
     * Отправляет фрейм данных на устройство.
     *
     * @param data байтовый массив с фреймированными данными
     *             (формат: {@code [0x94][0xC3][len_msb][len_lsb][payload]})
     */
    void sendBytes(byte[] data);

    /**
     * Отправляет фрейм данных на устройство с указанием, нужно ли ожидать
     * входящую активность как признак "живого" транспорта после записи.
     * <p>
     * Для transport-ов без отдельного stall-detector поведение совпадает
     * с обычным {@link #sendBytes(byte[])}.
     *
     * @param data байтовый массив с фреймированными данными
     * @param expectResponseAfterWrite {@code true}, если эта запись должна arm-ить
     *                                 receive-stall watchdog; {@code false} для keepalive/heartbeat
     */
    default void sendBytes(byte[] data, boolean expectResponseAfterWrite) {
        sendBytes(data);
    }

    /**
     * Устанавливает слушателя входящих данных. Callback вызывается из потока чтения
     * с декодированным protobuf-payload (без заголовка фрейма).
     *
     * @param listener callback для приёма данных, или {@code null} для отключения
     */
    void setDataListener(Consumer<byte[]> listener);

    /**
     * Устанавливает слушателя событий соединения (подключение, разрыв, ошибка).
     *
     * @param listener слушатель событий, или {@code null} для отключения
     */
    void setConnectionListener(ConnectionListener listener);
}
