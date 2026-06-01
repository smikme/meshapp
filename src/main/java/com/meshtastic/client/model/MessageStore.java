package com.meshtastic.client.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Meshtastic chat message store.
 * <p>
 * Stores channel and direct messages and tracks pending ACKs. Thread safety is
 * provided by ConcurrentHashMap and synchronized lists.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>store channel messages by channelIndex</li>
 *   <li>store direct messages by peerNodeId</li>
 *   <li>track pending ACKs for outgoing messages</li>
 *   <li>deduplicate messages by packetId</li>
 * </ul>
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class MessageStore {

    private static final Logger log = LoggerFactory.getLogger(MessageStore.class);

    /** Maximum number of in-memory messages per channel or DM. */
    private static final int MAX_MESSAGES_IN_MEMORY = 100;

    /** Internal entry for a pending ACK. */
    public record PendingAckEntry(MeshMessage message, long registeredAtMillis) {}

    /** Channel messages: channelIndex -> List<MeshMessage>. */
    private final Map<Integer, List<MeshMessage>> messagesByChannel = new ConcurrentHashMap<>();

    /** Direct messages: peerNodeId -> List<MeshMessage>. */
    private final Map<String, List<MeshMessage>> directMessages = new ConcurrentHashMap<>();

    /** Pending ACK: packetId -> PendingAckEntry */
    private final ConcurrentHashMap<Integer, PendingAckEntry> pendingAcks = new ConcurrentHashMap<>();

    /** Message update listeners. */
    private final CopyOnWriteArrayList<Runnable> messageListeners = new CopyOnWriteArrayList<>();
    /** Detailed message-change listeners used for targeted UI updates. */
    private final CopyOnWriteArrayList<Consumer<MessageChangeEvent>> messageChangeListeners = new CopyOnWriteArrayList<>();

    /**
     * Adds a channel message with packet-identity deduplication:
     * {@code packetId + from/to + outgoing + channel}.
     *
     * @param msg message to add
     */
    public void addMessage(MeshMessage msg) {
        List<MeshMessage> list = messagesByChannel
                .computeIfAbsent(msg.getChannelIndex(), k -> Collections.synchronizedList(new ArrayList<>()));
        boolean notifyListeners = false;
        synchronized (list) {
        // In a channel, packetId alone is not enough: different senders can use
        // the same packet id.
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
     * Returns channel messages for the given channel.
     *
     * @param channelIndex channel index
     * @return message list, never null
     */
    public List<MeshMessage> getMessages(int channelIndex) {
        List<MeshMessage> list = messagesByChannel.get(channelIndex);
        return list != null ? list : Collections.emptyList();
    }

    /**
     * Returns all channel messages.
     *
     * @return {@code Map<channelIndex, List<MeshMessage>>}
     */
    public Map<Integer, List<MeshMessage>> getAllChannelMessages() {
        return messagesByChannel;
    }

    /**
     * Adds a direct message with packet-identity deduplication:
     * {@code packetId + from/to + outgoing + channel}.
     *
     * @param msg        message to add
     * @param peerNodeId peer node_id, used as the grouping key
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
     * Returns direct messages for the given peer.
     *
     * @param peerNodeId peer node_id
     * @return message list, never null
     */
    public List<MeshMessage> getDirectMessages(String peerNodeId) {
        List<MeshMessage> list = directMessages.get(peerNodeId);
        return list != null ? list : Collections.emptyList();
    }

    /**
     * Returns all direct messages.
     *
     * @return {@code Map<peerNodeId, List<MeshMessage>>}
     */
    public Map<String, List<MeshMessage>> getAllDirectMessages() {
        return directMessages;
    }

    /**
     * Deletes direct-message history for the given peer.
     *
     * @param peerNodeId peer node_id
     */
    public void removeDirectMessages(String peerNodeId) {
        directMessages.remove(peerNodeId);
    }

    /**
     * Ensures that a DM list exists for the given peer.
     *
     * @param peerNodeId peer node_id
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
     * Adds a listener for message-change notifications.
     *
     * @param listener no-argument callback
     */
    public void addMessageListener(Runnable listener) {
        messageListeners.add(listener);
    }

    /**
     * Removes a message-change listener.
     *
     * @param listener previously added listener
     */
    public void removeMessageListener(Runnable listener) {
        messageListeners.remove(listener);
    }

    /**
     * Adds a listener for detailed message changes.
     *
     * @param listener event consumer
     */
    public void addMessageChangeListener(Consumer<MessageChangeEvent> listener) {
        messageChangeListeners.add(listener);
    }

    /**
     * Removes a detailed message-change listener.
     *
     * @param listener previously added consumer
     */
    public void removeMessageChangeListener(Consumer<MessageChangeEvent> listener) {
        messageChangeListeners.remove(listener);
    }

    /**
     * Notifies all listeners about message changes.
     */
    public void fireMessageListeners() {
        for (Runnable r : messageListeners) {
            try { r.run(); }
            catch (Exception e) { log.error("Exception in message listener", e); }
        }
    }

    /**
     * Notifies detailed listeners about a specific message change.
     *
     * @param event change event
     */
    public void fireMessageChange(MessageChangeEvent event) {
        MessageChangeEvent safeEvent = event != null ? event : MessageChangeEvent.unknown();
        for (Consumer<MessageChangeEvent> listener : messageChangeListeners) {
            try { listener.accept(safeEvent); }
            catch (Exception e) { log.error("Exception in message change listener", e); }
        }
    }

    /**
     * Registers an outgoing message for ACK/NAK tracking.
     *
     * @param packetId unique packet identifier
     * @param msg      message in SENDING status
     */
    public void registerPendingAck(int packetId, MeshMessage msg) {
        pendingAcks.put(packetId, new PendingAckEntry(msg, System.currentTimeMillis()));
    }

    /**
     * Retrieves and removes a message from the pending ACK queue.
     *
     * @param packetId packet identifier from the routing response
     * @return message waiting for acknowledgement, or {@code null}
     */
    public MeshMessage resolvePendingAck(int packetId) {
        PendingAckEntry entry = pendingAcks.remove(packetId);
        return entry != null ? entry.message() : null;
    }

    /**
     * Marks all pending-ACK messages as FAILED with the given reason.
     *
     * @param reason failure reason, for example "DISCONNECTED"
     */
    public void failAllPendingAcks(String reason) {
        if (pendingAcks.isEmpty()) { return; }
        int count = 0;
        var iterator = pendingAcks.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            iterator.remove();
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
     * Marks messages as FAILED and updates their status in the database.
     * Calls MessageDbService.updateStatus for every message.
     *
     * @param reason failure reason
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
            
        // Update in the database.
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
     * Finds a message by {@code packetId} in memory.
     *
     * @param packetId packet identifier
     * @return matching message, or {@code null}
     */
    public MeshMessage findMessageByPacketId(int packetId) {
        if (packetId == 0) { return null; }
        // Search channel messages.
        for (List<MeshMessage> msgs : messagesByChannel.values()) {
            synchronized (msgs) {
                for (MeshMessage msg : msgs) {
                    if (msg.getPacketId() == packetId) { return msg; }
                }
            }
        }
        // Search direct messages.
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
     * Clears all messages from memory while leaving pending ACKs for processing.
     */
    public void clear() {
        messagesByChannel.clear();
        directMessages.clear();
    }

    /**
     * Returns the internal pending ACK map for ACK sweeping.
     *
     * @return {@code ConcurrentHashMap<Integer, PendingAckEntry>}
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
        if (current == MeshMessage.DeliveryStatus.DELIVERED) {
            return incoming == MeshMessage.DeliveryStatus.CONFIRMED;
        }
        return false;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
