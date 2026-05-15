package com.meshtastic.client.service;

import org.junit.jupiter.api.Test;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;
import org.meshtastic.proto.MQTTProtos;

import com.google.protobuf.ByteString;
import com.meshtastic.client.model.DeviceState;

import java.nio.charset.StandardCharsets;

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
    void addressedToLocalNodeRecognizesServiceEnvelopeTarget() {
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x12345678);
        byte[] payload = MQTTProtos.ServiceEnvelope.newBuilder()
                .setPacket(MeshProtos.MeshPacket.newBuilder()
                        .setTo(0x12345678)
                        .build())
                .build()
                .toByteArray();

        assertTrue(MqttProxyService.isAddressedToLocalNode(payload, state));
    }

    @Test
    void addressedToLocalNodeRejectsOtherTargetsAndNonEnvelopePayloads() {
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x12345678);
        byte[] otherTarget = MQTTProtos.ServiceEnvelope.newBuilder()
                .setPacket(MeshProtos.MeshPacket.newBuilder()
                        .setTo(0x87654321)
                        .build())
                .build()
                .toByteArray();

        assertFalse(MqttProxyService.isAddressedToLocalNode(otherTarget, state));
        assertFalse(MqttProxyService.isAddressedToLocalNode("not protobuf".getBytes(StandardCharsets.UTF_8), state));
    }

    @Test
    void fromLocalNodeRecognizesLocalTopicSourceOnly() {
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x04c5b420);
        byte[] payload = MQTTProtos.ServiceEnvelope.newBuilder()
                .setPacket(MeshProtos.MeshPacket.newBuilder()
                        .setFrom(0x04c5b420)
                        .setTo(0x12345678)
                        .build())
                .build()
                .toByteArray();

        assertTrue(MqttProxyService.isFromLocalNode("msh/RU/MSK/2/e/PKI/!04c5b420", new byte[] {0x01}, state));
        assertFalse(MqttProxyService.isFromLocalNode("msh/RU/MSK/2/e/PKI/!12345678", payload, state));
    }

    @Test
    void fromLocalNodeRejectsRemoteTopicAndInvalidPayload() {
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x04c5b420);
        byte[] payload = MQTTProtos.ServiceEnvelope.newBuilder()
                .setPacket(MeshProtos.MeshPacket.newBuilder()
                        .setFrom(0x12345678)
                        .setTo(0x04c5b420)
                        .build())
                .build()
                .toByteArray();

        assertFalse(MqttProxyService.isFromLocalNode("msh/RU/MSK/2/e/PKI/!12345678", payload, state));
        assertFalse(MqttProxyService.isFromLocalNode("msh/RU/MSK/2/e/PKI/!12345678",
                "not protobuf".getBytes(StandardCharsets.UTF_8), state));
    }

    @Test
    void backgroundYieldRemainingMillisKeepsBackgroundDownlinkQuietAfterLocalUplink() {
        assertEquals(500, MqttProxyService.backgroundYieldRemainingMillis(1_000, 2_500));
        assertEquals(0, MqttProxyService.backgroundYieldRemainingMillis(1_000, 3_000));
        assertEquals(0, MqttProxyService.backgroundYieldRemainingMillis(0, 1_000));
    }
}
