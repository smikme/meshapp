package com.meshtastic.client.model;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.service.MessageDbService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.MeshProtos;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceStateTest {

    @TempDir
    Path tempHome;

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
        MessageDbService.getInstance();
    }

    @AfterEach
    void tearDown() {
        TestEnvironmentSupport.resetSingletons();
    }

    private DeviceState createDeviceStateWithOwner() {
        DeviceState state = new DeviceState();
        MeshProtos.User owner = MeshProtos.User.newBuilder()
                .setId("!owner")
                .build();
        state.setOwnerInfo(owner);
        return state;
    }

    @Test
    void addMessageDeduplicatesByPacketIdAndKeepsLatest100() {
        DeviceState state = createDeviceStateWithOwner();

        MeshMessage duplicateA = message("one", 0, 123);
        MeshMessage duplicateB = message("two", 0, 123);
        state.addMessage(duplicateA);
        state.addMessage(duplicateB);

        for (int i = 1; i <= 100; i++) {
            state.addMessage(message("msg-" + i, 0, 1000 + i));
        }

        assertEquals(100, state.getMessages(0).size());
        assertEquals("msg-1", state.getMessages(0).getFirst().getText());
        assertEquals("msg-100", state.getMessages(0).getLast().getText());
        assertTrue(state.getMessages(0).stream().noneMatch(msg -> msg.getPacketId() == 123));

        state.shutdown();
    }

    @Test
    void failAllPendingAcksMarksMessagesAsFailedAndClearsQueue() {
        DeviceState state = createDeviceStateWithOwner();
        MeshMessage outgoing = message("pending", 0, 777);
        outgoing.setStatus(MeshMessage.DeliveryStatus.SENDING);
        state.addMessage(outgoing);

        state.registerPendingAck(777, outgoing);
        state.failAllPendingAcks("DISCONNECTED");

        assertEquals(MeshMessage.DeliveryStatus.FAILED, outgoing.getStatus());
        assertEquals("DISCONNECTED", outgoing.getErrorReason());
        assertNull(state.resolvePendingAck(777));

        MeshMessage persisted = MessageDbService.getInstance().findByPacketId(777);
        assertNotNull(persisted);
        assertEquals(MeshMessage.DeliveryStatus.FAILED, persisted.getStatus());
        assertEquals("DISCONNECTED", persisted.getErrorReason());

        state.shutdown();
    }

    @Test
    void clearResetsRuntimeStateButKeepsPendingFixedPosition() {
        DeviceState state = createDeviceStateWithOwner();
        state.setMyNodeNum(42);
        state.getOrCreateNode(42).setLongName("Owner");
        state.addMessage(message("channel", 0, 1));
        state.addDirectMessage(message("dm", 0, 2), "!peer");
        state.setPendingFixedPosition(55.75, 37.61, 200);

        assertTrue(state.hasPendingFixedPosition());

        state.clear();

        assertEquals(0, state.getMyNodeNum());
        assertTrue(state.getNodeDb().isEmpty());
        assertTrue(state.getMessages(0).isEmpty());
        assertTrue(state.getDirectMessages("!peer").isEmpty());
        assertFalse(state.isChannelCatalogReady());
        assertTrue(state.hasPendingFixedPosition());
        assertEquals(55.75, state.getPendingFixedLat());
        assertEquals(37.61, state.getPendingFixedLon());
        assertEquals(200, state.getPendingFixedAlt());

        state.shutdown();
    }

    @Test
    void addChannelReplacesExistingChannelWithSameIndex() {
        DeviceState state = createDeviceStateWithOwner();

        state.addChannel(ChannelProtos.Channel.newBuilder()
                .setIndex(2)
                .setRole(ChannelProtos.Channel.Role.SECONDARY)
                .build());
        state.addChannel(ChannelProtos.Channel.newBuilder()
                .setIndex(2)
                .setRole(ChannelProtos.Channel.Role.DISABLED)
                .build());

        assertEquals(1, state.getChannels().size());
        assertEquals(ChannelProtos.Channel.Role.DISABLED, state.getChannels().getFirst().getRole());

        state.shutdown();
    }

    @Test
    void findMessageByPacketIdSearchesMemoryBeforeDatabase() {
        DeviceState state = createDeviceStateWithOwner();
        MeshMessage inMemory = message("memory", 1, 500);
        MeshMessage persisted = message("database", 1, 501);
        state.addMessage(inMemory);
        MessageDbService.getInstance().save(persisted, "channel", "1", "!owner");

        assertSame(inMemory, state.findMessageByPacketId(500));
        assertEquals("database", state.findMessageByPacketId(501).getText());
        assertNull(state.findMessageByPacketId(999999));

        state.shutdown();
    }

    private static MeshMessage message(String text, int channelIndex, int packetId) {
        MeshMessage message = new MeshMessage("!00000001", "!ffffffff", channelIndex, text, 1_700_000_000L, false);
        message.setPacketId(packetId);
        return message;
    }
}