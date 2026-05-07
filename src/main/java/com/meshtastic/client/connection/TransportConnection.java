package com.meshtastic.client.connection;

import java.util.function.Consumer;

/**
 * Низкоуровневое транспортное соединение, которое используется протокольными адаптерами.
 * <p>
 * Реализации отвечают только за жизненный цикл byte stream: открыть соединение,
 * закрыть соединение, отправить подготовленные байты и передать входящие payload-ы
 * на уровень протокола. В этом интерфейсе не должно быть бизнес-логики конкретной
 * радиосети или сетевого протокола.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface TransportConnection {

    /**
     * Открывает транспортное соединение.
     *
     * @throws ConnectionException если транспорт не удалось открыть
     */
    void connect() throws ConnectionException;

    /** Закрывает транспорт. Метод безопасен при повторном вызове. */
    void disconnect();

    /**
     * @return {@code true}, если транспорт открыт и готов к записи
     */
    boolean isConnected();

    /**
     * Отправляет через транспорт байтовый массив, уже подготовленный активным протоколом.
     *
     * @param data байты, подготовленные активным протокольным адаптером
     */
    void sendBytes(byte[] data);

    /**
     * Отправляет байты и сообщает transport-ам с receive watchdog,
     * должна ли эта запись ожидать входящую активность.
     *
     * @param data байты, подготовленные активным протокольным адаптером
     * @param expectResponseAfterWrite {@code true} для обычных запросов,
     *                                 {@code false} для keepalive/heartbeat-записей
     */
    default void sendBytes(byte[] data, boolean expectResponseAfterWrite) {
        sendBytes(data);
    }

    /**
     * Регистрирует слушателя входящих protocol payload-ов, которые уже извлечены
     * transport framing layer из конкретного TCP/Serial/BLE потока.
     *
     * @param listener callback для полученных payload-байтов, или {@code null}
     */
    void setDataListener(Consumer<byte[]> listener);

    /**
     * Регистрирует слушателя событий жизненного цикла транспорта.
     *
     * @param listener слушатель событий подключения/отключения/ошибки, или {@code null}
     */
    void setConnectionListener(ConnectionListener listener);
}
