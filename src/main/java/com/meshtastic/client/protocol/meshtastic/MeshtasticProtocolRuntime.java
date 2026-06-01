package com.meshtastic.client.protocol.meshtastic;

import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.protocol.FromRadioListener;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.protocol.ProtocolRuntime;
import com.meshtastic.client.protocol.ProtocolRuntimeContext;
import com.meshtastic.client.service.ConfigExchangeService;
import com.meshtastic.client.service.ConnectionManager;
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
 * Runtime services for one Meshtastic connection.
 * <p>
 * Combines the Meshtastic protocol handler, {@link DeviceState}, incoming
 * message processing, config exchange, and MQTT proxy. This lets
 * {@code ConnectionManager} manage only the transport lifecycle while
 * Meshtastic-specific behavior stays in one adapter.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class MeshtasticProtocolRuntime implements ProtocolRuntime<DeviceState> {

    private static final Logger log = LoggerFactory.getLogger(MeshtasticProtocolRuntime.class);

    private final ProtocolRuntimeContext context;
    private final DeviceState deviceState = new DeviceState();
    private final ProtocolHandler protocolHandler;
    private final MessageListenerService messageListenerService;
    private final MqttProxyService mqttProxyService;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean reconnectHandoffRequested = new AtomicBoolean(false);
    private final FromRadioListener rebootListener = new FromRadioListener() {
        @Override
        public void onRebooted() {
            handleRadioRebooted();
        }
    };

    private ConfigExchangeService configExchangeService;
    private CompletableFuture<DeviceState> readyFuture;

    /**
     * Creates a Meshtastic protocol runtime over the supplied transport.
 *
     * @param context connection parameters, transport, and log description
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
     * @return runtime protocol type
     */
    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.MESHTASTIC;
    }

    /**
     * @return Meshtastic device state populated by config exchange and incoming packets
     */
    @Override
    public DeviceState getState() {
        return deviceState;
    }

    /**
     * Compatibility accessor for existing UI code.
 *
     * @return Meshtastic device state
     */
    public DeviceState getDeviceState() {
        return deviceState;
    }

    /**
     * Compatibility accessor for forms and services that still send Meshtastic
     * commands directly.
 *
     * @return Meshtastic protocol dispatcher
     */
    public ProtocolHandler getProtocolHandler() {
        return protocolHandler;
    }

    /**
     * Compatibility accessor for the incoming-message service.
 *
     * @return service that processes incoming mesh packets
     */
    public MessageListenerService getMessageListenerService() {
        return messageListenerService;
    }

    /**
     * Prepares the runtime for an expected disconnect during reboot/reconnect.
     * <p>
     * Meshtastic currently needs this to stop the MQTT proxy before handing
     * control back to the normal auto-reconnect flow.
     */
    public void prepareForReconnectHandoff() {
        reconnectHandoffRequested.set(true);
        mqttProxyService.close();
    }

    /**
     * Returns the Meshtastic config-exchange completion future.
 *
     * @return future completed with the populated {@link DeviceState}
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
     * Starts the Meshtastic runtime: heartbeat when needed, incoming mesh-packet
     * listener, and config exchange.
 *
     * @return future completed after config exchange
     */
    @Override
    public CompletableFuture<DeviceState> start() {
        if (readyFuture != null) {
            return readyFuture;
        }
        if (MeshtasticProtocol.shouldStartHeartbeat(context.connectionEntry())) {
            protocolHandler.startHeartbeat();
        }

        protocolHandler.addListener(rebootListener);
        protocolHandler.addListener(messageListenerService);
        configExchangeService = new ConfigExchangeService(protocolHandler, deviceState);
        readyFuture = configExchangeService.startConfigExchange();
        return readyFuture;
    }

    /**
     * Returns the nodeId of the local Meshtastic device.
 *
     * @return nodeId in the {@code !1234abcd} form, or {@code ?} before the device is known
     */
    @Override
    public String getOwnerId() {
        return resolveLocalNodeId(deviceState);
    }

    /**
     * Runs post-connect work after successful config exchange: logs node context,
     * starts the MQTT proxy when enabled, and requests firmware metadata.
     */
    @Override
    public void onReady() {
        logNodeConnectionContext(context.connectionEntry(), deviceState);
        mqttProxyService.startIfEnabled();
        requestAndLogDeviceMetadata(context.connectionEntry(), protocolHandler, deviceState);
    }

    /**
     * Releases all Meshtastic resources and marks pending operations as disconnected.
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
        protocolHandler.removeListener(rebootListener);
        if (configExchangeService != null) {
            configExchangeService.abort("connection cleanup");
        }
        protocolHandler.shutdown();
        if (readyFuture != null && !readyFuture.isDone()) {
            readyFuture.completeExceptionally(new CancellationException("Protocol runtime closed"));
        }
    }

    /**
     * Requests firmware/device metadata and writes the result to the log.
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

    private void handleRadioRebooted() {
        if (closed.get() || !reconnectHandoffRequested.compareAndSet(false, true)) {
            return;
        }

        log.info("Connection '{}' radio reported reboot; switching to reconnect flow",
                context.connectionEntry().getName());
        boolean handoffStarted = ConnectionManager.getInstance()
                .disconnectForDeviceRebootFromRuntime(context.connectionId(), this);
        if (!handoffStarted) {
            log.debug("Connection '{}' reboot handoff skipped because runtime is no longer current",
                    context.connectionEntry().getName());
        }
    }

    /**
     * Checks whether an error is an expected consequence of runtime cleanup during disconnect.
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
     * Logs the local node's basic context after config exchange.
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
     * Finds local-node data in {@link DeviceState}.
     */
    private static NodeData resolveLocalNode(DeviceState state) {
        if (state == null || state.getMyNodeNum() == 0) {
            return null;
        }
        return state.getNodeDb().get(state.getMyNodeNum());
    }

    /**
     * Returns the best available local-node name for logs.
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
     * Returns the local Meshtastic nodeId, or a placeholder if it is still unknown.
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
     * Normalizes a string value for logs.
     */
    private static String safeText(String value) {
        return value == null || value.isBlank() ? "?" : value.trim();
    }
}
