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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
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
    void loadAfterSupportsForwardPaginationWithLimit() {
        MeshMessage first = message("first", 1, 10);
        MeshMessage second = message("second", 2, 20);
        MeshMessage third = message("third", 3, 30);
        MeshMessage fourth = message("fourth", 4, 40);

        service.save(first, "channel", "7", "!owner");
        service.save(second, "channel", "7", "!owner");
        service.save(third, "channel", "7", "!owner");
        service.save(fourth, "channel", "7", "!owner");

        List<MeshMessage> page = service.loadAfter("channel", "7", first.getDbId(), 2, "!owner");

        assertEquals(List.of("second", "third"), page.stream().map(MeshMessage::getText).toList());
        assertEquals(List.of("fourth"),
                service.loadAfter("channel", "7", page.getLast().getDbId(), "!owner")
                        .stream()
                        .map(MeshMessage::getText)
                        .toList());
    }

    @Test
    void unreadEligibleCountIncludesIncomingSystemMessagesButExcludesOutgoing() {
        MeshMessage incoming = message("incoming", 10, 10);
        MeshMessage outgoing = new MeshMessage("!00000001", "!ffffffff", 0, "outgoing", 20, true);
        outgoing.setPacketId(11);
        MeshMessage systemIncoming = message("system", 12, 30);
        systemIncoming.setSystemMessage(true);

        service.save(incoming, "channel", "0", "!owner");
        service.save(outgoing, "channel", "0", "!owner");
        service.save(systemIncoming, "channel", "0", "!owner");

        assertEquals(2, service.getUnreadEligibleMessageCount("channel", "0", "!owner"));
    }

    @Test
    void loadAllReadCountsNormalizesLegacyCountsToUnreadEligibleMessages() {
        MeshMessage incoming = message("incoming", 21, 10);
        MeshMessage outgoing = new MeshMessage("!00000001", "!ffffffff", 0, "outgoing", 20, true);
        outgoing.setPacketId(22);
        MeshMessage systemIncoming = message("system", 23, 30);
        systemIncoming.setSystemMessage(true);

        service.save(incoming, "channel", "0", "!owner");
        service.save(outgoing, "channel", "0", "!owner");
        service.save(systemIncoming, "channel", "0", "!owner");
        service.saveReadCount("channel", "0", 3, "!owner");

        assertEquals(2, service.loadAllReadCounts("!owner").get("ch:0"));
    }

    @Test
    void totalUnreadCountSubtractsReadCountsAcrossChatsAndOwners() {
        MeshMessage channelOne = message("channel-one", 31, 10);
        MeshMessage channelTwo = message("channel-two", 32, 20);
        MeshMessage dm = new MeshMessage("!peer", "!owner", 0, "dm", 30, false);
        dm.setPacketId(33);
        MeshMessage outgoing = new MeshMessage("!owner", "!ffffffff", 0, "outgoing", 40, true);
        outgoing.setPacketId(34);

        service.save(channelOne, "channel", "0", "!owner");
        service.save(channelTwo, "channel", "0", "!owner");
        service.save(dm, "dm", "!peer", "!owner");
        service.save(outgoing, "channel", "0", "!owner");
        service.save(message("other-owner", 35, 50), "channel", "0", "!other");
        service.saveReadCount("channel", "0", 1, "!owner");

        assertEquals(2, service.getTotalUnreadCount("!owner"));
        assertEquals(1, service.getTotalUnreadCount("!other"));
    }

    @Test
    void updateStatusAndFindByPacketIdReturnPersistedMetadata() {
        MeshMessage message = message("payload", 777, 10);
        message.setStatus(MeshMessage.DeliveryStatus.SENDING);
        message.setReplyId(55);
        message.setReplyText("quoted");
        message.setSenderName("alice");
        message.setViaMqtt(true);
        service.save(message, "dm", "!peer", "!owner");

        service.updateStatus(777, MeshMessage.DeliveryStatus.FAILED, "TIMEOUT");

        MeshMessage loaded = service.findByPacketId(777);
        assertNotNull(loaded);
        assertEquals(MeshMessage.DeliveryStatus.FAILED, loaded.getStatus());
        assertEquals("TIMEOUT", loaded.getErrorReason());
        assertEquals(55, loaded.getReplyId());
        assertEquals("quoted", loaded.getReplyText());
        assertEquals("alice", loaded.getSenderName());
        assertTrue(loaded.isViaMqtt());
    }

    @Test
    void scopedFindByPacketIdUsesOwnerChatAndOldestMatchingRow() {
        MeshMessage ownerA = message("owner-a", 888, 10);
        MeshMessage ownerB = message("owner-b", 888, 20);
        MeshMessage channelOne = message("channel-one", 888, 30);

        service.save(ownerA, "channel", "0", "!ownerA");
        service.save(ownerB, "channel", "0", "!ownerB");
        service.save(channelOne, "channel", "1", "!ownerA");

        assertEquals("owner-a", service.findByPacketId(888, "channel", "0", "!ownerA").getText());
        assertEquals("owner-b", service.findByPacketId(888, "channel", "0", "!ownerB").getText());
        assertEquals("channel-one", service.findByPacketId(888, "channel", "1", "!ownerA").getText());
    }

    @Test
    void hydrateReplyTextsFillsMissingQuoteFromSameChatAndPersistsIt() {
        MeshMessage original = message("quoted text", 901, 10);
        MeshMessage reply = message("reply", 902, 20);
        reply.setReplyId(901);

        service.save(original, "channel", "0", "!owner");
        service.save(reply, "channel", "0", "!owner");

        List<MeshMessage> loaded = service.loadLast("channel", "0", 10, "!owner");

        assertEquals(1, service.hydrateReplyTexts(loaded, "channel", "0", "!owner"));

        MeshMessage loadedReply = loaded.stream()
                .filter(message -> message.getPacketId() == 902)
                .findFirst()
                .orElseThrow();
        assertEquals("quoted text", loadedReply.getReplyText());
        assertEquals("quoted text",
                service.findByPacketId(902, "channel", "0", "!owner").getReplyText());
    }

    @Test
    void hydrateReplyTextsDoesNotUseOriginalFromAnotherScope() {
        MeshMessage otherOwnerOriginal = message("wrong owner", 903, 10);
        MeshMessage otherChannelOriginal = message("wrong channel", 904, 11);
        MeshMessage replyToOtherOwner = message("reply owner", 905, 20);
        MeshMessage replyToOtherChannel = message("reply channel", 906, 21);
        replyToOtherOwner.setReplyId(903);
        replyToOtherChannel.setReplyId(904);

        service.save(otherOwnerOriginal, "channel", "0", "!other");
        service.save(otherChannelOriginal, "channel", "1", "!owner");
        service.save(replyToOtherOwner, "channel", "0", "!owner");
        service.save(replyToOtherChannel, "channel", "0", "!owner");

        List<MeshMessage> loaded = service.loadLast("channel", "0", 10, "!owner");

        assertEquals(0, service.hydrateReplyTexts(loaded, "channel", "0", "!owner"));
        assertTrue(loaded.stream().allMatch(message -> message.getReplyText() == null));
    }

    @Test
    void backfillMissingReplyTextsUpdatesAllResolvableRepliesInChat() {
        MeshMessage original = message("old quoted", 907, 10);
        MeshMessage reply = message("old reply", 908, 20);
        MeshMessage unresolvedReply = message("unresolved", 909, 30);
        reply.setReplyId(907);
        unresolvedReply.setReplyId(123456);

        service.save(original, "channel", "0", "!owner");
        service.save(reply, "channel", "0", "!owner");
        service.save(unresolvedReply, "channel", "0", "!owner");

        assertEquals(1, service.backfillMissingReplyTexts("channel", "0", "!owner"));
        assertEquals("old quoted",
                service.findByPacketId(908, "channel", "0", "!owner").getReplyText());
        assertNull(service.findByPacketId(909, "channel", "0", "!owner").getReplyText());
    }

    @Test
    void saveDoesNotInsertDuplicateForSameScopedPacketId() {
        MeshMessage first = message("first", 990, 10);
        MeshMessage duplicate = message("duplicate", 990, 20);
        duplicate.setStatus(MeshMessage.DeliveryStatus.DELIVERED);
        duplicate.setViaMqtt(true);

        service.save(first, "channel", "0", "!owner");
        service.save(duplicate, "channel", "0", "!owner");

        List<MeshMessage> loaded = service.loadLast("channel", "0", 10, "!owner");
        assertEquals(1, loaded.size());
        assertEquals(first.getDbId(), duplicate.getDbId());
        assertEquals("first", loaded.getFirst().getText());
        assertEquals(MeshMessage.DeliveryStatus.DELIVERED, loaded.getFirst().getStatus());
        assertFalse(loaded.getFirst().isViaMqtt());
    }

    @Test
    void saveKeepsDistinctChannelMessagesWhenPacketIdsMatchAcrossDifferentSenders() {
        MeshMessage first = message("alice", 991, 10);
        MeshMessage second = new MeshMessage("!00000002", "!ffffffff", 0, "bob", 20, false);
        second.setPacketId(991);

        service.save(first, "channel", "0", "!owner");
        service.save(second, "channel", "0", "!owner");

        List<MeshMessage> loaded = service.loadLast("channel", "0", 10, "!owner");
        assertEquals(2, loaded.size());
        assertEquals(List.of("alice", "bob"), loaded.stream().map(MeshMessage::getText).toList());
    }

    @Test
    void saveDuplicateRemovesMqttFlagWhenLoraCopyArrivesLater() {
        MeshMessage mqtt = message("first", 991, 10);
        mqtt.setViaMqtt(true);
        service.save(mqtt, "channel", "0", "!owner");

        MeshMessage lora = message("duplicate", 991, 20);
        lora.setViaMqtt(false);
        lora.setHopStart(6);
        lora.setHopLimit(3);
        lora.setRxRssi(-88);
        lora.setRxSnr(4.5f);
        service.save(lora, "channel", "0", "!owner");

        MeshMessage loaded = service.findByPacketId(991, "channel", "0", "!owner");
        assertNotNull(loaded);
        assertFalse(loaded.isViaMqtt());
        assertEquals(6, loaded.getHopStart());
        assertEquals(3, loaded.getHopLimit());
        assertEquals(-88, loaded.getRxRssi());
        assertEquals(4.5f, loaded.getRxSnr());
    }

    @Test
    void saveDuplicateKeepsLoraFlagWhenMqttCopyArrivesLater() {
        MeshMessage lora = message("first", 992, 10);
        lora.setViaMqtt(false);
        lora.setHopStart(4);
        lora.setHopLimit(2);
        service.save(lora, "channel", "0", "!owner");

        MeshMessage mqtt = message("duplicate", 992, 20);
        mqtt.setViaMqtt(true);
        mqtt.setRxRssi(-70);
        service.save(mqtt, "channel", "0", "!owner");

        MeshMessage loaded = service.findByPacketId(992, "channel", "0", "!owner");
        assertNotNull(loaded);
        assertFalse(loaded.isViaMqtt());
        assertEquals(4, loaded.getHopStart());
        assertEquals(2, loaded.getHopLimit());
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
