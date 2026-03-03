package com.meshtastic.client.model;

import org.meshtastic.proto.ChannelProtos;

import java.util.List;

/**
 * Элемент списка чатов — обёртка для канала или DM.
 */
public class ChatItem {

    public enum ChatType { CHANNEL, DIRECT_MESSAGE }

    private static final int MAX_PREVIEW_LENGTH = 60;
    private static final String[] CHANNEL_COLORS = {
            "#5B8DEF", "#E57C23", "#9B59B6", "#1EA97C",
            "#E74C3C", "#3498DB", "#F39C12", "#1ABC9C"
    };

    private final ChatType type;
    private final String displayName;
    private final String avatarText;
    private final String avatarColor;
    private final String lastMessageText;
    private final long lastMessageTime;
    private final int unreadCount;
    private final int channelIndex;
    private final String peerNodeId;

    private ChatItem(ChatType type, String displayName, String avatarText, String avatarColor,
                     String lastMessageText, long lastMessageTime, int unreadCount,
                     int channelIndex, String peerNodeId) {
        this.type = type;
        this.displayName = displayName;
        this.avatarText = avatarText;
        this.avatarColor = avatarColor;
        this.lastMessageText = lastMessageText;
        this.lastMessageTime = lastMessageTime;
        this.unreadCount = unreadCount;
        this.channelIndex = channelIndex;
        this.peerNodeId = peerNodeId;
    }

    /**
     * Создаёт ChatItem для канала с последним сообщением из in-memory списка.
     *
     * @param channel     канал (содержит имя и индекс)
     * @param messages    список сообщений канала (последнее используется для preview)
     * @param unreadCount количество непрочитанных сообщений
     * @return элемент списка чатов
     */
    public static ChatItem fromChannel(ChannelProtos.Channel channel,
                                       List<MeshMessage> messages,
                                       int unreadCount) {
        String name = channel.getSettings().getName();
        if (name == null || name.isEmpty()) {
            name = channel.getIndex() == 0 ? "Primary" : "Ch " + channel.getIndex();
        }

        // Аватар: # + первые 3 символа имени
        String abbr = name.length() > 3 ? name.substring(0, 3) : name;
        String avatarText = "#" + abbr;

        String color = CHANNEL_COLORS[channel.getIndex() % CHANNEL_COLORS.length];

        // Последнее сообщение
        String lastText = null;
        long lastTime = 0;
        if (!messages.isEmpty()) {
            MeshMessage last = messages.get(messages.size() - 1);
            lastText = truncate(last.getText());
            lastTime = last.getTimestamp();
        }

        return new ChatItem(ChatType.CHANNEL, name, avatarText, color,
                lastText, lastTime, unreadCount, channel.getIndex(), null);
    }

    /**
     * Создаёт ChatItem для DM с последним сообщением из in-memory списка.
     *
     * @param peerNodeId  node_id собеседника (например {@code "!9e755af0"})
     * @param peerNode    данные ноды (для отображения имени), может быть {@code null}
     * @param messages    список сообщений DM (последнее используется для preview)
     * @param unreadCount количество непрочитанных сообщений
     * @return элемент списка чатов
     */
    public static ChatItem fromDirectMessage(String peerNodeId, NodeData peerNode,
                                             List<MeshMessage> messages,
                                             int unreadCount) {
        String displayName;
        String avatarText;

        if (peerNode != null && peerNode.getLongName() != null && !peerNode.getLongName().isEmpty()) {
            displayName = peerNode.getLongName();
        } else if (peerNode != null && peerNode.getNodeId() != null) {
            displayName = peerNode.getNodeId();
        } else {
            displayName = peerNodeId;
        }

        if (peerNode != null && peerNode.getShortName() != null && !peerNode.getShortName().isEmpty()) {
            avatarText = peerNode.getShortName().length() > 4
                    ? peerNode.getShortName().substring(0, 4).toUpperCase()
                    : peerNode.getShortName().toUpperCase();
        } else {
            avatarText = displayName.length() > 4
                    ? displayName.substring(0, 4).toUpperCase()
                    : displayName.toUpperCase();
        }

        String color = CHANNEL_COLORS[Math.abs(peerNodeId.hashCode()) % CHANNEL_COLORS.length];

        // Последнее сообщение
        String lastText = null;
        long lastTime = 0;
        if (!messages.isEmpty()) {
            MeshMessage last = messages.get(messages.size() - 1);
            lastText = truncate(last.getText());
            lastTime = last.getTimestamp();
        }

        return new ChatItem(ChatType.DIRECT_MESSAGE, displayName, avatarText, color,
                lastText, lastTime, unreadCount, 0, peerNodeId);
    }

    // === DB-backed factory methods ===

    /**
     * Создаёт ChatItem для канала с последним сообщением из БД.
     */
    public static ChatItem fromChannel(ChannelProtos.Channel channel,
                                       MeshMessage lastMessage,
                                       int unreadCount) {
        String name = channel.getSettings().getName();
        if (name == null || name.isEmpty()) {
            name = channel.getIndex() == 0 ? "Primary" : "Ch " + channel.getIndex();
        }

        String abbr = name.length() > 3 ? name.substring(0, 3) : name;
        String avatarText = "#" + abbr;
        String color = CHANNEL_COLORS[channel.getIndex() % CHANNEL_COLORS.length];

        String lastText = null;
        long lastTime = 0;
        if (lastMessage != null) {
            lastText = truncate(lastMessage.getText());
            lastTime = lastMessage.getTimestamp();
        }

        return new ChatItem(ChatType.CHANNEL, name, avatarText, color,
                lastText, lastTime, unreadCount, channel.getIndex(), null);
    }

    /**
     * Создаёт ChatItem для DM с последним сообщением из БД.
     */
    public static ChatItem fromDirectMessage(String peerNodeId, NodeData peerNode,
                                             MeshMessage lastMessage,
                                             int unreadCount) {
        String displayName;
        String avatarText;

        if (peerNode != null && peerNode.getLongName() != null && !peerNode.getLongName().isEmpty()) {
            displayName = peerNode.getLongName();
        } else if (peerNode != null && peerNode.getNodeId() != null) {
            displayName = peerNode.getNodeId();
        } else {
            displayName = peerNodeId;
        }

        if (peerNode != null && peerNode.getShortName() != null && !peerNode.getShortName().isEmpty()) {
            avatarText = peerNode.getShortName().length() > 4
                    ? peerNode.getShortName().substring(0, 4).toUpperCase()
                    : peerNode.getShortName().toUpperCase();
        } else {
            avatarText = displayName.length() > 4
                    ? displayName.substring(0, 4).toUpperCase()
                    : displayName.toUpperCase();
        }

        String color = CHANNEL_COLORS[Math.abs(peerNodeId.hashCode()) % CHANNEL_COLORS.length];

        String lastText = null;
        long lastTime = 0;
        if (lastMessage != null) {
            lastText = truncate(lastMessage.getText());
            lastTime = lastMessage.getTimestamp();
        }

        return new ChatItem(ChatType.DIRECT_MESSAGE, displayName, avatarText, color,
                lastText, lastTime, unreadCount, 0, peerNodeId);
    }

    private static String truncate(String text) {
        if (text == null) return null;
        text = text.replace('\n', ' ').replace('\r', ' ');
        return text.length() > MAX_PREVIEW_LENGTH
                ? text.substring(0, MAX_PREVIEW_LENGTH) + "…"
                : text;
    }

    public ChatType getType() { return type; }
    public String getDisplayName() { return displayName; }
    public String getAvatarText() { return avatarText; }
    public String getAvatarColor() { return avatarColor; }
    public String getLastMessageText() { return lastMessageText; }
    public long getLastMessageTime() { return lastMessageTime; }
    public int getUnreadCount() { return unreadCount; }
    public int getChannelIndex() { return channelIndex; }
    public String getPeerNodeId() { return peerNodeId; }
}
