package com.meshtastic.client.model;

import com.meshtastic.client.utils.UnicodeTextUtils;
import org.meshtastic.proto.ChannelProtos;
import java.util.Locale;

import java.util.List;

/**
 * Chat-list item representing either a channel or a direct message conversation.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ChatItem {

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
    private final boolean muted;

    private ChatItem(ChatType type, String displayName, String avatarText, String avatarColor,
                     String lastMessageText, long lastMessageTime, int unreadCount,
                     int channelIndex, String peerNodeId, boolean muted) {
        this.type = type;
        this.displayName = displayName;
        this.avatarText = avatarText;
        this.avatarColor = avatarColor;
        this.lastMessageText = lastMessageText;
        this.lastMessageTime = lastMessageTime;
        this.unreadCount = unreadCount;
        this.channelIndex = channelIndex;
        this.peerNodeId = peerNodeId;
        this.muted = muted;
    }

    /**
     * Creates a chat item from a remote RPC snapshot.
     */
    public static ChatItem remote(ChatType type,
                                  String displayName,
                                  String avatarText,
                                  String avatarColor,
                                  String lastMessageText,
                                  long lastMessageTime,
                                  int unreadCount,
                                  int channelIndex,
                                  String peerNodeId,
                                  boolean muted) {
        return new ChatItem(
                type,
                UnicodeTextUtils.sanitize(displayName),
                UnicodeTextUtils.sanitize(avatarText),
                avatarColor,
                truncate(lastMessageText),
                lastMessageTime,
                Math.max(0, unreadCount),
                channelIndex,
                peerNodeId,
                muted);
    }

    /**
     * Creates a channel chat item using the last message from an in-memory list.
     *
     * @param channel channel containing name and index
     * @param messages channel messages; the last one is used for preview
     * @param unreadCount unread message count
     * @return chat-list item
     */
    public static ChatItem fromChannel(ChannelProtos.Channel channel,
                                       List<MeshMessage> messages,
                                       int unreadCount,
                                       boolean muted) {
        String name = channel.getSettings().getName();
        if (name == null || name.isEmpty()) {
            name = channel.getIndex() == 0 ? "Primary" : "Ch " + channel.getIndex();
        }
        name = UnicodeTextUtils.sanitize(name);

        // Avatar: hash plus the first three characters of the name.
        String abbr = UnicodeTextUtils.prefixByCodePoints(name, 3);
        String avatarText = "#" + abbr;

        String color = CHANNEL_COLORS[channel.getIndex() % CHANNEL_COLORS.length];

        // Last message preview.
        String lastText = null;
        long lastTime = 0;
        if (!messages.isEmpty()) {
            MeshMessage last = messages.get(messages.size() - 1);
            lastText = truncate(last.getText());
            lastTime = last.getTimestamp();
        }

        return new ChatItem(ChatType.CHANNEL, name, avatarText, color,
                lastText, lastTime, unreadCount, channel.getIndex(), null, muted);
    }

    /**
     * Creates a direct-message chat item using the last message from an in-memory list.
     *
     * @param peerNodeId peer node id, for example {@code "!9e755af0"}
     * @param peerNode node data for display name resolution, or {@code null}
     * @param messages direct-message history; the last one is used for preview
     * @param unreadCount unread message count
     * @return chat-list item
     */
    public static ChatItem fromDirectMessage(String peerNodeId, NodeData peerNode,
                                             List<MeshMessage> messages,
                                             int unreadCount,
                                             boolean muted) {
        String displayName;
        String avatarText;

        if (peerNode != null && peerNode.getLongName() != null && !peerNode.getLongName().isEmpty()) {
            displayName = peerNode.getLongName();
        } else if (peerNode != null && peerNode.getNodeId() != null) {
            displayName = peerNode.getNodeId();
        } else {
            displayName = peerNodeId;
        }
        displayName = UnicodeTextUtils.sanitize(displayName);

        if (peerNode != null && peerNode.getShortName() != null && !peerNode.getShortName().isEmpty()) {
            avatarText = UnicodeTextUtils.prefixByCodePoints(peerNode.getShortName(), 4).toUpperCase(Locale.ROOT);
        } else {
            avatarText = UnicodeTextUtils.prefixByCodePoints(displayName, 4).toUpperCase(Locale.ROOT);
        }

        String color = CHANNEL_COLORS[Math.abs(peerNodeId.hashCode()) % CHANNEL_COLORS.length];

        // Last message preview.
        String lastText = null;
        long lastTime = 0;
        if (!messages.isEmpty()) {
            MeshMessage last = messages.get(messages.size() - 1);
            lastText = truncate(last.getText());
            lastTime = last.getTimestamp();
        }

        return new ChatItem(ChatType.DIRECT_MESSAGE, displayName, avatarText, color,
                lastText, lastTime, unreadCount, 0, peerNodeId, muted);
    }

    // === DB-backed factory methods ===

    /**
     * Creates a channel chat item from the last database message.
     */
    public static ChatItem fromChannel(ChannelProtos.Channel channel,
                                       MeshMessage lastMessage,
                                       int unreadCount,
                                       boolean muted) {
        String name = channel.getSettings().getName();
        if (name == null || name.isEmpty()) {
            name = channel.getIndex() == 0 ? "Primary" : "Ch " + channel.getIndex();
        }
        name = UnicodeTextUtils.sanitize(name);

        String abbr = UnicodeTextUtils.prefixByCodePoints(name, 3);
        String avatarText = "#" + abbr;
        String color = CHANNEL_COLORS[channel.getIndex() % CHANNEL_COLORS.length];

        String lastText = null;
        long lastTime = 0;
        if (lastMessage != null) {
            lastText = truncate(lastMessage.getText());
            lastTime = lastMessage.getTimestamp();
        }

        return new ChatItem(ChatType.CHANNEL, name, avatarText, color,
                lastText, lastTime, unreadCount, channel.getIndex(), null, muted);
    }

    /**
     * Creates a direct-message chat item from the last database message.
     */
    public static ChatItem fromDirectMessage(String peerNodeId, NodeData peerNode,
                                             MeshMessage lastMessage,
                                             int unreadCount,
                                             boolean muted) {
        String displayName;
        String avatarText;

        if (peerNode != null && peerNode.getLongName() != null && !peerNode.getLongName().isEmpty()) {
            displayName = peerNode.getLongName();
        } else if (peerNode != null && peerNode.getNodeId() != null) {
            displayName = peerNode.getNodeId();
        } else {
            displayName = peerNodeId;
        }
        displayName = UnicodeTextUtils.sanitize(displayName);

        if (peerNode != null && peerNode.getShortName() != null && !peerNode.getShortName().isEmpty()) {
            avatarText = UnicodeTextUtils.prefixByCodePoints(peerNode.getShortName(), 4).toUpperCase(Locale.ROOT);
        } else {
            avatarText = UnicodeTextUtils.prefixByCodePoints(displayName, 4).toUpperCase(Locale.ROOT);
        }

        String color = CHANNEL_COLORS[Math.abs(peerNodeId.hashCode()) % CHANNEL_COLORS.length];

        String lastText = null;
        long lastTime = 0;
        if (lastMessage != null) {
            lastText = truncate(lastMessage.getText());
            lastTime = lastMessage.getTimestamp();
        }

        return new ChatItem(ChatType.DIRECT_MESSAGE, displayName, avatarText, color,
                lastText, lastTime, unreadCount, 0, peerNodeId, muted);
    }

    private static String truncate(String text) {
        if (text == null) { return null; }
        text = text.replace('\n', ' ').replace('\r', ' ');
        return UnicodeTextUtils.truncateWithSuffix(text, MAX_PREVIEW_LENGTH, "...");
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
    public boolean isMuted() { return muted; }
}
