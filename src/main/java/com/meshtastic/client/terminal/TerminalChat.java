package com.meshtastic.client.terminal;

import java.util.Locale;

/**
 * Terminal chat list row backed by a persisted channel or direct-message thread.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
record TerminalChat(String dbType,
                    String dbKey,
                    String label,
                    String description,
                    int channelIndex,
                    String peerNodeId,
                    long lastMessageTime,
                    int unreadCount) {

    static TerminalChat channel(int channelIndex,
                                String label,
                                String description,
                                long lastMessageTime,
                                int unreadCount) {
        return new TerminalChat("channel", String.valueOf(channelIndex), label, description,
                channelIndex, null, lastMessageTime, unreadCount);
    }

    static TerminalChat dm(String peerNodeId,
                           String label,
                           String description,
                           long lastMessageTime,
                           int unreadCount) {
        return new TerminalChat("dm", peerNodeId, label, description,
                0, peerNodeId, lastMessageTime, unreadCount);
    }

    String key() {
        return dbType + ":" + dbKey;
    }

    String sortKey() {
        return ("channel".equals(dbType) ? "0:" + channelIndex : "1:" + label).toLowerCase(Locale.ROOT);
    }

    String menuLabel() {
        return "channel".equals(dbType) ? "#" + channelIndex + " " + label : "@ " + label;
    }

    boolean sameChat(TerminalChat other) {
        return other != null && key().equals(other.key());
    }
}
