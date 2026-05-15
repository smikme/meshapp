package com.meshtastic.client.service;

import com.google.protobuf.InvalidProtocolBufferException;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.protocol.FromRadioListener;
import com.meshtastic.client.protocol.ProtocolHandler;
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;
import org.meshtastic.proto.MQTTProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Desktop-side MQTT bridge for nodes that proxy MQTT through the connected client.
 * <p>
 * The service intentionally stays transport-agnostic: it forwards topic/payload/retained
 * between Meshtastic {@code MqttClientProxyMessage} and the external broker without
 * trying to reinterpret Meshtastic MQTT envelopes.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class MqttProxyService implements FromRadioListener, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MqttProxyService.class);

    static final String DEFAULT_MQTT_ROOT = "msh";
    static final String DEFAULT_BROKER_HOST = "mqtt.meshtastic.org";
    static final int DEFAULT_TCP_PORT = 1883;
    static final int DEFAULT_TLS_PORT = 8883;
    static final long LOCAL_ECHO_TTL_MS = 10_000;
    private static final long DOWNLINK_DIRECT_SEND_MIN_INTERVAL_MS = 75L;
    private static final long DOWNLINK_BACKGROUND_SEND_MIN_INTERVAL_MS = 250L;
    private static final long DOWNLINK_BACKGROUND_YIELD_AFTER_LOCAL_UPLINK_MS = 2_000L;
    private static final int MQTT_QOS = 0;
    private static final int DISCONNECTED_BUFFER_SIZE = 256;
    private static final int DOWNLINK_QUEUE_SIZE = 16_384;
    private static final int DOWNLINK_PRIORITY_DIRECT_TO_LOCAL = 0;
    private static final int DOWNLINK_PRIORITY_DEFAULT = 1;
    private static final long DOWNLINK_QUEUE_LOG_INTERVAL_SECONDS = 5L;

    private final String connectionId;
    private final String connectionName;
    private final ProtocolHandler protocolHandler;
    private final DeviceState deviceState;
    private final Object lifecycleLock = new Object();
    private final LocalEchoSuppressor localEchoSuppressor = new LocalEchoSuppressor(LOCAL_ECHO_TTL_MS);
    private final PriorityBlockingQueue<DownlinkMessage> downlinkQueue = new PriorityBlockingQueue<>();
    private final Semaphore downlinkSlots = new Semaphore(DOWNLINK_QUEUE_SIZE);
    private final AtomicLong downlinkSequence = new AtomicLong();
    private final Thread downlinkThread;
    private final ScheduledExecutorService downlinkQueueLogScheduler;

    private volatile boolean closed;
    private volatile boolean listenerRegistered;
    private volatile MqttAsyncClient client;
    private volatile ProxyConfig activeConfig;
    private volatile long lastDownlinkForwardAtMillis;
    private volatile long lastLocalMqttUplinkAtMillis;
    private volatile ScheduledFuture<?> downlinkQueueLogFuture;

    public MqttProxyService(String connectionId,
                            String connectionName,
                            ProtocolHandler protocolHandler,
                            DeviceState deviceState) {
        this.connectionId = connectionId;
        this.connectionName = connectionName;
        this.protocolHandler = protocolHandler;
        this.deviceState = deviceState;
        this.downlinkThread = new Thread(
                this::downlinkLoop,
                "mqtt-proxy-downlink-" + sanitizeThreadSuffix(connectionId)
        );
        this.downlinkThread.setDaemon(true);
        this.downlinkThread.start();
        this.downlinkQueueLogScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mqtt-proxy-queue-log-" + sanitizeThreadSuffix(connectionId));
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts the bridge only when the device explicitly requests client-side MQTT proxying.
     */
    public void startIfEnabled() {
        ProxyState proxyState = evaluateProxyState(deviceState);
        ProxyConfig config = proxyState.config();
        if (config == null) {
            log.debug("MQTT proxy is disabled for '{}': {}", connectionName, proxyState.reason());
            return;
        }

        synchronized (lifecycleLock) {
            if (closed || client != null) {
                return;
            }

            try {
                String brokerUri = buildBrokerUri(config.address(), config.tlsEnabled());
                String clientId = buildClientId(deviceState, connectionId);
                MqttAsyncClient mqttClient = new MqttAsyncClient(
                        brokerUri,
                        clientId,
                        new MemoryPersistence()
                );
                mqttClient.setBufferOpts(buildDisconnectedBufferOptions());
                mqttClient.setCallback(new ProxyCallback());

                this.client = mqttClient;
                this.activeConfig = config;
                startDownlinkQueueLogging();

                if (!listenerRegistered) {
                    protocolHandler.addListener(this);
                    listenerRegistered = true;
                }

                log.info("Starting MQTT proxy for '{}' -> broker='{}', root='{}', clientId='{}'",
                        connectionName, brokerUri, config.root(), clientId);
                mqttClient.connect(buildConnectOptions(config), null, new IMqttActionListener() {
                    @Override
                    public void onSuccess(IMqttToken asyncActionToken) {
                        log.debug("Initial MQTT proxy connect initiated for '{}'", connectionName);
                    }

                    @Override
                    public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                        if (closed) {
                            return;
                        }
                        log.warn("Initial MQTT proxy connect failed for '{}': {}",
                                connectionName,
                                exception != null ? exception.getMessage() : "unknown error");
                    }
                });
            } catch (Exception e) {
                log.error("Failed to start MQTT proxy for '{}'", connectionName, e);
                stopClient(true);
            }
        }
    }

    @Override
    public void onMqttClientProxyMessage(MeshProtos.MqttClientProxyMessage proxyMessage) {
        if (proxyMessage == null || proxyMessage.getTopic().isBlank()) {
            return;
        }

        MqttAsyncClient mqttClient = client;
        if (mqttClient == null || activeConfig == null) {
            log.debug("Dropping MQTT proxy uplink for '{}': bridge is not started", connectionName);
            return;
        }

        byte[] payload = extractPayload(proxyMessage);
        lastLocalMqttUplinkAtMillis = System.currentTimeMillis();
        // The broker can echo QoS 0 publishes back to this subscribed client before publish() returns.
        RecentPublication publication = localEchoSuppressor.remember(proxyMessage.getTopic(), payload);
        try {
            mqttClient.publish(proxyMessage.getTopic(), payload, MQTT_QOS, proxyMessage.getRetained());
            log.debug("Forwarded MQTT uplink for '{}': topic='{}' bytes={} retained={}",
                    connectionName, proxyMessage.getTopic(), payload.length, proxyMessage.getRetained());
        } catch (MqttException e) {
            localEchoSuppressor.forget(publication);
            log.warn("Failed to publish MQTT proxy uplink for '{}' topic='{}'",
                    connectionName, proxyMessage.getTopic(), e);
        }
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        stopClient(true);
        downlinkThread.interrupt();
        downlinkQueueLogScheduler.shutdownNow();
        downlinkQueue.clear();
        downlinkSlots.release(DOWNLINK_QUEUE_SIZE);
    }

    static ProxyConfig loadProxyConfig(DeviceState state) {
        return evaluateProxyState(state).config();
    }

    static ProxyState evaluateProxyState(DeviceState state) {
        if (state == null) {
            return new ProxyState(null, "device state is unavailable");
        }

        List<ModuleConfigProtos.ModuleConfig> moduleConfigs = state.getModuleConfigs();
        synchronized (moduleConfigs) {
            for (ModuleConfigProtos.ModuleConfig moduleConfig : moduleConfigs) {
                if (!moduleConfig.hasMqtt()) {
                    continue;
                }
                ModuleConfigProtos.ModuleConfig.MQTTConfig mqttConfig = moduleConfig.getMqtt();
                if (!mqttConfig.getEnabled() || !mqttConfig.getProxyToClientEnabled()) {
                    return new ProxyState(
                            null,
                            "mqtt.enabled=" + mqttConfig.getEnabled()
                                    + ", proxy_to_client_enabled=" + mqttConfig.getProxyToClientEnabled()
                    );
                }
                return new ProxyState(
                        new ProxyConfig(
                                effectiveAddress(mqttConfig.getAddress(), mqttConfig.getTlsEnabled()),
                                trimToNull(mqttConfig.getUsername()),
                                trimToNull(mqttConfig.getPassword()),
                                effectiveRoot(mqttConfig.getRoot()),
                                mqttConfig.getTlsEnabled()
                        ),
                        "enabled"
                );
            }
        }
        return new ProxyState(null, "MQTT module config was not received from the device");
    }

    static String buildBrokerUri(String address, boolean tlsEnabled) {
        String rawAddress = trimToNull(address);
        if (rawAddress == null) {
            rawAddress = defaultBrokerAddress(tlsEnabled);
        }
        if (rawAddress.contains("://")) {
            return rawAddress;
        }

        String hostPort = rawAddress;
        if (!rawAddress.startsWith("[") && rawAddress.indexOf(':') < 0) {
            hostPort = rawAddress + ":" + (tlsEnabled ? DEFAULT_TLS_PORT : DEFAULT_TCP_PORT);
        }
        String scheme = tlsEnabled ? "ssl://" : "tcp://";
        return scheme + hostPort;
    }

    static String effectiveRoot(String root) {
        String trimmed = trimToNull(root);
        return trimmed != null ? trimmed : DEFAULT_MQTT_ROOT;
    }

    static String effectiveAddress(String address, boolean tlsEnabled) {
        String trimmed = trimToNull(address);
        return trimmed != null ? trimmed : defaultBrokerAddress(tlsEnabled);
    }

    static String defaultBrokerAddress(boolean tlsEnabled) {
        return DEFAULT_BROKER_HOST + ":" + (tlsEnabled ? DEFAULT_TLS_PORT : DEFAULT_TCP_PORT);
    }

    static byte[] extractPayload(MeshProtos.MqttClientProxyMessage proxyMessage) {
        return switch (proxyMessage.getPayloadVariantCase()) {
            case DATA -> proxyMessage.getData().toByteArray();
            case TEXT -> proxyMessage.getText().getBytes(StandardCharsets.UTF_8);
            case PAYLOADVARIANT_NOT_SET -> new byte[0];
        };
    }

    static String buildClientId(DeviceState state, String connectionId) {
        String nodeId = resolveLocalNodeId(state);
        if (nodeId != null) {
            return "MeshAppMqttProxy-" + nodeId;
        }

        String compact = connectionId == null ? "" : connectionId.replace("-", "");
        if (compact.length() > 14) {
            compact = compact.substring(0, 14);
        }
        if (compact.isBlank()) {
            compact = Long.toHexString(System.nanoTime());
        }
        return "MeshAppMqttProxy-" + compact.toLowerCase(Locale.ROOT);
    }

    private static String resolveLocalNodeId(DeviceState state) {
        if (state == null) {
            return null;
        }
        int myNodeNum = state.getMyNodeNum();
        if (myNodeNum == 0) {
            return null;
        }
        return String.format(Locale.ROOT, "!%08x", myNodeNum);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static MqttConnectOptions buildConnectOptions(ProxyConfig config) {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(30);
        options.setMaxInflight(128);
        if (config.username() != null) {
            options.setUserName(config.username());
        }
        if (config.password() != null) {
            options.setPassword(config.password().toCharArray());
        }
        return options;
    }

    private static DisconnectedBufferOptions buildDisconnectedBufferOptions() {
        DisconnectedBufferOptions options = new DisconnectedBufferOptions();
        options.setBufferEnabled(true);
        options.setBufferSize(DISCONNECTED_BUFFER_SIZE);
        options.setDeleteOldestMessages(true);
        options.setPersistBuffer(false);
        return options;
    }

    private void stopClient(boolean removeListener) {
        MqttAsyncClient mqttClient;
        synchronized (lifecycleLock) {
            mqttClient = client;
            client = null;
            activeConfig = null;
            localEchoSuppressor.clear();
            stopDownlinkQueueLogging();
            if (removeListener && listenerRegistered) {
                protocolHandler.removeListener(this);
                listenerRegistered = false;
            }
        }

        if (mqttClient == null) {
            return;
        }

        try {
            if (mqttClient.isConnected()) {
                mqttClient.disconnect().waitForCompletion(2_000);
            }
        } catch (MqttException e) {
            log.debug("MQTT proxy disconnect for '{}' completed with error", connectionName, e);
        } finally {
            try {
                mqttClient.close();
            } catch (MqttException e) {
                log.debug("Failed to close MQTT client for '{}'", connectionName, e);
            }
        }
    }

    private void onBrokerMessage(String topic, MqttMessage mqttMessage) {
        if (topic == null || topic.isBlank() || mqttMessage == null || closed) {
            return;
        }

        byte[] payload = mqttMessage.getPayload();
        if (payload == null) {
            payload = new byte[0];
        } else {
            payload = payload.clone();
        }
        boolean retained = mqttMessage.isRetained();
        if (localEchoSuppressor.consume(topic, payload)) {
            log.debug("Suppressed MQTT loopback for '{}' topic='{}' bytes={} retained={}",
                    connectionName, topic, payload.length, retained);
            return;
        }
        if (isFromLocalNode(topic, payload, deviceState)) {
            log.debug("Suppressed local MQTT publication for '{}' topic='{}' bytes={} retained={}",
                    connectionName, topic, payload.length, retained);
            return;
        }

        boolean addressedToLocalNode = isAddressedToLocalNode(payload, deviceState);
        DownlinkMessage downlinkMessage = new DownlinkMessage(
                addressedToLocalNode ? DOWNLINK_PRIORITY_DIRECT_TO_LOCAL : DOWNLINK_PRIORITY_DEFAULT,
                downlinkSequence.getAndIncrement(),
                topic,
                payload,
                retained,
                addressedToLocalNode
        );
        int queueDepth = downlinkQueue.size();
        log.trace("Received MQTT downlink for '{}': topic='{}' bytes={} retained={} directToLocal={} queueDepth={}/{}",
                connectionName, topic, payload.length, retained, addressedToLocalNode, queueDepth, DOWNLINK_QUEUE_SIZE);
        enqueueDownlink(downlinkMessage);
    }

    private void downlinkLoop() {
        log.debug("MQTT proxy downlink thread started for '{}'", connectionName);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                DownlinkMessage message = downlinkQueue.take();
                if (shouldYieldBackgroundDownlink(message)) {
                    downlinkQueue.offer(message);
                    sleepBeforeRetryingBackgroundDownlink();
                    continue;
                }
                try {
                    forwardBrokerMessageToRadio(message);
                } finally {
                    downlinkSlots.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error processing MQTT downlink for '{}'", connectionName, e);
            }
        }
        log.debug("MQTT proxy downlink thread exiting for '{}'", connectionName);
    }

    private void enqueueDownlink(DownlinkMessage message) {
        boolean acquired = false;
        while (!closed) {
            try {
                downlinkSlots.acquire();
                acquired = true;
                break;
            } catch (InterruptedException e) {
                if (closed) {
                    Thread.currentThread().interrupt();
                    return;
                }
                log.warn("Interrupted while waiting for MQTT downlink queue space for '{}'; retrying",
                        connectionName);
            }
        }
        if (!acquired) {
            return;
        }
        if (closed) {
            downlinkSlots.release();
            return;
        }
        downlinkQueue.offer(message);
    }

    private void startDownlinkQueueLogging() {
        ScheduledFuture<?> current = downlinkQueueLogFuture;
        if (current != null && !current.isDone()) {
            return;
        }
        downlinkQueueLogFuture = downlinkQueueLogScheduler.scheduleWithFixedDelay(
                this::logDownlinkQueueDepthSafely,
                DOWNLINK_QUEUE_LOG_INTERVAL_SECONDS,
                DOWNLINK_QUEUE_LOG_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    private void stopDownlinkQueueLogging() {
        ScheduledFuture<?> current = downlinkQueueLogFuture;
        downlinkQueueLogFuture = null;
        if (current != null) {
            current.cancel(false);
        }
    }

    private void logDownlinkQueueDepthSafely() {
        try {
            if (closed || client == null || activeConfig == null) {
                return;
            }
            log.debug("MQTT downlink queue for '{}': currentSize={}, maxSize={}",
                    connectionName, downlinkQueue.size(), DOWNLINK_QUEUE_SIZE);
        } catch (Throwable t) {
            log.warn("Failed to log MQTT downlink queue depth for '{}'", connectionName, t);
        }
    }

    private void forwardBrokerMessageToRadio(DownlinkMessage message) {
        if (closed) {
            return;
        }
        if (!awaitDownlinkSendWindow(message.addressedToLocalNode())) {
            return;
        }
        try {
            MessageService.sendMqttClientProxyMessage(
                    protocolHandler,
                    message.topic(),
                    message.payload(),
                    message.retained()
            );
        } catch (RuntimeException e) {
            log.warn("Failed to forward MQTT downlink for '{}' topic='{}'", connectionName, message.topic(), e);
            return;
        }
        log.trace("Forwarded MQTT downlink for '{}': topic='{}' bytes={} retained={} directToLocal={}",
                connectionName, message.topic(), message.payload().length,
                message.retained(), message.addressedToLocalNode());
    }

    static boolean isAddressedToLocalNode(byte[] payload, DeviceState state) {
        if (payload == null || payload.length == 0 || state == null || state.getMyNodeNum() == 0) {
            return false;
        }
        try {
            MQTTProtos.ServiceEnvelope envelope = MQTTProtos.ServiceEnvelope.parseFrom(payload);
            return envelope.hasPacket() && envelope.getPacket().getTo() == state.getMyNodeNum();
        } catch (InvalidProtocolBufferException e) {
            return false;
        }
    }

    static boolean isFromLocalNode(String topic, byte[] payload, DeviceState state) {
        String localNodeId = resolveLocalNodeId(state);
        if (localNodeId == null) {
            return false;
        }
        return topic != null && topic.endsWith("/" + localNodeId);
    }

    private boolean shouldYieldBackgroundDownlink(DownlinkMessage message) {
        return message != null
                && !message.addressedToLocalNode()
                && backgroundYieldRemainingMillis(lastLocalMqttUplinkAtMillis, System.currentTimeMillis()) > 0;
    }

    static long backgroundYieldRemainingMillis(long lastLocalUplinkAtMillis, long nowMillis) {
        if (lastLocalUplinkAtMillis <= 0) {
            return 0;
        }
        long quietUntil = lastLocalUplinkAtMillis + DOWNLINK_BACKGROUND_YIELD_AFTER_LOCAL_UPLINK_MS;
        return Math.max(0, quietUntil - nowMillis);
    }

    private void sleepBeforeRetryingBackgroundDownlink() throws InterruptedException {
        long waitMs = backgroundYieldRemainingMillis(lastLocalMqttUplinkAtMillis, System.currentTimeMillis());
        if (waitMs <= 0) {
            return;
        }
        Thread.sleep(Math.min(waitMs, DOWNLINK_DIRECT_SEND_MIN_INTERVAL_MS));
    }

    private boolean awaitDownlinkSendWindow(boolean addressedToLocalNode) {
        long minIntervalMs = addressedToLocalNode
                ? DOWNLINK_DIRECT_SEND_MIN_INTERVAL_MS
                : DOWNLINK_BACKGROUND_SEND_MIN_INTERVAL_MS;
        long lastForwardAt = lastDownlinkForwardAtMillis;
        long now = System.currentTimeMillis();
        long waitMs = minIntervalMs - (now - lastForwardAt);
        if (waitMs > 0) {
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            now = System.currentTimeMillis();
        }
        if (closed) {
            return false;
        }
        lastDownlinkForwardAtMillis = now;
        return true;
    }

    private void subscribeToBroker(ProxyConfig config, boolean reconnect) {
        MqttAsyncClient mqttClient = client;
        if (mqttClient == null || config == null) {
            return;
        }

        String topicFilter = config.root() + "/#";
        try {
            mqttClient.subscribe(topicFilter, MQTT_QOS);
            log.info("MQTT proxy for '{}' {}subscribed to '{}'",
                    connectionName, reconnect ? "re" : "", topicFilter);
        } catch (MqttException e) {
            log.warn("Failed to subscribe MQTT proxy for '{}' to '{}'", connectionName, topicFilter, e);
        }
    }

    private static String sanitizeThreadSuffix(String value) {
        String sanitized = value == null ? "" : value.replaceAll("[^A-Za-z0-9]+", "");
        if (sanitized.isBlank()) {
            return Long.toHexString(System.nanoTime());
        }
        return sanitized.length() > 16 ? sanitized.substring(0, 16) : sanitized;
    }

    record ProxyConfig(String address,
                       String username,
                       String password,
                       String root,
                       boolean tlsEnabled) {}

    record ProxyState(ProxyConfig config, String reason) {}

    private record RecentPublication(String topic,
                                     byte[] payload,
                                     long expiresAtMillis) {}

    private record DownlinkMessage(int priority,
                                   long sequence,
                                   String topic,
                                   byte[] payload,
                                   boolean retained,
                                   boolean addressedToLocalNode) implements Comparable<DownlinkMessage> {

        @Override
        public int compareTo(DownlinkMessage other) {
            int priorityCompare = Integer.compare(priority, other.priority);
            if (priorityCompare != 0) {
                return priorityCompare;
            }
            return Long.compare(sequence, other.sequence);
        }
    }

    static final class LocalEchoSuppressor {
        private final long ttlMillis;
        private final Deque<RecentPublication> recentPublications = new ArrayDeque<>();

        LocalEchoSuppressor(long ttlMillis) {
            this.ttlMillis = ttlMillis;
        }

        RecentPublication remember(String topic, byte[] payload) {
            synchronized (recentPublications) {
                long nowMillis = System.currentTimeMillis();
                purgeExpiredPublications(nowMillis);
                RecentPublication publication = new RecentPublication(
                        topic,
                        payload.clone(),
                        nowMillis + ttlMillis
                );
                recentPublications.addLast(publication);
                return publication;
            }
        }

        void forget(RecentPublication publication) {
            if (publication == null) {
                return;
            }
            synchronized (recentPublications) {
                recentPublications.remove(publication);
            }
        }

        boolean consume(String topic, byte[] payload) {
            synchronized (recentPublications) {
                purgeExpiredPublications(System.currentTimeMillis());
                for (var it = recentPublications.iterator(); it.hasNext(); ) {
                    RecentPublication recent = it.next();
                    if (!recent.topic.equals(topic)) {
                        continue;
                    }
                    if (!Arrays.equals(recent.payload, payload)) {
                        continue;
                    }
                    it.remove();
                    return true;
                }
            }
            return false;
        }

        void clear() {
            synchronized (recentPublications) {
                recentPublications.clear();
            }
        }

        private void purgeExpiredPublications(long nowMillis) {
            while (!recentPublications.isEmpty()) {
                RecentPublication first = recentPublications.peekFirst();
                if (first == null || first.expiresAtMillis > nowMillis) {
                    return;
                }
                recentPublications.removeFirst();
            }
        }
    }

    private final class ProxyCallback implements MqttCallbackExtended {

        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            ProxyConfig config = activeConfig;
            log.info("MQTT proxy for '{}' connected to '{}'", connectionName, serverURI);
            subscribeToBroker(config, reconnect);
        }

        @Override
        public void connectionLost(Throwable cause) {
            if (closed) {
                return;
            }
            if (cause == null) {
                log.warn("MQTT proxy for '{}' lost broker connection", connectionName);
            } else {
                log.warn("MQTT proxy for '{}' lost broker connection: {}", connectionName, cause.getMessage());
            }
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            onBrokerMessage(topic, message);
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // publish-path ACKs are not required for the Meshtastic proxy bridge
        }
    }
}
