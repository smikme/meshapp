package com.meshtastic.client.protocol;

import com.meshtastic.client.model.ProtocolType;

import java.util.concurrent.CompletableFuture;

/**
 * Runtime-экземпляр одного коммуникационного протокола поверх одного transport-соединения.
 * <p>
 * Runtime связывает transport с протокольными сервисами: слушателями входящих
 * сообщений, начальным handshake/config exchange, состоянием устройства и
 * post-connect действиями.
 *
 * @param <S> тип состояния, специфичный для протокола
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface ProtocolRuntime<S> extends AutoCloseable {

    /**
     * @return тип протокола этого runtime-а
     */
    ProtocolType getProtocolType();

    /**
     * @return текущее состояние протокола/устройства
     */
    S getState();

    /**
     * Возвращает future готовности runtime-а.
     * <p>
     * Для Meshtastic это завершение config exchange; для других протоколов это
     * может быть авторизация, handshake или иная начальная синхронизация.
     *
     * @return future, завершающийся после готовности протокола
     */
    CompletableFuture<S> getReadyFuture();

    /**
     * Запускает протокольные слушатели и начальный handshake/config exchange.
     *
     * @return future готовности, совпадающий по смыслу с {@link #getReadyFuture()}
     */
    CompletableFuture<S> start();

    /**
     * Стабильный идентификатор локального/владельческого устройства,
     * если протокол уже смог его определить.
     *
     * @return идентификатор владельца или {@code null}, если он неизвестен
     */
    default String getOwnerId() {
        return null;
    }

    /**
     * Вызывается менеджером подключений после успешной готовности runtime-а,
     * если transport всё ещё активен.
     */
    default void onReady() {
    }

    /**
     * Освобождает протокольные ресурсы: слушатели, scheduler-ы, pending ACK-и,
     * вспомогательные сервисы и состояние.
     */
    @Override
    void close();
}
