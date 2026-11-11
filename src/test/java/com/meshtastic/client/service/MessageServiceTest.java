package com.meshtastic.client.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.google.protobuf.ByteString;
import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.connection.MeshtasticConnection;
import com.meshtastic.client.logging.UiLogAppender;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.LogEntry;
import com.meshtastic.client.model.MessageReaction;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.utils.OutgoingText;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meshtastic.proto.AdminProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;
import org.meshtastic.proto.Portnums;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.slf4j.LoggerFactory;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class MessageServiceTest {

    @Test
    void compactsChannelRepliesOnWireAndValidatesRetriesBeforeChangingHistory() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x12345678);
        String original = "а".repeat(223);
        state.getOrCreateNode(state.getMyNodeNum()).setNodeId("!12345678");
        try {
            AppPreferences.setCyrillicCompactionEnabled(true);
            MeshMessage message = MessageService.sendChannelMessage(handler, state, 0, original, 42);
            var data = connection.nextPacket().getPacket().getDecoded();
            assertEquals(original, message.getText());
            assertEquals(OutgoingText.byteLength(original), data.getPayload().size());
            assertEquals("a".repeat(223), data.getPayload().toStringUtf8());
            assertEquals(42, data.getReplyId());
            assertEquals(233, data.getSerializedSize());
            long dbId = message.getDbId();
            message.setStatus(MeshMessage.DeliveryStatus.FAILED);
            assertTrue(MessageService.retryMessage(handler, state, message));
            assertEquals(dbId, message.getDbId());
            assertEquals("a".repeat(223), connection.nextPacket().getPacket().getDecoded().getPayload().toStringUtf8());
            message.setStatus(MeshMessage.DeliveryStatus.FAILED);
            int packetId = message.getPacketId();
            byte[] lastPacket = connection.lastSentBytes;
            AppPreferences.setCyrillicCompactionEnabled(false);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> MessageService.retryMessage(handler, state, message));
            assertSame(lastPacket, connection.lastSentBytes);
            assertEquals(packetId, message.getPacketId());
            assertEquals(MeshMessage.DeliveryStatus.FAILED, message.getStatus());
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> MessageService.sendChannelMessage(handler, state, 0, "а".repeat(224), 42, true));
        } finally {
            AppPreferences.setCyrillicCompactionEnabled(false);
            state.shutdown();
        }
    }

    @Test
    void directMessagesUseExplicitClientChoiceAndKeepOriginalText() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x12345678);
        var peer = new com.meshtastic.client.model.NodeData(0x23456789);
        peer.setNodeId("!23456789");
        state.getNodeDb().put(peer.getNodeNum(), peer);
        String text = "Всему свое время";
        state.getOrCreateNode(state.getMyNodeNum()).setNodeId("!12345678");
        var message = MessageService.sendDirectMessage(handler, state, peer.getNodeId(), text, 0, true);
        assertEquals(text, message.getText());
        assertEquals(21, connection.nextPacket().getPacket().getDecoded().getPayload().size());
        message.setStatus(MeshMessage.DeliveryStatus.FAILED);
        assertTrue(MessageService.retryMessage(handler, state, message, true));
        assertEquals(21, connection.nextPacket().getPacket().getDecoded().getPayload().size());
        MessageService.sendDirectMessage(handler, state, peer.getNodeId(), text, 0, false);
        assertEquals(30, connection.nextPacket().getPacket().getDecoded().getPayload().size());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> MessageService.sendDirectMessage(handler, state, peer.getNodeId(), "а".repeat(229), 0, true));
        state.shutdown();
    }

    @Test
    void compactRepliesReserveAuthenticationBytesOnFirstSendAndRetry() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x12345678);
        state.getOrCreateNode(state.getMyNodeNum()).setNodeId("!12345678");
        state.setDeviceMetadata(MeshProtos.DeviceMetadata.newBuilder()
                .setFirmwareVersion("2.8.2.d5f1619").setHasXeddsa(true).build());
        state.addConfig(ConfigProtos.Config.newBuilder().setSecurity(ConfigProtos.Config.SecurityConfig.newBuilder()
                .setPacketSignaturePolicy(ConfigProtos.Config.SecurityConfig.PacketSignaturePolicy.PACKET_SIGNATURE_POLICY_STRICT)).build());
        state.addChannel(org.meshtastic.proto.ChannelProtos.Channel.newBuilder().setIndex(0)
                .setRole(org.meshtastic.proto.ChannelProtos.Channel.Role.PRIMARY)
                .setSettings(org.meshtastic.proto.ChannelProtos.ChannelSettings.newBuilder().setUseAead(true)).build());
        try {
            for (int channel : new int[]{0, 1}) {
                int overhead = channel == 0 ? 12 : 66;
                String text = "а".repeat(223 - overhead);
                var message = MessageService.sendChannelMessage(handler, state, channel, text, 42, true);
                assertEquals(233, connection.nextPacket().getPacket().getDecoded().getSerializedSize() + overhead);
                message.setStatus(MeshMessage.DeliveryStatus.FAILED);
                assertTrue(MessageService.retryMessage(handler, state, message, true));
                assertEquals(233, connection.nextPacket().getPacket().getDecoded().getSerializedSize() + overhead);
                org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                        () -> MessageService.sendChannelMessage(handler, state, channel, text + "а", 42, true));
            }
        } finally { state.shutdown(); }
    }

    @TempDir
    Path tempHome;

    private final List<ProtocolHandler> handlersToShutdown = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
        MessageDbService.getInstance();
    }

    @AfterEach
    void tearDown() {
        for (ProtocolHandler handler : handlersToShutdown) {
            handler.shutdown();
        }
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void unavailableDatabaseDoesNotSendAnInvisibleChannelMessage() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x12345678);
        DatabaseProvider.getConnection().close();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> MessageService.sendChannelMessage(handler, state, 0, "keep this draft", 0));
        assertNull(connection.lastSentBytes, "never transmit a message which failed local persistence");
        assertTrue(state.getMessageStore().getPendingAcks().isEmpty());
    }

    @Test
    void unavailableDatabaseDoesNotSendAnInvisibleDirectMessage() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x12345678);
        var peer = new com.meshtastic.client.model.NodeData(0x23456789);
        peer.setNodeId("!23456789");
        state.getNodeDb().put(peer.getNodeNum(), peer);
        NodeCacheService.getInstance();
        DatabaseProvider.getConnection().close();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> MessageService.sendDirectMessage(handler, state, "!23456789", "keep this draft", 0));
        assertNull(connection.lastSentBytes);
        assertTrue(state.getMessageStore().getPendingAcks().isEmpty());
    }

    @Test
    void requestSessionPasskeyKeepsWantResponseEnabled() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x04c5b420);

        MessageService.requestSessionPasskey(handler, state);

        MeshProtos.ToRadio sent = parseLastToRadio(connection);
        assertTrue(sent.getPacket().getDecoded().getWantResponse());
        AdminProtos.AdminMessage admin =
                AdminProtos.AdminMessage.parseFrom(sent.getPacket().getDecoded().getPayload());
        assertTrue(admin.hasGetConfigRequest());
    }

    @Test
    void requestRingtoneKeepsWantResponseEnabled() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x04c5b420);

        MessageService.requestRingtone(handler, state);

        MeshProtos.ToRadio sent = parseLastToRadio(connection);
        assertTrue(sent.getPacket().getDecoded().getWantResponse());
        AdminProtos.AdminMessage admin =
                AdminProtos.AdminMessage.parseFrom(sent.getPacket().getDecoded().getPayload());
        assertTrue(admin.hasGetRingtoneRequest());
        assertTrue(admin.getGetRingtoneRequest());
    }

    @Test
    void setModuleConfigUsesRoutingAckWithoutAdminResponse() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x04c5b420);
        state.setSessionPasskey(ByteString.copyFromUtf8("passkey"));

        ModuleConfigProtos.ModuleConfig mqttConfig = ModuleConfigProtos.ModuleConfig.newBuilder()
                .setMqtt(ModuleConfigProtos.ModuleConfig.MQTTConfig.newBuilder()
                        .setEnabled(false)
                        .build())
                .build();

        MessageService.setModuleConfig(handler, state, mqttConfig);

        MeshProtos.ToRadio sent = parseLastToRadio(connection);
        assertFalse(sent.getPacket().getDecoded().getWantResponse());
        AdminProtos.AdminMessage admin =
                AdminProtos.AdminMessage.parseFrom(sent.getPacket().getDecoded().getPayload());
        assertTrue(admin.hasSetModuleConfig());
        assertFalse(admin.getSessionPasskey().isEmpty());
    }

    @Test
    void setStoreForwardModuleConfigSendsEnabledValue() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x04c5b420);
        state.setSessionPasskey(ByteString.copyFromUtf8("passkey"));

        ModuleConfigProtos.ModuleConfig storeForwardConfig =
                ModuleConfigProtos.ModuleConfig.newBuilder()
                        .setStoreForward(ModuleConfigProtos.ModuleConfig.StoreForwardConfig.newBuilder()
                                .setEnabled(true)
                                .setIsServer(true)
                                .build())
                        .build();

        MessageService.setModuleConfig(handler, state, storeForwardConfig);

        MeshProtos.ToRadio sent = parseLastToRadio(connection);
        assertEquals(0x04c5b420, sent.getPacket().getFrom());
        assertEquals(0x04c5b420, sent.getPacket().getTo());
        assertEquals(0, sent.getPacket().getChannel());
        assertTrue(sent.getPacket().getWantAck());
        assertEquals(MeshProtos.MeshPacket.Priority.RELIABLE, sent.getPacket().getPriority());
        assertEquals(3, sent.getPacket().getHopLimit());
        assertEquals(3, sent.getPacket().getHopStart());
        AdminProtos.AdminMessage admin =
                AdminProtos.AdminMessage.parseFrom(sent.getPacket().getDecoded().getPayload());
        assertTrue(admin.hasSetModuleConfig());
        assertTrue(admin.getSetModuleConfig().hasStoreForward());
        assertTrue(admin.getSetModuleConfig().getStoreForward().getEnabled());
        assertTrue(admin.getSetModuleConfig().getStoreForward().getIsServer());
    }

    @Test
    void setModuleConfigUsesConfiguredLoraHopLimitForAdminPacket() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x04c5b420);
        state.addConfig(ConfigProtos.Config.newBuilder()
                .setLora(ConfigProtos.Config.LoRaConfig.newBuilder()
                        .setHopLimit(5)
                        .build())
                .build());

        ModuleConfigProtos.ModuleConfig storeForwardConfig =
                ModuleConfigProtos.ModuleConfig.newBuilder()
                        .setStoreForward(ModuleConfigProtos.ModuleConfig.StoreForwardConfig.newBuilder()
                                .setEnabled(true)
                                .build())
                        .build();

        MessageService.setModuleConfig(handler, state, storeForwardConfig);

        MeshProtos.MeshPacket packet = parseLastToRadio(connection).getPacket();
        assertEquals(5, packet.getHopLimit());
        assertEquals(5, packet.getHopStart());
    }

    @Test
    void setRingtoneUsesRoutingAckWithoutAdminResponse() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x04c5b420);
        state.setSessionPasskey(ByteString.copyFromUtf8("passkey"));

        MessageService.setRingtone(handler, state, "ring:d=4,o=5,b=120:c");

        MeshProtos.ToRadio sent = parseLastToRadio(connection);
        assertFalse(sent.getPacket().getDecoded().getWantResponse());
        AdminProtos.AdminMessage admin =
                AdminProtos.AdminMessage.parseFrom(sent.getPacket().getDecoded().getPayload());
        assertTrue(admin.hasSetRingtoneMessage());
        assertEquals("ring:d=4,o=5,b=120:c", admin.getSetRingtoneMessage());
        assertFalse(admin.getSessionPasskey().isEmpty());
    }

    @Test
    void setTimeOnlyUsesRoutingAckWithoutAdminResponse() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x04c5b420);
        state.setSessionPasskey(ByteString.copyFromUtf8("passkey"));

        MessageService.setTimeOnly(handler, state, 1_775_000_123L);

        MeshProtos.ToRadio sent = parseLastToRadio(connection);
        assertEquals(0x04c5b420, sent.getPacket().getFrom());
        assertEquals(0x04c5b420, sent.getPacket().getTo());
        assertFalse(sent.getPacket().getDecoded().getWantResponse());
        AdminProtos.AdminMessage admin =
                AdminProtos.AdminMessage.parseFrom(sent.getPacket().getDecoded().getPayload());
        assertTrue(admin.hasSetTimeOnly());
        assertEquals(1_775_000_123L, Integer.toUnsignedLong(admin.getSetTimeOnly()));
        assertFalse(admin.getSessionPasskey().isEmpty());
    }

    @Test
    void requestOtaModeUsesRoutingAckAndSendsHash() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x04c5b420);
        state.setSessionPasskey(ByteString.copyFromUtf8("passkey"));
        byte[] hash = new byte[32];
        for (int i = 0; i < hash.length; i++) {
            hash[i] = (byte) i;
        }

        MessageService.requestOtaMode(handler, state, AdminProtos.OTAMode.OTA_BLE, hash);

        MeshProtos.ToRadio sent = parseLastToRadio(connection);
        assertFalse(sent.getPacket().getDecoded().getWantResponse());
        AdminProtos.AdminMessage admin =
                AdminProtos.AdminMessage.parseFrom(sent.getPacket().getDecoded().getPayload());
        assertTrue(admin.hasOtaRequest());
        assertEquals(AdminProtos.OTAMode.OTA_BLE, admin.getOtaRequest().getRebootOtaMode());
        assertEquals(ByteString.copyFrom(hash), admin.getOtaRequest().getOtaHash());
        assertFalse(admin.getSessionPasskey().isEmpty());
    }

    @Test
    void enterDfuModeUsesRoutingAckWithoutAdminResponse() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x04c5b420);
        state.setSessionPasskey(ByteString.copyFromUtf8("passkey"));

        MessageService.enterDfuMode(handler, state);

        MeshProtos.ToRadio sent = parseLastToRadio(connection);
        assertFalse(sent.getPacket().getDecoded().getWantResponse());
        AdminProtos.AdminMessage admin =
                AdminProtos.AdminMessage.parseFrom(sent.getPacket().getDecoded().getPayload());
        assertTrue(admin.hasEnterDfuModeRequest());
        assertTrue(admin.getEnterDfuModeRequest());
        assertFalse(admin.getSessionPasskey().isEmpty());
    }

    @Test
    void sendPhoneTimePositionUsesLocalPositionAppPacket() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x04c5b420);

        MessageService.sendPhoneTimePosition(handler, state, 1_775_000_123L);

        MeshProtos.ToRadio sent = parseLastToRadio(connection);
        MeshProtos.MeshPacket packet = sent.getPacket();
        assertEquals(0, packet.getFrom());
        assertEquals(0x04c5b420, packet.getTo());
        assertEquals(Portnums.PortNum.POSITION_APP, packet.getDecoded().getPortnum());
        MeshProtos.Position position = MeshProtos.Position.parseFrom(packet.getDecoded().getPayload());
        assertEquals(1_775_000_123L, Integer.toUnsignedLong(position.getTime()));
    }

    @Test
    void setOwnerInfoSendsLicensedFlag() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x04c5b420);
        ByteString passkey = ByteString.copyFromUtf8("passkey");

        MessageService.setOwnerInfo(handler, state, "CALLSIGN", "CS", true, passkey);

        MeshProtos.ToRadio sent = parseLastToRadio(connection);
        AdminProtos.AdminMessage admin =
                AdminProtos.AdminMessage.parseFrom(sent.getPacket().getDecoded().getPayload());
        assertTrue(admin.hasSetOwner());
        assertEquals("CALLSIGN", admin.getSetOwner().getLongName());
        assertEquals("CS", admin.getSetOwner().getShortName());
        assertTrue(admin.getSetOwner().getIsLicensed());
        assertEquals(passkey, admin.getSessionPasskey());
    }

    @Test
    void sendChannelReactionSetsReplyIdAndEmojiFlagAndPersistsReaction() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x04c5b420);
        state.getOrCreateNode(state.getMyNodeNum()).setNodeId("!04c5b420");

        MeshMessage targetMessage = new MeshMessage("!11111111", "!ffffffff", 0, "hello", 1_700_000_000L, false);
        targetMessage.setPacketId(12345);

        assertTrue(MessageService.sendChannelReaction(handler, state, 3, targetMessage, "💪"));

        MeshProtos.ToRadio sent = parseLastToRadio(connection);
        assertEquals(Portnums.PortNum.TEXT_MESSAGE_APP, sent.getPacket().getDecoded().getPortnum());
        assertEquals(12345, sent.getPacket().getDecoded().getReplyId());
        assertEquals(1, sent.getPacket().getDecoded().getEmoji());
        assertEquals("💪", sent.getPacket().getDecoded().getPayload().toStringUtf8());

        MessageReaction stored = MessageDbService.getInstance()
                .loadReactionsByTargetPacketIds("channel", "3", "!04c5b420", List.of(12345))
                .get(12345).getFirst();
        assertEquals("💪", stored.getEmoji());
        assertEquals(MeshMessage.DeliveryStatus.SENDING, stored.getStatus());
        state.shutdown();
    }

    @Test
    void sendChannelReactionFallsBackToOwnerNodeIdWhenOwnNodeMissingInState() {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x04c5b420);

        MeshMessage targetMessage = new MeshMessage("!11111111", "!ffffffff", 0, "hello", 1_700_000_000L, false);
        targetMessage.setPacketId(22222);

        assertTrue(MessageService.sendChannelReaction(handler, state, 0, targetMessage, "👍"));

        MessageReaction stored = MessageDbService.getInstance()
                .loadReactionsByTargetPacketIds("channel", "0", "!04c5b420", List.of(22222))
                .get(22222).getFirst();
        assertEquals("!04c5b420", stored.getFromNodeId());
        state.shutdown();
    }

    @Test
    void sendChannelMessageReplyDebugDoesNotLogPayloadBytesDump() {
        UiLogAppender.clearBuffer();
        UiLogAppender appender = new UiLogAppender();
        appender.start();

        Logger logger = (Logger) LoggerFactory.getLogger(MessageService.class);
        Level previousLevel = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            RecordingConnection connection = new RecordingConnection();
            ProtocolHandler handler = track(new ProtocolHandler(connection));
            DeviceState state = new DeviceState();
            state.setMyNodeNum(0x04c5b420);
            state.getOrCreateNode(state.getMyNodeNum()).setNodeId("!04c5b420");

            MessageService.sendChannelMessage(handler, state, 2, "private reply payload", 12345);

            List<String> loggedMessages = UiLogAppender.getBuffer().stream()
                    .map(LogEntry::getFullMessage)
                    .toList();
            assertTrue(loggedMessages.stream().anyMatch(message ->
                    message.contains("REPLY_DEBUG send channel: replyId=12345")));
            assertTrue(loggedMessages.stream().anyMatch(message -> message.contains("payloadBytes=")));
            assertTrue(loggedMessages.stream().noneMatch(message -> message.contains("data bytes=")));
            assertTrue(loggedMessages.stream().noneMatch(message -> message.contains("private reply payload")));

            state.shutdown();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            UiLogAppender.clearBuffer();
        }
    }

    @Test
    void sendChannelMessageHydratesReplyTextFromScopedOriginalAndDoesNotDuplicate() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x04c5b420);
        state.getOrCreateNode(state.getMyNodeNum()).setNodeId("!04c5b420");

        MeshMessage original = new MeshMessage("!11111111", "!ffffffff", 2, "quoted", 1_700_000_000L, false);
        original.setPacketId(22222);
        MessageDbService.getInstance().save(original, "channel", "2", "!04c5b420");

        MeshMessage sent = MessageService.sendChannelMessage(handler, state, 2, "reply", 22222);

        assertNotNull(sent);
        assertEquals("quoted", sent.getReplyText());
        assertEquals(22222, parseLastToRadio(connection).getPacket().getDecoded().getReplyId());
        assertEquals(2, MessageDbService.getInstance().loadLast("channel", "2", 10, "!04c5b420").size());

        state.shutdown();
    }

    @Test
    void sendChannelMessageDoesNotHydrateReplyTextFromAnotherOwner() {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x04c5b420);
        state.getOrCreateNode(state.getMyNodeNum()).setNodeId("!04c5b420");

        MeshMessage otherOwnerOriginal = new MeshMessage("!11111111", "!ffffffff", 2, "wrong", 1_700_000_000L, false);
        otherOwnerOriginal.setPacketId(33333);
        MessageDbService.getInstance().save(otherOwnerOriginal, "channel", "2", "!other");

        MeshMessage sent = MessageService.sendChannelMessage(handler, state, 2, "reply", 33333);

        assertNotNull(sent);
        assertNull(sent.getReplyText());

        state.shutdown();
    }

    @Test
    void retryMessageReusesExistingChannelRowAndRegistersNewPendingAck() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x04c5b420);
        state.getOrCreateNode(state.getMyNodeNum()).setNodeId("!04c5b420");

        MeshMessage failed = new MeshMessage("!04c5b420", "!ffffffff", 2, "retry me", 1_700_000_000L, true);
        failed.setPacketId(12345);
        failed.setStatus(MeshMessage.DeliveryStatus.FAILED);
        failed.setErrorReason("TIMEOUT");
        MessageDbService.getInstance().save(failed, "channel", "2", "!04c5b420");

        int previousPacketId = failed.getPacketId();
        assertTrue(MessageService.retryMessage(handler, state, failed));
        assertEquals(MeshMessage.DeliveryStatus.SENDING, failed.getStatus());
        assertNull(failed.getErrorReason());
        assertNotEquals(previousPacketId, failed.getPacketId());

        MeshProtos.ToRadio sent = parseLastToRadio(connection);
        assertEquals(failed.getPacketId(), sent.getPacket().getId());
        assertEquals("retry me", sent.getPacket().getDecoded().getPayload().toStringUtf8());

        MeshMessage persisted = MessageDbService.getInstance().findByPacketId(failed.getPacketId());
        assertNotNull(persisted);
        assertEquals(failed.getDbId(), persisted.getDbId());
        assertEquals(MeshMessage.DeliveryStatus.SENDING, persisted.getStatus());
        assertNull(MessageDbService.getInstance().findByPacketId(previousPacketId));
        assertSame(failed, state.resolvePendingAck(failed.getPacketId()));

        state.shutdown();
    }

    private ProtocolHandler track(ProtocolHandler handler) {
        handlersToShutdown.add(handler);
        return handler;
    }

    private static MeshProtos.ToRadio parseLastToRadio(RecordingConnection connection) throws Exception {
        byte[] frame = connection.awaitLastSentBytes();
        int payloadLength = ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
        byte[] payload = new byte[payloadLength];
        System.arraycopy(frame, 4, payload, 0, payloadLength);
        return MeshProtos.ToRadio.parseFrom(payload);
    }

    private static final class RecordingConnection implements MeshtasticConnection {
        private volatile byte[] lastSentBytes;
        private final java.util.concurrent.BlockingQueue<byte[]> sentFrames = new java.util.concurrent.LinkedBlockingQueue<>();
        private final CountDownLatch firstSend = new CountDownLatch(1);

        MeshProtos.ToRadio nextPacket() throws Exception {
            byte[] frame = sentFrames.poll(2, TimeUnit.SECONDS);
            assertNotNull(frame, "Timed out waiting for the next outbound frame");
            return MeshProtos.ToRadio.parseFrom(java.util.Arrays.copyOfRange(frame, 4, frame.length));
        }

        @Override
        public void connect() {
            // no-op
        }

        @Override
        public void disconnect() {
            // no-op
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void sendBytes(byte[] data) {
            lastSentBytes = data;
            sentFrames.add(data);
            firstSend.countDown();
        }

        @Override
        public void setDataListener(Consumer<byte[]> listener) {
            // no-op
        }

        @Override
        public void setConnectionListener(ConnectionListener listener) {
            // no-op
        }

        byte[] awaitLastSentBytes() throws InterruptedException {
            assertTrue(firstSend.await(1, TimeUnit.SECONDS), "Timed out waiting for outbound frame");
            return lastSentBytes;
        }
    }
}
