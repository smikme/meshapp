package com.meshtastic.client.protocol;

import com.meshtastic.client.connection.TransportConnection;
import com.meshtastic.client.model.ConnectionEntry;

/**
 * Неизменяемый набор входных данных для создания протокольного runtime-а.
 *
 * @param connectionId строковый id профиля подключения
 * @param connectionEntry сохранённый профиль подключения
 * @param transportConnection открытый transport, через который протокол пишет и читает байты
 * @param transportDescription человекочитаемое описание транспорта для логов
 */
public record ProtocolRuntimeContext(String connectionId,
                                     ConnectionEntry connectionEntry,
                                     TransportConnection transportConnection,
                                     String transportDescription) {
}
