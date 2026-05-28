package com.meshtastic.client.model;

/**
 * Детальное событие изменения чат-сообщений.
 *
 * <p>Старый {@code Runnable}-listener сообщает только "что-то изменилось" и
 * вынуждает UI перечитывать и пересобирать видимое окно сообщений. Это событие
 * несёт enough scope, чтобы экран чата мог обновить одну строку или одну часть
 * строки без полной пересборки JavaFX-узлов.
 *
 * @param kind тип изменения
 * @param chatType тип чата: {@code channel} или {@code dm}
 * @param chatKey ключ чата: индекс канала или nodeId собеседника
 * @param ownerNodeId nodeId локального владельца истории
 * @param packetId packet id сообщения или реакции
 * @param targetPacketId packet id сообщения, к которому относится реакция
 * @param dbId id сообщения в локальной БД, если известен
 * @param message сообщение, если оно уже есть в памяти
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
