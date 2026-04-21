package com.meshtastic.client.service;

import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.connection.MeshtasticConnection;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.AdminProtos;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageServiceNodeInfoExchangeTest {

    @Test
    void exchangeNodeUserInfoSendsLocalUserThenRequestsRemoteUser() throws Exception {
        int myNodeNum = 0x10203040;
        int targetNodeNum = 0x55667788;

        DeviceState state = new DeviceState();
        state.setMyNodeNum(myNodeNum);
        NodeData myNode = state.getOrCreateNode(myNodeNum);
        myNode.setNodeId("!10203040");
        myNode.setLongName("Mesh Owner");
        myNode.setShortName("MO");
        myNode.setRole("ROUTER");
        myNode.setHwModel("TBEAM");
        myNode.setUnmessagable(false);
        state.addConfig(ConfigProtos.Config.newBuilder()
                .setSecurity(ConfigProtos.Config.SecurityConfig.newBuilder()
                        .setPublicKey(com.google.protobuf.ByteString.copyFrom(new byte[] {1, 2, 3, 4}))
                        .build())
                .build());

        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = new ProtocolHandler(connection);
        try {
            MessageService.exchangeNodeUserInfo(handler, state, targetNodeNum);

            List<byte[]> sentFrames = connection.awaitSentFrames(2);
            assertEquals(2, sentFrames.size(), "exchange should send user info and follow-up request");

            MeshProtos.ToRadio sharedUser = parseToRadio(sentFrames.get(0));
            MeshProtos.MeshPacket sharedPacket = sharedUser.getPacket();
            assertEquals(myNodeNum, sharedPacket.getFrom());
            assertEquals(targetNodeNum, sharedPacket.getTo());
            assertTrue(sharedPacket.getWantAck());
            assertEquals(Portnums.PortNum.NODEINFO_APP, sharedPacket.getDecoded().getPortnum());
            assertFalse(sharedPacket.getDecoded().getWantResponse());

            MeshProtos.User sharedPayload = MeshProtos.User.parseFrom(sharedPacket.getDecoded().getPayload());
            assertEquals("!10203040", sharedPayload.getId());
            assertEquals("Mesh Owner", sharedPayload.getLongName());
            assertEquals("MO", sharedPayload.getShortName());
            assertEquals(ConfigProtos.Config.DeviceConfig.Role.ROUTER, sharedPayload.getRole());
            assertEquals(MeshProtos.HardwareModel.TBEAM, sharedPayload.getHwModel());
            assertArrayEquals(new byte[] {1, 2, 3, 4}, sharedPayload.getPublicKey().toByteArray());
            assertTrue(sharedPayload.hasIsUnmessagable());
            assertFalse(sharedPayload.getIsUnmessagable());

            MeshProtos.ToRadio requestUser = parseToRadio(sentFrames.get(1));
            MeshProtos.MeshPacket requestPacket = requestUser.getPacket();
            assertEquals(myNodeNum, requestPacket.getFrom());
            assertEquals(targetNodeNum, requestPacket.getTo());
            assertEquals(Portnums.PortNum.NODEINFO_APP, requestPacket.getDecoded().getPortnum());
            assertTrue(requestPacket.getDecoded().getWantResponse());
            assertTrue(requestPacket.getDecoded().getPayload().isEmpty());
        } finally {
            handler.shutdown();
        }
    }

    @Test
    void exchangeNodeUserInfoFallsBackToDeviceMetadataForRoleAndHwModel() throws Exception {
        int myNodeNum = 0x10203040;
        int targetNodeNum = 0x55667788;

        DeviceState state = new DeviceState();
        state.setMyNodeNum(myNodeNum);
        NodeData myNode = state.getOrCreateNode(myNodeNum);
        myNode.setNodeId("!10203040");
        myNode.setLongName("Mesh Owner");
        myNode.setShortName("MO");
        state.setDeviceMetadata(MeshProtos.DeviceMetadata.newBuilder()
                .setRole(ConfigProtos.Config.DeviceConfig.Role.CLIENT_BASE)
                .setHwModel(MeshProtos.HardwareModel.RAK4631)
                .build());
        state.addConfig(ConfigProtos.Config.newBuilder()
                .setSecurity(ConfigProtos.Config.SecurityConfig.newBuilder()
                        .setPublicKey(com.google.protobuf.ByteString.copyFrom(new byte[] {1, 2, 3, 4}))
                        .build())
                .build());

        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = new ProtocolHandler(connection);
        try {
            MessageService.exchangeNodeUserInfo(handler, state, targetNodeNum);

            List<byte[]> sentFrames = connection.awaitSentFrames(2);
            assertEquals(2, sentFrames.size());

            MeshProtos.ToRadio sharedUser = parseToRadio(sentFrames.get(0));
            MeshProtos.User sharedPayload = MeshProtos.User.parseFrom(sharedUser.getPacket().getDecoded().getPayload());
            assertEquals(ConfigProtos.Config.DeviceConfig.Role.CLIENT_BASE, sharedPayload.getRole());
            assertEquals(MeshProtos.HardwareModel.RAK4631, sharedPayload.getHwModel());
        } finally {
            handler.shutdown();
        }
    }

    @Test
    void exchangeNodeUserInfoRequestsOwnerInfoBeforeSharingWhenLocalUserUnavailable() throws Exception {
        int myNodeNum = 0x0A0B0C0D;
        int targetNodeNum = 0x01020304;

        DeviceState state = new DeviceState();
        state.setMyNodeNum(myNodeNum);
        state.addConfig(ConfigProtos.Config.newBuilder()
                .setSecurity(ConfigProtos.Config.SecurityConfig.newBuilder()
                        .setPublicKey(com.google.protobuf.ByteString.copyFrom(new byte[] {9, 8, 7}))
                        .build())
                .build());

        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = new ProtocolHandler(connection);
        try {
            MessageService.exchangeNodeUserInfo(handler, state, targetNodeNum);

            List<byte[]> sentFrames = connection.awaitSentFrames(1);
            assertEquals(1, sentFrames.size(), "without local user data exchange should start with owner-info request");

            MeshProtos.ToRadio ownerRequest = parseToRadio(sentFrames.get(0));
            MeshProtos.MeshPacket ownerPacket = ownerRequest.getPacket();
            assertEquals(myNodeNum, ownerPacket.getTo());
            assertEquals(Portnums.PortNum.ADMIN_APP, ownerPacket.getDecoded().getPortnum());
            assertTrue(ownerPacket.getDecoded().getWantResponse());

            AdminProtos.AdminMessage adminMessage = AdminProtos.AdminMessage.parseFrom(ownerPacket.getDecoded().getPayload());
            assertTrue(adminMessage.getGetOwnerRequest());

            state.setOwnerInfo(MeshProtos.User.newBuilder()
                    .setId("!0a0b0c0d")
                    .setLongName("Recovered Owner")
                    .setShortName("RO")
                    .build());
            state.fireOwnerInfoListeners();

            sentFrames = connection.awaitSentFrames(3);
            assertEquals(3, sentFrames.size(), "owner-info response should resume user exchange");

            MeshProtos.ToRadio sharedUser = parseToRadio(sentFrames.get(1));
            MeshProtos.MeshPacket sharedPacket = sharedUser.getPacket();
            assertEquals(targetNodeNum, sharedPacket.getTo());
            assertEquals(Portnums.PortNum.NODEINFO_APP, sharedPacket.getDecoded().getPortnum());
            assertFalse(sharedPacket.getDecoded().getWantResponse());

            MeshProtos.User sharedPayload = MeshProtos.User.parseFrom(sharedPacket.getDecoded().getPayload());
            assertEquals("Recovered Owner", sharedPayload.getLongName());
            assertEquals("RO", sharedPayload.getShortName());
            assertArrayEquals(new byte[] {9, 8, 7}, sharedPayload.getPublicKey().toByteArray());

            MeshProtos.ToRadio requestUser = parseToRadio(sentFrames.get(2));
            MeshProtos.MeshPacket requestPacket = requestUser.getPacket();
            assertEquals(targetNodeNum, requestPacket.getTo());
            assertEquals(Portnums.PortNum.NODEINFO_APP, requestPacket.getDecoded().getPortnum());
            assertTrue(requestPacket.getDecoded().getWantResponse());
            assertTrue(requestPacket.getDecoded().getPayload().isEmpty());
        } finally {
            handler.shutdown();
        }
    }

    @Test
    void sendDirectMessageFallsBackToHexNodeIdWhenCurrentUserIdChanged() throws Exception {
        int myNodeNum = 0x10203040;
        int targetNodeNum = 0x55667788;

        DeviceState state = new DeviceState();
        state.setMyNodeNum(myNodeNum);
        NodeData myNode = state.getOrCreateNode(myNodeNum);
        myNode.setNodeId("!10203040");

        NodeData peerNode = state.getOrCreateNode(targetNodeNum);
        peerNode.setNodeId("alice-custom-id");

        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = new ProtocolHandler(connection);
        try {
            MeshMessage sent = MessageService.sendDirectMessage(handler, state, "!55667788", "test dm", 0);

            List<byte[]> sentFrames = connection.awaitSentFrames(1);
            assertEquals(1, sentFrames.size(), "DM should still be sent using fallback hex nodeId");
            assertEquals("!55667788", sent.getToNodeId());

            MeshProtos.ToRadio directMessage = parseToRadio(sentFrames.get(0));
            MeshProtos.MeshPacket packet = directMessage.getPacket();
            assertEquals(myNodeNum, packet.getFrom());
            assertEquals(targetNodeNum, packet.getTo());
            assertEquals(Portnums.PortNum.TEXT_MESSAGE_APP, packet.getDecoded().getPortnum());
            assertEquals("test dm", packet.getDecoded().getPayload().toStringUtf8());
        } finally {
            handler.shutdown();
        }
    }

    @Test
    void sendDirectMessageUsesPeerSecondaryChannelWhenResolvedViaLegacyHexId() throws Exception {
        int myNodeNum = 0x11111111;
        int targetNodeNum = 0x22222222;

        DeviceState state = new DeviceState();
        state.setMyNodeNum(myNodeNum);
        NodeData myNode = state.getOrCreateNode(myNodeNum);
        myNode.setNodeId("!11111111");
        myNode.setLongName("Base");
        myNode.setShortName("BAS");
        myNode.setRole("TAK_TRACKER");
        myNode.setHwModel("RAK4631");
        myNode.setUnmessagable(false);
        state.addConfig(ConfigProtos.Config.newBuilder()
                .setSecurity(ConfigProtos.Config.SecurityConfig.newBuilder()
                        .setPublicKey(com.google.protobuf.ByteString.copyFrom(new byte[] {1, 2, 3, 4}))
                        .build())
                .build());

        NodeData peerNode = state.getOrCreateNode(targetNodeNum);
        peerNode.setNodeId("custom-peer-id");
        peerNode.setChannel(3);

        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = new ProtocolHandler(connection);
        try {
            MeshMessage sent = MessageService.sendDirectMessage(handler, state, "!22222222", "secondary dm", 0);

            assertEquals("!22222222", sent.getToNodeId());
            assertEquals(3, sent.getChannelIndex());
            List<byte[]> sentFrames = connection.awaitSentFrames(1);
            assertEquals(1, sentFrames.size());

            MeshProtos.ToRadio directMessage = parseToRadio(sentFrames.get(0));
            MeshProtos.MeshPacket packet = directMessage.getPacket();
            assertEquals(targetNodeNum, packet.getTo());
            assertEquals(3, packet.getChannel());
        } finally {
            handler.shutdown();
        }
    }

    @Test
    void sendDirectMessageUsesRecentSecondaryDmChannelFromResolvedPeerId() throws Exception {
        int myNodeNum = 0x11111111;
        int targetNodeNum = 0x33333333;

        DeviceState state = new DeviceState();
        state.setMyNodeNum(myNodeNum);
        NodeData myNode = state.getOrCreateNode(myNodeNum);
        myNode.setNodeId("!11111111");
        myNode.setLongName("Base");
        myNode.setShortName("BAS");
        myNode.setRole("TAK_TRACKER");
        myNode.setHwModel("RAK4631");
        myNode.setUnmessagable(false);
        state.addConfig(ConfigProtos.Config.newBuilder()
                .setSecurity(ConfigProtos.Config.SecurityConfig.newBuilder()
                        .setPublicKey(com.google.protobuf.ByteString.copyFrom(new byte[] {1, 2, 3, 4}))
                        .build())
                .build());

        NodeData peerNode = state.getOrCreateNode(targetNodeNum);
        peerNode.setNodeId("current-peer-id");

        MeshMessage previousIncoming = new MeshMessage("current-peer-id", "!11111111", 4, "prev dm", 12345, false);
        state.addDirectMessage(previousIncoming, "current-peer-id");

        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = new ProtocolHandler(connection);
        try {
            MeshMessage sent = MessageService.sendDirectMessage(handler, state, "!33333333", "reply dm", 0);

            assertEquals("!33333333", sent.getToNodeId());
            assertEquals(4, sent.getChannelIndex());
            List<byte[]> sentFrames = connection.awaitSentFrames(1);
            assertEquals(1, sentFrames.size());

            MeshProtos.ToRadio directMessage = parseToRadio(sentFrames.get(0));
            MeshProtos.MeshPacket packet = directMessage.getPacket();
            assertEquals(targetNodeNum, packet.getTo());
            assertEquals(4, packet.getChannel());
        } finally {
            handler.shutdown();
        }
    }

    @Test
    void sendDirectMessageUsesLegacyCachedSecondaryChannelWhenPeerIdChangedAndHistoryDeleted() throws Exception {
        int myNodeNum = 0x11111111;
        int targetNodeNum = 0x77777777;
        String legacyPeerNodeId = "!77777777";

        DeviceState state = new DeviceState();
        state.setMyNodeNum(myNodeNum);
        NodeData myNode = state.getOrCreateNode(myNodeNum);
        myNode.setNodeId("!11111111");
        state.addChannel(ChannelProtos.Channel.newBuilder()
                .setIndex(0)
                .setRole(ChannelProtos.Channel.Role.PRIMARY)
                .build());
        state.addChannel(ChannelProtos.Channel.newBuilder()
                .setIndex(4)
                .setRole(ChannelProtos.Channel.Role.SECONDARY)
                .build());

        NodeData peerNode = state.getOrCreateNode(targetNodeNum);
        peerNode.setNodeId("current-peer-id");

        NodeData legacyCachedPeer = new NodeData(targetNodeNum);
        legacyCachedPeer.setNodeId(legacyPeerNodeId);
        legacyCachedPeer.setChannel(4);
        NodeCacheService.getInstance().update(legacyCachedPeer);

        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = new ProtocolHandler(connection);
        try {
            MeshMessage sent = MessageService.sendDirectMessage(handler, state, legacyPeerNodeId, "cached secondary dm", 0);

            assertEquals(4, sent.getChannelIndex());
            List<byte[]> sentFrames = connection.awaitSentFrames(1);
            assertEquals(1, sentFrames.size());

            MeshProtos.ToRadio directMessage = parseToRadio(sentFrames.get(0));
            MeshProtos.MeshPacket packet = directMessage.getPacket();
            assertEquals(targetNodeNum, packet.getTo());
            assertEquals(4, packet.getChannel());
            assertEquals(4, peerNode.getChannel());

            NodeData currentCachedPeer = NodeCacheService.getInstance().get("current-peer-id");
            assertNotNull(currentCachedPeer);
            assertEquals(4, currentCachedPeer.getChannel());
        } finally {
            handler.shutdown();
        }
    }

    @Test
    void sendDirectMessageIgnoresStaleSecondaryChannelMissingFromLocalRadio() throws Exception {
        int myNodeNum = 0x11111111;
        int targetNodeNum = 0x44444444;

        DeviceState state = new DeviceState();
        state.setMyNodeNum(myNodeNum);
        NodeData myNode = state.getOrCreateNode(myNodeNum);
        myNode.setNodeId("!11111111");
        myNode.setLongName("Base");
        myNode.setShortName("BAS");
        myNode.setRole("TAK_TRACKER");
        myNode.setHwModel("RAK4631");
        myNode.setUnmessagable(false);
        state.addConfig(ConfigProtos.Config.newBuilder()
                .setSecurity(ConfigProtos.Config.SecurityConfig.newBuilder()
                        .setPublicKey(com.google.protobuf.ByteString.copyFrom(new byte[] {1, 2, 3, 4}))
                        .build())
                .build());
        state.addChannel(ChannelProtos.Channel.newBuilder()
                .setIndex(0)
                .setRole(ChannelProtos.Channel.Role.PRIMARY)
                .build());

        NodeData peerNode = state.getOrCreateNode(targetNodeNum);
        peerNode.setNodeId("current-peer-id");
        peerNode.setChannel(5);

        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = new ProtocolHandler(connection);
        try {
            MeshMessage sent = MessageService.sendDirectMessage(handler, state, "!44444444", "dm on primary", 0);

            assertEquals(0, sent.getChannelIndex());
            List<byte[]> sentFrames = connection.awaitSentFrames(1);
            assertEquals(1, sentFrames.size());

            MeshProtos.ToRadio directMessage = parseToRadio(sentFrames.get(0));
            MeshProtos.MeshPacket packet = directMessage.getPacket();
            assertEquals(targetNodeNum, packet.getTo());
            assertEquals(0, packet.getChannel());
        } finally {
            handler.shutdown();
        }
    }

    @Test
    void sendDirectMessageUsesPkiWhenPeerHasPublicKey() throws Exception {
        int myNodeNum = 0x11111111;
        int targetNodeNum = 0x55555555;
        String peerNodeId = "!55555555";

        DeviceState state = new DeviceState();
        state.setMyNodeNum(myNodeNum);
        NodeData myNode = state.getOrCreateNode(myNodeNum);
        myNode.setNodeId("!11111111");
        myNode.setLongName("Base");
        myNode.setShortName("BAS");
        myNode.setRole("TAK_TRACKER");
        myNode.setHwModel("RAK4631");
        myNode.setUnmessagable(false);
        state.addConfig(ConfigProtos.Config.newBuilder()
                .setSecurity(ConfigProtos.Config.SecurityConfig.newBuilder()
                        .setPublicKey(com.google.protobuf.ByteString.copyFrom(new byte[] {1, 2, 3, 4}))
                        .build())
                .build());

        NodeData peerNode = state.getOrCreateNode(targetNodeNum);
        peerNode.setNodeId(peerNodeId);
        peerNode.setPublicKey(new byte[] {7, 8, 9});
        peerNode.setChannel(3);

        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = new ProtocolHandler(connection);
        try {
            MeshMessage sent = MessageService.sendDirectMessage(handler, state, peerNodeId, "retry over pki", 0);

            assertEquals(0, sent.getChannelIndex());
            List<byte[]> sentFrames = connection.awaitSentFrames(2);
            assertEquals(2, sentFrames.size());

            MeshProtos.ToRadio sharedUser = parseToRadio(sentFrames.get(0));
            MeshProtos.MeshPacket sharedUserPacket = sharedUser.getPacket();
            assertEquals(Portnums.PortNum.NODEINFO_APP, sharedUserPacket.getDecoded().getPortnum());
            MeshProtos.User sharedPayload = MeshProtos.User.parseFrom(sharedUserPacket.getDecoded().getPayload());
            assertEquals("!11111111", sharedPayload.getId());
            assertEquals(ConfigProtos.Config.DeviceConfig.Role.TAK_TRACKER, sharedPayload.getRole());
            assertEquals(MeshProtos.HardwareModel.RAK4631, sharedPayload.getHwModel());
            assertArrayEquals(new byte[] {1, 2, 3, 4}, sharedPayload.getPublicKey().toByteArray());
            assertTrue(sharedPayload.hasIsUnmessagable());
            assertFalse(sharedPayload.getIsUnmessagable());

            MeshProtos.ToRadio seedContact = parseToRadio(sentFrames.get(1));
            MeshProtos.MeshPacket seedPacket = seedContact.getPacket();
            assertEquals(Portnums.PortNum.ADMIN_APP, seedPacket.getDecoded().getPortnum());
            AdminProtos.AdminMessage adminMessage = AdminProtos.AdminMessage.parseFrom(seedPacket.getDecoded().getPayload());
            assertEquals(targetNodeNum, adminMessage.getAddContact().getNodeNum());
            assertArrayEquals(new byte[] {7, 8, 9},
                    adminMessage.getAddContact().getUser().getPublicKey().toByteArray());

            assertTrue(state.completePendingPacketAck(sharedUserPacket.getId(), MeshProtos.Routing.Error.NONE));
            sentFrames = connection.awaitSentFrames(3);
            assertEquals(3, sentFrames.size());

            MeshProtos.ToRadio directMessage = parseToRadio(sentFrames.get(2));
            MeshProtos.MeshPacket packet = directMessage.getPacket();
            assertEquals(targetNodeNum, packet.getTo());
            assertTrue(packet.getPkiEncrypted());
            assertEquals(0, packet.getChannel());
            assertTrue(packet.getPublicKey().isEmpty());
        } finally {
            handler.shutdown();
        }
    }

    @Test
    void sendDirectMessageSkipsPeersMarkedUnmessagable() throws Exception {
        int myNodeNum = 0x11111111;
        int targetNodeNum = 0x66666666;
        String peerNodeId = "!66666666";

        DeviceState state = new DeviceState();
        state.setMyNodeNum(myNodeNum);
        state.getOrCreateNode(myNodeNum).setNodeId("!11111111");

        NodeData peerNode = state.getOrCreateNode(targetNodeNum);
        peerNode.setNodeId(peerNodeId);
        peerNode.setPublicKey(new byte[] {4, 5, 6});
        peerNode.setUnmessagable(true);

        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = new ProtocolHandler(connection);
        try {
            MeshMessage sent = MessageService.sendDirectMessage(handler, state, peerNodeId, "should stay local", 0);

            assertNull(sent);
            assertTrue(connection.snapshotSentFrames().isEmpty());
        } finally {
            handler.shutdown();
        }
    }

    private static MeshProtos.ToRadio parseToRadio(byte[] frame) throws Exception {
        int payloadLength = ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
        byte[] payload = Arrays.copyOfRange(frame, 4, 4 + payloadLength);
        return MeshProtos.ToRadio.parseFrom(payload);
    }

    private static final class RecordingConnection implements MeshtasticConnection {

        private final List<byte[]> sentFrames = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void connect() throws ConnectionException {
            // no-op for tests
        }

        @Override
        public void disconnect() {
            // no-op for tests
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void sendBytes(byte[] data) {
            sentFrames.add(Arrays.copyOf(data, data.length));
        }

        @Override
        public void sendBytes(byte[] data, boolean expectResponseAfterWrite) {
            sendBytes(data);
        }

        @Override
        public void setDataListener(Consumer<byte[]> listener) {
            // no-op for tests
        }

        @Override
        public void setConnectionListener(ConnectionListener listener) {
            // no-op for tests
        }

        List<byte[]> awaitSentFrames(int expectedCount) throws InterruptedException {
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            List<byte[]> snapshot = snapshotSentFrames();
            while (snapshot.size() < expectedCount && System.nanoTime() < deadlineNanos) {
                Thread.sleep(10);
                snapshot = snapshotSentFrames();
            }
            if (snapshot.size() < expectedCount) {
                throw new AssertionError("Timed out waiting for " + expectedCount
                        + " outbound frames, got " + snapshot.size());
            }
            return snapshot;
        }

        List<byte[]> snapshotSentFrames() {
            synchronized (sentFrames) {
                return new ArrayList<>(sentFrames);
            }
        }
    }
}
