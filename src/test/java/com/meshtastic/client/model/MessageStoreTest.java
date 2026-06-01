package com.meshtastic.client.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class MessageStoreTest {

    private static MeshMessage createMessage(String text, int channelIndex, int packetId) {
        MeshMessage msg = new MeshMessage("!00000001", "!ffffffff", channelIndex, text, 1_700_000_000L, false);
        msg.setPacketId(packetId);
        return msg;
    }

    @Test
    void addMessageAddsNewMessage() {
        MessageStore store = new MessageStore();
        
        MeshMessage msg = createMessage("Hello", 0, 1);
        store.addMessage(msg);
        
        List<MeshMessage> messages = store.getMessages(0);
        assertEquals(1, messages.size());
        // Добавлено сообщение содержит ту же ссылку
        assertEquals("Hello", messages.getFirst().getText());
    }

    @Test
    void addMessageDeduplicatesByPacketId() {
        MessageStore store = new MessageStore();
        
        MeshMessage msg1 = createMessage("First", 0, 100);
        store.addMessage(msg1);
        
        // Second - тоже с packetId 100, должно быть проигнорировано
        MeshMessage msg2 = createMessage("Second", 0, 100);
        store.addMessage(msg2);
        
        List<MeshMessage> messages = store.getMessages(0);
        // Первый тест показывал, что length равен 1
        assertEquals(1, messages.size());
    }

    @Test
    void addMessageDuplicatePromotesStoredCopyFromMqttToLora() {
        MessageStore store = new MessageStore();

        MeshMessage mqtt = createMessage("First", 0, 100);
        mqtt.setViaMqtt(true);
        store.addMessage(mqtt);

        MeshMessage lora = createMessage("First", 0, 100);
        lora.setViaMqtt(false);
        lora.setHopStart(5);
        lora.setHopLimit(2);
        lora.setRxRssi(-91);
        lora.setRxSnr(7.5f);
        store.addMessage(lora);

        MeshMessage stored = store.getMessages(0).getFirst();
        assertEquals(1, store.getMessages(0).size());
        assertTrue(!stored.isViaMqtt());
        assertEquals(5, stored.getHopStart());
        assertEquals(2, stored.getHopLimit());
        assertEquals(-91, stored.getRxRssi());
        assertEquals(7.5f, stored.getRxSnr());
    }

    @Test
    void addMessageDuplicateDoesNotFillMissingLoraHopDataFromLaterMqttCopy() {
        MessageStore store = new MessageStore();

        MeshMessage lora = createMessage("First", 0, 101);
        lora.setViaMqtt(false);
        store.addMessage(lora);

        MeshMessage mqtt = createMessage("First", 0, 101);
        mqtt.setViaMqtt(true);
        mqtt.setHopStart(6);
        mqtt.setHopLimit(2);
        mqtt.setRxRssi(-80);
        mqtt.setRxSnr(3.5f);
        store.addMessage(mqtt);

        MeshMessage stored = store.getMessages(0).getFirst();
        assertEquals(1, store.getMessages(0).size());
        assertFalse(stored.isViaMqtt());
        assertEquals(0, stored.getHopStart());
        assertEquals(0, stored.getHopLimit());
        assertEquals(0, stored.getRxRssi());
        assertEquals(0.0f, stored.getRxSnr());
    }

    @Test
    void addMessageKeepsMax100Messages() {
        MessageStore store = new MessageStore();
        
        for (int i = 1; i <= 150; i++) {
            MeshMessage msg = createMessage("msg-" + i, 1, 1000 + i);
            store.addMessage(msg);
        }
        
        List<MeshMessage> messages = store.getMessages(1);
        assertEquals(100, messages.size());
        // После дедупликации проверяем только first/last
    }

    @Test
    void getMessagesReturnsEmptyListForUnknownChannel() {
        MessageStore store = new MessageStore();
        
        List<MeshMessage> messages = store.getMessages(99);
        assertNotNull(messages);
        assertTrue(messages.isEmpty());
    }

    @Test
    void getAllChannelMessagesReturnsAllChannels() {
        MessageStore store = new MessageStore();
        
        store.addMessage(createMessage("msg1", 0, 1));
        store.addMessage(createMessage("msg2", 1, 2));
        
        Map<Integer, List<MeshMessage>> all = store.getAllChannelMessages();
        assertEquals(2, all.size());
        assertTrue(all.containsKey(0));
        assertTrue(all.containsKey(1));
    }

    @Test
    void addDirectMessageAddsNewMessage() {
        MessageStore store = new MessageStore();
        
        MeshMessage msg = createMessage("DM Hello", 0, 1);
        store.addDirectMessage(msg, "!peer1");
        
        List<MeshMessage> messages = store.getDirectMessages("!peer1");
        assertEquals(1, messages.size());
    }

    @Test
    void addDirectMessageDeduplicatesByPacketId() {
        MessageStore store = new MessageStore();
        
        MeshMessage msg1 = createMessage("First", 0, 200);
        store.addDirectMessage(msg1, "!peer1");
        
        // Same packetId - should be ignored
        MeshMessage msg2 = createMessage("Second", 0, 200);
        store.addDirectMessage(msg2, "!peer1");
        
        List<MeshMessage> messages = store.getDirectMessages("!peer1");
        assertEquals(1, messages.size());
    }

    @Test
    void addDirectMessageDuplicateKeepsLoraWhenMqttCopyArrivesLater() {
        MessageStore store = new MessageStore();

        MeshMessage lora = createMessage("First", 0, 200);
        lora.setViaMqtt(false);
        lora.setHopStart(4);
        lora.setHopLimit(1);
        store.addDirectMessage(lora, "!peer1");

        MeshMessage mqtt = createMessage("First", 0, 200);
        mqtt.setViaMqtt(true);
        store.addDirectMessage(mqtt, "!peer1");

        MeshMessage stored = store.getDirectMessages("!peer1").getFirst();
        assertEquals(1, store.getDirectMessages("!peer1").size());
        assertFalse(stored.isViaMqtt());
        assertEquals(4, stored.getHopStart());
        assertEquals(1, stored.getHopLimit());
    }

    @Test
    void addDirectMessageDuplicateDoesNotFillMissingLoraHopDataFromLaterMqttCopy() {
        MessageStore store = new MessageStore();

        MeshMessage lora = createMessage("First", 0, 202);
        lora.setViaMqtt(false);
        store.addDirectMessage(lora, "!peer1");

        MeshMessage mqtt = createMessage("First", 0, 202);
        mqtt.setViaMqtt(true);
        mqtt.setHopStart(6);
        mqtt.setHopLimit(2);
        mqtt.setRxRssi(-80);
        mqtt.setRxSnr(3.5f);
        store.addDirectMessage(mqtt, "!peer1");

        MeshMessage stored = store.getDirectMessages("!peer1").getFirst();
        assertEquals(1, store.getDirectMessages("!peer1").size());
        assertFalse(stored.isViaMqtt());
        assertEquals(0, stored.getHopStart());
        assertEquals(0, stored.getHopLimit());
        assertEquals(0, stored.getRxRssi());
        assertEquals(0.0f, stored.getRxSnr());
    }

    @Test
    void addDirectMessageDuplicatePromotesDeliveredToConfirmed() {
        MessageStore store = new MessageStore();

        MeshMessage delivered = new MeshMessage("!00000001", "!peer1", 0, "First", 1_700_000_000L, true);
        delivered.setPacketId(201);
        delivered.setStatus(MeshMessage.DeliveryStatus.DELIVERED);
        store.addDirectMessage(delivered, "!peer1");

        MeshMessage confirmed = new MeshMessage("!00000001", "!peer1", 0, "First", 1_700_000_000L, true);
        confirmed.setPacketId(201);
        confirmed.setStatus(MeshMessage.DeliveryStatus.CONFIRMED);
        store.addDirectMessage(confirmed, "!peer1");

        MeshMessage stored = store.getDirectMessages("!peer1").getFirst();
        assertEquals(1, store.getDirectMessages("!peer1").size());
        assertEquals(MeshMessage.DeliveryStatus.CONFIRMED, stored.getStatus());
    }

    @Test
    void addDirectMessageKeepsMax100Messages() {
        MessageStore store = new MessageStore();
        
        for (int i = 1; i <= 150; i++) {
            MeshMessage msg = createMessage("dm-" + i, 0, 2000 + i);
            store.addDirectMessage(msg, "!peer2");
        }
        
        List<MeshMessage> messages = store.getDirectMessages("!peer2");
        assertEquals(100, messages.size());
    }

    @Test
    void getDirectMessagesReturnsEmptyListForUnknownPeer() {
        MessageStore store = new MessageStore();
        
        List<MeshMessage> messages = store.getDirectMessages("!unknown");
        assertNotNull(messages);
        assertTrue(messages.isEmpty());
    }

    @Test
    void getAllDirectMessagesReturnsAllPeers() {
        MessageStore store = new MessageStore();
        
        store.addDirectMessage(createMessage("dm1", 0, 1), "!peer1");
        store.addDirectMessage(createMessage("dm2", 0, 2), "!peer2");
        
        Map<String, List<MeshMessage>> all = store.getAllDirectMessages();
        assertEquals(2, all.size());
        assertTrue(all.containsKey("!peer1"));
        assertTrue(all.containsKey("!peer2"));
    }

    @Test
    void removeDirectMessagesRemovesThread() {
        MessageStore store = new MessageStore();
        
        store.addDirectMessage(createMessage("dm1", 0, 1), "!peer1");
        store.removeDirectMessages("!peer1");
        
        assertTrue(store.getDirectMessages("!peer1").isEmpty());
    }

    @Test
    void ensureDirectMessageThreadCreatesNewThread() {
        MessageStore store = new MessageStore();
        
        store.ensureDirectMessageThread("!newPeer");
        
        List<MeshMessage> messages = store.getDirectMessages("!newPeer");
        assertNotNull(messages);
    }

    @Test
    void ensureDirectMessageThreadDoesNotFireForExisting() {
        MessageStore store = new MessageStore();
        
        // Создаем поток
        store.ensureDirectMessageThread("!existingPeer");
        int initialSize = store.getDirectMessages("!existingPeer").size();
        
        // Повторный вызов не должен создавать новый поток
        store.ensureDirectMessageThread("!existingPeer");
        
        List<MeshMessage> messages = store.getDirectMessages("!existingPeer");
        assertEquals(initialSize, messages.size());
    }

    @Test
    void ensureDirectMessageThreadIgnoresNull() {
        MessageStore store = new MessageStore();
        
        store.ensureDirectMessageThread(null);
        
        // Ничего не должно сломаться
    }

    @Test
    void ensureDirectMessageThreadIgnoresEmpty() {
        MessageStore store = new MessageStore();
        
        store.ensureDirectMessageThread("");
        
        // Ничего не должно сломаться
    }

    @Test
    void registerPendingAckStoresEntry() {
        MessageStore store = new MessageStore();
        
        MeshMessage msg = createMessage("pending", 0, 300);
        store.registerPendingAck(300, msg);
        
        assertNotNull(store.resolvePendingAck(300));
    }

    @Test
    void resolvePendingAckRemovesEntry() {
        MessageStore store = new MessageStore();
        
        MeshMessage msg = createMessage("pending", 0, 400);
        store.registerPendingAck(400, msg);
        
        store.resolvePendingAck(400);
        assertNull(store.resolvePendingAck(400));
    }

    @Test
    void resolvePendingAckReturnsNullForUnknown() {
        MessageStore store = new MessageStore();
        
        assertNull(store.resolvePendingAck(999));
    }

    @Test
    void failAllPendingAcksMarksMessagesAsFailed() {
        MessageStore store = new MessageStore();
        
        MeshMessage msg1 = createMessage("pending1", 0, 500);
        MeshMessage msg2 = createMessage("pending2", 0, 501);
        
        store.registerPendingAck(500, msg1);
        store.registerPendingAck(501, msg2);
        
        store.failAllPendingAcks("TIMEOUT");
        
        assertNull(store.resolvePendingAck(500));
        assertNull(store.resolvePendingAck(501));
        
        assertEquals(MeshMessage.DeliveryStatus.FAILED, msg1.getStatus());
    }

    @Test
    void failAllPendingAcksClearsQueue() {
        MessageStore store = new MessageStore();
        
        MeshMessage msg = createMessage("pending", 0, 600);
        store.registerPendingAck(600, msg);
        
        store.failAllPendingAcks("DISCONNECTED");
        
        assertTrue(store.getPendingAcks().isEmpty());
    }

    @Test
    void findMessageByPacketIdFindsInChannelMessages() {
        MessageStore store = new MessageStore();
        
        MeshMessage msg = createMessage("channel", 0, 700);
        store.addMessage(msg);
        
        // Устанавливаем packetId - так как createMessage не устанавливает его
        msg.setPacketId(700);
        
        assertNotNull(store.findMessageByPacketId(700));
    }

    @Test
    void findMessageByPacketIdFindsInDirectMessages() {
        MessageStore store = new MessageStore();
        
        MeshMessage msg = createMessage("dm", 0, 800);
        store.addDirectMessage(msg, "!peer");
        
        // Устанавливаем packetId
        msg.setPacketId(800);
        
        assertNotNull(store.findMessageByPacketId(800));
    }

    @Test
    void findMessageByPacketIdReturnsNullForUnknown() {
        MessageStore store = new MessageStore();
        
        assertNull(store.findMessageByPacketId(999));
    }

    @Test
    void clearRemovesAllMessages() {
        MessageStore store = new MessageStore();
        
        store.addMessage(createMessage("channel", 0, 1));
        store.addDirectMessage(createMessage("dm", 0, 2), "!peer");
        
        store.clear();
        
        assertTrue(store.getAllChannelMessages().isEmpty());
        assertTrue(store.getAllDirectMessages().isEmpty());
    }

    @Test
    void clearPreservesPendingAcks() {
        MessageStore store = new MessageStore();
        
        MeshMessage msg = createMessage("pending", 0, 900);
        store.registerPendingAck(900, msg);
        
        store.clear();
        
        assertEquals(1, store.getPendingAcks().size());
    }

    @Test
    void fireMessageListenersNotifiesListeners() {
        MessageStore store = new MessageStore();
        
        boolean[] notified = {false};
        store.addMessageListener(() -> notified[0] = true);
        
        MeshMessage msg = createMessage("test", 0, 1);
        store.addMessage(msg);
        
        assertTrue(notified[0]);
    }

    @Test
    void fireMessageChangeNotifiesDetailedListeners() {
        MessageStore store = new MessageStore();
        MessageChangeEvent[] captured = {null};
        MeshMessage msg = createMessage("detail", 0, 77);
        MessageChangeEvent event = MessageChangeEvent.newMessage("channel", "0", "!00000001", msg);

        store.addMessageChangeListener(change -> captured[0] = change);
        store.fireMessageChange(event);

        assertEquals(MessageChangeEvent.Kind.NEW_MESSAGE, captured[0].kind());
        assertEquals("channel", captured[0].chatType());
        assertEquals("0", captured[0].chatKey());
        assertEquals(msg, captured[0].message());
    }

    @Test
    void removeMessageChangeListenerStopsDetailedNotifications() {
        MessageStore store = new MessageStore();
        boolean[] notified = {false};
        Consumer<MessageChangeEvent> listener = ignored -> notified[0] = true;

        store.addMessageChangeListener(listener);
        store.removeMessageChangeListener(listener);
        store.fireMessageChange(MessageChangeEvent.unknown());

        assertFalse(notified[0]);
    }
}
