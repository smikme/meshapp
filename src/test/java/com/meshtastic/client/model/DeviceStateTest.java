package com.meshtastic.client.model;

import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.ModuleConfigProtos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DeviceStateTest {

    private DeviceState state;

    @BeforeEach
    void setUp() {
        state = new DeviceState();
    }

    @AfterEach
    void tearDown() {
        state.shutdown();
    }

    // ═══════════════════════════════════════════════════════════
    //  Хелпер
    // ═══════════════════════════════════════════════════════════

    private static MeshMessage createMessage(int channelIndex, int packetId, String text) {
        MeshMessage msg = new MeshMessage("!00000001", "!00000002", channelIndex, text,
                System.currentTimeMillis() / 1000, false);
        msg.setPacketId(packetId);
        return msg;
    }

    // ═══════════════════════════════════════════════════════════
    //  Сообщения: добавление и дедупликация
    // ═══════════════════════════════════════════════════════════

    @Test
    void testAddMessageStoresInCorrectChannel() {
        MeshMessage msg = createMessage(0, 100, "Hello");
        state.addMessage(msg);

        assertEquals(1, state.getMessages(0).size(), "Message should be in channel 0");
        assertEquals(0, state.getMessages(1).size(), "Channel 1 should be empty");
    }

    @Test
    void testAddMessageDeduplicatesByPacketId() {
        MeshMessage msg1 = createMessage(0, 100, "First");
        MeshMessage msg2 = createMessage(0, 100, "Duplicate");

        state.addMessage(msg1);
        state.addMessage(msg2);

        assertEquals(1, state.getMessages(0).size(),
                "Duplicate packetId should be rejected");
        assertEquals("First", state.getMessages(0).get(0).getText(),
                "Original message should be kept");
    }

    @Test
    void testAddMessageAllowsPacketIdZeroDuplicates() {
        MeshMessage msg1 = createMessage(0, 0, "First");
        MeshMessage msg2 = createMessage(0, 0, "Second");

        state.addMessage(msg1);
        state.addMessage(msg2);

        assertEquals(2, state.getMessages(0).size(),
                "packetId=0 should bypass deduplication");
    }

    @Test
    void testAddMessageFiresListeners() {
        AtomicBoolean fired = new AtomicBoolean(false);
        state.addMessageListener(() -> fired.set(true));

        state.addMessage(createMessage(0, 100, "Hello"));

        assertTrue(fired.get(), "Message listener should be fired");
    }

    // ═══════════════════════════════════════════════════════════
    //  Direct Messages
    // ═══════════════════════════════════════════════════════════

    @Test
    void testAddDirectMessageStoresInCorrectPeer() {
        MeshMessage msg = createMessage(0, 200, "DM");
        state.addDirectMessage(msg, "!0000002a");

        assertEquals(1, state.getDirectMessages("!0000002a").size(), "DM should be in peer !0000002a list");
        assertEquals(0, state.getDirectMessages("!00000063").size(), "Peer !00000063 should be empty");
    }

    @Test
    void testAddDirectMessageDeduplicatesByPacketId() {
        MeshMessage msg1 = createMessage(0, 200, "First DM");
        MeshMessage msg2 = createMessage(0, 200, "Duplicate DM");

        state.addDirectMessage(msg1, "!0000002a");
        state.addDirectMessage(msg2, "!0000002a");

        assertEquals(1, state.getDirectMessages("!0000002a").size(),
                "Duplicate DM packetId should be rejected");
    }

    // ═══════════════════════════════════════════════════════════
    //  findMessageByPacketId
    // ═══════════════════════════════════════════════════════════

    @Test
    void testFindMessageByPacketIdInChannelMessages() {
        MeshMessage msg = createMessage(0, 300, "Findable");
        state.addMessage(msg);

        MeshMessage found = state.findMessageByPacketId(300);

        assertNotNull(found);
        assertEquals("Findable", found.getText());
    }

    @Test
    void testFindMessageByPacketIdInDirectMessages() {
        MeshMessage msg = createMessage(0, 400, "Findable DM");
        state.addDirectMessage(msg, "!0000002a");

        MeshMessage found = state.findMessageByPacketId(400);

        assertNotNull(found);
        assertEquals("Findable DM", found.getText());
    }

    @Test
    void testFindMessageByPacketIdReturnsNullForZero() {
        state.addMessage(createMessage(0, 0, "Zero ID"));

        MeshMessage found = state.findMessageByPacketId(0);

        assertNull(found, "packetId=0 should return null (early return)");
    }

    @Test
    void testFindMessageByPacketIdReturnsNullForUnknown() {
        // DeviceState пустой — in-memory поиск ничего не найдёт,
        // DB fallback вернёт null (try/catch обрабатывает)
        MeshMessage found = state.findMessageByPacketId(999);

        assertNull(found, "Unknown packetId should return null");
    }

    // ═══════════════════════════════════════════════════════════
    //  Node DB
    // ═══════════════════════════════════════════════════════════

    @Test
    void testGetOrCreateNodeCreatesNew() {
        NodeData node = state.getOrCreateNode(123);

        assertNotNull(node);
        assertEquals(123, node.getNodeNum());
        assertEquals(1, state.getNodeDb().size());
    }

    @Test
    void testGetOrCreateNodeReturnsExisting() {
        NodeData first = state.getOrCreateNode(123);
        first.setLongName("TestNode");

        NodeData second = state.getOrCreateNode(123);

        assertSame(first, second, "Should return the same instance");
        assertEquals("TestNode", second.getLongName());
        assertEquals(1, state.getNodeDb().size(), "Should not create duplicate");
    }

    // ═══════════════════════════════════════════════════════════
    //  clear()
    // ═══════════════════════════════════════════════════════════

    @Test
    void testClearWipesAllState() {
        // Заполняем всё
        state.setMyNodeNum(42);
        state.getOrCreateNode(1);
        state.addChannel(ChannelProtos.Channel.getDefaultInstance());
        state.addConfig(ConfigProtos.Config.getDefaultInstance());
        state.addModuleConfig(ModuleConfigProtos.ModuleConfig.getDefaultInstance());
        state.addMessage(createMessage(0, 500, "msg"));
        state.addDirectMessage(createMessage(0, 501, "dm"), "!0000000a");
        state.registerPendingAck(502, createMessage(0, 502, "ack"));
        state.addTelemetryEntry(new TelemetryEntry(System.currentTimeMillis() / 1000, "!00000001"));

        state.clear();

        assertEquals(0, state.getMyNodeNum());
        assertTrue(state.getNodeDb().isEmpty());
        assertTrue(state.getChannels().isEmpty());
        assertTrue(state.getConfigs().isEmpty());
        assertTrue(state.getModuleConfigs().isEmpty());
        assertEquals(0, state.getMessages(0).size());
        assertEquals(0, state.getDirectMessages("!0000000a").size());
        assertNull(state.resolvePendingAck(502), "Pending acks should be cleared");
        assertTrue(state.getTelemetryHistory().isEmpty());
    }

    // ═══════════════════════════════════════════════════════════
    //  Listeners: add / remove / fire
    // ═══════════════════════════════════════════════════════════

    @Test
    void testListenerAddRemoveFire() {
        // --- Message listeners ---
        AtomicInteger msgCount = new AtomicInteger(0);
        Runnable msgListener = msgCount::incrementAndGet;

        state.addMessageListener(msgListener);
        state.fireMessageListeners();
        assertEquals(1, msgCount.get(), "Message listener should fire");

        state.removeMessageListener(msgListener);
        state.fireMessageListeners();
        assertEquals(1, msgCount.get(), "Removed message listener should not fire again");

        // --- Node update listeners ---
        AtomicInteger nodeCount = new AtomicInteger(0);
        java.util.function.IntConsumer nodeListener = n -> nodeCount.incrementAndGet();

        state.addNodeUpdateListener(nodeListener);
        state.fireNodeUpdateListeners(42);
        assertEquals(1, nodeCount.get(), "Node listener should fire");

        state.removeNodeUpdateListener(nodeListener);
        state.fireNodeUpdateListeners(42);
        assertEquals(1, nodeCount.get(), "Removed node listener should not fire again");

        // --- Telemetry listeners ---
        AtomicInteger telCount = new AtomicInteger(0);
        Runnable telListener = telCount::incrementAndGet;

        state.addTelemetryListener(telListener);
        state.fireTelemetryListeners();
        assertEquals(1, telCount.get(), "Telemetry listener should fire");

        state.removeTelemetryListener(telListener);
        state.fireTelemetryListeners();
        assertEquals(1, telCount.get(), "Removed telemetry listener should not fire again");
    }

    // ═══════════════════════════════════════════════════════════
    //  Pending ACKs
    // ═══════════════════════════════════════════════════════════

    @Test
    void testRegisterAndResolvePendingAck() {
        MeshMessage msg = createMessage(0, 700, "test");
        state.registerPendingAck(700, msg);

        MeshMessage resolved = state.resolvePendingAck(700);
        assertSame(msg, resolved, "Should return the same MeshMessage instance");

        MeshMessage secondResolve = state.resolvePendingAck(700);
        assertNull(secondResolve, "Second resolve should return null (already removed)");
    }

    @Test
    void testFailAllPendingAcksMarksAllAsFailed() {
        MeshMessage msg1 = createMessage(0, 600, "msg1");
        msg1.setStatus(MeshMessage.DeliveryStatus.SENDING);
        MeshMessage msg2 = createMessage(0, 601, "msg2");
        msg2.setStatus(MeshMessage.DeliveryStatus.SENDING);

        state.registerPendingAck(600, msg1);
        state.registerPendingAck(601, msg2);

        state.failAllPendingAcks("DISCONNECTED");

        assertEquals(MeshMessage.DeliveryStatus.FAILED, msg1.getStatus());
        assertEquals("DISCONNECTED", msg1.getErrorReason());
        assertEquals(MeshMessage.DeliveryStatus.FAILED, msg2.getStatus());
        assertEquals("DISCONNECTED", msg2.getErrorReason());
        assertNull(state.resolvePendingAck(600), "Pending acks should be empty after failAll");
        assertNull(state.resolvePendingAck(601), "Pending acks should be empty after failAll");
    }

    @Test
    void testFailAllPendingAcksEmptyMapDoesNotFireListeners() {
        AtomicBoolean listenerFired = new AtomicBoolean(false);
        state.addMessageListener(() -> listenerFired.set(true));

        state.failAllPendingAcks("DISCONNECTED");

        assertFalse(listenerFired.get(),
                "No listeners should fire when there are no pending acks");
    }
}
