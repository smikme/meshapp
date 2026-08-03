package com.meshtastic.client.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class ChatItemTest {

    @Test
    void directMessagePreviewTruncationKeepsWholeEmoji() {
        MeshMessage message = new MeshMessage(
                "!00000001",
                "!00000002",
                0,
                "a".repeat(59) + "😀" + "z",
                10,
                false
        );

        ChatItem item = ChatItem.fromDirectMessage("!00000002", null, List.of(message), 0, false);

        assertEquals("a".repeat(59) + "😀...", item.getLastMessageText());
    }

    @Test
    void liveUpdateReturnsNewImmutableChatItem() {
        ChatItem original = ChatItem.fromDirectMessage("!00000002", null, List.of(), 2, false);
        MeshMessage latest = new MeshMessage(
                "!00000002", "!00000001", 0, "new message", 42, false);

        ChatItem updated = original.withLastMessage(latest, 3).withMuted(true);

        assertEquals(null, original.getLastMessageText());
        assertEquals(2, original.getUnreadCount());
        assertEquals("new message", updated.getLastMessageText());
        assertEquals(42, updated.getLastMessageTime());
        assertEquals(3, updated.getUnreadCount());
        assertTrue(updated.isMuted());
    }
}
