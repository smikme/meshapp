package com.meshtastic.client.service;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.model.MessageReaction;
import com.meshtastic.client.model.MeshMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
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
    void findByPacketIdsLoadsOneScopedBatch() {
        service.save(message("one", 101, 10), "channel", "0", "!owner");
        service.save(message("two", 102, 20), "channel", "0", "!owner");
        service.save(message("other-chat", 103, 30), "channel", "1", "!owner");
        service.save(message("other-owner", 104, 40), "channel", "0", "!other");

        Map<Integer, MeshMessage> loaded = service.findByPacketIds(
                List.of(101, 102, 103, 104), "channel", "0", "!owner");

        assertEquals(List.of(101, 102), loaded.keySet().stream().sorted().toList());
        assertEquals("two", loaded.get(102).getText());
    }

    @Test
    void getChatSummariesCombinesLatestMessageAndUnreadCount() {
        service.save(message("first", 201, 10), "channel", "0", "!owner");
        MeshMessage outgoing = new MeshMessage(
                "!owner", "!peer", 0, "latest", 20, true);
        outgoing.setPacketId(202);
        service.save(outgoing, "channel", "0", "!owner");
        service.save(message("dm", 203, 30), "dm", "!peer", "!owner");
        service.save(message("other", 204, 40), "channel", "0", "!other");

        Map<String, MessageDbService.ChatSummary> summaries = service.getChatSummaries("!owner");
        MessageDbService.ChatSummary channel = summaries.get(
                MessageDbService.chatSummaryKey("channel", "0"));
        MessageDbService.ChatSummary direct = summaries.get(
                MessageDbService.chatSummaryKey("dm", "!peer"));

        assertEquals(2, summaries.size());
        assertEquals("latest", channel.lastMessage().getText());
        assertEquals(1, channel.unreadEligibleCount());
        assertEquals("dm", direct.lastMessage().getText());
        assertEquals(1, direct.unreadEligibleCount());
    }

    @Test
    void loadTracerouteResultsForNodeMatchesTargetResponseAndLegacyDmScope() {
        long targetNodeNum = Integer.toUnsignedLong(0x71A67CF5);

        service.saveTracerouteResult(
                "!owner", "", "", "test", "target", 0,
                targetNodeNum, "!71a67cf5", "Target", 0, null, null, null, 300);
        service.saveTracerouteResult(
                "!owner", "", "", "test", "response", 0,
                0, null, "Target", targetNodeNum, "!71a67cf5", null, null, 200);
        service.saveTracerouteResult(
                "!owner", "dm", "!71a67cf5", "test", "legacy-dm", 0,
                0, null, "Target", 0, null, null, null, 100);
        service.saveTracerouteResult(
                "!owner", "", "", "test", "other-target", 0,
                Integer.toUnsignedLong(0x22222222), "!22222222", "Other", 0, null, null, null, 400);
        service.saveTracerouteResult(
                "!other-owner", "", "", "test", "other-owner", 0,
                targetNodeNum, "!71a67cf5", "Target", 0, null, null, null, 500);

        List<MessageDbService.TracerouteResultRecord> traces =
                service.loadTracerouteResultsForNode("!owner", targetNodeNum, "!71a67cf5");

        assertEquals(
                List.of("target", "response", "legacy-dm"),
                traces.stream().map(MessageDbService.TracerouteResultRecord::requestId).toList());
    }

    @Test
    void loadTracerouteResultsForNodeSupportsDateFilterAndCursorPaging() {
        long targetNodeNum = Integer.toUnsignedLong(0x71A67CF5);
        LocalDate traceDate = LocalDate.of(2026, 5, 30);
        long start = traceDate.atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        long end = traceDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond();

        for (int i = 0; i < 25; i++) {
            service.saveTracerouteResult(
                    "!owner", "", "", "test", "page-" + i, 0,
                    targetNodeNum, "!71a67cf5", "Target", 0, null, null, null, start + i);
        }
        service.saveTracerouteResult(
                "!owner", "", "", "test", "next-day", 0,
                targetNodeNum, "!71a67cf5", "Target", 0, null, null, null, end + 1);

        List<MessageDbService.TracerouteResultRecord> firstPage = service.loadTracerouteResultsForNode(
                "!owner", targetNodeNum, "!71a67cf5", start, end, 20, 0, 0);
        MessageDbService.TracerouteResultRecord lastFirstPageRecord = firstPage.getLast();
        List<MessageDbService.TracerouteResultRecord> secondPage = service.loadTracerouteResultsForNode(
                "!owner",
                targetNodeNum,
                "!71a67cf5",
                start,
                end,
                20,
                lastFirstPageRecord.timestamp(),
                lastFirstPageRecord.id());

        assertEquals(20, firstPage.size());
        assertEquals("page-24", firstPage.getFirst().requestId());
        assertEquals("page-5", firstPage.getLast().requestId());
        assertEquals(
                List.of("page-4", "page-3", "page-2", "page-1", "page-0"),
                secondPage.stream().map(MessageDbService.TracerouteResultRecord::requestId).toList());
    }

    @Test
    void loadTracerouteResultReturnsOneRecordInsideOwnerScope() {
        long requestedId = service.saveTracerouteResult(
                "!owner", "", "", "test", "requested", 0,
                Integer.toUnsignedLong(0x71A67CF5), "!71a67cf5", "Target",
                0, null, null, null, 100);
        service.saveTracerouteResult(
                "!other-owner", "", "", "test", "other-owner", 0,
                Integer.toUnsignedLong(0x71A67CF5), "!71a67cf5", "Target",
                0, null, null, null, 200);

        assertEquals("requested",
                service.loadTracerouteResult(requestedId, "!owner")
                        .map(MessageDbService.TracerouteResultRecord::requestId)
                        .orElseThrow());
        assertTrue(service.loadTracerouteResult(requestedId, "!other-owner").isEmpty());
    }

    @Test
    void messageSearchMatchesTextCaseInsensitivelyAndKeepsChatScope() {
        MeshMessage first = message("Alpha payload", 41, 10);
        MeshMessage second = message("beta alpha", 42, 20);
        MeshMessage otherChat = message("alpha from another channel", 43, 30);
        MeshMessage otherOwner = message("alpha from another owner", 44, 40);

        service.save(first, "channel", "0", "!owner");
        service.save(second, "channel", "0", "!owner");
        service.save(otherChat, "channel", "1", "!owner");
        service.save(otherOwner, "channel", "0", "!other");

        MessageDbService.MessageSearchCount count =
                service.countMessageSearchMatchesLimited("channel", "0", "ALPHA", "!owner");
        assertEquals(2, count.count());
        assertFalse(count.limited());
        assertEquals(second.getDbId(), service.findLatestMessageSearchMatch("channel", "0", "ALPHA", "!owner"));
        assertEquals(first.getDbId(),
                service.findPreviousMessageSearchMatch("channel", "0", "ALPHA", "!owner", second.getDbId()));
        assertEquals(second.getDbId(),
                service.findNextMessageSearchMatch("channel", "0", "ALPHA", "!owner", first.getDbId()));
        assertEquals(0,
                service.findNextMessageSearchMatch("channel", "0", "ALPHA", "!owner", second.getDbId()));
    }

    @Test
    void messageSearchUsesFullTextWordsAndDoesNotMatchPartialTerms() {
        MeshMessage alpha = message("alpha payload", 45, 10);
        MeshMessage alphabet = message("alphabet soup", 46, 20);

        service.save(alpha, "channel", "0", "!owner");
        service.save(alphabet, "channel", "0", "!owner");

        assertEquals(alpha.getDbId(), service.findLatestMessageSearchMatch("channel", "0", "alpha", "!owner"));
        assertEquals(1, service.countMessageSearchMatchesLimited("channel", "0", "alpha", "!owner").count());
        assertEquals(0, service.findLatestMessageSearchMatch("channel", "0", "alp", "!owner"));
        assertEquals(0, service.countMessageSearchMatchesLimited("channel", "0", "alp", "!owner").count());
    }

    @Test
    void messageSearchMatchesRussianWordFormsByStemPrefix() {
        MeshMessage plural = message("ремонт дверей", 54, 10);
        MeshMessage adjective = message("дверной замок", 55, 20);
        MeshMessage unrelated = message("доверие к связи", 56, 30);

        service.save(plural, "channel", "0", "!owner");
        service.save(adjective, "channel", "0", "!owner");
        service.save(unrelated, "channel", "0", "!owner");

        MessageDbService.MessageSearchCount count =
                service.countMessageSearchMatchesLimited("channel", "0", "дверь", "!owner");
        assertEquals(2, count.count());
        assertFalse(count.limited());
        assertEquals(adjective.getDbId(),
                service.findLatestMessageSearchMatch("channel", "0", "дверь", "!owner"));
        assertEquals(plural.getDbId(),
                service.findPreviousMessageSearchMatch("channel", "0", "дверь", "!owner", adjective.getDbId()));
        assertTrue(service.messageMatchesSearch("channel", "0", "дверь", "!owner", adjective.getDbId()));
        assertFalse(service.messageMatchesSearch("channel", "0", "дверь", "!owner", unrelated.getDbId()));
    }

    @Test
    void messageSearchRequiresAllFullTextTermsInsideChatScope() {
        MeshMessage alphaPayload = message("alpha payload", 47, 10);
        MeshMessage alphaOnly = message("alpha", 48, 20);
        MeshMessage payloadOtherChat = message("payload", 49, 30);

        service.save(alphaPayload, "channel", "0", "!owner");
        service.save(alphaOnly, "channel", "0", "!owner");
        service.save(payloadOtherChat, "channel", "1", "!owner");

        assertTrue(service.messageMatchesSearch(
                "channel",
                "0",
                "alpha payload",
                "!owner",
                alphaPayload.getDbId()));
        assertFalse(service.messageMatchesSearch(
                "channel",
                "0",
                "alpha payload",
                "!owner",
                alphaOnly.getDbId()));
    }

    @Test
    void messageSearchUsesRealtimeFullTextIndexUpdates() {
        assertEquals(0, service.findLatestMessageSearchMatch("channel", "0", "later", "!owner"));

        MeshMessage insertedAfterIndexCreation = message("later indexed message", 50, 10);
        service.save(insertedAfterIndexCreation, "channel", "0", "!owner");

        assertEquals(insertedAfterIndexCreation.getDbId(),
                service.findLatestMessageSearchMatch("channel", "0", "later", "!owner"));
    }

    @Test
    void messageSearchCanBeFilteredBySenderNode() {
        MeshMessage fromFirstNode = messageFrom("!00000001", "alpha from first node", 52, 10);
        MeshMessage fromSecondNode = messageFrom("!00000002", "alpha from second node", 53, 20);

        service.save(fromFirstNode, "channel", "0", "!owner");
        service.save(fromSecondNode, "channel", "0", "!owner");

        MessageDbService.MessageSearchCount firstNodeCount =
                service.countMessageSearchMatchesLimited("channel", "0", "alpha", "!owner", "!00000001");
        assertEquals(1, firstNodeCount.count());
        assertFalse(firstNodeCount.limited());
        assertEquals(fromFirstNode.getDbId(),
                service.findLatestMessageSearchMatch("channel", "0", "alpha", "!owner", "!00000001"));
        assertEquals(fromSecondNode.getDbId(),
                service.findLatestMessageSearchMatch("channel", "0", "alpha", "!owner", "!00000002"));
        assertEquals(0,
                service.findLatestMessageSearchMatch("channel", "0", "alpha", "!owner", "!00000003"));
        assertTrue(service.messageMatchesSearch(
                "channel",
                "0",
                "alpha",
                "!owner",
                "!00000001",
                fromFirstNode.getDbId()));
        assertFalse(service.messageMatchesSearch(
                "channel",
                "0",
                "alpha",
                "!owner",
                "!00000002",
                fromFirstNode.getDbId()));
    }

    @Test
    void fullTextIndexInitializationIsIdempotentAcrossServiceRestarts() {
        MeshMessage persisted = message("restart searchable", 51, 10);
        service.save(persisted, "channel", "0", "!owner");

        TestEnvironmentSupport.resetSingletons();
        service = MessageDbService.getInstance();

        assertEquals(persisted.getDbId(),
                service.findLatestMessageSearchMatch("channel", "0", "restart", "!owner"));
    }

    @Test
    void explicitDmThreadSurvivesServiceRestartWithoutMessages() {
        service.ensureChatThread("dm", "!peer", "!owner");

        TestEnvironmentSupport.resetSingletons();
        service = MessageDbService.getInstance();

        assertEquals(List.of("!peer"), service.getDistinctDmPeers("!owner"));
        assertTrue(service.loadLast("dm", "!peer", 10, "!owner").isEmpty());
    }

    @Test
    void deleteChatKeepsExplicitDmThreadButDeleteChatThreadRemovesIt() {
        service.ensureChatThread("dm", "!peer", "!owner");
        service.save(message("dm", 61, 10), "dm", "!peer", "!owner");

        service.deleteChat("dm", "!peer", "!owner");

        assertTrue(service.loadLast("dm", "!peer", 10, "!owner").isEmpty());
        assertEquals(List.of("!peer"), service.getDistinctDmPeers("!owner"));

        service.deleteChatThread("dm", "!peer", "!owner");

        assertTrue(service.getDistinctDmPeers("!owner").isEmpty());
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
        mqtt.setHopStart(7);
        mqtt.setHopLimit(6);
        mqtt.setRxRssi(-70);
        service.save(mqtt, "channel", "0", "!owner");

        MeshMessage loaded = service.findByPacketId(992, "channel", "0", "!owner");
        assertNotNull(loaded);
        assertFalse(loaded.isViaMqtt());
        assertEquals(4, loaded.getHopStart());
        assertEquals(2, loaded.getHopLimit());
        assertEquals(0, loaded.getRxRssi());
    }

    @Test
    void saveDuplicateDoesNotFillMissingLoraHopDataFromLaterMqttCopy() {
        MeshMessage lora = message("first", 993, 10);
        lora.setViaMqtt(false);
        service.save(lora, "channel", "0", "!owner");

        MeshMessage mqtt = message("duplicate", 993, 20);
        mqtt.setViaMqtt(true);
        mqtt.setHopStart(7);
        mqtt.setHopLimit(3);
        mqtt.setRxRssi(-70);
        mqtt.setRxSnr(5.5f);
        service.save(mqtt, "channel", "0", "!owner");

        MeshMessage loaded = service.findByPacketId(993, "channel", "0", "!owner");
        assertNotNull(loaded);
        assertFalse(loaded.isViaMqtt());
        assertEquals(0, loaded.getHopStart());
        assertEquals(0, loaded.getHopLimit());
        assertEquals(0, loaded.getRxRssi());
        assertEquals(0.0f, loaded.getRxSnr());
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
    void deleteChatRemovesMessagesReactionsAndReadCountOnlyForTargetChat() {
        service.save(message("one", 1, 10), "channel", "2", "!owner");
        service.save(message("two", 2, 20), "channel", "2", "!owner");
        service.save(message("other-chat", 3, 30), "channel", "3", "!owner");
        service.save(message("other-owner", 4, 40), "channel", "2", "!other");
        service.saveReaction(reaction(1, 9004, "😀", 21, false), "channel", "2", "!owner");
        service.saveReaction(reaction(3, 9005, "👍", 31, false), "channel", "3", "!owner");
        service.saveReaction(reaction(4, 9006, "🎉", 41, false), "channel", "2", "!other");
        service.saveReadCount("channel", "2", 2, "!owner");
        service.saveReadCount("channel", "3", 1, "!owner");
        service.saveReadCount("channel", "2", 1, "!other");

        service.deleteChat("channel", "2", "!owner");

        assertTrue(service.loadLast("channel", "2", 10, "!owner").isEmpty());
        assertTrue(service.loadReactionsByTargetPacketIds("channel", "2", "!owner", List.of(1)).isEmpty());
        assertNull(service.loadAllReadCounts("!owner").get("ch:2"));
        assertEquals(List.of("other-chat"),
                service.loadLast("channel", "3", 10, "!owner").stream().map(MeshMessage::getText).toList());
        assertEquals(List.of("other-owner"),
                service.loadLast("channel", "2", 10, "!other").stream().map(MeshMessage::getText).toList());
        assertEquals(List.of("👍"),
                service.loadReactionsByTargetPacketIds("channel", "3", "!owner", List.of(3)).get(3)
                        .stream()
                        .map(MessageReaction::getEmoji)
                        .toList());
        assertEquals(List.of("🎉"),
                service.loadReactionsByTargetPacketIds("channel", "2", "!other", List.of(4)).get(4)
                        .stream()
                        .map(MessageReaction::getEmoji)
                        .toList());
        assertEquals(1, service.loadAllReadCounts("!owner").get("ch:3"));
        assertEquals(1, service.loadAllReadCounts("!other").get("ch:2"));
    }

    private static MeshMessage message(String text, int packetId, long timestamp) {
        MeshMessage message = messageFrom("!00000001", text, packetId, timestamp);
        return message;
    }

    private static MeshMessage messageFrom(String fromNodeId, String text, int packetId, long timestamp) {
        MeshMessage message = new MeshMessage(fromNodeId, "!ffffffff", 0, text, timestamp, false);
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
