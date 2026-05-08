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
 * Диспетчер протокола Meshtastic. Принимает сырые protobuf-payload из
 * {@link TransportConnection}, парсит {@code FromRadio} и распределяет
 * по зарегистрированным {@link FromRadioListener}-ам.
 * <p>
 * Также предоставляет метод отправки {@code ToRadio} на устройство
 * через фреймирование ({@link PacketFramer}) и транспорт.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class ProtocolHandler {

    private static final Logger log = LoggerFactory.getLogger(ProtocolHandler.class);
    private static final byte[] SHUTDOWN_MARKER = new byte[0];

    /** Интервал heartbeat (секунды). Прошивка Meshtastic закрывает TCP при idle (~5-7 сек). */
    private static final int HEARTBEAT_INTERVAL_SEC = 5;
    /** Задержка перед первым heartbeat (секунды). 0 = отправить сразу после config exchange. */
    private static final int HEARTBEAT_INITIAL_DELAY_SEC = 0;
    private static final int INCOMING_QUEUE_WARN_THRESHOLD = 256;
    private static final int INCOMING_QUEUE_WARN_STEP = 256;
    private static final int OUTBOUND_PRIORITY_DEFAULT = 0;
    private static final int OUTBOUND_PRIORITY_HEARTBEAT = 1;
    private static final int OUTBOUND_PRIORITY_MQTT_PROXY = 2;

    private final TransportConnection connection;
    private final String connectionId;
    private final List<FromRadioListener> listeners = new CopyOnWriteArrayList<>();

    /** Очередь входящих пакетов — разделяет reader-поток и обработку,
     *  чтобы reader не блокировался на listeners и не терял данные из serial-буфера. */
    private final BlockingQueue<byte[]> incomingQueue = new LinkedBlockingQueue<>();
    /** Очередь исходящих пакетов с приоритетом обычных команд над MQTT downlink. */
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
     * Регистрирует слушателя входящих {@code FromRadio} сообщений.
     *
     * @param listener слушатель для регистрации
     */
    public void addListener(FromRadioListener listener) {
        listeners.add(listener);
    }

    /**
     * Удаляет ранее зарегистрированного слушателя.
     *
     * @param listener слушатель для удаления
     */
    public void removeListener(FromRadioListener listener) {
        listeners.remove(listener);
    }

    /**
     * Отправляет {@code ToRadio} сообщение на устройство.
     * Сериализует protobuf, оборачивает в фрейм и передаёт через транспорт.
     *
     * @param toRadio сообщение для отправки
     */
    public void sendToRadio(MeshProtos.ToRadio toRadio) {
        sendToRadio(toRadio, true);
    }

    /**
     * Отправляет {@code ToRadio} сообщение на устройство с возможностью не arm-ить
     * transport-level receive watchdog для keepalive/heartbeat-пакетов.
     *
     * @param toRadio сообщение для отправки
     * @param expectResponseAfterWrite {@code true} для обычных запросов/пакетов,
     *                                 {@code false} для keepalive
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
     * Запускает периодическую отправку heartbeat на устройство.
     * Прошивка Meshtastic закрывает TCP-соединение при отсутствии активности.
     * Вызывать после успешного config exchange.
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

    /** Останавливает отправку heartbeat. */
    public void stopHeartbeat() {
        ScheduledFuture<?> f = heartbeatFuture;
        if (f != null) {
            f.cancel(false);
            heartbeatFuture = null;
            log.info("Heartbeat stopped");
        }
    }

    /** Останавливает heartbeat, dispatcher и освобождает scheduler. */
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
            case CHANNEL -> {
                log.debug("Received Channel: index={}", fromRadio.getChannel().getIndex());
                notifyListeners(l -> l.onChannel(fromRadio.getChannel()));
            }
            case CONFIG_COMPLETE_ID -> {
                log.info("Received config_complete_id: {}", fromRadio.getConfigCompleteId());
                notifyListeners(l -> l.onConfigComplete(fromRadio.getConfigCompleteId()));
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
