package com.meshtastic.client.model;

import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;
import com.google.protobuf.ByteString;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Центральное хранилище состояния подключённого Meshtastic-устройства.
 * <p>
 * Содержит базу нод ({@code nodeDb}), каналы, конфиги, сообщения (канальные и личные),
 * телеметрию и ожидающие подтверждения ACK. Каждое TCP-соединение получает свой экземпляр
 * {@code DeviceState} через {@link com.meshtastic.client.service.ConnectionManager}.
 * <p>
 * Потокобезопасность: nodeDb ({@link java.util.concurrent.ConcurrentHashMap}),
 * каналы/конфиги ({@link java.util.Collections#synchronizedList}), списки слушателей
 * ({@link java.util.concurrent.CopyOnWriteArrayList}). Списки сообщений по каналам/DM
 * хранятся в {@code ConcurrentHashMap}, каждый список — {@code synchronizedList}.
 * UI-обновления выполняются через {@code Platform.runLater()}.
 */
public class DeviceState {

    private static final Logger log = LoggerFactory.getLogger(DeviceState.class);

    /** Таймаут ожидания ACK: если за это время ACK не пришёл — статус → FAILED */
    private static final long ACK_TIMEOUT_MS = 240_000;
    /** Интервал проверки просроченных pending ACK */
    private static final long ACK_SWEEP_INTERVAL_MS = 10_000;
    /** Максимум сообщений в памяти на канал/DM (история загружается из БД) */
    private static final int MAX_MESSAGES_IN_MEMORY = 100;

    /** Запись в очереди ожидающих ACK: сообщение + время регистрации */
    private record PendingAckEntry(MeshMessage message, long registeredAtMillis) {}

    private volatile int myNodeNum;
    private final ConcurrentHashMap<Integer, NodeData> nodeDb = new ConcurrentHashMap<>();
    private final List<ChannelProtos.Channel> channels = Collections.synchronizedList(new ArrayList<>());
    private final List<ConfigProtos.Config> configs = Collections.synchronizedList(new ArrayList<>());
    private final List<ModuleConfigProtos.ModuleConfig> moduleConfigs = Collections.synchronizedList(new ArrayList<>());
    private final Map<Integer, List<MeshMessage>> messagesByChannel = new ConcurrentHashMap<>();
    private final Map<String, List<MeshMessage>> directMessages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, PendingAckEntry> pendingAcks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CompletableFuture<MeshProtos.Routing.Error>> pendingPacketAcks =
            new ConcurrentHashMap<>();

    private final ScheduledExecutorService ackTimeoutExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ack-timeout-sweeper");
        t.setDaemon(true);
        return t;
    });
    /** История телеметрии (последние MAX_TELEMETRY_HISTORY записей). */
    private static final int MAX_TELEMETRY_HISTORY = 200;
    private final List<TelemetryEntry> telemetryHistory = new LinkedList<>();
    private final List<Runnable> telemetryListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> messageListeners = new CopyOnWriteArrayList<>();
    private final List<java.util.function.IntConsumer> nodeUpdateListeners = new CopyOnWriteArrayList<>();
    private final List<BiConsumer<Integer, MeshProtos.RouteDiscovery>> tracerouteListeners = new CopyOnWriteArrayList<>();

    // Owner info (from admin get_owner_response)
    private volatile MeshProtos.User ownerInfo;
    private volatile ByteString sessionPasskey;
    private final List<Runnable> ownerInfoListeners = new CopyOnWriteArrayList<>();
    private volatile MeshProtos.DeviceMetadata deviceMetadata;
    private final List<Runnable> deviceMetadataListeners = new CopyOnWriteArrayList<>();

    // Pending fixed position — saved by user, not yet confirmed by device.
    // Survives clear() to protect against stale position from config re-exchange.
    private volatile double pendingFixedLat;
    private volatile double pendingFixedLon;
    private volatile int pendingFixedAlt;
    private volatile long pendingFixedSetAt; // epoch millis, 0 = none

    public DeviceState() {
        ackTimeoutExecutor.scheduleWithFixedDelay(this::runAckSweepSafely,
                ACK_SWEEP_INTERVAL_MS, ACK_SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public int getMyNodeNum() { return myNodeNum; }
    public void setMyNodeNum(int myNodeNum) { this.myNodeNum = myNodeNum; }

    public ConcurrentHashMap<Integer, NodeData> getNodeDb() { return nodeDb; }

    /**
     * Возвращает ноду из базы или создаёт новую атомарно.
     * Использует {@link java.util.concurrent.ConcurrentHashMap#computeIfAbsent},
     * гарантируя что для одного {@code nodeNum} создаётся ровно один объект.
     *
     * @param nodeNum номер ноды
     * @return существующая или новая {@link NodeData}
     */
    public NodeData getOrCreateNode(int nodeNum) {
        return nodeDb.computeIfAbsent(nodeNum, NodeData::new);
    }

    public List<ChannelProtos.Channel> getChannels() { return channels; }

    public void addChannel(ChannelProtos.Channel channel) {
        channels.add(channel);
    }

    /**
     * Обновить канал по индексу. Если канал с таким индексом есть — заменить, иначе добавить.
     */
    public void updateChannel(ChannelProtos.Channel channel) {
        synchronized (channels) {
            for (int i = 0; i < channels.size(); i++) {
                if (channels.get(i).getIndex() == channel.getIndex()) {
                    channels.set(i, channel);
                    fireMessageListeners();
                    return;
                }
            }
            channels.add(channel);
        }
        fireMessageListeners();
    }

    /**
     * Найти первый свободный слот для SECONDARY канала (индексы 1-7).
     * Возвращает -1, если все слоты заняты.
     */
    public int findFirstAvailableChannelSlot() {
        synchronized (channels) {
            java.util.Set<Integer> usedIndices = new java.util.HashSet<>();
            for (ChannelProtos.Channel ch : channels) {
                if (ch.getRole() != ChannelProtos.Channel.Role.DISABLED) {
                    usedIndices.add(ch.getIndex());
                }
            }
            for (int i = 1; i <= 7; i++) {
                if (!usedIndices.contains(i)) { return i; }
            }
        }
        return -1;
    }

    public List<ConfigProtos.Config> getConfigs() { return configs; }

    public void addConfig(ConfigProtos.Config config) {
        configs.add(config);
    }

    public List<ModuleConfigProtos.ModuleConfig> getModuleConfigs() { return moduleConfigs; }

    public void addModuleConfig(ModuleConfigProtos.ModuleConfig moduleConfig) {
        moduleConfigs.add(moduleConfig);
    }

    public void addMessageListener(Runnable listener) { messageListeners.add(listener); }
    public void removeMessageListener(Runnable listener) { messageListeners.remove(listener); }

    public void addNodeUpdateListener(java.util.function.IntConsumer listener) { nodeUpdateListeners.add(listener); }
    public void removeNodeUpdateListener(java.util.function.IntConsumer listener) { nodeUpdateListeners.remove(listener); }
    public void fireNodeUpdateListeners(int nodeNum) {
        for (java.util.function.IntConsumer l : nodeUpdateListeners) {
            try { l.accept(nodeNum); }
            catch (Exception e) { log.error("Exception in node update listener for !{}", Integer.toHexString(nodeNum), e); }
        }
    }

    public void addTracerouteListener(BiConsumer<Integer, MeshProtos.RouteDiscovery> listener) { tracerouteListeners.add(listener); }
    public void removeTracerouteListener(BiConsumer<Integer, MeshProtos.RouteDiscovery> listener) { tracerouteListeners.remove(listener); }
    public void fireTracerouteListeners(int fromNodeNum, MeshProtos.RouteDiscovery route) {
        for (BiConsumer<Integer, MeshProtos.RouteDiscovery> l : tracerouteListeners) {
            try { l.accept(fromNodeNum, route); }
            catch (Exception e) { log.error("Exception in traceroute listener", e); }
        }
    }

    public void addTelemetryListener(Runnable listener) { telemetryListeners.add(listener); }
    public void removeTelemetryListener(Runnable listener) { telemetryListeners.remove(listener); }
    public void fireTelemetryListeners() {
        for (Runnable r : telemetryListeners) {
            try { r.run(); }
            catch (Exception e) { log.error("Exception in telemetry listener", e); }
        }
    }

    /**
     * Добавляет запись телеметрии в историю. Если размер истории превышает
     * {@code MAX_TELEMETRY_HISTORY} (200), самые старые записи удаляются.
     * После добавления оповещает telemetry-слушателей.
     *
     * @param entry запись телеметрии
     */
    public void addTelemetryEntry(TelemetryEntry entry) {
        synchronized (telemetryHistory) {
            telemetryHistory.addLast(entry);
            while (telemetryHistory.size() > MAX_TELEMETRY_HISTORY) {
                telemetryHistory.removeFirst();
            }
        }
        fireTelemetryListeners();
    }

    public List<TelemetryEntry> getTelemetryHistory() {
        synchronized (telemetryHistory) {
            return new ArrayList<>(telemetryHistory);
        }
    }

    /**
     * Загружает архивные записи телеметрии в начало истории (перед live-данными).
     * Вызывается один раз при подключении к устройству.
     */
    public void prependTelemetryHistory(List<TelemetryEntry> archived) {
        if (archived == null || archived.isEmpty()) { return; }
        synchronized (telemetryHistory) {
            // Добавляем архив перед текущими live-записями
            telemetryHistory.addAll(0, archived);
            // Обрезаем до лимита, удаляя самые старые
            while (telemetryHistory.size() > MAX_TELEMETRY_HISTORY) {
                telemetryHistory.removeFirst();
            }
        }
        fireTelemetryListeners();
    }

    public void fireMessageListeners() {
        for (Runnable r : messageListeners) {
            try { r.run(); }
            catch (Exception e) { log.error("Exception in message listener", e); }
        }
    }

    /**
     * Добавляет канальное сообщение с дедупликацией по {@code packetId}.
     * Радио может ретранслировать один и тот же пакет несколько раз;
     * если сообщение с таким {@code packetId} уже есть в списке канала, оно игнорируется.
     * Сообщения с {@code packetId=0} не дедуплицируются.
     * После добавления оповещает всех message-слушателей.
     *
     * @param msg сообщение для добавления
     */
    public void addMessage(MeshMessage msg) {
        List<MeshMessage> list = messagesByChannel
                .computeIfAbsent(msg.getChannelIndex(), k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            // Дедупликация по packetId (радио может ретранслировать пакеты)
            if (msg.getPacketId() != 0) {
                for (MeshMessage existing : list) {
                    if (existing.getPacketId() == msg.getPacketId()) { return; }
                }
            }
            list.add(msg);
            while (list.size() > MAX_MESSAGES_IN_MEMORY) {
                list.remove(0);
            }
        }
        fireMessageListeners();
    }

    public List<MeshMessage> getMessages(int channelIndex) {
        List<MeshMessage> list = messagesByChannel.get(channelIndex);
        return list != null ? list : Collections.emptyList();
    }

    /**
     * Добавляет личное (DM) сообщение с дедупликацией по {@code packetId}.
     * Сообщения группируются по {@code peerNodeId} — node_id собеседника.
     * Дубликаты (повторные ретрансляции) игнорируются.
     *
     * @param msg        сообщение для добавления
     * @param peerNodeId node_id собеседника (ключ группировки)
     */
    public void addDirectMessage(MeshMessage msg, String peerNodeId) {
        List<MeshMessage> list = directMessages
                .computeIfAbsent(peerNodeId, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            // Дедупликация по packetId (радио может ретранслировать пакеты)
            if (msg.getPacketId() != 0) {
                for (MeshMessage existing : list) {
                    if (existing.getPacketId() == msg.getPacketId()) { return; }
                }
            }
            list.add(msg);
            while (list.size() > MAX_MESSAGES_IN_MEMORY) {
                list.remove(0);
            }
        }
        fireMessageListeners();
    }

    public void ensureDirectMessageThread(String peerNodeId) {
        if (peerNodeId == null || peerNodeId.isEmpty()) { return; }
        List<MeshMessage> existing = directMessages.putIfAbsent(
                peerNodeId, Collections.synchronizedList(new ArrayList<>()));
        if (existing == null) {
            fireMessageListeners();
        }
    }

    public List<MeshMessage> getDirectMessages(String peerNodeId) {
        List<MeshMessage> list = directMessages.get(peerNodeId);
        return list != null ? list : Collections.emptyList();
    }

    public Map<String, List<MeshMessage>> getAllDirectMessages() {
        return directMessages;
    }

    public void removeDirectMessages(String peerNodeId) {
        directMessages.remove(peerNodeId);
    }

    /** Удалить ноду из nodeDb и directMessages, оповестить listener'ы. */
    public void removeNode(int nodeNum) {
        NodeData node = nodeDb.get(nodeNum);
        nodeDb.remove(nodeNum);
        if (node != null && node.getNodeId() != null) {
            directMessages.remove(node.getNodeId());
        }
        fireNodeUpdateListeners(nodeNum);
    }

    /**
     * Найти ноду по node_id (перебор nodeDb.values()).
     * @return NodeData или {@code null}
     */
    public NodeData getNodeByNodeId(String nodeId) {
        if (nodeId == null) { return null; }
        for (NodeData n : nodeDb.values()) {
            if (nodeId.equals(n.getNodeId())) { return n; }
        }
        return null;
    }

    public Map<Integer, List<MeshMessage>> getAllChannelMessages() {
        return messagesByChannel;
    }

    /**
     * Регистрирует исходящее сообщение для отслеживания ACK/NAK.
     * При получении routing-ответа с этим {@code packetId}
     * статус сообщения будет обновлён через {@link #resolvePendingAck}.
     * Если ACK не придёт в течение {@link #ACK_TIMEOUT_MS}, сообщение
     * автоматически получит статус {@code FAILED} с причиной {@code TIMEOUT}.
     *
     * @param packetId уникальный идентификатор пакета
     * @param msg      сообщение в статусе {@code SENDING}
     */
    public void registerPendingAck(int packetId, MeshMessage msg) {
        pendingAcks.put(packetId, new PendingAckEntry(msg, System.currentTimeMillis()));
    }

    /**
     * Извлекает и удаляет сообщение из очереди ожидающих ACK.
     * Вызывается при получении routing-ответа (ACK/NAK).
     *
     * @param packetId идентификатор пакета из routing-ответа
     * @return сообщение, ожидавшее подтверждения, или {@code null} если не найдено
     */
    public MeshMessage resolvePendingAck(int packetId) {
        PendingAckEntry entry = pendingAcks.remove(packetId);
        return entry != null ? entry.message() : null;
    }

    /**
     * Регистрирует generic ACK waiter для не-чатовых пакетов
     * (например, ADMIN_APP при сохранении конфигурации).
     *
     * @param packetId идентификатор исходящего пакета
     * @return future, который завершится routing ACK/NAK от устройства
     */
    public CompletableFuture<MeshProtos.Routing.Error> registerPendingPacketAck(int packetId) {
        CompletableFuture<MeshProtos.Routing.Error> future = new CompletableFuture<>();
        pendingPacketAcks.put(packetId, future);
        return future;
    }

    /**
     * Завершает generic ACK waiter для не-чатового пакета.
     *
     * @param packetId идентификатор исходящего пакета
     * @param error    routing result от устройства
     * @return {@code true}, если waiter существовал и был завершён
     */
    public boolean completePendingPacketAck(int packetId, MeshProtos.Routing.Error error) {
        CompletableFuture<MeshProtos.Routing.Error> future = pendingPacketAcks.remove(packetId);
        if (future == null) {
            return false;
        }
        future.complete(error);
        return true;
    }

    /**
     * Помечает все ожидающие ACK сообщения как {@code FAILED} с указанной причиной.
     * Обновляет статус в БД и оповещает слушателей.
     * Вызывается при отключении от устройства.
     *
     * @param reason причина неудачи (например, {@code "DISCONNECTED"})
     */
    public void failAllPendingAcks(String reason) {
        if (pendingAcks.isEmpty()) { return; }
        int count = 0;
        var iterator = pendingAcks.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            iterator.remove();
            int packetId = entry.getKey();
            MeshMessage msg = entry.getValue().message();
            msg.setStatus(MeshMessage.DeliveryStatus.FAILED);
            msg.setErrorReason(reason);
            try {
                com.meshtastic.client.service.MessageDbService.getInstance()
                        .updateStatus(packetId, msg.getStatus(), msg.getErrorReason());
            } catch (Exception e) {
                log.warn("Failed to update status in DB for packetId {} during failAll", packetId, e);
            }
            count++;
        }
        if (count > 0) {
            log.info("Marked {} pending messages as FAILED (reason: {})", count, reason);
            fireMessageListeners();
        }
    }

    /**
     * Завершает все generic ACK waiter-ы ошибкой disconnect/cleanup.
     * Нужен для admin save-flow, чтобы BLE/TCP/Serial disconnect не оставлял
     * ожидающие future висеть до таймаута.
     *
     * @param reason причина очистки waiters
     */
    public void failAllPendingPacketAcks(String reason) {
        if (pendingPacketAcks.isEmpty()) { return; }
        var iterator = pendingPacketAcks.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            iterator.remove();
            entry.getValue().completeExceptionally(
                    new IllegalStateException("Packet ACK waiter aborted: " + reason));
        }
    }

    /**
     * Останавливает фоновый поток проверки таймаутов ACK.
     * Вызывается при удалении {@code DeviceState} (отключение от устройства).
     */
    public void shutdown() {
        ackTimeoutExecutor.shutdownNow();
    }

    /**
     * Ищет сообщение по {@code packetId} сначала в in-memory кэше (канальные и DM),
     * затем — fallback в H2 БД через {@link com.meshtastic.client.service.MessageDbService}.
     * Используется для получения текста цитируемого сообщения ({@code replyText}).
     *
     * @param packetId идентификатор пакета (0 — не искать)
     * @return найденное сообщение или {@code null}
     */
    public MeshMessage findMessageByPacketId(int packetId) {
        if (packetId == 0) { return null; }
        // Сначала ищем в памяти (быстро, для текущей сессии)
        for (List<MeshMessage> msgs : messagesByChannel.values()) {
            synchronized (msgs) {
                for (MeshMessage msg : msgs) {
                    if (msg.getPacketId() == packetId) { return msg; }

                }
            }
        }
        for (List<MeshMessage> msgs : directMessages.values()) {
            synchronized (msgs) {
                for (MeshMessage msg : msgs) {
                    if (msg.getPacketId() == packetId) { return msg; }

                }
            }
        }
        // Fallback — поиск в БД (для reply_text старых сообщений)
        try {
            return com.meshtastic.client.service.MessageDbService.getInstance().findByPacketId(packetId);
        } catch (Exception e) {
            return null;
        }
    }

    // --- Owner info / session passkey (admin) ---
    public MeshProtos.User getOwnerInfo() { return ownerInfo; }
    public void setOwnerInfo(MeshProtos.User ownerInfo) { this.ownerInfo = ownerInfo; }

    public ByteString getSessionPasskey() { return sessionPasskey; }
    public void setSessionPasskey(ByteString sessionPasskey) { this.sessionPasskey = sessionPasskey; }

    public MeshProtos.DeviceMetadata getDeviceMetadata() { return deviceMetadata; }
    public void setDeviceMetadata(MeshProtos.DeviceMetadata deviceMetadata) { this.deviceMetadata = deviceMetadata; }

    /**
     * Listeners are notified when admin owner info arrives or when a fresh
     * {@code session_passkey} is received via any ADMIN_APP response.
     */
    public void addOwnerInfoListener(Runnable listener) { ownerInfoListeners.add(listener); }
    public void removeOwnerInfoListener(Runnable listener) { ownerInfoListeners.remove(listener); }
    public void fireOwnerInfoListeners() {
        for (Runnable r : ownerInfoListeners) {
            try { r.run(); }
            catch (Exception e) { log.error("Exception in owner info listener", e); }
        }
    }

    public void addDeviceMetadataListener(Runnable listener) { deviceMetadataListeners.add(listener); }
    public void removeDeviceMetadataListener(Runnable listener) { deviceMetadataListeners.remove(listener); }
    public void fireDeviceMetadataListeners() {
        for (Runnable r : deviceMetadataListeners) {
            try { r.run(); }
            catch (Exception e) { log.error("Exception in device metadata listener", e); }
        }
    }

    /**
     * Периодическая проверка просроченных pending ACK.
     * Сообщения, ожидающие ACK дольше {@link #ACK_TIMEOUT_MS},
     * помечаются как {@code FAILED} с причиной {@code TIMEOUT}.
     */
    private void runAckSweepSafely() {
        try {
            sweepExpiredAcks();
        } catch (Throwable t) {
            // scheduleWithFixedDelay прекращает будущие запуски после uncaught exception.
            // Таймер ACK должен переживать локальные сбои и продолжать переводить
            // зависшие "часы" в FAILED, иначе UI залипает в SENDING навсегда.
            log.error("ACK sweep crashed", t);
        }
    }

    /**
     * Периодическая проверка просроченных pending ACK.
     * Сообщения, ожидающие ACK дольше {@link #ACK_TIMEOUT_MS},
     * помечаются как {@code FAILED} с причиной {@code TIMEOUT}.
     */
    private void sweepExpiredAcks() {
        long now = System.currentTimeMillis();
        boolean anyExpired = false;
        for (var mapEntry : pendingAcks.entrySet()) {
            int packetId = mapEntry.getKey();
            PendingAckEntry entry = mapEntry.getValue();
            if (now - entry.registeredAtMillis() >= ACK_TIMEOUT_MS) {
                PendingAckEntry removed = pendingAcks.remove(packetId);
                if (removed != null) {
                    MeshMessage msg = removed.message();
                    msg.setStatus(MeshMessage.DeliveryStatus.FAILED);
                    msg.setErrorReason("TIMEOUT");
                    try {
                        com.meshtastic.client.service.MessageDbService.getInstance()
                                .updateStatus(packetId, msg.getStatus(), msg.getErrorReason());
                    } catch (Exception e) {
                        log.warn("Failed to update timed-out message status in DB for packetId {}", packetId, e);
                    }
                    log.warn("ACK timeout for packetId {}", packetId);
                    anyExpired = true;
                }
            }
        }
        if (anyExpired) {
            fireMessageListeners();
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Pending fixed position (survives clear)
    // ═══════════════════════════════════════════════════════════

    public void setPendingFixedPosition(double lat, double lon, int alt) {
        this.pendingFixedLat = lat;
        this.pendingFixedLon = lon;
        this.pendingFixedAlt = alt;
        this.pendingFixedSetAt = System.currentTimeMillis();
    }

    public void clearPendingFixedPosition() {
        this.pendingFixedSetAt = 0;
    }

    /**
     * Returns {@code true} if a fixed position was set by the user recently (< 120s ago).
     */
    public boolean hasPendingFixedPosition() {
        long setAt = pendingFixedSetAt;
        return setAt > 0 && (System.currentTimeMillis() - setAt) < 120_000;
    }

    public double getPendingFixedLat() { return pendingFixedLat; }
    public double getPendingFixedLon() { return pendingFixedLon; }
    public int getPendingFixedAlt() { return pendingFixedAlt; }

    /**
     * Полностью сбрасывает состояние устройства: очищает nodeDb, каналы,
     * конфиги, все сообщения, ожидающие ACK, owner info и историю телеметрии.
     * Вызывается перед началом нового config exchange.
     * Pending fixed position НЕ сбрасывается — он должен пережить переподключение.
     */
    public void clear() {
        myNodeNum = 0;
        nodeDb.clear();
        channels.clear();
        configs.clear();
        moduleConfigs.clear();
        messagesByChannel.clear();
        directMessages.clear();
        pendingAcks.clear();
        failAllPendingPacketAcks("STATE_CLEARED");
        ownerInfo = null;
        sessionPasskey = null;
        deviceMetadata = null;
        synchronized (telemetryHistory) {
            telemetryHistory.clear();
        }
    }

    public int getNodeCount() {
        return nodeDb.size();
    }
}
