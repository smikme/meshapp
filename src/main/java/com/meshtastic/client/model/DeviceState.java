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

import com.meshtastic.client.service.MessageDbService;

/**
 * Центральное хранилище состояния подключённого Meshtastic-устройства.
 * <p>
 * Содержит базу нод, каналы, конфиги, сообщения (канальные и личные),
 * телеметрию и ожидающие подтверждения ACK. Каждое TCP-соединение получает свой экземпляр
 * {@code DeviceState} через {@link com.meshtastic.client.service.ConnectionManager}.
 * <p>
 * После рефакторинга: делегирует большую часть операций компонентам
 * ({@link NodeDatabase}, {@link ChannelStore}, {@link ConfigStore}, {@link MessageStore}).
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

    // ═══════════════════════════════════════════════════════════
    //  Компоненты состояния (новая архитектура)
    // ═══════════════════════════════════════════════════════════

    /** Управление узлами сети */
    private final NodeDatabase nodeDatabase = new NodeDatabase();

    /** Управление каналами */
    private final ChannelStore channelStore = new ChannelStore();

    /** Управление конфигурацией */
    private final ConfigStore configStore = new ConfigStore();

    /** Управление сообщениями и ACK */
    private final MessageStore messageStore = new MessageStore();

    // ═══════════════════════════════════════════════════════════
    //  Message DB Service (для сохранения сообщений)
    // ═══════════════════════════════════════════════════════════

    private final MessageDbService messageDbService;

    // ═══════════════════════════════════════════════════════════
    //  Специфичные для DeviceState поля (не вынесены в компоненты)
    // ═══════════════════════════════════════════════════════════

    private volatile int myNodeNum;

    /** Pending packet ACK waiters для generic (non-chat) пакетов */
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
    private final CopyOnWriteArrayList<Runnable> telemetryListeners = new CopyOnWriteArrayList<>();

    // Owner info (from admin get_owner_response)
    private volatile MeshProtos.User ownerInfo;
    private volatile ByteString sessionPasskey;
    private final CopyOnWriteArrayList<Runnable> ownerInfoListeners = new CopyOnWriteArrayList<>();
    private volatile MeshProtos.DeviceMetadata deviceMetadata;
    private final CopyOnWriteArrayList<Runnable> deviceMetadataListeners = new CopyOnWriteArrayList<>();

    // Pending fixed position
    private volatile double pendingFixedLat;
    private volatile double pendingFixedLon;
    private volatile int pendingFixedAlt;
    private volatile long pendingFixedSetAt; // epoch millis, 0 = none

    public DeviceState() {
        this.messageDbService = MessageDbService.getInstance();
        ackTimeoutExecutor.scheduleWithFixedDelay(this::runAckSweepSafely,
                ACK_SWEEP_INTERVAL_MS, ACK_SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    // ═══════════════════════════════════════════════════════════
    //  Getters/Setters для компонентов (для backward compatibility)
    // ═══════════════════════════════════════════════════════════

    public NodeDatabase getNodeDatabase() { return nodeDatabase; }
    public ChannelStore getChannelStore() { return channelStore; }
    public ConfigStore getConfigStore() { return configStore; }
    public MessageStore getMessageStore() { return messageStore; }

    // ═══════════════════════════════════════════════════════════
    //  Node database methods (delegate to NodeDatabase)
    // ═══════════════════════════════════════════════════════════

    public int getMyNodeNum() { return myNodeNum; }
    public void setMyNodeNum(int myNodeNum) { this.myNodeNum = myNodeNum; }

    /**
     * Возвращает внутренний map узлов (для backward compatibility).
     *
     * @return ConcurrentHashMap<Integer, NodeData>
     */
    public ConcurrentHashMap<Integer, NodeData> getNodeDb() { return nodeDatabase.getNodeDb(); }

    /**
     * Возвращает ноду из базы или создаёт новую атомарно.
     */
    public NodeData getOrCreateNode(int nodeNum) {
        return nodeDatabase.getOrCreateNode(nodeNum);
    }

    public List<ChannelProtos.Channel> getChannels() { return channelStore.getChannels(); }

    public void addChannel(ChannelProtos.Channel channel) {
        channelStore.addChannel(channel);
    }

    /**
     * Обновить канал по индексу. Если канал с таким индексом есть — заменить, иначе добавить.
     */
    public void updateChannel(ChannelProtos.Channel channel) {
        channelStore.updateChannel(channel);
        fireMessageListeners();
    }

    public boolean isChannelCatalogReady() {
        return channelStore.isChannelCatalogReady();
    }

    public void setChannelCatalogReady(boolean channelCatalogReady) {
        channelStore.setChannelCatalogReady(channelCatalogReady);
    }

    public long getChannelCatalogEpoch() {
        return channelStore.getChannelCatalogEpoch();
    }

    public boolean hasEnabledChannel(int channelIndex) {
        return channelStore.hasEnabledChannel(channelIndex);
    }

    /**
     * Найти первый свободный слот для SECONDARY канала (индексы 1-7).
     */
    public int findFirstAvailableChannelSlot() {
        return channelStore.findFirstAvailableChannelSlot();
    }

    public List<ConfigProtos.Config> getConfigs() { return configStore.getConfigs(); }

    public void addConfig(ConfigProtos.Config config) {
        configStore.addConfig(config);
    }

    public List<ModuleConfigProtos.ModuleConfig> getModuleConfigs() { return configStore.getModuleConfigs(); }

    public void addModuleConfig(ModuleConfigProtos.ModuleConfig moduleConfig) {
        configStore.addModuleConfig(moduleConfig);
    }

    // ═══════════════════════════════════════════════════════════
    //  Message listeners (delegated to MessageStore)
    // ═══════════════════════════════════════════════════════════

    public void addMessageListener(Runnable listener) { messageStore.addMessageListener(listener); }
    public void removeMessageListener(Runnable listener) { messageStore.removeMessageListener(listener); }

    public void addNodeUpdateListener(java.util.function.IntConsumer listener) { nodeDatabase.addNodeUpdateListener(listener); }
    public void removeNodeUpdateListener(java.util.function.IntConsumer listener) { nodeDatabase.removeNodeUpdateListener(listener); }
    public void fireNodeUpdateListeners(int nodeNum) {
        nodeDatabase.fireNodeUpdateListeners(nodeNum);
    }

    public void addTracerouteListener(java.util.function.BiConsumer<Integer, MeshProtos.RouteDiscovery> listener) { tracerouteListeners.add(listener); }
    public void removeTracerouteListener(java.util.function.BiConsumer<Integer, MeshProtos.RouteDiscovery> listener) { tracerouteListeners.remove(listener); }
    public void fireTracerouteListeners(int fromNodeNum, MeshProtos.RouteDiscovery route) {
        for (java.util.function.BiConsumer<Integer, MeshProtos.RouteDiscovery> l : tracerouteListeners) {
            try { l.accept(fromNodeNum, route); }
            catch (Exception e) { log.error("Exception in traceroute listener", e); }
        }
    }

    public void addTelemetryListener(Runnable listener) { telemetryListeners.add(listener); }
    public void removeTelemetryListener(Runnable listener) { telemetryListeners.remove(listener); }

    /**
     * Добавляет запись телеметрии в историю.
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
     * Загружает архивные записи телеметрии в начало истории.
     */
    public void prependTelemetryHistory(List<TelemetryEntry> archived) {
        if (archived == null || archived.isEmpty()) { return; }
        synchronized (telemetryHistory) {
            telemetryHistory.addAll(0, archived);
            while (telemetryHistory.size() > MAX_TELEMETRY_HISTORY) {
                telemetryHistory.removeFirst();
            }
        }
        fireTelemetryListeners();
    }

    public void fireMessageListeners() {
        messageStore.fireMessageListeners();
    }

    /**
     * Добавляет канальное сообщение с дедупликацией по {@code packetId}.
     * Сохраняет сообщение в БД сразу после добавления.
     */
    public void addMessage(MeshMessage msg) {
        messageStore.addMessage(msg);
        // Сохраняем в БД
        String ownerNodeId = getOwnerNodeId();
        if (ownerNodeId != null && msg.getPacketId() > 0) {
            messageDbService.save(msg, "channel", String.valueOf(msg.getChannelIndex()), ownerNodeId);
        }
    }

    public List<MeshMessage> getMessages(int channelIndex) {
        return messageStore.getMessages(channelIndex);
    }

    /**
     * Добавляет личное (DM) сообщение с дедупликацией по {@code packetId}.
     * Сохраняет сообщение в БД сразу после добавления.
     */
    public void addDirectMessage(MeshMessage msg, String peerNodeId) {
        messageStore.addDirectMessage(msg, peerNodeId);
        // Сохраняем в БД
        String ownerNodeId = getOwnerNodeId();
        if (ownerNodeId != null && msg.getPacketId() > 0) {
            messageDbService.save(msg, "dm", peerNodeId, ownerNodeId);
        }
    }

    public void ensureDirectMessageThread(String peerNodeId) {
        messageStore.ensureDirectMessageThread(peerNodeId);
    }

    public List<MeshMessage> getDirectMessages(String peerNodeId) {
        return messageStore.getDirectMessages(peerNodeId);
    }

    public Map<String, List<MeshMessage>> getAllDirectMessages() {
        return messageStore.getAllDirectMessages();
    }

    public void removeDirectMessages(String peerNodeId) {
        messageStore.removeDirectMessages(peerNodeId);
    }

    /** Удалить ноду из nodeDb и directMessages. */
    public void removeNode(int nodeNum) {
        NodeData node = getNodeByNodeId(String.format("!%08x", nodeNum));
        nodeDatabase.removeNode(nodeNum);
    }

    /**
     * Найти ноду по node_id (перебор nodeDb.values()).
     */
    public NodeData getNodeByNodeId(String nodeId) {
        return nodeDatabase.getNodeByNodeId(nodeId);
    }

    public Map<Integer, List<MeshMessage>> getAllChannelMessages() {
        return messageStore.getAllChannelMessages();
    }

    // ═══════════════════════════════════════════════════════════
    //  ACK management (delegated to MessageStore)
    // ═══════════════════════════════════════════════════════════

    /**
     * Регистрирует исходящее сообщение для отслеживания ACK/NAK.
     */
    public void registerPendingAck(int packetId, MeshMessage msg) {
        messageStore.registerPendingAck(packetId, msg);
    }

    /**
     * Извлекает и удаляет сообщение из очереди ожидающих ACK.
     */
    public MeshMessage resolvePendingAck(int packetId) {
        return messageStore.resolvePendingAck(packetId);
    }

    /**
     * Регистрирует generic ACK waiter для не-чатовых пакетов.
     */
    public CompletableFuture<MeshProtos.Routing.Error> registerPendingPacketAck(int packetId) {
        CompletableFuture<MeshProtos.Routing.Error> future = new CompletableFuture<>();
        pendingPacketAcks.put(packetId, future);
        future.whenComplete((ignored, ignoredError) -> pendingPacketAcks.remove(packetId, future));
        return future;
    }

    /**
     * Завершает generic ACK waiter для не-чатового пакета.
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
     * Помечает все ожидающие ACK сообщения как FAILED с указанной причиной.
     */
    public void failAllPendingAcks(String reason) {
        // Обновляем статус в БД для каждого сообщения
        messageStore.failAllPendingAcksWithDbUpdate(reason, (packetId, msg) -> {
            if (msg != null) {
                messageDbService.updateStatus(packetId, msg.getStatus(), msg.getErrorReason());
            }
        });
    }

    /**
     * Завершает все generic ACK waiter-ы ошибкой disconnect/cleanup.
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
     */
    public void shutdown() {
        ackTimeoutExecutor.shutdownNow();
    }

    /**
     * Ищет сообщение по {@code packetId} в памяти, затем в БД.
     */
    public MeshMessage findMessageByPacketId(int packetId) {
        // Сначала ищем в памяти
        MeshMessage msg = findRuntimeMessageByPacketId(packetId);
        if (msg != null) {
            return msg;
        }
        // Если не найдено, ищем в БД
        if (packetId > 0) {
            return messageDbService.findByPacketId(packetId);
        }
        return null;
    }

    /**
     * Ищет сообщение только в текущем runtime-хранилище, без fallback в БД.
     */
    public MeshMessage findRuntimeMessageByPacketId(int packetId) {
        return messageStore.findMessageByPacketId(packetId);
    }

    // ═══════════════════════════════════════════════════════════
    //  Owner info / session passkey (admin)
    // ═══════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════
    //  Telemetry listeners (internal)
    // ═══════════════════════════════════════════════════════════

    private final CopyOnWriteArrayList<java.util.function.BiConsumer<Integer, MeshProtos.RouteDiscovery>> tracerouteListeners = new CopyOnWriteArrayList<>();

    private void fireTelemetryListeners() {
        for (Runnable r : telemetryListeners) {
            try { r.run(); }
            catch (Exception e) { log.error("Exception in telemetry listener", e); }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  ACK sweep (internal)
    // ═══════════════════════════════════════════════════════════

    private void runAckSweepSafely() {
        try {
            sweepExpiredAcks();
        } catch (Throwable t) {
            log.error("ACK sweep crashed", t);
        }
    }

    private void sweepExpiredAcks() {
        long now = System.currentTimeMillis();
        boolean anyExpired = false;
        // ACK sweep through MessageStore
        for (var mapEntry : messageStore.getPendingAcks().entrySet()) {
            int packetId = mapEntry.getKey();
            MessageStore.PendingAckEntry entry = mapEntry.getValue();
            if (now - entry.registeredAtMillis() >= ACK_TIMEOUT_MS) {
                MessageStore.PendingAckEntry removed = messageStore.getPendingAcks().remove(packetId);
                if (removed != null) {
                    MeshMessage msg = removed.message();
                    msg.setStatus(MeshMessage.DeliveryStatus.FAILED);
                    msg.setErrorReason("TIMEOUT");
                    log.warn("ACK timeout for packetId {}", packetId);
                    
                    // Обновляем в БД
                    if (packetId > 0) {
                        messageDbService.updateStatus(packetId, msg.getStatus(), msg.getErrorReason());
                    }
                    
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
     * Pending fixed position НЕ сбрасывается — он должен пережить переподключение.
     */
    public void clear() {
        myNodeNum = 0;
        nodeDatabase.clear();
        channelStore.clear();
        configStore.clear();
        messageStore.clear();
        pendingPacketAcks.clear();
        ownerInfo = null;
        sessionPasskey = null;
        deviceMetadata = null;
        synchronized (telemetryHistory) {
            telemetryHistory.clear();
        }
    }

    public int getNodeCount() {
        return nodeDatabase.getNodeCount();
    }

    // ═══════════════════════════════════════════════════════════
    //  Helper methods
    // ═══════════════════════════════════════════════════════════

    /**
     * Возвращает nodeId устройства-владельца из ownerInfo.
     */
    private String getOwnerNodeId() {
        if (ownerInfo != null) {
            return ownerInfo.getId();
        }
        return null;
    }
}
