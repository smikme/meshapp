package com.meshtastic.client.service;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.model.MessageReaction;
import com.meshtastic.client.model.MeshMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageDbServiceTest {

    @TempDir
    Path tempHome;

    private MessageDbService service;

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
        service = MessageDbService.getInstance();
    }

    @AfterEach
    void tearDown() {
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void saveAndLoadLastAreScopedByOwnerNodeId() {
        MeshMessage ownerAFirst = message("a-1", 101, 10);
        MeshMessage ownerASecond = message("a-2", 102, 20);
        MeshMessage ownerB = message("b-1", 201, 30);

        service.save(ownerAFirst, "channel", "0", "!ownerA");
        service.save(ownerASecond, "channel", "0", "!ownerA");
        service.save(ownerB, "channel", "0", "!ownerB");

        List<MeshMessage> ownerAMessages = service.loadLast("channel", "0", 10, "!ownerA");
        List<MeshMessage> ownerBMessages = service.loadLast("channel", "0", 10, "!ownerB");

        assertEquals(List.of("a-1", "a-2"), ownerAMessages.stream().map(MeshMessage::getText).toList());
        assertEquals(List.of("b-1"), ownerBMessages.stream().map(MeshMessage::getText).toList());
    }

    @Test
    void loadBeforeReturnsOlderMessagesInChronologicalOrder() {
        MeshMessage first = message("first", 1, 10);
        MeshMessage second = message("second", 2, 20);
        MeshMessage third = message("third", 3, 30);

        service.save(first, "channel", "7", "!owner");
        service.save(second, "channel", "7", "!owner");
        service.save(third, "channel", "7", "!owner");

        List<MeshMessage> latest = service.loadLast("channel", "7", 3, "!owner");
        long beforeDbId = latest.getLast().getDbId();
        List<MeshMessage> older = service.loadBefore("channel", "7", beforeDbId, 10, "!owner");

        assertEquals(List.of("first", "second"), older.stream().map(MeshMessage::getText).toList());
    }

    @Test
    void updateStatusAndFindByPacketIdReturnPersistedMetadata() {
        MeshMessage message = message("payload", 777, 10);
        message.setStatus(MeshMessage.DeliveryStatus.SENDING);
        message.setReplyId(55);
        message.setReplyText("quoted");
        message.setSenderName("alice");
        service.save(message, "dm", "!peer", "!owner");

        service.updateStatus(777, MeshMessage.DeliveryStatus.FAILED, "TIMEOUT");

        MeshMessage loaded = service.findByPacketId(777);
        assertNotNull(loaded);
        assertEquals(MeshMessage.DeliveryStatus.FAILED, loaded.getStatus());
        assertEquals("TIMEOUT", loaded.getErrorReason());
        assertEquals(55, loaded.getReplyId());
        assertEquals("quoted", loaded.getReplyText());
        assertEquals("alice", loaded.getSenderName());
    }

    @Test
    void saveAndLoadReactionsAreScopedByOwnerAndTargetPacketId() {
        MessageReaction ownerAFirst = reaction(555, 9001, "👍", 10, false);
        MessageReaction ownerASecond = reaction(555, 9002, "🎉", 20, true);
        MessageReaction ownerB = reaction(555, 9003, "💩", 30, false);

        service.saveReaction(ownerAFirst, "channel", "0", "!ownerA");
        service.saveReaction(ownerASecond, "channel", "0", "!ownerA");
        service.saveReaction(ownerB, "channel", "0", "!ownerB");

        Map<Integer, List<MessageReaction>> ownerAReactions =
                service.loadReactionsByTargetPacketIds("channel", "0", "!ownerA", List.of(555, 777));
        Map<Integer, List<MessageReaction>> ownerBReactions =
                service.loadReactionsByTargetPacketIds("channel", "0", "!ownerB", List.of(555));

        assertEquals(List.of("👍", "🎉"),
                ownerAReactions.get(555).stream().map(MessageReaction::getEmoji).toList());
        assertEquals(List.of("💩"),
                ownerBReactions.get(555).stream().map(MessageReaction::getEmoji).toList());
    }

    @Test
    void loadReactionsSupportsNegativeTargetPacketIds() {
        MessageReaction reaction = reaction(-766654509, 9010, "👋", 10, true);
        service.saveReaction(reaction, "channel", "0", "!owner");

        Map<Integer, List<MessageReaction>> reactions =
                service.loadReactionsByTargetPacketIds("channel", "0", "!owner", List.of(-766654509));

        assertEquals(List.of("👋"),
                reactions.get(-766654509).stream().map(MessageReaction::getEmoji).toList());
    }

    @Test
    void updateReactionStatusPersistsDeliveryMetadata() {
        MessageReaction reaction = reaction(777, 9901, "🙏", 10, true);
        reaction.setStatus(MeshMessage.DeliveryStatus.SENDING);
        service.saveReaction(reaction, "dm", "!peer", "!owner");

        assertTrue(service.updateReactionStatus(9901, MeshMessage.DeliveryStatus.FAILED, "NO_ROUTE"));

        MessageReaction loaded = service.loadReactionsByTargetPacketIds(
                "dm", "!peer", "!owner", List.of(777)).get(777).getFirst();
        assertEquals(MeshMessage.DeliveryStatus.FAILED, loaded.getStatus());
        assertEquals("NO_ROUTE", loaded.getErrorReason());
    }

    @Test
    void saveReadCountUsesTypeSpecificKeys() {
        service.saveReadCount("channel", "4", 12, "!owner");
        service.saveReadCount("dm", "!peer", 3, "!owner");
        service.saveReadCount("channel", "4", 99, "!other");

        Map<String, Integer> counts = service.loadAllReadCounts("!owner");

        assertEquals(2, counts.size());
        assertEquals(12, counts.get("ch:4"));
        assertEquals(3, counts.get("dm:!peer"));
        assertNull(counts.get("ch:99"));
    }

    @Test
    void deleteChatRemovesMessagesAndReadCount() {
        service.save(message("one", 1, 10), "channel", "2", "!owner");
        service.save(message("two", 2, 20), "channel", "2", "!owner");
        service.saveReaction(reaction(1, 9004, "😀", 21, false), "channel", "2", "!owner");
        service.saveReadCount("channel", "2", 2, "!owner");

        service.deleteChat("channel", "2", "!owner");

        assertTrue(service.loadLast("channel", "2", 10, "!owner").isEmpty());
        assertTrue(service.loadReactionsByTargetPacketIds("channel", "2", "!owner", List.of(1)).isEmpty());
        assertTrue(service.loadAllReadCounts("!owner").isEmpty());
    }

    private static MeshMessage message(String text, int packetId, long timestamp) {
        MeshMessage message = new MeshMessage("!00000001", "!ffffffff", 0, text, timestamp, false);
        message.setPacketId(packetId);
        return message;
    }

    private static MessageReaction reaction(int targetPacketId,
                                            int packetId,
                                            String emoji,
                                            long timestamp,
                                            boolean outgoing) {
        MessageReaction reaction = new MessageReaction(targetPacketId, "!00000002", emoji, timestamp, outgoing);
        reaction.setPacketId(packetId);
        return reaction;
    }
}
