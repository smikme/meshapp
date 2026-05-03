package com.meshtastic.client.components.chat;

import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MessageReaction;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class ChatReactionHelperTest {

    @Test
    void shouldAggregateVisibleReactionsInFirstAppearanceOrder() {
        DeviceState state = new DeviceState();

        NodeData alice = state.getOrCreateNode(0x00000001);
        alice.setLongName("Alice");
        alice.setShortName("ALC");

        NodeData bob = state.getOrCreateNode(0x00000002);
        bob.setShortName("BOB");

        MessageReaction first = reaction("!00000001", "👍", false);
        first.setSenderName("Old Alice");

        MessageReaction second = reaction("!00000002", "👍", true);
        MessageReaction third = reaction("!00000002", "🔥", false);

        MessageReaction failed = reaction("!00000001", "👍", false);
        failed.setStatus(MeshMessage.DeliveryStatus.FAILED);

        List<ChatReactionHelper.ReactionSummary> summaries =
                ChatReactionHelper.summarize(state, List.of(first, second, third, failed));

        assertEquals(2, summaries.size());

        ChatReactionHelper.ReactionSummary thumbsUp = summaries.getFirst();
        assertEquals("👍", thumbsUp.emoji());
        assertTrue(thumbsUp.own());
        assertEquals(2, thumbsUp.count());
        assertEquals("Alice\nBOB", thumbsUp.tooltipText());

        ChatReactionHelper.ReactionSummary fire = summaries.get(1);
        assertEquals("🔥", fire.emoji());
        assertFalse(fire.own());
        assertEquals(1, fire.count());
        assertEquals("BOB", fire.tooltipText());
    }

    private static MessageReaction reaction(String fromNodeId, String emoji, boolean outgoing) {
        return new MessageReaction(100, fromNodeId, emoji, 1_700_000_000L, outgoing);
    }
}
