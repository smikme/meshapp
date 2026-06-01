package com.meshtastic.client.model;

/**
 * Detailed chat-message change event.
 *
 * <p>The legacy {@code Runnable} listener can only say that something changed,
 * which forces the UI to reload and rebuild the visible message window. This
 * event carries enough scope for the chat screen to update a single row, or part
 * of a row, without rebuilding its JavaFX nodes.
 *
 * @param kind change kind
 * @param chatType chat type: {@code channel} or {@code dm}
 * @param chatKey chat key: channel index or peer node id
 * @param ownerNodeId node id of the local history owner
 * @param packetId packet id of the message or reaction
 * @param targetPacketId packet id of the message targeted by a reaction
 * @param dbId local database id of the message, when known
 * @param message message already held in memory, when available
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record MessageChangeEvent(Kind kind,
                                 String chatType,
                                 String chatKey,
                                 String ownerNodeId,
                                 int packetId,
                                 int targetPacketId,
                                 long dbId,
                                 MeshMessage message) {

    public enum Kind {
        NEW_MESSAGE,
        REACTION_CHANGED,
        STATUS_CHANGED,
        METADATA_CHANGED,
        DELETE,
        UNKNOWN
    }

    public static MessageChangeEvent newMessage(String chatType,
                                                String chatKey,
                                                String ownerNodeId,
                                                MeshMessage message) {
        return new MessageChangeEvent(
                Kind.NEW_MESSAGE,
                chatType,
                chatKey,
                ownerNodeId,
                message != null ? message.getPacketId() : 0,
                0,
                message != null ? message.getDbId() : 0,
                message);
    }

    public static MessageChangeEvent statusChanged(String chatType,
                                                   String chatKey,
                                                   String ownerNodeId,
                                                   MeshMessage message) {
        return new MessageChangeEvent(
                Kind.STATUS_CHANGED,
                chatType,
                chatKey,
                ownerNodeId,
                message != null ? message.getPacketId() : 0,
                0,
                message != null ? message.getDbId() : 0,
                message);
    }

    public static MessageChangeEvent reactionChanged(String chatType,
                                                     String chatKey,
                                                     String ownerNodeId,
                                                     int targetPacketId) {
        return new MessageChangeEvent(
                Kind.REACTION_CHANGED,
                chatType,
                chatKey,
                ownerNodeId,
                0,
                targetPacketId,
                0,
                null);
    }

    public static MessageChangeEvent unknown() {
        return new MessageChangeEvent(Kind.UNKNOWN, null, null, null, 0, 0, 0, null);
    }

    public boolean hasChatScope() {
        return chatType != null && chatKey != null && ownerNodeId != null;
    }
}
