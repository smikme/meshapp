package com.meshtastic.client.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Управление сообщениями Meshtastic-чата.
 * <p>
 * Хранит канальные и личные сообщения, отслеживает pending ACK.
 * Потокобезопасность через ConcurrentHashMap и synchronized списки.
 * <p>
 * Ответственность:
 * <ul>
 *   <li>Хранение канальных сообщений по channelIndex</li>
 *   <li>Хранение личных сообщений (DM) по peerNodeId</li>
 *   <li>Отслеживание pending ACK для исходящих сообщений</li>
 *   <li>Дедупликация сообщений по packetId</li>
 * </ul>
 */
public class MessageStore {

    private static final Logger log = LoggerFactory.getLogger(MessageStore.class);

    /** Максимум сообщений в памяти на канал/DM */
    private static final int MAX_MESSAGES_IN_MEMORY = 100;

    /** Служебная запись для pending ACK */
    public record PendingAckEntry(MeshMessage message, long registeredAtMillis) {}

    /** Канальные сообщения: channelIndex -> List<MeshMessage> */
    private final Map<Integer, List<MeshMessage>> messagesByChannel = new ConcurrentHashMap<>();

    /** Личные сообщения: peerNodeId -> List<MeshMessage> */
    private final Map<String, List<MeshMessage>> directMessages = new ConcurrentHashMap<>();

    /** Pending ACK: packetId -> PendingAckEntry */
    private final ConcurrentHashMap<Integer, PendingAckEntry> pendingAcks = new ConcurrentHashMap<>();

    /** Слушатели обновлений сообщений */
    private final CopyOnWriteArrayList<Runnable> messageListeners = new CopyOnWriteArrayList<>();

    /**
     * Добавляет канальное сообщение с дедупликацией по идентичности пакета
     * ({@code packetId + from/to + outgoing + channel}).
     *
     * @param msg сообщение для добавления
     */
    public void addMessage(MeshMessage msg) {
        List<MeshMessage> list = messagesByChannel
                .computeIfAbsent(msg.getChannelIndex(), k -> Collections.synchronizedList(new ArrayList<>()));
        boolean notifyListeners = false;
        synchronized (list) {
            // В канале packetId сам по себе недостаточен: у разных sender'ов
            // может совпасть идентификатор пакета.
            if (msg.getPacketId() != 0) {
                for (MeshMessage existing : list) {
                    if (isDuplicateMessage(existing, msg)) {
                        notifyListeners = mergeDuplicateMessage(existing, msg);
                        if (!notifyListeners) {
                            return;
                        }
                        break;
                    }
                }
            }
            if (!notifyListeners) {
                list.add(msg);
                while (list.size() > MAX_MESSAGES_IN_MEMORY) {
                    list.remove(0);
                }
                notifyListeners = true;
            }
        }
        if (notifyListeners) {
            fireMessageListeners();
        }
    }

    /**
     * Возвращает список канальных сообщений для указанного канала.
     *
     * @param channelIndex индекс канала
     * @return список сообщений (не null)
     */
    public List<MeshMessage> getMessages(int channelIndex) {
        List<MeshMessage> list = messagesByChannel.get(channelIndex);
        return list != null ? list : Collections.emptyList();
    }

    /**
     * Возвращает все канальные сообщения.
     *
     * @return Map<channelIndex, List<MeshMessage>>
     */
    public Map<Integer, List<MeshMessage>> getAllChannelMessages() {
        return messagesByChannel;
    }

    /**
     * Добавляет личное сообщение с дедупликацией по идентичности пакета
     * ({@code packetId + from/to + outgoing + channel}).
     *
     * @param msg        сообщение для добавления
     * @param peerNodeId node_id собеседника (ключ группировки)
     */
    public void addDirectMessage(MeshMessage msg, String peerNodeId) {
        List<MeshMessage> list = directMessages
                .computeIfAbsent(peerNodeId, k -> Collections.synchronizedList(new ArrayList<>()));
        boolean notifyListeners = false;
        synchronized (list) {
            if (msg.getPacketId() != 0) {
                for (MeshMessage existing : list) {
                    if (isDuplicateMessage(existing, msg)) {
                        notifyListeners = mergeDuplicateMessage(existing, msg);
                        if (!notifyListeners) {
                            return;
                        }
                        break;
                    }
                }
            }
            if (!notifyListeners) {
                list.add(msg);
                while (list.size() > MAX_MESSAGES_IN_MEMORY) {
                    list.remove(0);
                }
                notifyListeners = true;
            }
        }
        if (notifyListeners) {
            fireMessageListeners();
        }
    }

    /**
     * Возвращает список личных сообщений для указанного собеседника.
     *
     * @param peerNodeId node_id собеседника
     * @return список сообщений (не null)
     */
    public List<MeshMessage> getDirectMessages(String peerNodeId) {
        List<MeshMessage> list = directMessages.get(peerNodeId);
        return list != null ? list : Collections.emptyList();
    }

    /**
     * Возвращает все личные сообщения.
     *
     * @return Map<peerNodeId, List<MeshMessage>>
     */
    public Map<String, List<MeshMessage>> getAllDirectMessages() {
        return directMessages;
    }

    /**
     * Удаляет историю личных сообщений с указанным собеседником.
     *
     * @param peerNodeId node_id собеседника
     */
    public void removeDirectMessages(String peerNodeId) {
        directMessages.remove(peerNodeId);
    }

    /**
     * Гарантирует наличие списка DM для указанного собеседника.
     *
     * @param peerNodeId node_id собеседника
     */
    public void ensureDirectMessageThread(String peerNodeId) {
        if (peerNodeId == null || peerNodeId.isEmpty()) { return; }
        List<MeshMessage> existing = directMessages.putIfAbsent(
                peerNodeId, Collections.synchronizedList(new ArrayList<>()));
        if (existing == null) {
            fireMessageListeners();
        }
    }

    /**
     * Добавляет listener для уведомлений об изменениях в сообщениях.
     *
     * @param listener функция без параметров
     */
    public void addMessageListener(Runnable listener) {
        messageListeners.add(listener);
    }

    /**
     * Удаляет listener для уведомлений об изменениях в сообщениях.
     *
     * @param listener ранее добавленный listener
     */
    public void removeMessageListener(Runnable listener) {
        messageListeners.remove(listener);
    }

    /**
     * Оповещает всех listener'ов об изменениях в сообщениях.
     */
    public void fireMessageListeners() {
        for (Runnable r : messageListeners) {
            try { r.run(); }
            catch (Exception e) { log.error("Exception in message listener", e); }
        }
    }

    /**
     * Регистрирует исходящее сообщение для отслеживания ACK/NAK.
     *
     * @param packetId уникальный идентификатор пакета
     * @param msg      сообщение в статусе SENDING
     */
    public void registerPendingAck(int packetId, MeshMessage msg) {
        pendingAcks.put(packetId, new PendingAckEntry(msg, System.currentTimeMillis()));
    }

    /**
     * Извлекает и удаляет сообщение из очереди ожидающих ACK.
     *
     * @param packetId идентификатор пакета из routing-ответа
     * @return сообщение, ожидавшее подтверждения, или {@code null}
     */
    public MeshMessage resolvePendingAck(int packetId) {
        PendingAckEntry entry = pendingAcks.remove(packetId);
        return entry != null ? entry.message() : null;
    }

    /**
     * Помечает все ожидающие ACK сообщения как FAILED с указанной причиной.
     *
     * @param reason причина неудачи (например, "DISCONNECTED")
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
            count++;
        }
        if (count > 0) {
            log.info("Marked {} pending messages as FAILED (reason: {})", count, reason);
            fireMessageListeners();
        }
    }

    /**
     * Помечает сообщения как FAILED и обновляет статус в БД.
     * Вызывает MessageDbService.updateStatus для каждого сообщения.
     *
     * @param reason причина неудачи
     */
    public void failAllPendingAcksWithDbUpdate(String reason,
                                               java.util.function.BiConsumer<Integer, MeshMessage> updateDb) {
        if (pendingAcks.isEmpty()) { return; }
        int count = 0;
        var iterator = pendingAcks.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            int packetId = entry.getKey();
            MeshMessage msg = entry.getValue().message();
            
            iterator.remove();
            msg.setStatus(MeshMessage.DeliveryStatus.FAILED);
            msg.setErrorReason(reason);
            
            // Обновляем в БД
            if (updateDb != null && packetId > 0) {
                updateDb.accept(packetId, msg);
            }
            
            count++;
        }
        if (count > 0) {
            log.info("Marked {} pending messages as FAILED (reason: {})", count, reason);
            fireMessageListeners();
        }
    }

    /**
     * Ищет сообщение по {@code packetId} в памяти.
     *
     * @param packetId идентификатор пакета
     * @return найденное сообщение или {@code null}
     */
    public MeshMessage findMessageByPacketId(int packetId) {
        if (packetId == 0) { return null; }
        // Ищем в канальных сообщениях
        for (List<MeshMessage> msgs : messagesByChannel.values()) {
            synchronized (msgs) {
                for (MeshMessage msg : msgs) {
                    if (msg.getPacketId() == packetId) { return msg; }
                }
            }
        }
        // Ищем в личных сообщениях
        for (List<MeshMessage> msgs : directMessages.values()) {
            synchronized (msgs) {
                for (MeshMessage msg : msgs) {
                    if (msg.getPacketId() == packetId) { return msg; }
                }
            }
        }
        return null;
    }

    /**
     * Очищает все сообщения из памяти (оставляет pending ACK для обработки).
     */
    public void clear() {
        messagesByChannel.clear();
        directMessages.clear();
    }

    /**
     * Возвращает internal map pending ACK (для ACK sweep).
     *
     * @return ConcurrentHashMap<Integer, PendingAckEntry>
     */
    public ConcurrentHashMap<Integer, PendingAckEntry> getPendingAcks() {
        return pendingAcks;
    }

    private boolean mergeDuplicateMessage(MeshMessage existing, MeshMessage incoming) {
        if (existing == null || incoming == null) {
            return false;
        }

        boolean changed = false;
        boolean existingViaMqtt = existing.isViaMqtt();
        boolean incomingViaMqtt = incoming.isViaMqtt();
        boolean preferIncomingTransportMetadata = !incomingViaMqtt || existingViaMqtt == incomingViaMqtt;

        MeshMessage.DeliveryStatus incomingStatus = incoming.getStatus();
        if (incomingStatus != null && shouldReplaceStatus(existing.getStatus(), incomingStatus)) {
            existing.setStatus(incomingStatus);
            changed = true;
        }

        if (!hasText(existing.getErrorReason()) && hasText(incoming.getErrorReason())) {
            existing.setErrorReason(incoming.getErrorReason());
            changed = true;
        }
        if (existing.getReplyId() == 0 && incoming.getReplyId() != 0) {
            existing.setReplyId(incoming.getReplyId());
            changed = true;
        }
        if (!hasText(existing.getReplyText()) && hasText(incoming.getReplyText())) {
            existing.setReplyText(incoming.getReplyText());
            changed = true;
        }
        if (!hasText(existing.getSenderName()) && hasText(incoming.getSenderName())) {
            existing.setSenderName(incoming.getSenderName());
            changed = true;
        }

        if (preferIncomingTransportMetadata) {
            if (incoming.getHopStart() != 0 && existing.getHopStart() != incoming.getHopStart()) {
                existing.setHopStart(incoming.getHopStart());
                changed = true;
            }
            if (incoming.getHopLimit() != 0 && existing.getHopLimit() != incoming.getHopLimit()) {
                existing.setHopLimit(incoming.getHopLimit());
                changed = true;
            }
            if (incoming.getRxRssi() != 0 && existing.getRxRssi() != incoming.getRxRssi()) {
                existing.setRxRssi(incoming.getRxRssi());
                changed = true;
            }
            if (incoming.getRxSnr() != 0 && Float.compare(existing.getRxSnr(), incoming.getRxSnr()) != 0) {
                existing.setRxSnr(incoming.getRxSnr());
                changed = true;
            }
        }

        boolean mergedViaMqtt = existingViaMqtt && incomingViaMqtt;
        if (existing.isViaMqtt() != mergedViaMqtt) {
            existing.setViaMqtt(mergedViaMqtt);
            changed = true;
        }
        if (!existing.isSystemMessage() && incoming.isSystemMessage()) {
            existing.setSystemMessage(true);
            changed = true;
        }
        return changed;
    }

    private static boolean isDuplicateMessage(MeshMessage existing, MeshMessage incoming) {
        return existing != null
                && incoming != null
                && existing.getPacketId() != 0
                && existing.getPacketId() == incoming.getPacketId()
                && existing.getChannelIndex() == incoming.getChannelIndex()
                && existing.isOutgoing() == incoming.isOutgoing()
                && Objects.equals(existing.getFromNodeId(), incoming.getFromNodeId())
                && Objects.equals(existing.getToNodeId(), incoming.getToNodeId());
    }

    private static boolean shouldReplaceStatus(MeshMessage.DeliveryStatus current,
                                               MeshMessage.DeliveryStatus incoming) {
        if (incoming == null || incoming == current) {
            return false;
        }
        if (current == null) {
            return true;
        }
        if (current == MeshMessage.DeliveryStatus.SENDING) {
            return incoming != MeshMessage.DeliveryStatus.SENDING;
        }
        return false;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
