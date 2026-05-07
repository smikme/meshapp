package com.meshtastic.client.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
