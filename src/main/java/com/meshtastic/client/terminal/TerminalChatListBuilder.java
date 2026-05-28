package com.meshtastic.client.terminal;

import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.service.MessageDbService;
import org.meshtastic.proto.ChannelProtos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds terminal chat rows from configured channels, direct-message threads, and persisted history.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class TerminalChatListBuilder {

    private TerminalChatListBuilder() {
    }

    static List<TerminalChat> build(DeviceState state, int selectedChannelIndex, String ownerId) {
        if (state == null) {
            return List.of();
        }

        MessageDbService db = MessageDbService.getInstance();
        Map<String, MeshMessage> lastChannels = db.getLastMessagePerChat("channel", ownerId);
        Map<String, MeshMessage> lastDms = db.getLastMessagePerChat("dm", ownerId);
        Map<String, Integer> readCounts = db.loadAllReadCounts(ownerId);
        List<TerminalChat> items = new ArrayList<>();

        if (state.getChannels() != null) {
            for (ChannelProtos.Channel channel : state.getChannels()) {
                if (channel.getRole() == ChannelProtos.Channel.Role.DISABLED) {
                    continue;
                }
                int index = channel.getIndex();
                String key = String.valueOf(index);
                MeshMessage last = lastChannels.get(key);
                int unread = unreadCount(db, "channel", key, ownerId, readCounts.getOrDefault("ch:" + key, 0));
                items.add(TerminalChat.channel(index,
                        TerminalChannelFormatter.channelLabel(state, index),
                        TerminalChannelFormatter.channelDescription(state, index),
                        TerminalDisplayFormatter.lastMessageTime(last),
                        unread));
            }
        }
        if (items.isEmpty()) {
            String key = String.valueOf(selectedChannelIndex);
            MeshMessage last = lastChannels.get(key);
            int unread = unreadCount(db, "channel", key, ownerId, readCounts.getOrDefault("ch:" + key, 0));
            items.add(TerminalChat.channel(selectedChannelIndex,
                    TerminalChannelFormatter.channelLabel(state, selectedChannelIndex),
                    "index " + selectedChannelIndex,
                    TerminalDisplayFormatter.lastMessageTime(last),
                    unread));
        }

        Set<String> dmPeers = new LinkedHashSet<>(db.getDistinctDmPeers(ownerId));
        dmPeers.addAll(state.getAllDirectMessages().keySet());
        for (String peerNodeId : dmPeers) {
            if (peerNodeId == null || peerNodeId.isBlank()) {
                continue;
            }
            MeshMessage last = lastDms.get(peerNodeId);
            int unread = unreadCount(db, "dm", peerNodeId, ownerId, readCounts.getOrDefault("dm:" + peerNodeId, 0));
            items.add(TerminalChat.dm(peerNodeId,
                    TerminalDisplayFormatter.displayDirectChatLabel(state, peerNodeId),
                    directChatDescription(state, peerNodeId),
                    TerminalDisplayFormatter.lastMessageTime(last),
                    unread));
        }

        items.sort(Comparator
                .comparingLong(TerminalChat::lastMessageTime).reversed()
                .thenComparing(TerminalChat::sortKey));
        return items;
    }

    private static int unreadCount(MessageDbService db, String chatType, String chatKey, String ownerId, int readCount) {
        return Math.max(0, db.getUnreadEligibleMessageCount(chatType, chatKey, ownerId) - readCount);
    }

    private static String directChatDescription(DeviceState state, String peerNodeId) {
        return TerminalDisplayFormatter.hasNodeDisplayName(state, peerNodeId)
                ? "dm"
                : "dm " + TerminalDisplayFormatter.safe(peerNodeId);
    }
}
