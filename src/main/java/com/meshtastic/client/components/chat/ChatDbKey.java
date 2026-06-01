package com.meshtastic.client.components.chat;

import com.meshtastic.client.model.ChatItem;
import com.meshtastic.client.utils.AppPreferences;

import java.util.Objects;

/**
 * Stable database and preference keys for a chat.
 *
 * <p>The UI works with {@link ChatItem}, while persistence uses string pairs
 * such as {@code channel/0} and {@code dm/!abcd1234}. This record keeps those
 * conversions in one place so form code does not repeat channel/direct branches.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record ChatDbKey(
        String dbType,
        String dbKey,
        String readKey,
        String preferenceId,
        String scrollStateKey) {

    /**
     * Creates keys for a channel chat.
     *
     * @param channelIndex channel index from Meshtastic settings
     * @return database and preference keys for the channel
     */
    public static ChatDbKey channel(int channelIndex) {
        String key = String.valueOf(channelIndex);
        return new ChatDbKey(
                "channel",
                key,
                "ch:" + key,
                AppPreferences.composeChatPreferenceId("channel", key),
                "channel:" + key);
    }

    /**
     * Creates keys for a direct chat.
     *
     * @param peerNodeId peer node id, for example {@code !9e755af0}
     * @return database and preference keys for the direct chat
     */
    public static ChatDbKey direct(String peerNodeId) {
        return new ChatDbKey(
                "dm",
                peerNodeId,
                "dm:" + peerNodeId,
                AppPreferences.composeChatPreferenceId("dm", peerNodeId),
                "dm:" + peerNodeId);
    }

    /**
     * Creates keys from a chat-list item.
     *
     * @param item chat-list item
     * @return database and preference keys for the item
     */
    public static ChatDbKey from(ChatItem item) {
        Objects.requireNonNull(item, "item");
        return switch (item.getType()) {
            case CHANNEL -> channel(item.getChannelIndex());
            case DIRECT_MESSAGE -> direct(item.getPeerNodeId());
        };
    }
}
