package com.meshtastic.client.components.chat;

import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MessageReaction;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class ChatNodeDisplayHelperTest {

    @Test
    void shouldResolveIncomingPresentationWithLongNameAndShortNameAvatar() {
        DeviceState state = new DeviceState();
        NodeData sender = state.getOrCreateNode(0x00000011);
        sender.setLongName("Alice");
        sender.setShortName("ALC");

        MeshMessage incoming = new MeshMessage("!00000011", "!ffffffff", 0, "hello", 10, false);
        incoming.setSenderName("Cached Alice");

        ChatNodeDisplayHelper.IncomingMessagePresentation presentation =
                ChatNodeDisplayHelper.resolveIncomingMessagePresentation(state, incoming);

        assertEquals("Alice", presentation.senderName());
        assertEquals("ALC", presentation.avatar().text());
    }

    @Test
    void shouldResolveReplySenderNameAndReactionFallbacks() {
        MeshMessage outgoing = new MeshMessage("!00000001", "!ffffffff", 0, "outgoing", 10, true);
        MeshMessage incoming = new MeshMessage("!00000022", "!ffffffff", 0, "incoming", 10, false);
        incoming.setSenderName("Remote User");

        MessageReaction storedNameReaction = new MessageReaction(100, "!00000033", "🎉", 20, false);
        storedNameReaction.setSenderName("Stored Name");

        MessageReaction nodeIdOnlyReaction = new MessageReaction(100, "!00000044", "🎉", 20, false);

        assertEquals("Вы", ChatNodeDisplayHelper.resolveReplySenderName(null, outgoing));
        assertEquals("Remote User", ChatNodeDisplayHelper.resolveReplySenderName(null, incoming));
        assertEquals("Stored Name", ChatNodeDisplayHelper.resolveReactionSenderDisplayName(null, storedNameReaction));
        assertEquals("!00000044", ChatNodeDisplayHelper.resolveReactionSenderDisplayName(null, nodeIdOnlyReaction));
    }
}
