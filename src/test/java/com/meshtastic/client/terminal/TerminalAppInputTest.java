package com.meshtastic.client.terminal;

import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests terminal chat input limits and display formatting helpers.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class TerminalAppInputTest {

    @Test
    void maxInputBytesMatchesGuiBudgetWithoutReply() {
        assertEquals(228, TerminalInputLimits.maxInputBytes(false));
    }

    @Test
    void maxInputBytesReservesReplyIdOverhead() {
        assertEquals(223, TerminalInputLimits.maxInputBytes(true));
    }

    @Test
    void textByteLengthUsesUtf8LikeGuiCounter() {
        assertEquals(1, TerminalInputLimits.textByteLength("a"));
        assertEquals(2, TerminalInputLimits.textByteLength("я"));
        assertEquals(4, TerminalInputLimits.textByteLength("\uD83D\uDE00"));
    }

    @Test
    void messageHopsLabelShowsTraveledHops() {
        MeshMessage msg = new MeshMessage("!00000011", "!ffffffff", 0, "hello", 10, false);
        msg.setHopStart(5);
        msg.setHopLimit(2);

        assertEquals(" [\uD83D\uDC073]", TerminalDisplayFormatter.messageHopsLabel(msg));
    }

    @Test
    void messageHopsLabelShowsZeroForDirectValidHopData() {
        MeshMessage msg = new MeshMessage("!00000011", "!ffffffff", 0, "hello", 10, false);
        msg.setHopStart(3);
        msg.setHopLimit(3);

        assertEquals(" [\uD83D\uDC070]", TerminalDisplayFormatter.messageHopsLabel(msg));
    }

    @Test
    void messageHopsLabelSkipsUnknownHopData() {
        MeshMessage msg = new MeshMessage("!00000011", "!ffffffff", 0, "hello", 10, false);

        assertEquals("", TerminalDisplayFormatter.messageHopsLabel(msg));
    }

    @Test
    void incomingSenderNameMatchesGuiPriority() {
        DeviceState state = new DeviceState();
        NodeData sender = state.getOrCreateNode(0x00000011);
        sender.setLongName("Alice");
        sender.setShortName("ALC");

        MeshMessage incoming = new MeshMessage("!00000011", "!ffffffff", 0, "hello", 10, false);
        incoming.setSenderName("Cached Alice");

        assertEquals("Alice", TerminalDisplayFormatter.displayIncomingSenderName(state, incoming));
        assertEquals("Cached Alice", TerminalDisplayFormatter.displayIncomingSenderName(null, incoming));

        sender.setLongName(null);
        incoming.setSenderName(null);
        assertEquals("ALC", TerminalDisplayFormatter.displayIncomingSenderName(state, incoming));
    }

    @Test
    void directChatLabelMatchesGuiPriority() {
        DeviceState state = new DeviceState();
        NodeData peer = state.getOrCreateNode(0x00000022);
        peer.setShortName("BOB");

        assertEquals("BOB", TerminalDisplayFormatter.displayDirectChatLabel(state, "!00000022"));
        assertEquals(true, TerminalDisplayFormatter.hasNodeDisplayName(state, "!00000022"));

        peer.setLongName("Bob Long");
        assertEquals("Bob Long", TerminalDisplayFormatter.displayDirectChatLabel(state, "!00000022"));
    }
}
