package com.meshtastic.client.service;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.connection.TransportConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;

import com.google.protobuf.ByteString;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.PacketLogEntry;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.utils.AppPreferences;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.meshtastic.proto.MQTTProtos;
import org.meshtastic.proto.Portnums;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class MqttProxyServiceTest {

    private static final int LOCAL_NODE_NUM = 0x12345678;
    private static final int REMOTE_NODE_NUM = 0x76543210;
    private static final int BROADCAST_NODE_NUM = 0xFFFFFFFF;
    private static final byte[] DEFAULT_CHANNEL_KEY = new byte[] {
            (byte) 0xd4, (byte) 0xf1, (byte) 0xbb, 0x3a,
            0x20, 0x29, 0x07, 0x59,
            (byte) 0xf0, (byte) 0xbc, (byte) 0xff, (byte) 0xab,
            (byte) 0xcf, 0x4e, 0x69, 0x01
    };

    @TempDir
    Path tempHome;

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
    }

    @AfterEach
    void tearDown() {
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void loadProxyConfigReturnsNullWhenProxyIsDisabled() {
        DeviceState state = new DeviceState();
        state.addModuleConfig(ModuleConfigProtos.ModuleConfig.newBuilder()
                .setMqtt(ModuleConfigProtos.ModuleConfig.MQTTConfig.newBuilder()
                        .setEnabled(true)
                        .setProxyToClientEnabled(false)
                        .build())
                .build());

        assertNull(MqttProxyService.loadProxyConfig(state));
        assertEquals("mqtt.enabled=true, proxy_to_client_enabled=false",
                MqttProxyService.evaluateProxyState(state).reason());
    }

    @Test
    void loadProxyConfigAppliesDefaultsForBlankRootAndAddress() {
        DeviceState state = new DeviceState();
        state.addModuleConfig(ModuleConfigProtos.ModuleConfig.newBuilder()
                .setSerial(ModuleConfigProtos.ModuleConfig.SerialConfig.newBuilder().setEnabled(true).build())
                .build());
        state.addModuleConfig(ModuleConfigProtos.ModuleConfig.newBuilder()
                .setMqtt(ModuleConfigProtos.ModuleConfig.MQTTConfig.newBuilder()
                        .setEnabled(true)
                        .setProxyToClientEnabled(true)
                        .setTlsEnabled(true)
                        .build())
                .build());

        MqttProxyService.ProxyConfig config = MqttProxyService.loadProxyConfig(state);

        assertNotNull(config);
        assertEquals("mqtt.meshtastic.org:8883", config.address());
        assertEquals("msh", config.root());
    }

    @Test
    void buildBrokerUriPrefixesSchemeAndDefaultPort() {
        assertEquals("tcp://broker.example.org:1883",
                MqttProxyService.buildBrokerUri("broker.example.org", false));
        assertEquals("ssl://broker.example.org:8883",
                MqttProxyService.buildBrokerUri("broker.example.org", true));
        assertEquals("tcp://broker.example.org:1884",
                MqttProxyService.buildBrokerUri("broker.example.org:1884", false));
        assertEquals("ssl://broker.example.org:8883",
                MqttProxyService.buildBrokerUri("ssl://broker.example.org:8883", true));
    }

    @Test
    void buildClientIdUsesLocalNodeIdentifierWhenAvailable() {
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0xb3dacf5d);

        assertEquals("MeshAppMqttProxy-!b3dacf5d",
                MqttProxyService.buildClientId(state, "ignored-connection-id"));
    }

    @Test
    void buildClientIdFallsBackToConnectionIdentifierWhenNodeIsUnknown() {
        DeviceState state = new DeviceState();

        assertEquals("MeshAppMqttProxy-abcd1234",
                MqttProxyService.buildClientId(state, "abcd1234"));
    }

    @Test
    void extractPayloadPreservesBinaryAndUtf8TextVariants() {
        MeshProtos.MqttClientProxyMessage binaryMessage = MeshProtos.MqttClientProxyMessage.newBuilder()
                .setTopic("msh/test")
                .setData(ByteString.copyFrom(new byte[] {0x01, 0x02, 0x03}))
                .build();
        MeshProtos.MqttClientProxyMessage textMessage = MeshProtos.MqttClientProxyMessage.newBuilder()
                .setTopic("msh/test")
                .setText("hello")
                .build();

        assertArrayEquals(new byte[] {0x01, 0x02, 0x03}, MqttProxyService.extractPayload(binaryMessage));
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), MqttProxyService.extractPayload(textMessage));
    }

    @Test
    void localEchoSuppressorConsumesMatchingPublicationOnlyOnce() {
        MqttProxyService.LocalEchoSuppressor suppressor =
                new MqttProxyService.LocalEchoSuppressor(MqttProxyService.LOCAL_ECHO_TTL_MS);
        byte[] publishedPayload = new byte[] {0x01, 0x02, 0x03};

        suppressor.remember("msh/test", publishedPayload);
        publishedPayload[0] = 0x09;

        assertTrue(suppressor.consume("msh/test", new byte[] {0x01, 0x02, 0x03}));
        assertFalse(suppressor.consume("msh/test", new byte[] {0x01, 0x02, 0x03}));
    }

    @Test
    void localEchoSuppressorKeepsDifferentPayloadDistinct() {
        MqttProxyService.LocalEchoSuppressor suppressor =
                new MqttProxyService.LocalEchoSuppressor(MqttProxyService.LOCAL_ECHO_TTL_MS);

        suppressor.remember("msh/test", new byte[] {0x01});

        assertFalse(suppressor.consume("msh/test", new byte[] {0x02}));
        assertTrue(suppressor.consume("msh/test", new byte[] {0x01}));
    }

    @Test
    void localEchoSuppressorExpiresOldPublications() {
        MqttProxyService.LocalEchoSuppressor suppressor = new MqttProxyService.LocalEchoSuppressor(-1);

        suppressor.remember("msh/test", new byte[] {0x01});

        assertFalse(suppressor.consume("msh/test", new byte[] {0x01}));
    }

    @Test
    void downlinkFilterDisabledForwardsUnparseablePayload() {
        assertTrue(shouldForward(
                new byte[] {0x01, 0x02, 0x03},
                AppPreferences.MqttDownlinkFilterMode.NO_FILTER
        ));
    }

    @Test
    void downlinkFilterForwardsDecodedTextMessages() {
        assertTrue(shouldForward(
                serviceEnvelopePayload(decodedPacket(Portnums.PortNum.TEXT_MESSAGE_APP)),
                AppPreferences.MqttDownlinkFilterMode.FILTERED
        ));
        assertTrue(shouldForward(
                serviceEnvelopePayload(decodedPacket(Portnums.PortNum.TEXT_MESSAGE_COMPRESSED_APP)),
                AppPreferences.MqttDownlinkFilterMode.FILTERED
        ));
    }

    @Test
    void downlinkFilterForwardsRawMeshPacketPayloads() {
        assertTrue(shouldForward(
                decodedPacket(Portnums.PortNum.TEXT_MESSAGE_APP).toByteArray(),
                AppPreferences.MqttDownlinkFilterMode.FILTERED
        ));
        assertTrue(shouldForward(
                encryptedPacket(0xFFFFFFFF).toByteArray(),
                AppPreferences.MqttDownlinkFilterMode.FILTERED_WITH_ENCRYPTED
        ));
    }

    @Test
    void filteredModeDropsEncryptedBroadcastRawMeshPacketPayloads() {
        assertFalse(shouldForward(
                encryptedPacket(0xFFFFFFFF).toByteArray(),
                AppPreferences.MqttDownlinkFilterMode.FILTERED
        ));
    }

    @Test
    void downlinkFilterDropsDecodedNonChatMessages() {
        assertFalse(shouldForward(
                serviceEnvelopePayload(decodedPacket(Portnums.PortNum.POSITION_APP)),
                AppPreferences.MqttDownlinkFilterMode.FILTERED
        ));
        assertFalse(shouldForward(
                serviceEnvelopePayload(decodedPacket(Portnums.PortNum.NODEINFO_APP)),
                AppPreferences.MqttDownlinkFilterMode.FILTERED_WITH_ENCRYPTED
        ));
    }

    @Test
    void downlinkFilterForwardsRoutingAcksAddressedToLocalNode() {
        assertTrue(shouldForward(
                serviceEnvelopePayload(routingAckPacket(LOCAL_NODE_NUM, 1234)),
                AppPreferences.MqttDownlinkFilterMode.FILTERED
        ));
        assertTrue(shouldForward(
                serviceEnvelopePayload(routingAckPacket(LOCAL_NODE_NUM, 1234)),
                AppPreferences.MqttDownlinkFilterMode.FILTERED_WITH_ENCRYPTED
        ));
    }

    @Test
    void downlinkFilterDropsRoutingPacketsNotAddressedToLocalNode() {
        assertFalse(shouldForward(
                serviceEnvelopePayload(routingAckPacket(REMOTE_NODE_NUM, 1234)),
                AppPreferences.MqttDownlinkFilterMode.FILTERED
        ));
        assertFalse(shouldForward(
                serviceEnvelopePayload(routingAckPacket(0xFFFFFFFF, 1234)),
                AppPreferences.MqttDownlinkFilterMode.FILTERED_WITH_ENCRYPTED
        ));
    }

    @Test
    void downlinkFilterDropsRoutingPacketsWithoutRequestId() {
        assertFalse(shouldForward(
                serviceEnvelopePayload(routingAckPacket(LOCAL_NODE_NUM, 0)),
                AppPreferences.MqttDownlinkFilterMode.FILTERED
        ));
    }

    @Test
    void downlinkFilterForwardsEncryptedPacketsAddressedToLocalNode() {
        assertTrue(shouldForward(
                serviceEnvelopePayload(encryptedPacket(LOCAL_NODE_NUM)),
                AppPreferences.MqttDownlinkFilterMode.FILTERED
        ));
    }

    @Test
    void downlinkFilterDropsEncryptedPacketsNotAddressedToLocalNode() {
        assertFalse(shouldForward(
                serviceEnvelopePayload(encryptedPacket(REMOTE_NODE_NUM)),
                AppPreferences.MqttDownlinkFilterMode.FILTERED
        ));
        assertFalse(shouldForward(
                serviceEnvelopePayload(encryptedPacket(0xFFFFFFFF)),
                AppPreferences.MqttDownlinkFilterMode.FILTERED
        ));
    }

    @Test
    void downlinkFilterWithEncryptedForwardsAnyEncryptedPacket() {
        assertTrue(shouldForward(
                serviceEnvelopePayload(encryptedPacket(REMOTE_NODE_NUM)),
                AppPreferences.MqttDownlinkFilterMode.FILTERED_WITH_ENCRYPTED
        ));
        assertTrue(shouldForward(
                serviceEnvelopePayload(encryptedPacket(0xFFFFFFFF)),
                AppPreferences.MqttDownlinkFilterMode.FILTERED_WITH_ENCRYPTED
        ));
    }

    @Test
    void downlinkFilterDecryptsEncryptedChannelTextBeforeFiltering() {
        DeviceState state = deviceStateWithDefaultChannelKey();
        MeshProtos.MeshPacket packet = encryptedChannelPacket(Portnums.PortNum.TEXT_MESSAGE_APP);

        MqttProxyService.DownlinkFilterDecision decision = MqttProxyService.evaluateDownlinkFilter(
                serviceEnvelopePayload(packet),
                AppPreferences.MqttDownlinkFilterMode.FILTERED,
                state
        );

        assertTrue(decision.forward());
        assertNotNull(decision.monitorPacket());
        assertTrue(decision.monitorPacket().hasDecoded());
        assertEquals(Portnums.PortNum.TEXT_MESSAGE_APP, decision.monitorPacket().getDecoded().getPortnum());
    }

    @Test
    void downlinkFilterBuildsStableDuplicateKeyFromMeshPacket() {
        MeshProtos.MeshPacket packet = decodedPacket(Portnums.PortNum.TEXT_MESSAGE_APP);

        MqttProxyService.DownlinkFilterDecision firstDecision = MqttProxyService.evaluateDownlinkFilter(
                serviceEnvelopePayload(packet, "gateway-a"),
                AppPreferences.MqttDownlinkFilterMode.FILTERED,
                LOCAL_NODE_NUM
        );
        MqttProxyService.DownlinkFilterDecision secondDecision = MqttProxyService.evaluateDownlinkFilter(
                serviceEnvelopePayload(packet, "gateway-b"),
                AppPreferences.MqttDownlinkFilterMode.FILTERED,
                LOCAL_NODE_NUM
        );

        assertTrue(firstDecision.forward());
        assertTrue(secondDecision.forward());
        assertNotNull(firstDecision.duplicateKey());
        assertEquals(firstDecision.duplicateKey(), secondDecision.duplicateKey());
    }

    @Test
    void downlinkFilterDropsDecryptedEncryptedChannelTelemetry() {
        DeviceState state = deviceStateWithDefaultChannelKey();
        MeshProtos.MeshPacket packet = encryptedChannelPacket(Portnums.PortNum.TELEMETRY_APP);

        MqttProxyService.DownlinkFilterDecision decision = MqttProxyService.evaluateDownlinkFilter(
                serviceEnvelopePayload(packet),
                AppPreferences.MqttDownlinkFilterMode.FILTERED_WITH_ENCRYPTED,
                state
        );

        assertFalse(decision.forward());
        assertNotNull(decision.monitorPacket());
        assertEquals(Portnums.PortNum.TELEMETRY_APP, decision.monitorPacket().getDecoded().getPortnum());
    }

    @Test
    void downlinkFilterDropsInvalidOrEmptyEnvelopePayloads() {
        assertFalse(shouldForward(
                new byte[] {0x01, 0x02, 0x03},
                AppPreferences.MqttDownlinkFilterMode.FILTERED
        ));
        assertFalse(shouldForward(
                MQTTProtos.ServiceEnvelope.newBuilder().build().toByteArray(),
                AppPreferences.MqttDownlinkFilterMode.FILTERED_WITH_ENCRYPTED
        ));
    }

    @Test
    void mqttMonitorRecordsIncomingDownlinkOnlyAfterFilterPasses() throws Exception {
        AppPreferences.setMqttDownlinkFilterMode(AppPreferences.MqttDownlinkFilterMode.FILTERED);

        DeviceState state = new DeviceState();
        state.setMyNodeNum(LOCAL_NODE_NUM);
        ProtocolHandler handler = new ProtocolHandler("mqtt-monitor-test", new FakeTransportConnection());
        MqttProxyService proxy = new MqttProxyService("mqtt-monitor-test", "MQTT Monitor Test", handler, state);
        PacketMonitorService monitor = PacketMonitorService.getInstance();
        monitor.startCapture();

        try {
            invokeBrokerMessage(proxy, "msh/test/drop", new byte[] {0x01, 0x02, 0x03});
            assertEquals(0, monitor.loadAll(mqttQuery()).size());

            invokeBrokerMessage(
                    proxy,
                    "msh/test/pass",
                    serviceEnvelopePayload(decodedPacket(Portnums.PortNum.TEXT_MESSAGE_APP))
            );

            assertTrue(awaitMqttEntryCount(monitor, 1));
            List<PacketLogEntry> mqttEntries = monitor.loadAll(mqttQuery());
            assertEquals(1, mqttEntries.size());
            PacketLogEntry entry = mqttEntries.getFirst();
            assertEquals(PacketLogEntry.Direction.INCOMING, entry.getDirection());
            assertEquals("TEXT_MESSAGE_APP", entry.getPacketType());
            assertTrue(entry.getPayloadText().contains("topic=\"msh/test/pass\""));
        } finally {
            proxy.close();
            handler.shutdown();
        }
    }

    @Test
    void mqttFilterSuppressesDuplicateAcceptedDownlinkPackets() throws Exception {
        AppPreferences.setMqttDownlinkFilterMode(AppPreferences.MqttDownlinkFilterMode.FILTERED);

        DeviceState state = new DeviceState();
        state.setMyNodeNum(LOCAL_NODE_NUM);
        ProtocolHandler handler = new ProtocolHandler("mqtt-duplicate-test", new FakeTransportConnection());
        MqttProxyService proxy = new MqttProxyService("mqtt-duplicate-test", "MQTT Duplicate Test", handler, state);
        PacketMonitorService monitor = PacketMonitorService.getInstance();
        monitor.startCapture();

        MeshProtos.MeshPacket packet = decodedPacket(Portnums.PortNum.TEXT_MESSAGE_APP);
        try {
            invokeBrokerMessage(
                    proxy,
                    "msh/test/duplicate/a",
                    serviceEnvelopePayload(packet, "gateway-a")
            );
            invokeBrokerMessage(
                    proxy,
                    "msh/test/duplicate/b",
                    serviceEnvelopePayload(packet, "gateway-b")
            );

            assertTrue(awaitMqttEntryCount(monitor, 1));
            Thread.sleep(150);
            List<PacketLogEntry> mqttEntries = monitor.loadAll(mqttQuery());
            assertEquals(1, mqttEntries.size());
            assertTrue(mqttEntries.getFirst().getPayloadText().contains("topic=\"msh/test/duplicate/a\""));
        } finally {
            proxy.close();
            handler.shutdown();
        }
    }

    @Test
    void mqttMonitorDisplaysDecryptedIncomingDownlinkAfterFilterPasses() throws Exception {
        AppPreferences.setMqttDownlinkFilterMode(AppPreferences.MqttDownlinkFilterMode.FILTERED);

        DeviceState state = deviceStateWithDefaultChannelKey();
        state.setMyNodeNum(LOCAL_NODE_NUM);
        ProtocolHandler handler = new ProtocolHandler("mqtt-monitor-decrypt-test", new FakeTransportConnection());
        MqttProxyService proxy = new MqttProxyService("mqtt-monitor-decrypt-test", "MQTT Monitor Test", handler, state);
        PacketMonitorService monitor = PacketMonitorService.getInstance();
        monitor.startCapture();

        try {
            invokeBrokerMessage(
                    proxy,
                    "msh/test/encrypted-chat",
                    serviceEnvelopePayload(encryptedChannelPacket(Portnums.PortNum.TEXT_MESSAGE_APP))
            );

            assertTrue(awaitMqttEntryCount(monitor, 1));
            PacketLogEntry entry = monitor.loadAll(mqttQuery()).getFirst();
            assertEquals(PacketLogEntry.Direction.INCOMING, entry.getDirection());
            assertEquals("TEXT_MESSAGE_APP", entry.getPacketType());
            assertTrue(entry.getPayloadText().contains("topic=\"msh/test/encrypted-chat\""));
            assertTrue(entry.getPayloadText().contains("\"payload\""));
        } finally {
            proxy.close();
            handler.shutdown();
        }
    }

    private static boolean shouldForward(byte[] payload, AppPreferences.MqttDownlinkFilterMode mode) {
        return MqttProxyService.evaluateDownlinkFilter(payload, mode, LOCAL_NODE_NUM).forward();
    }

    private static void invokeBrokerMessage(MqttProxyService proxy, String topic, byte[] payload) throws Exception {
        Method method = MqttProxyService.class.getDeclaredMethod("onBrokerMessage", String.class, MqttMessage.class);
        method.setAccessible(true);
        method.invoke(proxy, topic, new MqttMessage(payload));
    }

    private static boolean awaitMqttEntryCount(PacketMonitorService monitor, int expectedCount) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            if (monitor.loadAll(mqttQuery()).size() == expectedCount) {
                return true;
            }
            Thread.sleep(25);
        }
        return monitor.loadAll(mqttQuery()).size() == expectedCount;
    }

    private static PacketMonitorService.PacketQuery mqttQuery() {
        return new PacketMonitorService.PacketQuery(
                null,
                null,
                PacketMonitorService.TRANSPORT_MQTT,
                null,
                null,
                null
        );
    }

    private static byte[] serviceEnvelopePayload(MeshProtos.MeshPacket packet) {
        return serviceEnvelopePayload(packet, "");
    }

    private static byte[] serviceEnvelopePayload(MeshProtos.MeshPacket packet, String gatewayId) {
        return MQTTProtos.ServiceEnvelope.newBuilder()
                .setPacket(packet)
                .setGatewayId(gatewayId)
                .build()
                .toByteArray();
    }

    private static MeshProtos.MeshPacket decodedPacket(Portnums.PortNum portNum) {
        return MeshProtos.MeshPacket.newBuilder()
                .setFrom(REMOTE_NODE_NUM)
                .setTo(BROADCAST_NODE_NUM)
                .setChannel(0)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(portNum)
                        .setPayload(ByteString.copyFromUtf8("payload"))
                        .build())
                .build();
    }

    private static MeshProtos.MeshPacket routingAckPacket(int toNodeNum, int requestId) {
        return MeshProtos.MeshPacket.newBuilder()
                .setFrom(REMOTE_NODE_NUM)
                .setTo(toNodeNum)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.ROUTING_APP)
                        .setRequestId(requestId)
                        .setPayload(MeshProtos.Routing.newBuilder()
                                .setErrorReason(MeshProtos.Routing.Error.NONE)
                                .build()
                                .toByteString())
                        .build())
                .build();
    }

    private static MeshProtos.MeshPacket encryptedPacket(int toNodeNum) {
        return MeshProtos.MeshPacket.newBuilder()
                .setFrom(REMOTE_NODE_NUM)
                .setTo(toNodeNum)
                .setEncrypted(ByteString.copyFrom(new byte[] {0x01, 0x02, 0x03}))
                .build();
    }

    private static MeshProtos.MeshPacket encryptedChannelPacket(Portnums.PortNum portNum) {
        MeshProtos.Data decoded = MeshProtos.Data.newBuilder()
                .setPortnum(portNum)
                .setPayload(ByteString.copyFromUtf8("payload"))
                .build();
        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(REMOTE_NODE_NUM)
                .setTo(BROADCAST_NODE_NUM)
                .setId(0x10203040)
                .setChannel(0)
                .build();
        return packet.toBuilder()
                .setEncrypted(ByteString.copyFrom(aesCtr(packet, DEFAULT_CHANNEL_KEY, decoded.toByteArray())))
                .build();
    }

    private static DeviceState deviceStateWithDefaultChannelKey() {
        DeviceState state = new DeviceState();
        state.addChannel(ChannelProtos.Channel.newBuilder()
                .setIndex(0)
                .setRole(ChannelProtos.Channel.Role.PRIMARY)
                .setSettings(ChannelProtos.ChannelSettings.newBuilder()
                        .setName("LongFast")
                        .setPsk(ByteString.copyFrom(new byte[] {1}))
                        .build())
                .build());
        return state;
    }

    private static byte[] aesCtr(MeshProtos.MeshPacket packet, byte[] key, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(nonce(packet))
            );
            return cipher.doFinal(input);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to transform test packet", e);
        }
    }

    private static byte[] nonce(MeshProtos.MeshPacket packet) {
        ByteBuffer buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(Integer.toUnsignedLong(packet.getId()));
        buffer.putInt(packet.getFrom());
        buffer.putInt(0);
        return buffer.array();
    }

    private static final class FakeTransportConnection implements TransportConnection {
        @Override
        public void connect() throws ConnectionException {}

        @Override
        public void disconnect() {}

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void sendBytes(byte[] data) {}

        @Override
        public void setDataListener(Consumer<byte[]> listener) {}

        @Override
        public void setConnectionListener(ConnectionListener listener) {}
    }
}
