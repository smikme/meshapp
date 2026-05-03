package com.meshtastic.client.protocol;

import org.meshtastic.proto.*;

/**
 * Слушатель входящих сообщений {@code FromRadio} от Meshtastic-устройства.
 * <p>
 * Все методы имеют пустую реализацию по умолчанию ({@code default}),
 * что позволяет реализовывать только нужные callback-и.
 * Вызываются из transport reader-потока при получении и парсинге {@code FromRadio}.
 *
 * @see ProtocolHandler
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface FromRadioListener {

    /** Получена информация о локальной ноде (номер, версия прошивки). */
    default void onMyNodeInfo(MeshProtos.MyNodeInfo myInfo) {}

    /** Получена информация о ноде сети (имя, позиция, метрики). */
    default void onNodeInfo(MeshProtos.NodeInfo nodeInfo) {}

    /** Получена секция конфигурации устройства (Device, Position, LoRa и др.). */
    default void onConfig(ConfigProtos.Config config) {}

    /** Получена секция модульной конфигурации (MQTT, Serial, Telemetry и др.). */
    default void onModuleConfig(ModuleConfigProtos.ModuleConfig moduleConfig) {}

    /** Получен канал (PRIMARY или SECONDARY). */
    default void onChannel(ChannelProtos.Channel channel) {}

    /** Config exchange завершён. {@code configCompleteId} должен совпадать с отправленным {@code want_config_id}. */
    default void onConfigComplete(int configCompleteId) {}

    /** Получен mesh-пакет (текстовое сообщение, телеметрия, routing ACK и др.). */
    default void onMeshPacket(MeshProtos.MeshPacket packet) {}

    /** Получено MQTT proxy-сообщение для публикации через клиент / desktop bridge. */
    default void onMqttClientProxyMessage(MeshProtos.MqttClientProxyMessage proxyMessage) {}

    /** Получена запись лога устройства. */
    default void onLogRecord(MeshProtos.LogRecord logRecord) {}

    /** Получен статус очереди отправки устройства. */
    default void onQueueStatus(MeshProtos.QueueStatus queueStatus) {}
}
