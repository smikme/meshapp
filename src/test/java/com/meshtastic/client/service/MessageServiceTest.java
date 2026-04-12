package com.meshtastic.client.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.google.protobuf.ByteString;
import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.connection.MeshtasticConnection;
import com.meshtastic.client.logging.UiLogAppender;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MessageReaction;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.protocol.ProtocolHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meshtastic.proto.AdminProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;
import org.meshtastic.proto.Portnums;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.slf4j.LoggerFactory;

class MessageServiceTest {

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
    void setTimeOnlyUsesRoutingAckWithoutAdminResponse() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = track(new ProtocolHandler(connection));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x04c5b420);
        state.setSessionPasskey(ByteString.copyFromUtf8("passkey"));

        MessageService.setTimeOnly(handler, state, 1_775_000_123L);

        MeshProtos.ToRadio sent = parseLastToRadio(connection);
        assertFalse(sent.getPacket().getDecoded().getWantResponse());
        AdminProtos.AdminMessage admin =
                AdminProtos.AdminMessage.parseFrom(sent.getPacket().getDecoded().getPayload());
        assertTrue(admin.hasSetTimeOnly());
        assertEquals(1_775_000_123L, Integer.toUnsignedLong(admin.getSetTimeOnly()));
        assertFalse(admin.getSessionPasskey().isEmpty());
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
    void sendChannelReactionFallsBackToOwnerNodeIdWhenOwnNodeMissingInState() throws Exception {
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
                    .map(entry -> entry.getFullMessage())
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

    private ProtocolHandler track(ProtocolHandler handler) {
        handlersToShutdown.add(handler);
        return handler;
    }

    private static MeshProtos.ToRadio parseLastToRadio(RecordingConnection connection) throws Exception {
        byte[] frame = connection.lastSentBytes();
        int payloadLength = ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
        byte[] payload = new byte[payloadLength];
        System.arraycopy(frame, 4, payload, 0, payloadLength);
        return MeshProtos.ToRadio.parseFrom(payload);
    }

    private static final class RecordingConnection implements MeshtasticConnection {
        private volatile byte[] lastSentBytes;

        @Override
        public void connect() throws ConnectionException {
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
        }

        @Override
        public void setDataListener(Consumer<byte[]> listener) {
            // no-op
        }

        @Override
        public void setConnectionListener(ConnectionListener listener) {
            // no-op
        }

        byte[] lastSentBytes() {
            return lastSentBytes;
        }
    }
}
