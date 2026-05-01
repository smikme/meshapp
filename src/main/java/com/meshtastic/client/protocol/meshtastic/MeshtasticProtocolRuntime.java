package com.meshtastic.client.protocol.meshtastic;

import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.protocol.ProtocolRuntime;
import com.meshtastic.client.protocol.ProtocolRuntimeContext;
import com.meshtastic.client.service.ConfigExchangeService;
import com.meshtastic.client.service.MessageListenerService;
import com.meshtastic.client.service.MessageService;
import com.meshtastic.client.service.MqttProxyService;
import org.meshtastic.proto.MeshProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime-сервисы одного Meshtastic-подключения.
 * <p>
 * Класс объединяет Meshtastic protocol handler, {@link DeviceState},
 * обработчик входящих сообщений, config exchange и MQTT proxy. Благодаря этому
 * {@code ConnectionManager} управляет только transport lifecycle, а вся
 * Meshtastic-специфичная функциональность остаётся в одном адаптере.
 */
public final class MeshtasticProtocolRuntime implements ProtocolRuntime<DeviceState> {

    private static final Logger log = LoggerFactory.getLogger(MeshtasticProtocolRuntime.class);

    private final ProtocolRuntimeContext context;
    private final DeviceState deviceState = new DeviceState();
    private final ProtocolHandler protocolHandler;
    private final MessageListenerService messageListenerService;
    private final MqttProxyService mqttProxyService;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private ConfigExchangeService configExchangeService;
    private CompletableFuture<DeviceState> readyFuture;

    /**
     * Создаёт runtime Meshtastic-протокола поверх переданного transport-а.
     *
     * @param context параметры подключения, transport и описание для логов
     */
    public MeshtasticProtocolRuntime(ProtocolRuntimeContext context) {
        this.context = context;
        this.protocolHandler = new ProtocolHandler(context.connectionId(), context.transportConnection());
        this.messageListenerService = new MessageListenerService(deviceState, protocolHandler);
        this.mqttProxyService = new MqttProxyService(
                context.connectionId(),
                context.connectionEntry().getName(),
                protocolHandler,
                deviceState
        );
    }

    /**
     * @return тип протокола runtime-а
     */
    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.MESHTASTIC;
    }

    /**
     * @return состояние Meshtastic-устройства, которое наполняется config exchange и входящими пакетами
     */
    @Override
    public DeviceState getState() {
        return deviceState;
    }

    /**
     * Совместимый accessor для существующего UI-кода.
     *
     * @return состояние Meshtastic-устройства
     */
    public DeviceState getDeviceState() {
        return deviceState;
    }

    /**
     * Совместимый accessor для форм и сервисов, которые пока отправляют Meshtastic-команды напрямую.
     *
     * @return dispatcher Meshtastic-протокола
     */
    public ProtocolHandler getProtocolHandler() {
        return protocolHandler;
    }

    /**
     * Совместимый accessor к сервису входящих сообщений.
     *
     * @return сервис обработки входящих mesh-пакетов
     */
    public MessageListenerService getMessageListenerService() {
        return messageListenerService;
    }

    /**
     * Подготавливает runtime к ожидаемому разрыву соединения при reboot/reconnect.
     * <p>
     * Сейчас это нужно Meshtastic, чтобы остановить MQTT proxy до передачи
     * управления обычному auto-reconnect flow.
     */
    public void prepareForReconnectHandoff() {
        mqttProxyService.close();
    }

    /**
     * Возвращает future завершения Meshtastic config exchange.
     *
     * @return future с заполненным {@link DeviceState}
     */
    @Override
    public CompletableFuture<DeviceState> getReadyFuture() {
        if (readyFuture == null) {
            CompletableFuture<DeviceState> notStarted = new CompletableFuture<>();
            notStarted.completeExceptionally(new IllegalStateException("Протокольный runtime ещё не запущен"));
            return notStarted;
        }
        return readyFuture;
    }

    /**
     * Запускает Meshtastic runtime: heartbeat при необходимости, listener входящих
     * mesh-пакетов и config exchange.
     *
     * @return future, завершающийся после config exchange
     */
    @Override
    public CompletableFuture<DeviceState> start() {
        if (readyFuture != null) {
            return readyFuture;
        }
        if (MeshtasticProtocol.shouldStartHeartbeat(context.connectionEntry())) {
            protocolHandler.startHeartbeat();
        }

        protocolHandler.addListener(messageListenerService);
        configExchangeService = new ConfigExchangeService(protocolHandler, deviceState);
        readyFuture = configExchangeService.startConfigExchange();
        return readyFuture;
    }

    /**
     * Возвращает nodeId локального Meshtastic-устройства.
     *
     * @return nodeId вида {@code !1234abcd} или {@code ?}, если устройство ещё не определено
     */
    @Override
    public String getOwnerId() {
        return resolveLocalNodeId(deviceState);
    }

    /**
     * Выполняет post-connect действия после успешного config exchange:
     * логирует сведения о ноде, запускает MQTT proxy при включенной настройке и
     * запрашивает firmware metadata.
     */
    @Override
    public void onReady() {
        logNodeConnectionContext(context.connectionEntry(), deviceState);
        mqttProxyService.startIfEnabled();
        requestAndLogDeviceMetadata(context.connectionEntry(), protocolHandler, deviceState);
    }

    /**
     * Освобождает все Meshtastic-ресурсы и переводит pending операции в состояние disconnect.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        deviceState.failAllPendingAcks("DISCONNECTED");
        deviceState.failAllPendingPacketAcks("DISCONNECTED");
        deviceState.shutdown();
        messageListenerService.getNotificationManager().dispose();
        mqttProxyService.close();
        if (configExchangeService != null) {
            configExchangeService.abort("connection cleanup");
        }
        protocolHandler.shutdown();
        if (readyFuture != null && !readyFuture.isDone()) {
            readyFuture.completeExceptionally(new CancellationException("Protocol runtime closed"));
        }
    }

    /**
     * Запрашивает firmware/device metadata и пишет результат в лог.
     */
    private void requestAndLogDeviceMetadata(ConnectionEntry entry,
                                             ProtocolHandler handler,
                                             DeviceState state) {
        if (handler == null || state == null || state.getMyNodeNum() == 0) {
            return;
        }

        Runnable[] listenerHolder = new Runnable[1];
        listenerHolder[0] = () -> {
            MeshProtos.DeviceMetadata metadata = state.getDeviceMetadata();
            if (metadata == null) {
                return;
            }
            state.removeDeviceMetadataListener(listenerHolder[0]);
            log.info("Connection '{}' firmware identified: name='{}', nodeId={}, firmware='{}', params={}",
                    entry.getName(),
                    resolveLocalNodeName(state),
                    resolveLocalNodeId(state),
                    safeText(metadata.getFirmwareVersion()),
                    context.transportDescription());
        };

        state.addDeviceMetadataListener(listenerHolder[0]);
        if (state.getDeviceMetadata() != null) {
            listenerHolder[0].run();
            return;
        }

        MessageService.requestDeviceMetadata(handler, state)
                .whenComplete((routingError, throwable) -> {
                    if (throwable != null) {
                        state.removeDeviceMetadataListener(listenerHolder[0]);
                        if (isExpectedDisconnectAbort(throwable)) {
                            log.debug("Device metadata request for '{}' aborted during disconnect",
                                    entry.getName());
                        } else {
                            log.debug("Device metadata request failed for '{}'", entry.getName(), throwable);
                        }
                    } else if (routingError != null && routingError != MeshProtos.Routing.Error.NONE) {
                        state.removeDeviceMetadataListener(listenerHolder[0]);
                        log.debug("Device metadata request for '{}' completed with {}",
                                entry.getName(), routingError);
                    }
                });
    }

    /**
     * Определяет, является ли ошибка ожидаемым следствием очистки runtime-а при disconnect.
     */
    private static boolean isExpectedDisconnectAbort(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("Packet ACK waiter aborted: DISCONNECTED")
                    || message.contains("Packet ACK waiter aborted: STATE_CLEARED"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Логирует базовый контекст локальной ноды после config exchange.
     */
    private void logNodeConnectionContext(ConnectionEntry entry, DeviceState state) {
        NodeData node = resolveLocalNode(state);
        log.info("Connection '{}' node identified: name='{}', short='{}', nodeId={}, hwModel={}, params={}",
                entry.getName(),
                resolveLocalNodeName(state),
                node != null ? safeText(node.getShortName()) : "?",
                resolveLocalNodeId(state),
                node != null ? safeText(node.getHwModel()) : "?",
                context.transportDescription());
    }

    /**
     * Находит в {@link DeviceState} данные локальной ноды.
     */
    private static NodeData resolveLocalNode(DeviceState state) {
        if (state == null || state.getMyNodeNum() == 0) {
            return null;
        }
        return state.getNodeDb().get(state.getMyNodeNum());
    }

    /**
     * Возвращает лучшее доступное имя локальной ноды для логов.
     */
    private static String resolveLocalNodeName(DeviceState state) {
        NodeData node = resolveLocalNode(state);
        if (node == null) {
            return resolveLocalNodeId(state);
        }
        if (node.getLongName() != null && !node.getLongName().isBlank()) {
            return node.getLongName().trim();
        }
        if (node.getShortName() != null && !node.getShortName().isBlank()) {
            return node.getShortName().trim();
        }
        if (node.getNodeId() != null && !node.getNodeId().isBlank()) {
            return node.getNodeId().trim();
        }
        return resolveLocalNodeId(state);
    }

    /**
     * Возвращает Meshtastic nodeId локальной ноды или placeholder, если он ещё неизвестен.
     */
    private static String resolveLocalNodeId(DeviceState state) {
        NodeData node = resolveLocalNode(state);
        if (node != null && node.getNodeId() != null && !node.getNodeId().isBlank()) {
            return node.getNodeId().trim();
        }
        int myNodeNum = state != null ? state.getMyNodeNum() : 0;
        return myNodeNum != 0 ? String.format("!%08x", myNodeNum) : "?";
    }

    /**
     * Нормализует строковое значение для логов.
     */
    private static String safeText(String value) {
        return value == null || value.isBlank() ? "?" : value.trim();
    }
}
