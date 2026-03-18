package com.meshtastic.client.protocol;

import org.meshtastic.proto.MeshProtos;
import com.google.protobuf.InvalidProtocolBufferException;
import com.meshtastic.client.connection.MeshtasticConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Диспетчер протокола Meshtastic. Принимает сырые protobuf-payload из
 * {@link MeshtasticConnection}, парсит {@code FromRadio} и распределяет
 * по зарегистрированным {@link FromRadioListener}-ам.
 * <p>
 * Также предоставляет метод отправки {@code ToRadio} на устройство
 * через фреймирование ({@link PacketFramer}) и транспорт.
 */
public class ProtocolHandler {

    private static final Logger log = LoggerFactory.getLogger(ProtocolHandler.class);

    /** Интервал heartbeat (секунды). Прошивка Meshtastic закрывает TCP при idle (~5-7 сек). */
    private static final int HEARTBEAT_INTERVAL_SEC = 5;
    /** Задержка перед первым heartbeat (секунды). 0 = отправить сразу после config exchange. */
    private static final int HEARTBEAT_INITIAL_DELAY_SEC = 0;

    private final MeshtasticConnection connection;
    private final List<FromRadioListener> listeners = new CopyOnWriteArrayList<>();

    /** Очередь входящих пакетов — разделяет reader-поток и обработку,
     *  чтобы reader не блокировался на listeners и не терял данные из serial-буфера. */
    private final BlockingQueue<byte[]> incomingQueue = new LinkedBlockingQueue<>(256);
    private final Thread dispatcherThread;

    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "heartbeat");
                t.setDaemon(true);
                return t;
            });
    private volatile ScheduledFuture<?> heartbeatFuture;
    private final AtomicInteger heartbeatNonce = new AtomicInteger(0);

    public ProtocolHandler(MeshtasticConnection connection) {
        this.connection = connection;
        connection.setDataListener(this::handleRawPacket);
        dispatcherThread = new Thread(this::dispatchLoop, "proto-dispatcher");
        dispatcherThread.setDaemon(true);
        dispatcherThread.start();
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
        byte[] frame = PacketFramer.frame(toRadio);
        log.debug("Sending ToRadio: {} ({} bytes framed)", toRadio.getPayloadVariantCase(), frame.length);
        connection.sendBytes(frame);
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
                    sendToRadio(heartbeat);
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
        dispatcherThread.interrupt();
    }

    private void handleRawPacket(byte[] data) {
        if (!incomingQueue.offer(data)) {
            log.warn("Incoming queue full, dropping packet ({} bytes)", data.length);
        }
    }

    private void dispatchLoop() {
        log.debug("Proto dispatcher thread started");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                byte[] data = incomingQueue.take();
                MeshProtos.FromRadio fromRadio = MeshProtos.FromRadio.parseFrom(data);
                dispatchFromRadio(fromRadio);
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
                log.debug("Received MeshPacket: from={} to={} portnum={}",
                        String.format("!%08x", pkt.getFrom()),
                        String.format("!%08x", pkt.getTo()),
                        pkt.hasDecoded() ? pkt.getDecoded().getPortnum() : "encrypted");
                notifyListeners(l -> l.onMeshPacket(pkt));
            }
            case LOG_RECORD -> {
                log.trace("Received LogRecord: {}", fromRadio.getLogRecord().getMessage());
                notifyListeners(l -> l.onLogRecord(fromRadio.getLogRecord()));
            }
            case QUEUESTATUS -> {
                var qs = fromRadio.getQueueStatus();
                log.debug("QueueStatus: res={} free={}/{} meshPacketId={}",
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
}
