package com.meshtastic.client.model;

import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;
import com.google.protobuf.ByteString;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.meshtastic.client.service.MessageDbService;

/**
 * Central state store for a connected Meshtastic device.
 * <p>
 * Contains the node database, channels, configuration, channel and direct
 * messages, telemetry, and pending ACK tracking. Each TCP connection receives
 * its own {@code DeviceState} through
 * {@link com.meshtastic.client.service.ConnectionManager}.
 * <p>
 * After refactoring, most operations are delegated to components:
 * {@link NodeDatabase}, {@link ChannelStore}, {@link ConfigStore}, and
 * {@link MessageStore}. UI updates are performed through {@code Platform.runLater()}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class DeviceState {

    private static final Logger log = LoggerFactory.getLogger(DeviceState.class);

    /** ACK wait timeout: DMs without recipient ACK stay sent, while channels become FAILED. */
    private static final long ACK_TIMEOUT_MS = 240_000;
    /** Interval for checking expired pending ACKs. */
    private static final long ACK_SWEEP_INTERVAL_MS = 10_000;

    // ═══════════════════════════════════════════════════════════
    // State components in the newer architecture.
    // ═══════════════════════════════════════════════════════════

    /** Network node management. */
    private final NodeDatabase nodeDatabase = new NodeDatabase();

    /** Channel management. */
    private final ChannelStore channelStore = new ChannelStore();

    /** Configuration management. */
    private final ConfigStore configStore = new ConfigStore();

    /** Message and ACK management. */
    private final MessageStore messageStore = new MessageStore();

    // ═══════════════════════════════════════════════════════════
    // Message DB service used for persistence.
    // ═══════════════════════════════════════════════════════════

    private final MessageDbService messageDbService;

    // ═══════════════════════════════════════════════════════════
    // Fields specific to DeviceState that have not been moved into components.
    // ═══════════════════════════════════════════════════════════

    private volatile int myNodeNum;

    /** Pending packet ACK waiters for generic non-chat packets. */
    private final ConcurrentHashMap<Integer, CompletableFuture<MeshProtos.Routing.Error>> pendingPacketAcks =
            new ConcurrentHashMap<>();

    private final ScheduledExecutorService ackTimeoutExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ack-timeout-sweeper");
        t.setDaemon(true);
        return t;
    });

    /** Telemetry history, limited to the last MAX_TELEMETRY_HISTORY entries. */
    private static final int MAX_TELEMETRY_HISTORY = 200;
    private final List<TelemetryEntry> telemetryHistory = new LinkedList<>();
    private final CopyOnWriteArrayList<Runnable> telemetryListeners = new CopyOnWriteArrayList<>();

    // Owner info (from admin get_owner_response)
    private volatile MeshProtos.User ownerInfo;
    private volatile ByteString sessionPasskey;
    private final CopyOnWriteArrayList<Runnable> ownerInfoListeners = new CopyOnWriteArrayList<>();
    private volatile MeshProtos.DeviceMetadata deviceMetadata;
    private final CopyOnWriteArrayList<Runnable> deviceMetadataListeners = new CopyOnWriteArrayList<>();
    private volatile String ringtone;
    private volatile boolean ringtoneLoaded;
    private final CopyOnWriteArrayList<Runnable> ringtoneListeners = new CopyOnWriteArrayList<>();

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
    // Component getters/setters kept for backward compatibility.
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
     * Returns the internal node map for backward compatibility.
     *
     * @return {@code ConcurrentHashMap<Integer, NodeData>}
     */
    public ConcurrentHashMap<Integer, NodeData> getNodeDb() { return nodeDatabase.getNodeDb(); }

    /**
     * Returns a node from the database, or creates it atomically.
     */
    public NodeData getOrCreateNode(int nodeNum) {
        return nodeDatabase.getOrCreateNode(nodeNum);
    }

    public List<ChannelProtos.Channel> getChannels() { return channelStore.getChannels(); }

    public void addChannel(ChannelProtos.Channel channel) {
        channelStore.addChannel(channel);
    }

    /**
     * Updates a channel by index. Existing channels are replaced; missing ones are added.
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
     * Finds the first free slot for a SECONDARY channel, using indexes 1-7.
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
    public void addMessageChangeListener(Consumer<MessageChangeEvent> listener) {
        messageStore.addMessageChangeListener(listener);
    }
    public void removeMessageChangeListener(Consumer<MessageChangeEvent> listener) {
        messageStore.removeMessageChangeListener(listener);
    }

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
     * Adds a telemetry entry to history.
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
     * Loads archived telemetry entries at the beginning of history.
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

    public void fireMessageChange(MessageChangeEvent event) {
        messageStore.fireMessageChange(event);
    }

    /**
     * Adds a channel message with {@code packetId} deduplication.
     * Saves the message to the database immediately after adding it.
     */
    public void addMessage(MeshMessage msg) {
        messageStore.addMessage(msg);
            // Save to the database.
        String ownerNodeId = getOwnerNodeId();
        if (ownerNodeId != null && msg.getPacketId() > 0) {
            messageDbService.save(msg, "channel", String.valueOf(msg.getChannelIndex()), ownerNodeId);
        }
        messageStore.fireMessageChange(MessageChangeEvent.newMessage(
                "channel",
                String.valueOf(msg.getChannelIndex()),
                ownerNodeId,
                msg));
    }

    public List<MeshMessage> getMessages(int channelIndex) {
        return messageStore.getMessages(channelIndex);
    }

    /**
     * Adds a direct message with {@code packetId} deduplication.
     * Saves the message to the database immediately after adding it.
     */
    public void addDirectMessage(MeshMessage msg, String peerNodeId) {
        messageStore.addDirectMessage(msg, peerNodeId);
            // Save to the database.
        String ownerNodeId = getOwnerNodeId();
        if (ownerNodeId != null && msg.getPacketId() > 0) {
            messageDbService.save(msg, "dm", peerNodeId, ownerNodeId);
        }
        messageStore.fireMessageChange(MessageChangeEvent.newMessage("dm", peerNodeId, ownerNodeId, msg));
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

    /** Removes a node from nodeDb and directMessages. */
    public void removeNode(int nodeNum) {
        nodeDatabase.removeNode(nodeNum);
    }

    /**
     * Finds a node by node_id by scanning nodeDb values.
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
     * Registers an outgoing message for ACK/NAK tracking.
     */
    public void registerPendingAck(int packetId, MeshMessage msg) {
        messageStore.registerPendingAck(packetId, msg);
    }

    /**
     * Retrieves and removes a message from the pending ACK queue.
     */
    public MeshMessage resolvePendingAck(int packetId) {
        return messageStore.resolvePendingAck(packetId);
    }

    /**
     * Registers a generic ACK waiter for non-chat packets.
     */
    public CompletableFuture<MeshProtos.Routing.Error> registerPendingPacketAck(int packetId) {
        CompletableFuture<MeshProtos.Routing.Error> future = new CompletableFuture<>();
        pendingPacketAcks.put(packetId, future);
        future.whenComplete((ignored, ignoredError) -> pendingPacketAcks.remove(packetId, future));
        return future;
    }

    /**
     * Completes a generic ACK waiter for a non-chat packet.
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
     * Marks all pending-ACK messages as FAILED with the given reason.
     */
    public void failAllPendingAcks(String reason) {
        // Update each message status in the database.
        messageStore.failAllPendingAcksWithDbUpdate(reason, (packetId, msg) -> {
            if (msg != null) {
                messageDbService.updateStatus(packetId, msg.getStatus(), msg.getErrorReason());
                fireMessageChange(statusChangedEvent(msg));
            }
        });
    }

    /**
     * Completes all generic ACK waiters with a disconnect/cleanup error.
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
     * Stops the background thread that checks ACK timeouts.
     */
    public void shutdown() {
        ackTimeoutExecutor.shutdownNow();
    }

    /**
     * Finds a message by {@code packetId}, checking memory first and then the database.
     */
    public MeshMessage findMessageByPacketId(int packetId) {
        // Search in memory first.
        MeshMessage msg = findRuntimeMessageByPacketId(packetId);
        if (msg != null) {
            return msg;
        }
        // If not found, search in the database.
        if (packetId > 0) {
            return messageDbService.findByPacketId(packetId);
        }
        return null;
    }

    /**
     * Finds a message only in the current runtime store, without falling back to the database.
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

    public String getRingtone() { return ringtone != null ? ringtone : ""; }
    public boolean isRingtoneLoaded() { return ringtoneLoaded; }
    public void setRingtone(String ringtone) {
        this.ringtone = ringtone != null ? ringtone : "";
        this.ringtoneLoaded = true;
    }

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

    public void addRingtoneListener(Runnable listener) { ringtoneListeners.add(listener); }
    public void removeRingtoneListener(Runnable listener) { ringtoneListeners.remove(listener); }
    public void fireRingtoneListeners() {
        for (Runnable r : ringtoneListeners) {
            try { r.run(); }
            catch (Exception e) { log.error("Exception in ringtone listener", e); }
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
                    if (msg.isDirectMessage()) {
                        msg.setStatus(MeshMessage.DeliveryStatus.DELIVERED);
                        msg.setErrorReason(null);
                        log.info("Recipient ACK timeout for DM packetId {}, marking as delivered", packetId);
                    } else {
                        msg.setStatus(MeshMessage.DeliveryStatus.FAILED);
                        msg.setErrorReason("TIMEOUT");
                        log.warn("ACK timeout for packetId {}", packetId);
                    }
                    
        // Update in the database.
                    if (packetId > 0) {
                        messageDbService.updateStatus(packetId, msg.getStatus(), msg.getErrorReason());
                    }
                    fireMessageChange(statusChangedEvent(msg));
                    
                    anyExpired = true;
                }
            }
        }
        if (anyExpired) {
            fireMessageListeners();
        }
    }

    private MessageChangeEvent statusChangedEvent(MeshMessage message) {
        return MessageChangeEvent.statusChanged(
                message.isDirectMessage() ? "dm" : "channel",
                message.isDirectMessage()
                        ? message.isOutgoing() ? message.getToNodeId() : message.getFromNodeId()
                        : String.valueOf(message.getChannelIndex()),
                getOwnerNodeId(),
                message);
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
     * Returns {@code true} if the fixed position was set by the user recently,
     * within the last 120 seconds.
     */
    public boolean hasPendingFixedPosition() {
        long setAt = pendingFixedSetAt;
        return setAt > 0 && (System.currentTimeMillis() - setAt) < 120_000;
    }

    public double getPendingFixedLat() { return pendingFixedLat; }
    public double getPendingFixedLon() { return pendingFixedLon; }
    public int getPendingFixedAlt() { return pendingFixedAlt; }

    /**
     * Fully resets device state: clears nodeDb, channels, configuration, all
     * messages, pending ACKs, owner info, and telemetry history. Pending fixed
     * position is intentionally not reset because it must survive reconnection.
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
        ringtone = null;
        ringtoneLoaded = false;
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
     * Returns the owner device nodeId from ownerInfo.
     */
    public String getOwnerNodeId() {
        if (ownerInfo != null) {
            return ownerInfo.getId();
        }
        return myNodeNum != 0 ? String.format("!%08x", myNodeNum) : null;
    }
}
