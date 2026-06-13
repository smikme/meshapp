package com.meshtastic.client.protocol;

import org.meshtastic.proto.MeshProtos;
import com.google.protobuf.InvalidProtocolBufferException;
import com.meshtastic.client.connection.TransportConnection;
import com.meshtastic.client.service.PacketMonitorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Meshtastic protocol dispatcher.
 * <p>
 * It receives raw protobuf payloads from {@link TransportConnection}, parses
 * {@code FromRadio}, and distributes messages to registered
 * {@link FromRadioListener}s. Outbound {@code ToRadio} messages are framed with
 * {@link PacketFramer} before being written to the transport.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class ProtocolHandler {

    private static final Logger log = LoggerFactory.getLogger(ProtocolHandler.class);
    private static final byte[] SHUTDOWN_MARKER = new byte[0];

    /** Heartbeat interval in seconds; Meshtastic firmware closes idle TCP connections after roughly 5-7 seconds. */
    private static final int HEARTBEAT_INTERVAL_SEC = 5;
    /** Delay before the first heartbeat, in seconds. Zero sends immediately after config exchange. */
    private static final int HEARTBEAT_INITIAL_DELAY_SEC = 0;
    private static final int INCOMING_QUEUE_WARN_THRESHOLD = 256;
    private static final int INCOMING_QUEUE_WARN_STEP = 256;
    private static final int OUTBOUND_PRIORITY_DEFAULT = 0;
    private static final int OUTBOUND_PRIORITY_HEARTBEAT = 1;
    private static final int OUTBOUND_PRIORITY_MQTT_PROXY = 2;

    private final TransportConnection connection;
    private final String connectionId;
    private final List<FromRadioListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Incoming packet queue separating the reader thread from listener dispatch
     * so transport input is not blocked and serial-buffer data is not lost.
     */
    private final BlockingQueue<byte[]> incomingQueue = new LinkedBlockingQueue<>();
    /** Outgoing packet queue, prioritizing normal commands over MQTT downlink. */
    private final PriorityBlockingQueue<OutboundFrame> outgoingQueue = new PriorityBlockingQueue<>();
    private final Thread dispatcherThread;
    private final Thread senderThread;
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
    private final AtomicInteger incomingQueueWarnBucket = new AtomicInteger(0);
    private final AtomicLong outboundSequence = new AtomicLong();

    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "heartbeat");
                t.setDaemon(true);
                return t;
            });
    private volatile ScheduledFuture<?> heartbeatFuture;
    private final AtomicInteger heartbeatNonce = new AtomicInteger(0);

    public ProtocolHandler(TransportConnection connection) {
        this(null, connection);
    }

    public ProtocolHandler(String connectionId, TransportConnection connection) {
        this.connectionId = connectionId;
        this.connection = connection;
        connection.setDataListener(this::handleRawPacket);
        dispatcherThread = new Thread(this::dispatchLoop, "proto-dispatcher");
        dispatcherThread.setDaemon(true);
        dispatcherThread.start();
        senderThread = new Thread(this::sendLoop, "proto-sender");
        senderThread.setDaemon(true);
        senderThread.start();
    }

    /**
     * Registers a listener for incoming {@code FromRadio} messages.
     *
     * @param listener listener to register
     */
    public void addListener(FromRadioListener listener) {
        listeners.add(listener);
    }

    /**
     * Removes a previously registered listener.
     *
     * @param listener listener to remove
     */
    public void removeListener(FromRadioListener listener) {
        listeners.remove(listener);
    }

    /**
     * Sends a {@code ToRadio} message to the device.
     *
     * @param toRadio message to send
     */
    public void sendToRadio(MeshProtos.ToRadio toRadio) {
        sendToRadio(toRadio, true);
    }

    /**
     * Sends a {@code ToRadio} message and optionally arms the transport-level
     * receive watchdog. Keepalive packets do not expect a direct response.
     *
     * @param toRadio message to send
     * @param expectResponseAfterWrite {@code true} for ordinary requests and packets,
     *                                 {@code false} for keepalive traffic
     */
    public void sendToRadio(MeshProtos.ToRadio toRadio, boolean expectResponseAfterWrite) {
        if (toRadio == null || shutdownRequested.get()) {
            return;
        }
        outgoingQueue.offer(new OutboundFrame(
                classifyOutboundPriority(toRadio),
                outboundSequence.getAndIncrement(),
                toRadio,
                expectResponseAfterWrite
        ));
    }

    /**
     * Starts periodic heartbeat delivery after a successful config exchange.
     * Meshtastic firmware closes TCP connections when they stay idle.
     */
    public void startHeartbeat() {
        stopHeartbeat();
        heartbeatFuture = heartbeatScheduler.scheduleWithFixedDelay(() -> {
            try {
                if (connection.isConnected()) {
                    MeshProtos.ToRadio heartbeat = MeshProtos.ToRadio.newBuilder()
                            .setHeartbeat(MeshProtos.Heartbeat.newBuilder()
                                    .setNonce(heartbeatNonce.incrementAndGet())
                                    .build())
                            .build();
                    sendToRadio(heartbeat, false);
                } else {
                    log.debug("Heartbeat skipped: connection not active");
                }
            } catch (Exception e) {
                log.warn("Heartbeat send failed", e);
            }
        }, HEARTBEAT_INITIAL_DELAY_SEC, HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS);
        log.info("Heartbeat started (initialDelay={}s, interval={}s)", HEARTBEAT_INITIAL_DELAY_SEC, HEARTBEAT_INTERVAL_SEC);
    }

    /** Stops heartbeat delivery. */
    public void stopHeartbeat() {
        ScheduledFuture<?> f = heartbeatFuture;
        if (f != null) {
            f.cancel(false);
            heartbeatFuture = null;
            log.info("Heartbeat stopped");
        }
    }

    /** Stops heartbeat, dispatcher, sender, and scheduler resources. */
    public void shutdown() {
        stopHeartbeat();
        heartbeatScheduler.shutdownNow();
        if (!shutdownRequested.compareAndSet(false, true)) {
            return;
        }
        connection.setDataListener(null);
        incomingQueue.clear();
        outgoingQueue.clear();
        senderThread.interrupt();
        incomingQueue.offer(SHUTDOWN_MARKER);
    }

    private void handleRawPacket(byte[] data) {
        if (shutdownRequested.get()) {
            return;
        }
        incomingQueue.offer(data);
        logIncomingQueueBacklogIfNeeded(incomingQueue.size());
    }

    private void dispatchLoop() {
        log.debug("Proto dispatcher thread started");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                byte[] data = incomingQueue.take();
                if (data == SHUTDOWN_MARKER) {
                    break;
                }
                try {
                    MeshProtos.FromRadio fromRadio = MeshProtos.FromRadio.parseFrom(data);
                    dispatchFromRadio(fromRadio);
                } finally {
                    resetIncomingQueueBacklogWarningIfDrained(incomingQueue.size());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (InvalidProtocolBufferException e) {
                log.debug("Failed to parse FromRadio: {}", e.getMessage());
            } catch (Exception e) {
                log.error("Error processing FromRadio", e);
            }
        }
        log.debug("Proto dispatcher thread exiting");
    }

    private void sendLoop() {
        log.debug("Proto sender thread started");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                OutboundFrame outbound = outgoingQueue.take();
                if (shutdownRequested.get()) {
                    break;
                }
                sendOutboundFrame(outbound);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error sending ToRadio", e);
            }
        }
        log.debug("Proto sender thread exiting");
    }

    private void logIncomingQueueBacklogIfNeeded(int queuedPackets) {
        if (queuedPackets < INCOMING_QUEUE_WARN_THRESHOLD) {
            return;
        }

        int bucket = ((queuedPackets - INCOMING_QUEUE_WARN_THRESHOLD) / INCOMING_QUEUE_WARN_STEP) + 1;
        int previousBucket = incomingQueueWarnBucket.get();
        while (bucket > previousBucket) {
            if (incomingQueueWarnBucket.compareAndSet(previousBucket, bucket)) {
                log.warn("Incoming packet backlog grew to {} frames; processing is lagging behind transport input",
                        queuedPackets);
                return;
            }
            previousBucket = incomingQueueWarnBucket.get();
        }
    }

    private void resetIncomingQueueBacklogWarningIfDrained(int queuedPackets) {
        if (queuedPackets < INCOMING_QUEUE_WARN_THRESHOLD) {
            incomingQueueWarnBucket.set(0);
        }
    }

    private void sendOutboundFrame(OutboundFrame outbound) {
        MeshProtos.ToRadio toRadio = outbound.toRadio();
        byte[] frame = PacketFramer.frame(toRadio);
        log.trace("Sending ToRadio: {} ({} bytes framed)", toRadio.getPayloadVariantCase(), frame.length);
        PacketMonitorService monitorService = PacketMonitorService.getIfInitialized();
        if (monitorService != null && toRadio.hasPacket()) {
            monitorService.recordOutgoing(connectionId, toRadio.getPacket());
        }
        connection.sendBytes(frame, outbound.expectResponseAfterWrite());
    }

    private static int classifyOutboundPriority(MeshProtos.ToRadio toRadio) {
        return switch (toRadio.getPayloadVariantCase()) {
            case MQTTCLIENTPROXYMESSAGE -> OUTBOUND_PRIORITY_MQTT_PROXY;
            case HEARTBEAT -> OUTBOUND_PRIORITY_HEARTBEAT;
            default -> OUTBOUND_PRIORITY_DEFAULT;
        };
    }

    private void dispatchFromRadio(MeshProtos.FromRadio fromRadio) {
        switch (fromRadio.getPayloadVariantCase()) {
            case MY_INFO -> {
                log.info("Received MyNodeInfo: nodeNum={}", fromRadio.getMyInfo().getMyNodeNum());
                notifyListeners(l -> l.onMyNodeInfo(fromRadio.getMyInfo()));
            }
            case NODE_INFO -> {
                log.debug("Received NodeInfo: num={}", fromRadio.getNodeInfo().getNum());
                notifyListeners(l -> l.onNodeInfo(fromRadio.getNodeInfo()));
            }
            case CONFIG -> {
                log.debug("Received Config: {}", fromRadio.getConfig().getPayloadVariantCase());
                notifyListeners(l -> l.onConfig(fromRadio.getConfig()));
            }
            case MODULECONFIG -> {
                log.debug("Received ModuleConfig: {}", fromRadio.getModuleConfig().getPayloadVariantCase());
                notifyListeners(l -> l.onModuleConfig(fromRadio.getModuleConfig()));
            }
            case METADATA -> {
                MeshProtos.DeviceMetadata metadata = fromRadio.getMetadata();
                log.debug("Received DeviceMetadata: firmwareVersion='{}', excludedModules={}",
                        metadata.getFirmwareVersion(), metadata.getExcludedModules());
                notifyListeners(l -> l.onDeviceMetadata(metadata));
            }
            case CHANNEL -> {
                log.debug("Received Channel: index={}", fromRadio.getChannel().getIndex());
                notifyListeners(l -> l.onChannel(fromRadio.getChannel()));
            }
            case CONFIG_COMPLETE_ID -> {
                log.info("Received config_complete_id: {}", fromRadio.getConfigCompleteId());
                notifyListeners(l -> l.onConfigComplete(fromRadio.getConfigCompleteId()));
            }
            case REBOOTED -> {
                log.info("Received radio reboot marker");
                notifyListeners(FromRadioListener::onRebooted);
            }
            case PACKET -> {
                MeshProtos.MeshPacket pkt = fromRadio.getPacket();
                if (log.isTraceEnabled()) {
                    log.trace("Received MeshPacket: id={} from={} to={} channel={} portnum={} viaMqtt={} transport={} rxTime={} hopStart={} hopLimit={}",
                            pkt.getId(),
                            String.format("!%08x", pkt.getFrom()),
                            String.format("!%08x", pkt.getTo()),
                            pkt.getChannel(),
                            pkt.hasDecoded() ? pkt.getDecoded().getPortnum() : "encrypted",
                            pkt.getViaMqtt(),
                            pkt.getTransportMechanism(),
                            pkt.getRxTime(),
                            pkt.getHopStart(),
                            pkt.getHopLimit());
                }
                PacketMonitorService monitorService = PacketMonitorService.getIfInitialized();
                if (monitorService != null) {
                    monitorService.recordIncoming(connectionId, pkt);
                }
                notifyListeners(l -> l.onMeshPacket(pkt));
            }
            case MQTTCLIENTPROXYMESSAGE -> {
                MeshProtos.MqttClientProxyMessage proxyMessage = fromRadio.getMqttClientProxyMessage();
                log.debug("Received MqttClientProxyMessage: topic='{}' variant={} retained={}",
                        proxyMessage.getTopic(), proxyMessage.getPayloadVariantCase(), proxyMessage.getRetained());
                notifyListeners(l -> l.onMqttClientProxyMessage(proxyMessage));
            }
            case LOG_RECORD -> {
                log.trace("Received LogRecord: {}", fromRadio.getLogRecord().getMessage());
                notifyListeners(l -> l.onLogRecord(fromRadio.getLogRecord()));
            }
            case QUEUESTATUS -> {
                var qs = fromRadio.getQueueStatus();
                log.trace("QueueStatus: res={} free={}/{} meshPacketId={}",
                        qs.getRes(), qs.getFree(), qs.getMaxlen(), qs.getMeshPacketId());
                notifyListeners(l -> l.onQueueStatus(qs));
            }
            default -> log.debug("Unhandled FromRadio variant: {}", fromRadio.getPayloadVariantCase());
        }
    }

    private void notifyListeners(java.util.function.Consumer<FromRadioListener> action) {
        for (FromRadioListener l : listeners) {
            try {
                action.accept(l);
            } catch (Exception e) {
                log.error("Error in FromRadioListener {}", l.getClass().getSimpleName(), e);
            }
        }
    }

    private record OutboundFrame(int priority,
                                 long sequence,
                                 MeshProtos.ToRadio toRadio,
                                 boolean expectResponseAfterWrite) implements Comparable<OutboundFrame> {

        @Override
        public int compareTo(OutboundFrame other) {
            int priorityCompare = Integer.compare(priority, other.priority);
            if (priorityCompare != 0) {
                return priorityCompare;
            }
            return Long.compare(sequence, other.sequence);
        }
    }
}
