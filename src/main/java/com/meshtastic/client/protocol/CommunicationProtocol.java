package com.meshtastic.client.protocol;

import com.meshtastic.client.model.ProtocolType;

/**
 * Фабрика runtime-а коммуникационного протокола.
 * <p>
 * Протокольный адаптер отвечает за парсинг входящих payload-ов, формирование
 * исходящих команд и запуск сервисов, специфичных для конкретного протокола.
 * На вход он получает уже открытый byte transport от {@code ConnectionManager}
 * и возвращает runtime-объект для управления жизненным циклом и состоянием.
 *
 * @param <S> тип состояния, которое ведёт конкретный протокол
 */
public interface CommunicationProtocol<S> {

    /**
     * Возвращает тип протокола, по которому адаптер регистрируется в {@link ProtocolRegistry}.
     *
     * @return тип протокола из профиля подключения
     */
    ProtocolType getType();

    /**
     * Создаёт runtime протокола для одного transport-соединения.
     *
     * @param context неизменяемые параметры подключения и открытый transport
     * @return runtime, который будет запущен менеджером подключений
     */
    ProtocolRuntime<S> createRuntime(ProtocolRuntimeContext context);
}
