package com.meshtastic.client.model;

import com.meshtastic.client.utils.UnicodeTextUtils;

import java.util.Collections;
import java.util.List;

/**
 * Meshtastic chat message, either channel-based or direct.
 * <p>
 * Holds text, addressing, delivery status, and routing metadata. Outgoing
 * messages are created by {@link com.meshtastic.client.service.MessageService};
 * incoming messages are created by
 * {@link com.meshtastic.client.service.MessageListenerService}. The
 * {@code status} field is volatile so transport-reader updates are visible to
 * the UI thread.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class MeshMessage {

    private static final String BROADCAST_NODE_ID = "!ffffffff";

    /**
     * Delivery state of a message.
     * <ul>
     *   <li>{@code SENDING}: sent to the radio and waiting for ACK</li>
     *   <li>{@code DELIVERED}: sent, but a DM recipient has not confirmed delivery</li>
     *   <li>{@code CONFIRMED}: ACK received from the DM recipient</li>
     *   <li>{@code FAILED}: NAK received or channel ACK timed out</li>
     * </ul>
     */
    public enum DeliveryStatus { SENDING, DELIVERED, CONFIRMED, FAILED }

    private final String fromNodeId;
    private final String toNodeId;
    private final int channelIndex;
    private final String text;
    private final long timestamp;
    private final boolean outgoing;

    private volatile DeliveryStatus status;
    private int packetId;
    private String errorReason;
    private int replyId;
    private String replyText;
    private int hopStart;
    private int hopLimit;
    private int rxRssi;
    private float rxSnr;
    private String senderName;
    private boolean viaMqtt;
    private boolean systemMessage;
    private long dbId;
    private List<MessageReaction> reactions = Collections.emptyList();
    private boolean replyToOutgoing;

    /**
     * Creates a message with its core fields.
     *
     * @param fromNodeId sender node id, for example {@code "!9e755af0"}
     * @param toNodeId recipient node id, with {@code "!ffffffff"} meaning broadcast
     * @param channelIndex channel index, where {@code 0} is primary
     * @param text UTF-8 message text
     * @param timestamp epoch time in seconds
     * @param outgoing {@code true} when the message was sent from this device
     */
    public MeshMessage(String fromNodeId, String toNodeId, int channelIndex, String text, long timestamp, boolean outgoing) {
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        this.channelIndex = channelIndex;
        this.text = UnicodeTextUtils.sanitize(text);
        this.timestamp = timestamp;
        this.outgoing = outgoing;
    }

    public String getFromNodeId() { return fromNodeId; }
    public String getToNodeId() { return toNodeId; }
    public int getChannelIndex() { return channelIndex; }
    public String getText() { return text; }
    public long getTimestamp() { return timestamp; }
    public boolean isOutgoing() { return outgoing; }

    public boolean isDirectMessage() {
        return toNodeId != null && !BROADCAST_NODE_ID.equalsIgnoreCase(toNodeId);
    }

    public DeliveryStatus getStatus() { return status; }
    public void setStatus(DeliveryStatus status) { this.status = status; }

    public int getPacketId() { return packetId; }
    public void setPacketId(int packetId) { this.packetId = packetId; }

    public String getErrorReason() { return errorReason; }
    public void setErrorReason(String errorReason) { this.errorReason = UnicodeTextUtils.sanitize(errorReason); }

    public int getReplyId() { return replyId; }
    public void setReplyId(int replyId) { this.replyId = replyId; }

    public String getReplyText() { return replyText; }
    public void setReplyText(String replyText) { this.replyText = UnicodeTextUtils.sanitize(replyText); }

    public int getHopStart() { return hopStart; }
    public void setHopStart(int hopStart) { this.hopStart = hopStart; }

    public int getHopLimit() { return hopLimit; }
    public void setHopLimit(int hopLimit) { this.hopLimit = hopLimit; }

    public int getRxRssi() { return rxRssi; }
    public void setRxRssi(int rxRssi) { this.rxRssi = rxRssi; }

    public float getRxSnr() { return rxSnr; }
    public void setRxSnr(float rxSnr) { this.rxSnr = rxSnr; }

    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = UnicodeTextUtils.sanitize(senderName); }

    public boolean isViaMqtt() { return viaMqtt; }
    public void setViaMqtt(boolean viaMqtt) { this.viaMqtt = viaMqtt; }

    public boolean isSystemMessage() { return systemMessage; }
    public void setSystemMessage(boolean systemMessage) { this.systemMessage = systemMessage; }

    public long getDbId() { return dbId; }
    public void setDbId(long dbId) { this.dbId = dbId; }

    public List<MessageReaction> getReactions() { return reactions; }
    public void setReactions(List<MessageReaction> reactions) {
        this.reactions = reactions == null || reactions.isEmpty()
                ? Collections.emptyList()
                : List.copyOf(reactions);
    }
    public boolean hasReactions() { return !reactions.isEmpty(); }

    public boolean isReplyToOutgoing() { return replyToOutgoing; }
    public void setReplyToOutgoing(boolean replyToOutgoing) { this.replyToOutgoing = replyToOutgoing; }

    /**
     * Returns whether hop count can be calculated safely.
     * {@code hopLimit} must not exceed the original {@code hopStart}; otherwise
     * the pair is treated as invalid and ignored by the UI.
     *
     * @return {@code true} when hop data is present and internally consistent
     */
    public boolean hasValidHopData() {
        return hopStart > 0 && hopLimit >= 0 && hopLimit <= hopStart;
    }

    /**
     * Calculates how many hops the message has traversed.
     * The value is {@code hopStart - hopLimit}; missing or invalid data returns 0.
     *
     * @return hop count, or 0 when unavailable
     */
    public int getHopsTraveled() {
        return hasValidHopData() ? hopStart - hopLimit : 0;
    }

}
