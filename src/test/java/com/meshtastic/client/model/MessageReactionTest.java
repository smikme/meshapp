package com.meshtastic.client.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageReactionTest {

    @Test
    void constructorSetsAllFields() {
        MessageReaction reaction = new MessageReaction(
            123,
            "!00000002",
            "thumbsup",
            1_700_000_000L,
            false
        );
        
        assertEquals(123, reaction.getTargetPacketId());
        assertEquals("!00000002", reaction.getFromNodeId());
        assertEquals("thumbsup", reaction.getEmoji());
        assertEquals(1_700_000_000L, reaction.getTimestamp());
        assertFalse(reaction.isOutgoing());
    }

    @Test
    void constructorSanitizesBrokenUnicodeEmoji() {
        MessageReaction reaction = new MessageReaction(
            123,
            "!00000002",
            "A\uD83DB\uDC00C",
            1_700_000_000L,
            false
        );

        assertEquals("ABC", reaction.getEmoji());
    }

    @Test
    void isOutgoingReturnsTrueForOutgoing() {
        MessageReaction reaction = new MessageReaction(
            123,
            "!00000002",
            "thumbsup",
            1_700_000_000L,
            true
        );
        
        assertTrue(reaction.isOutgoing());
    }

    @Test
    void getDbIdDefaultsToZero() {
        MessageReaction reaction = new MessageReaction(
            123,
            "!00000002",
            "thumbsup",
            1_700_000_000L,
            false
        );
        
        assertEquals(0, reaction.getDbId());
    }

    @Test
    void setDbId() {
        MessageReaction reaction = new MessageReaction(
            123,
            "!00000002",
            "thumbsup",
            1_700_000_000L,
            false
        );
        
        reaction.setDbId(456L);
        
        assertEquals(456L, reaction.getDbId());
    }

    @Test
    void getPacketIdDefaultsToZero() {
        MessageReaction reaction = new MessageReaction(
            123,
            "!00000002",
            "thumbsup",
            1_700_000_000L,
            false
        );
        
        assertEquals(0, reaction.getPacketId());
    }

    @Test
    void setPacketId() {
        MessageReaction reaction = new MessageReaction(
            123,
            "!00000002",
            "thumbsup",
            1_700_000_000L,
            false
        );
        
        reaction.setPacketId(789);
        
        assertEquals(789, reaction.getPacketId());
    }

    @Test
    void getStatusDefaultsToNull() {
        MessageReaction reaction = new MessageReaction(
            123,
            "!00000002",
            "thumbsup",
            1_700_000_000L,
            false
        );
        
        assertEquals(null, reaction.getStatus());
    }

    @Test
    void setStatus() {
        MessageReaction reaction = new MessageReaction(
            123,
            "!00000002",
            "thumbsup",
            1_700_000_000L,
            false
        );
        
        reaction.setStatus(MeshMessage.DeliveryStatus.SENDING);
        
        assertEquals(MeshMessage.DeliveryStatus.SENDING, reaction.getStatus());
    }

    @Test
    void getErrorReasonDefaultsToNull() {
        MessageReaction reaction = new MessageReaction(
            123,
            "!00000002",
            "thumbsup",
            1_700_000_000L,
            false
        );
        
        assertEquals(null, reaction.getErrorReason());
    }

    @Test
    void setErrorReason() {
        MessageReaction reaction = new MessageReaction(
            123,
            "!00000002",
            "thumbsup",
            1_700_000_000L,
            false
        );
        
        reaction.setErrorReason("TIMEOUT");
        
        assertEquals("TIMEOUT", reaction.getErrorReason());
    }

    @Test
    void getSenderNameDefaultsToNull() {
        MessageReaction reaction = new MessageReaction(
            123,
            "!00000002",
            "thumbsup",
            1_700_000_000L,
            false
        );
        
        assertEquals(null, reaction.getSenderName());
    }

    @Test
    void setSenderName() {
        MessageReaction reaction = new MessageReaction(
            123,
            "!00000002",
            "thumbsup",
            1_700_000_000L,
            false
        );
        
        reaction.setSenderName("Device1");
        
        assertEquals("Device1", reaction.getSenderName());
    }

    @Test
    void setSenderNameSanitizesBrokenUnicode() {
        MessageReaction reaction = new MessageReaction(
            123,
            "!00000002",
            "thumbsup",
            1_700_000_000L,
            false
        );

        reaction.setSenderName("A\uD83DB\uDC00C");

        assertEquals("ABC", reaction.getSenderName());
    }

    @Test
    void isVisibleReturnsTrueWhenNotFailed() {
        MessageReaction reaction = new MessageReaction(
            123,
            "!00000002",
            "thumbsup",
            1_700_000_000L,
            false
        );
        
        assertTrue(reaction.isVisible());
    }

    @Test
    void isVisibleReturnsFalseWhenFailed() {
        MessageReaction reaction = new MessageReaction(
            123,
            "!00000002",
            "thumbsup",
            1_700_000_000L,
            false
        );
        
        reaction.setStatus(MeshMessage.DeliveryStatus.FAILED);
        
        assertFalse(reaction.isVisible());
    }
}
