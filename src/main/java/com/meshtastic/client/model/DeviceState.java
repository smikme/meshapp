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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class DeviceState {

    private static final Logger log = LoggerFactory.getLogger(DeviceState.class);

    private volatile int myNodeNum;
    private final ConcurrentHashMap<Integer, NodeData> nodeDb = new ConcurrentHashMap<>();
    private final List<ChannelProtos.Channel> channels = Collections.synchronizedList(new ArrayList<>());
    private final List<ConfigProtos.Config> configs = Collections.synchronizedList(new ArrayList<>());
    private final List<ModuleConfigProtos.ModuleConfig> moduleConfigs = Collections.synchronizedList(new ArrayList<>());
    private final Map<Integer, List<MeshMessage>> messagesByChannel = new ConcurrentHashMap<>();
    private final Map<Integer, List<MeshMessage>> directMessages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, MeshMessage> pendingAcks = new ConcurrentHashMap<>();
    /** История телеметрии (последние MAX_TELEMETRY_HISTORY записей). */
    private static final int MAX_TELEMETRY_HISTORY = 200;
    private final LinkedList<TelemetryEntry> telemetryHistory = new LinkedList<>();
    private final List<Runnable> telemetryListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> messageListeners = new CopyOnWriteArrayList<>();
    private final List<java.util.function.IntConsumer> nodeUpdateListeners = new CopyOnWriteArrayList<>();
    private final List<BiConsumer<Integer, MeshProtos.RouteDiscovery>> tracerouteListeners = new CopyOnWriteArrayList<>();

    // Owner info (from admin get_owner_response)
    private volatile MeshProtos.User ownerInfo;
    private volatile ByteString sessionPasskey;
    private final List<Runnable> ownerInfoListeners = new CopyOnWriteArrayList<>();

    public int getMyNodeNum() { return myNodeNum; }
    public void setMyNodeNum(int myNodeNum) { this.myNodeNum = myNodeNum; }

    public ConcurrentHashMap<Integer, NodeData> getNodeDb() { return nodeDb; }

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
                if (!usedIndices.contains(i)) return i;
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
        if (archived == null || archived.isEmpty()) return;
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

    public void addMessage(MeshMessage msg) {
        List<MeshMessage> list = messagesByChannel
                .computeIfAbsent(msg.getChannelIndex(), k -> Collections.synchronizedList(new ArrayList<>()));
        // Дедупликация по packetId (радио может ретранслировать пакеты)
        if (msg.getPacketId() != 0) {
            synchronized (list) {
                for (MeshMessage existing : list) {
                    if (existing.getPacketId() == msg.getPacketId()) return;
                }
            }
        }
        list.add(msg);
        fireMessageListeners();
    }

    public List<MeshMessage> getMessages(int channelIndex) {
        List<MeshMessage> list = messagesByChannel.get(channelIndex);
        return list != null ? list : Collections.emptyList();
    }

    public void addDirectMessage(MeshMessage msg, int peerNodeNum) {
        List<MeshMessage> list = directMessages
                .computeIfAbsent(peerNodeNum, k -> Collections.synchronizedList(new ArrayList<>()));
        // Дедупликация по packetId (радио может ретранслировать пакеты)
        if (msg.getPacketId() != 0) {
            synchronized (list) {
                for (MeshMessage existing : list) {
                    if (existing.getPacketId() == msg.getPacketId()) return;
                }
            }
        }
        list.add(msg);
        fireMessageListeners();
    }

    public List<MeshMessage> getDirectMessages(int peerNodeNum) {
        List<MeshMessage> list = directMessages.get(peerNodeNum);
        return list != null ? list : Collections.emptyList();
    }

    public Map<Integer, List<MeshMessage>> getAllDirectMessages() {
        return directMessages;
    }

    public void removeDirectMessages(int peerNodeNum) {
        directMessages.remove(peerNodeNum);
    }

    public Map<Integer, List<MeshMessage>> getAllChannelMessages() {
        return messagesByChannel;
    }

    public void registerPendingAck(int packetId, MeshMessage msg) {
        pendingAcks.put(packetId, msg);
    }

    public MeshMessage resolvePendingAck(int packetId) {
        return pendingAcks.remove(packetId);
    }

    public MeshMessage findMessageByPacketId(int packetId) {
        if (packetId == 0) return null;
        // Сначала ищем в памяти (быстро, для текущей сессии)
        for (List<MeshMessage> msgs : messagesByChannel.values()) {
            synchronized (msgs) {
                for (MeshMessage msg : msgs) {
                    if (msg.getPacketId() == packetId) return msg;
                }
            }
        }
        for (List<MeshMessage> msgs : directMessages.values()) {
            synchronized (msgs) {
                for (MeshMessage msg : msgs) {
                    if (msg.getPacketId() == packetId) return msg;
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

    // --- Owner info (admin) ---
    public MeshProtos.User getOwnerInfo() { return ownerInfo; }
    public void setOwnerInfo(MeshProtos.User ownerInfo) { this.ownerInfo = ownerInfo; }

    public ByteString getSessionPasskey() { return sessionPasskey; }
    public void setSessionPasskey(ByteString sessionPasskey) { this.sessionPasskey = sessionPasskey; }

    public void addOwnerInfoListener(Runnable listener) { ownerInfoListeners.add(listener); }
    public void removeOwnerInfoListener(Runnable listener) { ownerInfoListeners.remove(listener); }
    public void fireOwnerInfoListeners() {
        for (Runnable r : ownerInfoListeners) {
            try { r.run(); }
            catch (Exception e) { log.error("Exception in owner info listener", e); }
        }
    }

    public void clear() {
        myNodeNum = 0;
        nodeDb.clear();
        channels.clear();
        configs.clear();
        moduleConfigs.clear();
        messagesByChannel.clear();
        directMessages.clear();
        pendingAcks.clear();
        ownerInfo = null;
        sessionPasskey = null;
        synchronized (telemetryHistory) {
            telemetryHistory.clear();
        }
    }

    public int getNodeCount() {
        return nodeDb.size();
    }
}
