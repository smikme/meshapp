package com.meshtastic.client.service;

import org.junit.jupiter.api.Test;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;

import com.google.protobuf.ByteString;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.utils.AppPreferences;

import java.nio.charset.StandardCharsets;

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

    private static boolean shouldForward(byte[] payload, AppPreferences.MqttDownlinkFilterMode mode) {
        return MqttProxyService.evaluateDownlinkFilter(payload, mode, LOCAL_NODE_NUM).forward();
    }

    private static byte[] serviceEnvelopePayload(MeshProtos.MeshPacket packet) {
        return MQTTProtos.ServiceEnvelope.newBuilder()
                .setPacket(packet)
                .build()
                .toByteArray();
    }

    private static MeshProtos.MeshPacket decodedPacket(Portnums.PortNum portNum) {
        return MeshProtos.MeshPacket.newBuilder()
                .setFrom(REMOTE_NODE_NUM)
                .setTo(0xFFFFFFFF)
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
}
