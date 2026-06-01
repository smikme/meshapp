package com.meshtastic.client.model;

import com.meshtastic.client.utils.UnicodeTextUtils;

/**
 * Message reaction delivered as a separate {@code TEXT_MESSAGE_APP} packet with
 * {@code reply_id} and the protobuf {@code emoji} field set.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class MessageReaction {

    private final int targetPacketId;
    private final String fromNodeId;
    private final String emoji;
    private final long timestamp;
    private final boolean outgoing;

    private long dbId;
    private int packetId;
    private MeshMessage.DeliveryStatus status;
    private String errorReason;
    private String senderName;

    public MessageReaction(int targetPacketId,
                           String fromNodeId,
                           String emoji,
                           long timestamp,
                           boolean outgoing) {
        this.targetPacketId = targetPacketId;
        this.fromNodeId = fromNodeId;
        this.emoji = UnicodeTextUtils.sanitize(emoji);
        this.timestamp = timestamp;
        this.outgoing = outgoing;
    }

    public int getTargetPacketId() { return targetPacketId; }
    public String getFromNodeId() { return fromNodeId; }
    public String getEmoji() { return emoji; }
    public long getTimestamp() { return timestamp; }
    public boolean isOutgoing() { return outgoing; }

    public long getDbId() { return dbId; }
    public void setDbId(long dbId) { this.dbId = dbId; }

    public int getPacketId() { return packetId; }
    public void setPacketId(int packetId) { this.packetId = packetId; }

    public MeshMessage.DeliveryStatus getStatus() { return status; }
    public void setStatus(MeshMessage.DeliveryStatus status) { this.status = status; }

    public String getErrorReason() { return errorReason; }
    public void setErrorReason(String errorReason) { this.errorReason = UnicodeTextUtils.sanitize(errorReason); }

    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = UnicodeTextUtils.sanitize(senderName); }

    public boolean isVisible() {
        return status != MeshMessage.DeliveryStatus.FAILED;
    }
}
