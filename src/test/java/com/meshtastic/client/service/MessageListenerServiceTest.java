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
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.model.TelemetryEntry;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.utils.AppPreferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meshtastic.proto.AdminProtos;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;
import org.meshtastic.proto.TelemetryProtos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class MessageListenerServiceTest {

    @TempDir
    Path tempHome;

    private DeviceState state;
    private MessageListenerService service;

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.ensureJavaFxStarted();
        TestEnvironmentSupport.resetSingletons();
        AppPreferences.setNotificationsEnabled(false);
        MessageDbService.getInstance();
        state = new DeviceState();
        state.setMyNodeNum(0x12345678);
        state.addChannel(ChannelProtos.Channel.newBuilder()
                .setIndex(0)
                .setRole(ChannelProtos.Channel.Role.PRIMARY)
                .build());
        state.addChannel(ChannelProtos.Channel.newBuilder()
                .setIndex(2)
                .setRole(ChannelProtos.Channel.Role.SECONDARY)
                .build());
        state.setChannelCatalogReady(true);
        service = new MessageListenerService(state);
    }

    @AfterEach
    void tearDown() {
        if (state != null) {
            state.shutdown();
        }
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void adminRingtoneResponseStoresRingtoneAndNotifiesListeners() {
        AtomicBoolean notified = new AtomicBoolean(false);
        state.addRingtoneListener(() -> notified.set(true));
        AdminProtos.AdminMessage admin = AdminProtos.AdminMessage.newBuilder()
                .setGetRingtoneResponse("ring:d=4,o=5,b=120:c")
                .build();
        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(state.getMyNodeNum())
                .setTo(state.getMyNodeNum())
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.ADMIN_APP)
                        .setPayload(admin.toByteString())
                        .build())
                .build();

        service.onMeshPacket(packet);

        assertTrue(state.isRingtoneLoaded());
        assertEquals("ring:d=4,o=5,b=120:c", state.getRingtone());
        assertTrue(notified.get());
    }

    @Test
    void onMeshPacketStoresIncomingChannelMessageInStateAndDatabase() {
        boolean notificationsEnabled = AppPreferences.isNotificationsEnabled();
        AppPreferences.setNotificationsEnabled(false);
        try {
            MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                    .setFrom(0x11111111)
                    .setTo(0xFFFFFFFF)
                    .setChannel(2)
                    .setId(7001)
                    .setRxTime(1_700_000_100)
                    .setHopStart(5)
                    .setHopLimit(3)
                    .setRxRssi(-77)
                    .setRxSnr(8.5f)
                    .setViaMqtt(true)
                    .setDecoded(MeshProtos.Data.newBuilder()
                            .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                            .setPayload(ByteString.copyFrom("hello channel", StandardCharsets.UTF_8))
                            .build())
                    .build();

            long beforeReceive = System.currentTimeMillis() / 1000;
            service.onMeshPacket(packet);
            long afterReceive = System.currentTimeMillis() / 1000;

            MeshMessage inMemory = state.getMessages(2).getFirst();
            assertEquals("hello channel", inMemory.getText());
            assertEquals("!11111111", inMemory.getFromNodeId());
            assertEquals("!ffffffff", inMemory.getToNodeId());
            assertTrue(inMemory.getTimestamp() >= beforeReceive);
            assertTrue(inMemory.getTimestamp() <= afterReceive);
            assertEquals(MeshMessage.DeliveryStatus.DELIVERED, inMemory.getStatus());
            assertEquals(-77, inMemory.getRxRssi());
            assertEquals(8.5f, inMemory.getRxSnr());
            assertTrue(inMemory.isViaMqtt());

            MeshMessage persisted = MessageDbService.getInstance().findByPacketId(7001);
            assertNotNull(persisted);
            assertEquals("hello channel", persisted.getText());
            assertEquals(inMemory.getTimestamp(), persisted.getTimestamp());
            assertEquals(MeshMessage.DeliveryStatus.DELIVERED, persisted.getStatus());
            assertTrue(persisted.isViaMqtt());
        } finally {
            AppPreferences.setNotificationsEnabled(notificationsEnabled);
        }
    }

    @Test
    void onMeshPacketPromotesDuplicateFromMqttToLora() {
        boolean notificationsEnabled = AppPreferences.isNotificationsEnabled();
        AppPreferences.setNotificationsEnabled(false);
        try {
            MeshProtos.MeshPacket mqttPacket = MeshProtos.MeshPacket.newBuilder()
                    .setFrom(0x11111111)
                    .setTo(0xFFFFFFFF)
                    .setChannel(2)
                    .setId(7013)
                    .setViaMqtt(true)
                    .setDecoded(MeshProtos.Data.newBuilder()
                            .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                            .setPayload(ByteString.copyFrom("hello channel", StandardCharsets.UTF_8))
                            .build())
                    .build();
            service.onMeshPacket(mqttPacket);

            MeshProtos.MeshPacket loraPacket = MeshProtos.MeshPacket.newBuilder()
                    .setFrom(0x11111111)
                    .setTo(0xFFFFFFFF)
                    .setChannel(2)
                    .setId(7013)
                    .setHopStart(5)
                    .setHopLimit(2)
                    .setRxRssi(-83)
                    .setRxSnr(6.5f)
                    .setTransportMechanism(MeshProtos.MeshPacket.TransportMechanism.TRANSPORT_LORA)
                    .setDecoded(MeshProtos.Data.newBuilder()
                            .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                            .setPayload(ByteString.copyFrom("hello channel", StandardCharsets.UTF_8))
                            .build())
                    .build();
            service.onMeshPacket(loraPacket);

            MeshMessage inMemory = state.getMessages(2).getFirst();
            assertFalse(inMemory.isViaMqtt());
            assertEquals(5, inMemory.getHopStart());
            assertEquals(2, inMemory.getHopLimit());
            assertEquals(-83, inMemory.getRxRssi());
            assertEquals(6.5f, inMemory.getRxSnr());

            MeshMessage persisted = MessageDbService.getInstance().findByPacketId(7013, "channel", "2", "!12345678");
            assertNotNull(persisted);
            assertFalse(persisted.isViaMqtt());
            assertEquals(5, persisted.getHopStart());
            assertEquals(2, persisted.getHopLimit());
        } finally {
            AppPreferences.setNotificationsEnabled(notificationsEnabled);
        }
    }

    @Test
    void onMeshPacketKeepsLoraWhenDuplicateArrivesLaterViaMqtt() {
        boolean notificationsEnabled = AppPreferences.isNotificationsEnabled();
        AppPreferences.setNotificationsEnabled(false);
        try {
            MeshProtos.MeshPacket loraPacket = MeshProtos.MeshPacket.newBuilder()
                    .setFrom(0x11111111)
                    .setTo(0xFFFFFFFF)
                    .setChannel(2)
                    .setId(7014)
                    .setHopStart(4)
                    .setHopLimit(1)
                    .setTransportMechanism(MeshProtos.MeshPacket.TransportMechanism.TRANSPORT_LORA)
                    .setDecoded(MeshProtos.Data.newBuilder()
                            .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                            .setPayload(ByteString.copyFrom("hello channel", StandardCharsets.UTF_8))
                            .build())
                    .build();
            service.onMeshPacket(loraPacket);

            MeshProtos.MeshPacket mqttPacket = MeshProtos.MeshPacket.newBuilder()
                    .setFrom(0x11111111)
                    .setTo(0xFFFFFFFF)
                    .setChannel(2)
                    .setId(7014)
                    .setViaMqtt(true)
                    .setDecoded(MeshProtos.Data.newBuilder()
                            .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                            .setPayload(ByteString.copyFrom("hello channel", StandardCharsets.UTF_8))
                            .build())
                    .build();
            service.onMeshPacket(mqttPacket);

            MeshMessage inMemory = state.getMessages(2).getFirst();
            assertFalse(inMemory.isViaMqtt());
            assertEquals(4, inMemory.getHopStart());
            assertEquals(1, inMemory.getHopLimit());

            MeshMessage persisted = MessageDbService.getInstance().findByPacketId(7014, "channel", "2", "!12345678");
            assertNotNull(persisted);
            assertFalse(persisted.isViaMqtt());
            assertEquals(4, persisted.getHopStart());
            assertEquals(1, persisted.getHopLimit());
        } finally {
            AppPreferences.setNotificationsEnabled(notificationsEnabled);
        }
    }

    @Test
    void onMeshPacketKeepsDistinctChannelMessagesWhenPacketIdsMatchAcrossDifferentSenders() {
        boolean notificationsEnabled = AppPreferences.isNotificationsEnabled();
        AppPreferences.setNotificationsEnabled(false);
        try {
            MeshProtos.MeshPacket firstPacket = MeshProtos.MeshPacket.newBuilder()
                    .setFrom(0x11111111)
                    .setTo(0xFFFFFFFF)
                    .setChannel(2)
                    .setId(7015)
                    .setDecoded(MeshProtos.Data.newBuilder()
                            .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                            .setPayload(ByteString.copyFrom("alice", StandardCharsets.UTF_8))
                            .build())
                    .build();
            MeshProtos.MeshPacket secondPacket = MeshProtos.MeshPacket.newBuilder()
                    .setFrom(0x22222222)
                    .setTo(0xFFFFFFFF)
                    .setChannel(2)
                    .setId(7015)
                    .setDecoded(MeshProtos.Data.newBuilder()
                            .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                            .setPayload(ByteString.copyFrom("bob", StandardCharsets.UTF_8))
                            .build())
                    .build();

            service.onMeshPacket(firstPacket);
            service.onMeshPacket(secondPacket);

            List<MeshMessage> inMemory = state.getMessages(2);
            assertEquals(2, inMemory.size());
            assertEquals(List.of("alice", "bob"), inMemory.stream().map(MeshMessage::getText).toList());

            List<MeshMessage> persisted = MessageDbService.getInstance().loadLast("channel", "2", 10, "!12345678");
            assertEquals(2, persisted.size());
            assertEquals(List.of("alice", "bob"), persisted.stream().map(MeshMessage::getText).toList());
        } finally {
            AppPreferences.setNotificationsEnabled(notificationsEnabled);
        }
    }

    @Test
    void onMeshPacketDoesNotLogIncomingMessageText() {
        boolean notificationsEnabled = AppPreferences.isNotificationsEnabled();
        AppPreferences.setNotificationsEnabled(false);
        UiLogAppender.clearBuffer();
        UiLogAppender appender = new UiLogAppender();
        appender.start();

        Logger logger = (Logger) LoggerFactory.getLogger(MessageListenerService.class);
        Level previousLevel = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                    .setFrom(0x11111111)
                    .setTo(0xFFFFFFFF)
                    .setChannel(2)
                    .setId(7999)
                    .setDecoded(MeshProtos.Data.newBuilder()
                            .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                            .setPayload(ByteString.copyFrom("private text", StandardCharsets.UTF_8))
                            .build())
                    .build();

            service.onMeshPacket(packet);

            List<String> loggedMessages = UiLogAppender.getBuffer().stream()
                    .map(entry -> entry.getFullMessage())
                    .toList();
            assertTrue(loggedMessages.stream().anyMatch(message ->
                    message.contains("Received channel 2 message from !11111111")));
            assertTrue(loggedMessages.stream().noneMatch(message -> message.contains("private text")));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            UiLogAppender.clearBuffer();
            AppPreferences.setNotificationsEnabled(notificationsEnabled);
        }
    }

    @Test
    void onMeshPacketDefersDirectMessagesUntilMyNodeNumIsKnown() {
        state.clear();

        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x11111111)
                .setTo(0x12345678)
                .setChannel(0)
                .setId(7009)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                        .setPayload(ByteString.copyFrom("hello dm", StandardCharsets.UTF_8))
                        .build())
                .build();

        service.onMeshPacket(packet);

        assertTrue(state.getDirectMessages("!11111111").isEmpty());
        assertNull(MessageDbService.getInstance().findByPacketId(7009));

        state.setMyNodeNum(0x12345678);
        service.onMyNodeInfo(MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(0x12345678).build());
        TestEnvironmentSupport.ensureJavaFxStarted();

        MeshMessage flushed = state.getDirectMessages("!11111111").getFirst();
        assertEquals("hello dm", flushed.getText());
        assertEquals("!11111111", flushed.getFromNodeId());

        MeshMessage persisted = MessageDbService.getInstance().findByPacketId(7009);
        assertNotNull(persisted);
        assertEquals("hello dm", persisted.getText());
    }

    @Test
    void onMeshPacketDefersTelemetryUntilMyNodeNumIsKnown() {
        state.clear();

        TelemetryProtos.Telemetry telemetry = TelemetryProtos.Telemetry.newBuilder()
                .setDeviceMetrics(TelemetryProtos.DeviceMetrics.newBuilder()
                        .setBatteryLevel(77)
                        .setVoltage(3.92f)
                        .build())
                .build();
        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0xCAFEBABE)
                .setTo(0xFFFFFFFF)
                .setId(7012)
                .setRxTime(1_700_000_222)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TELEMETRY_APP)
                        .setPayload(telemetry.toByteString())
                        .build())
                .build();

        service.onMeshPacket(packet);

        assertTrue(state.getNodeDb().isEmpty());
        assertEquals(0, NodeCacheService.getInstance().countTelemetryEntries("!12345678"));

        state.setMyNodeNum(0x12345678);
        service.onMyNodeInfo(MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(0x12345678).build());
        TestEnvironmentSupport.ensureJavaFxStarted();

        NodeData node = state.getOrCreateNode(0xCAFEBABE);
        assertEquals(77, node.getBatteryLevel());
        assertEquals(3.92f, node.getVoltage());
        assertEquals(1, NodeCacheService.getInstance().countTelemetryEntries("!12345678"));
    }

    @Test
    void onMeshPacketSeparatesExternalPowerFlagFromBatteryPercent() {
        TelemetryProtos.Telemetry telemetry = TelemetryProtos.Telemetry.newBuilder()
                .setDeviceMetrics(TelemetryProtos.DeviceMetrics.newBuilder()
                        .setBatteryLevel(101)
                        .setVoltage(4.0f)
                        .build())
                .build();
        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0xCAFEBABE)
                .setTo(0xFFFFFFFF)
                .setId(7013)
                .setRxTime(1_700_000_333)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TELEMETRY_APP)
                        .setPayload(telemetry.toByteString())
                        .build())
                .build();

        service.onMeshPacket(packet);

        NodeData node = state.getOrCreateNode(0xCAFEBABE);
        assertEquals(0, node.getBatteryLevel());
        assertTrue(node.isExternallyPowered());
        assertEquals(4.0f, node.getVoltage());

        TelemetryEntry runtimeEntry = state.getTelemetryHistory().getFirst();
        assertEquals(0, runtimeEntry.getBatteryLevel());
        assertTrue(runtimeEntry.isExternallyPowered());
        assertEquals(4.0f, runtimeEntry.getVoltage());

        List<TelemetryEntry> persistedEntries = NodeCacheService.getInstance().loadTelemetrySince(0, "!12345678");
        assertEquals(1, persistedEntries.size());
        TelemetryEntry persisted = persistedEntries.getFirst();
        assertEquals(0, persisted.getBatteryLevel());
        assertTrue(persisted.isExternallyPowered());
        assertEquals(4.0f, persisted.getVoltage());
    }

    @Test
    void onMeshPacketDefersBroadcastMessagesUntilChannelCatalogReady() {
        state.clear();
        state.setMyNodeNum(0x12345678);
        state.addChannel(ChannelProtos.Channel.newBuilder()
                .setIndex(0)
                .setRole(ChannelProtos.Channel.Role.PRIMARY)
                .build());
        state.addChannel(ChannelProtos.Channel.newBuilder()
                .setIndex(2)
                .setRole(ChannelProtos.Channel.Role.SECONDARY)
                .build());

        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x11111111)
                .setTo(0xFFFFFFFF)
                .setChannel(2)
                .setId(7010)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                        .setPayload(ByteString.copyFrom("deferred", StandardCharsets.UTF_8))
                        .build())
                .build();

        service.onMeshPacket(packet);

        assertTrue(state.getMessages(2).isEmpty());
        assertNull(MessageDbService.getInstance().findByPacketId(7010));

        state.setChannelCatalogReady(true);
        service.onConfigComplete(1);
        TestEnvironmentSupport.ensureJavaFxStarted();

        MeshMessage flushed = state.getMessages(2).getFirst();
        assertEquals("deferred", flushed.getText());
        assertNotNull(MessageDbService.getInstance().findByPacketId(7010));
    }

    @Test
    void onMeshPacketDoesNotDropDeferredBroadcastBurstWhileChannelCatalogLoads() throws Exception {
        state.clear();
        state.setMyNodeNum(0x12345678);
        state.addChannel(ChannelProtos.Channel.newBuilder()
                .setIndex(0)
                .setRole(ChannelProtos.Channel.Role.PRIMARY)
                .build());
        state.addChannel(ChannelProtos.Channel.newBuilder()
                .setIndex(2)
                .setRole(ChannelProtos.Channel.Role.SECONDARY)
                .build());

        int packetCount = 250;
        for (int i = 0; i < packetCount; i++) {
            MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                    .setFrom(0x11111111)
                    .setTo(0xFFFFFFFF)
                    .setChannel(2)
                    .setId(7_100 + i)
                    .setDecoded(MeshProtos.Data.newBuilder()
                            .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                            .setPayload(ByteString.copyFrom("deferred-" + i, StandardCharsets.UTF_8))
                            .build())
                    .build();
            service.onMeshPacket(packet);
        }

        assertTrue(state.getMessages(2).isEmpty());
        assertNull(MessageDbService.getInstance().findByPacketId(7_100));
        assertNull(MessageDbService.getInstance().findByPacketId(7_100 + packetCount - 1));

        state.setChannelCatalogReady(true);
        service.onConfigComplete(1);

        assertTrue(waitUntil(() ->
                        MessageDbService.getInstance().loadLast("channel", "2", packetCount + 10, "!12345678").size() == packetCount,
                5_000));

        List<MeshMessage> persisted = MessageDbService.getInstance()
                .loadLast("channel", "2", packetCount + 10, "!12345678");
        assertEquals(packetCount, persisted.size());
        assertEquals("deferred-0", persisted.getFirst().getText());
        assertEquals("deferred-" + (packetCount - 1), persisted.getLast().getText());
    }

    @Test
    void onMeshPacketDropsBroadcastMessagesForUnknownChannel() {
        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x11111111)
                .setTo(0xFFFFFFFF)
                .setChannel(5)
                .setId(7011)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                        .setPayload(ByteString.copyFrom("wrong channel", StandardCharsets.UTF_8))
                        .build())
                .build();

        service.onMeshPacket(packet);

        assertTrue(state.getMessages(5).isEmpty());
        assertTrue(state.getMessages(0).isEmpty());
        assertNull(MessageDbService.getInstance().findByPacketId(7011));
    }

    @Test
    void onMeshPacketIgnoresOutgoingTextEcho() {
        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(state.getMyNodeNum())
                .setTo(0xFFFFFFFF)
                .setChannel(0)
                .setId(7002)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                        .setPayload(ByteString.copyFrom("echo", StandardCharsets.UTF_8))
                        .build())
                .build();

        service.onMeshPacket(packet);

        assertEquals(0, state.getMessages(0).size());
        assertNull(MessageDbService.getInstance().findByPacketId(7002));
    }

    @Test
    void onMeshPacketStoresIncomingReactionSeparatelyFromMessages() {
        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x11111111)
                .setTo(0xFFFFFFFF)
                .setChannel(2)
                .setId(7003)
                .setRxTime(1_700_000_101)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                        .setReplyId(42)
                        .setEmoji(1)
                        .setPayload(ByteString.copyFrom("👍", StandardCharsets.UTF_8))
                        .build())
                .build();

        long beforeReceive = System.currentTimeMillis() / 1000;
        service.onMeshPacket(packet);
        long afterReceive = System.currentTimeMillis() / 1000;

        assertTrue(state.getMessages(2).isEmpty());
        assertNull(MessageDbService.getInstance().findByPacketId(7003));

        Map<Integer, List<MessageReaction>> reactions = MessageDbService.getInstance()
                .loadReactionsByTargetPacketIds("channel", "2", "!12345678", List.of(42));
        MessageReaction stored = reactions.get(42).getFirst();
        assertEquals("👍", stored.getEmoji());
        assertTrue(stored.getTimestamp() >= beforeReceive);
        assertTrue(stored.getTimestamp() <= afterReceive);
        assertEquals(MeshMessage.DeliveryStatus.DELIVERED, stored.getStatus());
        assertEquals("!11111111", stored.getFromNodeId());
    }

    @Test
    void onMeshPacketDropsIncomingChannelMessageFromIgnoredNode() {
        NodeCacheService.getInstance().setIgnored("!11111111", true);
        assertTrue(NodeCacheService.getInstance().isIgnored("!11111111"));

        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x11111111)
                .setTo(0xFFFFFFFF)
                .setChannel(2)
                .setId(7004)
                .setRxTime(1_700_000_102)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                        .setPayload(ByteString.copyFrom("ignore me", StandardCharsets.UTF_8))
                        .build())
                .build();

        service.onMeshPacket(packet);

        assertTrue(state.getMessages(2).isEmpty());
        assertNull(MessageDbService.getInstance().findByPacketId(7004));
    }

    @Test
    void onMeshPacketDropsIncomingReactionFromIgnoredNode() {
        NodeCacheService.getInstance().setIgnored("!11111111", true);
        assertTrue(NodeCacheService.getInstance().isIgnored("!11111111"));

        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x11111111)
                .setTo(0xFFFFFFFF)
                .setChannel(2)
                .setId(7005)
                .setRxTime(1_700_000_103)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                        .setReplyId(42)
                        .setEmoji(1)
                        .setPayload(ByteString.copyFrom("👍", StandardCharsets.UTF_8))
                        .build())
                .build();

        service.onMeshPacket(packet);

        assertTrue(state.getMessages(2).isEmpty());
        assertNull(MessageDbService.getInstance().findByPacketId(7005));
        assertTrue(MessageDbService.getInstance()
                .loadReactionsByTargetPacketIds("channel", "2", "!12345678", List.of(42))
                .isEmpty());
    }

    @Test
    void onMeshPacketFiresTracerouteListenerForRoutePayloadWithoutRequestId() {
        int fromNode = (int) 0xBBA9341CL;
        MeshProtos.RouteDiscovery route = MeshProtos.RouteDiscovery.newBuilder()
                .addRoute(48730296)
                .addRoute(1236700080)
                .addSnrTowards(-44)
                .addSnrTowards(-18)
                .addSnrTowards(-40)
                .build();
        List<Integer> fromNodes = new ArrayList<>();
        List<MeshProtos.RouteDiscovery> routes = new ArrayList<>();
        state.addTracerouteListener((nodeNum, receivedRoute) -> {
            fromNodes.add(nodeNum);
            routes.add(receivedRoute);
        });

        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(fromNode)
                .setTo(state.getMyNodeNum())
                .setId(480384382)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TRACEROUTE_APP)
                        .setWantResponse(true)
                        .setPayload(route.toByteString())
                        .build())
                .build();

        service.onMeshPacket(packet);

        assertEquals(List.of(fromNode), fromNodes);
        assertEquals(List.of(route), routes);
    }

    @Test
    void onMeshPacketIgnoresEmptyTraceroutePayloadWithoutRequestId() {
        List<MeshProtos.RouteDiscovery> routes = new ArrayList<>();
        state.addTracerouteListener((nodeNum, route) -> routes.add(route));

        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x11111111)
                .setTo(state.getMyNodeNum())
                .setId(7006)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TRACEROUTE_APP)
                        .setPayload(MeshProtos.RouteDiscovery.newBuilder().build().toByteString())
                        .build())
                .build();

        service.onMeshPacket(packet);

        assertTrue(routes.isEmpty());
    }

    @Test
    void onMeshPacketIgnoresTraceroutePayloadWithoutRequestIdWhenNotAddressedToLocalNode() {
        List<MeshProtos.RouteDiscovery> routes = new ArrayList<>();
        state.addTracerouteListener((nodeNum, route) -> routes.add(route));

        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x11111111)
                .setTo(0x22222222)
                .setId(7007)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TRACEROUTE_APP)
                        .setPayload(MeshProtos.RouteDiscovery.newBuilder()
                                .addRoute(0x33333333)
                                .build()
                                .toByteString())
                        .build())
                .build();

        service.onMeshPacket(packet);

        assertTrue(routes.isEmpty());
    }

    @Test
    void onMeshPacketMarksPendingMessageDeliveredWhenAckArrives() {
        MeshMessage pending = new MeshMessage("!12345678", "!ffffffff", 0, "pending", 1_700_000_000L, true);
        pending.setPacketId(42);
        pending.setStatus(MeshMessage.DeliveryStatus.SENDING);
        state.addMessage(pending);
        MessageDbService.getInstance().save(pending, "channel", "0", "!12345678");
        state.registerPendingAck(42, pending);

        MeshProtos.Routing routing = MeshProtos.Routing.newBuilder()
                .setErrorReason(MeshProtos.Routing.Error.NONE)
                .build();
        MeshProtos.MeshPacket ackPacket = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x11111111)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.ROUTING_APP)
                        .setRequestId(42)
                        .setPayload(routing.toByteString())
                        .build())
                .build();

        service.onMeshPacket(ackPacket);

        assertEquals(MeshMessage.DeliveryStatus.DELIVERED, pending.getStatus());
        assertNull(state.resolvePendingAck(42));

        MeshMessage persisted = MessageDbService.getInstance().findByPacketId(42);
        assertNotNull(persisted);
        assertEquals(MeshMessage.DeliveryStatus.DELIVERED, persisted.getStatus());
    }

    @Test
    void onMeshPacketMarksDirectMessageConfirmedOnlyWhenRecipientAckArrives() {
        MeshMessage pending = new MeshMessage("!12345678", "!22222222", 0, "pending dm", 1_700_000_000L, true);
        pending.setPacketId(43);
        pending.setStatus(MeshMessage.DeliveryStatus.SENDING);
        state.addDirectMessage(pending, "!22222222");
        state.registerPendingAck(43, pending);

        MeshProtos.Routing routing = MeshProtos.Routing.newBuilder()
                .setErrorReason(MeshProtos.Routing.Error.NONE)
                .build();
        MeshProtos.MeshPacket nonRecipientAck = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x11111111)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.ROUTING_APP)
                        .setRequestId(43)
                        .setPayload(routing.toByteString())
                        .build())
                .build();

        service.onMeshPacket(nonRecipientAck);

        assertEquals(MeshMessage.DeliveryStatus.DELIVERED, pending.getStatus());
        assertTrue(state.getMessageStore().getPendingAcks().containsKey(43));
        MeshMessage deliveredWithoutRecipientAck = MessageDbService.getInstance().findByPacketId(43);
        assertNotNull(deliveredWithoutRecipientAck);
        assertEquals(MeshMessage.DeliveryStatus.DELIVERED, deliveredWithoutRecipientAck.getStatus());

        MeshProtos.MeshPacket recipientAck = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x22222222)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.ROUTING_APP)
                        .setRequestId(43)
                        .setPayload(routing.toByteString())
                        .build())
                .build();

        service.onMeshPacket(recipientAck);

        assertEquals(MeshMessage.DeliveryStatus.CONFIRMED, pending.getStatus());
        assertNull(state.resolvePendingAck(43));
        MeshMessage confirmed = MessageDbService.getInstance().findByPacketId(43);
        assertNotNull(confirmed);
        assertEquals(MeshMessage.DeliveryStatus.CONFIRMED, confirmed.getStatus());
    }

    @Test
    void onMeshPacketMarksPendingMessageFailedWhenNakArrives() {
        MeshMessage pending = new MeshMessage("!12345678", "!ffffffff", 0, "pending", 1_700_000_000L, true);
        pending.setPacketId(77);
        pending.setStatus(MeshMessage.DeliveryStatus.SENDING);
        state.addMessage(pending);
        MessageDbService.getInstance().save(pending, "channel", "0", "!12345678");
        state.registerPendingAck(77, pending);

        MeshProtos.Routing routing = MeshProtos.Routing.newBuilder()
                .setErrorReason(MeshProtos.Routing.Error.NO_ROUTE)
                .build();
        MeshProtos.MeshPacket ackPacket = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x11111111)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.ROUTING_APP)
                        .setRequestId(77)
                        .setPayload(routing.toByteString())
                        .build())
                .build();

        service.onMeshPacket(ackPacket);

        assertEquals(MeshMessage.DeliveryStatus.FAILED, pending.getStatus());
        assertEquals("NO_ROUTE", pending.getErrorReason());

        MeshMessage persisted = MessageDbService.getInstance().findByPacketId(77);
        assertNotNull(persisted);
        assertEquals(MeshMessage.DeliveryStatus.FAILED, persisted.getStatus());
        assertEquals("NO_ROUTE", persisted.getErrorReason());
    }

    @Test
    void onMeshPacketUpdatesOutgoingReactionStatusWhenAckArrives() {
        MessageReaction reaction = new MessageReaction(42, "!12345678", "🎉", 1_700_000_000L, true);
        reaction.setPacketId(8080);
        reaction.setStatus(MeshMessage.DeliveryStatus.SENDING);
        MessageDbService.getInstance().saveReaction(reaction, "channel", "0", "!12345678");

        MeshProtos.Routing routing = MeshProtos.Routing.newBuilder()
                .setErrorReason(MeshProtos.Routing.Error.NONE)
                .build();
        MeshProtos.MeshPacket ackPacket = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x11111111)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.ROUTING_APP)
                        .setRequestId(8080)
                        .setPayload(routing.toByteString())
                        .build())
                .build();

        service.onMeshPacket(ackPacket);

        MessageReaction stored = MessageDbService.getInstance()
                .loadReactionsByTargetPacketIds("channel", "0", "!12345678", List.of(42))
                .get(42).getFirst();
        assertEquals(MeshMessage.DeliveryStatus.DELIVERED, stored.getStatus());
    }

    @Test
    void onMeshPacketUpdatesNodeInfoFromNodeInfoApp() {
        MeshProtos.User user = MeshProtos.User.newBuilder()
                .setId("!cafebabe")
                .setLongName("Alice")
                .setShortName("ALC")
                .setRole(ConfigProtos.Config.DeviceConfig.Role.ROUTER)
                .setHwModel(MeshProtos.HardwareModel.TLORA_V2)
                .build();
        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0xCAFEBABE)
                .setRxTime(1_700_000_200)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.NODEINFO_APP)
                        .setPayload(user.toByteString())
                        .build())
                .build();

        service.onMeshPacket(packet);

        NodeData node = state.getOrCreateNode(0xCAFEBABE);
        assertEquals("!cafebabe", node.getNodeId());
        assertEquals("Alice", node.getLongName());
        assertEquals("ALC", node.getShortName());
        assertEquals("ROUTER", node.getRole());
        assertEquals("TLORA_V2", node.getHwModel());
        assertEquals(1_700_000_200, node.getLastHeard());

        NodeData cached = NodeCacheService.getInstance().get("!cafebabe");
        assertNotNull(cached);
        assertEquals("Alice", cached.getLongName());
        assertEquals(node.getNodeId(), cached.getNodeId());
    }

    @Test
    void onMeshPacketSeedsLocalContactAndCreatesDirectThreadFromDirectedNodeInfo() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ProtocolHandler handler = new ProtocolHandler(connection);
        try {
            service = new MessageListenerService(state, handler);

            MeshProtos.User user = MeshProtos.User.newBuilder()
                    .setId("!cafed00d")
                    .setLongName("Bob")
                    .setShortName("BOB")
                    .setPublicKey(ByteString.copyFrom(new byte[] {9, 8, 7, 6}))
                    .build();
            MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                    .setFrom(0xCAFED00D)
                    .setTo(state.getMyNodeNum())
                    .setDecoded(MeshProtos.Data.newBuilder()
                            .setPortnum(Portnums.PortNum.NODEINFO_APP)
                            .setPayload(user.toByteString())
                            .build())
                    .build();

            service.onMeshPacket(packet);

            assertTrue(state.getAllDirectMessages().containsKey("!cafed00d"));
            List<byte[]> sentFrames = connection.awaitSentFrames(1);
            assertEquals(1, sentFrames.size());

            MeshProtos.ToRadio seedContact = parseToRadio(sentFrames.get(0));
            MeshProtos.MeshPacket seedPacket = seedContact.getPacket();
            assertEquals(Portnums.PortNum.ADMIN_APP, seedPacket.getDecoded().getPortnum());
            AdminProtos.AdminMessage adminMessage = AdminProtos.AdminMessage.parseFrom(seedPacket.getDecoded().getPayload());
            assertEquals(0xCAFED00D, adminMessage.getAddContact().getNodeNum());
            assertArrayEquals(new byte[] {9, 8, 7, 6},
                    adminMessage.getAddContact().getUser().getPublicKey().toByteArray());
        } finally {
            handler.shutdown();
        }
    }

    private static MeshProtos.ToRadio parseToRadio(byte[] frame) throws Exception {
        int payloadLength = ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
        byte[] payload = Arrays.copyOfRange(frame, 4, 4 + payloadLength);
        return MeshProtos.ToRadio.parseFrom(payload);
    }

    private static boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadlineNanos) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
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
