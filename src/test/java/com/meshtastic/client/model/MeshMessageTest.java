package com.meshtastic.client.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshMessageTest {

    private static MeshMessage createMessage() {
        return new MeshMessage("!00000001", "!ffffffff", 0, "Test message", 1_700_000_000L, false);
    }

    @Test
    void getFromNodeId() {
        MeshMessage msg = createMessage();
        
        assertEquals("!00000001", msg.getFromNodeId());
    }

    @Test
    void getToNodeId() {
        MeshMessage msg = createMessage();
        
        assertEquals("!ffffffff", msg.getToNodeId());
    }

    @Test
    void getChannelIndex() {
        MeshMessage msg = createMessage();
        
        assertEquals(0, msg.getChannelIndex());
    }

    @Test
    void getText() {
        MeshMessage msg = createMessage();
        
        assertEquals("Test message", msg.getText());
    }

    @Test
    void getTimestamp() {
        MeshMessage msg = createMessage();
        
        assertEquals(1_700_000_000L, msg.getTimestamp());
    }

    @Test
    void isOutgoing() {
        MeshMessage outgoing = new MeshMessage("!00000001", "!ffffffff", 0, "Test", 1_700_000_000L, true);
        MeshMessage incoming = new MeshMessage("!00000001", "!ffffffff", 0, "Test", 1_700_000_000L, false);
        
        assertTrue(outgoing.isOutgoing());
        assertFalse(incoming.isOutgoing());
    }

    @Test
    void getStatusDefaultsToNull() {
        MeshMessage msg = createMessage();
        
        assertNull(msg.getStatus());
    }

    @Test
    void setStatus() {
        MeshMessage msg = createMessage();
        
        msg.setStatus(MeshMessage.DeliveryStatus.SENDING);
        
        assertEquals(MeshMessage.DeliveryStatus.SENDING, msg.getStatus());
    }

    @Test
    void getPacketIdDefaultsToZero() {
        MeshMessage msg = createMessage();
        
        assertEquals(0, msg.getPacketId());
    }

    @Test
    void setPacketId() {
        MeshMessage msg = createMessage();
        
        msg.setPacketId(12345);
        
        assertEquals(12345, msg.getPacketId());
    }

    @Test
    void getErrorReasonDefaultsToNull() {
        MeshMessage msg = createMessage();
        
        assertNull(msg.getErrorReason());
    }

    @Test
    void setErrorReason() {
        MeshMessage msg = createMessage();
        
        msg.setErrorReason("TIMEOUT");
        
        assertEquals("TIMEOUT", msg.getErrorReason());
    }

    @Test
    void getReplyIdDefaultsToZero() {
        MeshMessage msg = createMessage();
        
        assertEquals(0, msg.getReplyId());
    }

    @Test
    void setReplyId() {
        MeshMessage msg = createMessage();
        
        msg.setReplyId(54321);
        
        assertEquals(54321, msg.getReplyId());
    }

    @Test
    void getReplyTextDefaultsToNull() {
        MeshMessage msg = createMessage();
        
        assertNull(msg.getReplyText());
    }

    @Test
    void setReplyText() {
        MeshMessage msg = createMessage();
        
        msg.setReplyText("reply");
        
        assertEquals("reply", msg.getReplyText());
    }

    @Test
    void getHopStartDefaultsToZero() {
        MeshMessage msg = createMessage();
        
        assertEquals(0, msg.getHopStart());
    }

    @Test
    void setHopStart() {
        MeshMessage msg = createMessage();
        
        msg.setHopStart(3);
        
        assertEquals(3, msg.getHopStart());
    }

    @Test
    void getHopLimitDefaultsToZero() {
        MeshMessage msg = createMessage();
        
        assertEquals(0, msg.getHopLimit());
    }

    @Test
    void setHopLimit() {
        MeshMessage msg = createMessage();
        
        msg.setHopLimit(2);
        
        assertEquals(2, msg.getHopLimit());
    }

    @Test
    void getRxRssiDefaultsToZero() {
        MeshMessage msg = createMessage();
        
        assertEquals(0, msg.getRxRssi());
    }

    @Test
    void setRxRssi() {
        MeshMessage msg = createMessage();
        
        msg.setRxRssi(-80);
        
        assertEquals(-80, msg.getRxRssi());
    }

    @Test
    void getRxSnrDefaultsToZero() {
        MeshMessage msg = createMessage();
        
        assertEquals(0.0f, msg.getRxSnr(), 0.001f);
    }

    @Test
    void setRxSnr() {
        MeshMessage msg = createMessage();
        
        msg.setRxSnr(6.5f);
        
        assertEquals(6.5f, msg.getRxSnr(), 0.001f);
    }

    @Test
    void getSenderNameDefaultsToNull() {
        MeshMessage msg = createMessage();
        
        assertNull(msg.getSenderName());
    }

    @Test
    void setSenderName() {
        MeshMessage msg = createMessage();
        
        msg.setSenderName("Device1");
        
        assertEquals("Device1", msg.getSenderName());
    }

    @Test
    void isViaMqttDefaultsToFalse() {
        MeshMessage msg = createMessage();

        assertFalse(msg.isViaMqtt());
    }

    @Test
    void setViaMqtt() {
        MeshMessage msg = createMessage();

        msg.setViaMqtt(true);

        assertTrue(msg.isViaMqtt());
    }

    @Test
    void isSystemMessageDefaultsToFalse() {
        MeshMessage msg = createMessage();
        
        assertFalse(msg.isSystemMessage());
    }

    @Test
    void setSystemMessage() {
        MeshMessage msg = createMessage();
        
        msg.setSystemMessage(true);
        
        assertTrue(msg.isSystemMessage());
    }

    @Test
    void getDbIdDefaultsToZero() {
        MeshMessage msg = createMessage();
        
        assertEquals(0, msg.getDbId());
    }

    @Test
    void setDbId() {
        MeshMessage msg = createMessage();
        
        msg.setDbId(100L);
        
        assertEquals(100L, msg.getDbId());
    }

    @Test
    void getReactionsDefaultsToEmptyList() {
        MeshMessage msg = createMessage();
        
        assertNotNull(msg.getReactions());
        assertTrue(msg.getReactions().isEmpty());
    }

    @Test
    void setReactionsStoresCopyOfList() {
        MeshMessage msg = createMessage();
        
        List<MessageReaction> reactions = List.of(
            new MessageReaction(1, "!00000002", "thumbsup", 1_700_000_000L, false)
        );
        
        msg.setReactions(reactions);
        
        assertEquals(1, msg.getReactions().size());
    }

    @Test
    void setReactionsHandlesNull() {
        MeshMessage msg = createMessage();
        
        msg.setReactions(null);
        
        assertTrue(msg.getReactions().isEmpty());
    }

    @Test
    void hasReactionsReturnsFalseWhenEmpty() {
        MeshMessage msg = createMessage();
        
        assertFalse(msg.hasReactions());
    }

    @Test
    void hasReactionsReturnsTrueWhenNotEmpty() {
        MeshMessage msg = createMessage();
        
        msg.setReactions(List.of(new MessageReaction(1, "!00000002", "thumbsup", 1_700_000_000L, false)));
        
        assertTrue(msg.hasReactions());
    }

    @Test
    void getHopsTraveledReturnsZeroWhenHopStartNotSet() {
        MeshMessage msg = createMessage();
        
        assertEquals(0, msg.getHopsTraveled());
    }

    @Test
    void getHopsTraveledCalculatesCorrectly() {
        MeshMessage msg = createMessage();
        
        msg.setHopStart(3);
        msg.setHopLimit(1);
        
        assertEquals(2, msg.getHopsTraveled());
    }
}
