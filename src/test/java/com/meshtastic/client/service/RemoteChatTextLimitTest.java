package com.meshtastic.client.service;

import com.meshtastic.client.model.DeviceState;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RemoteChatTextLimitTest {
    @Test
    void publishesRadioBudgetWithoutChargingChannelAuthenticationToDirectMessages() {
        DeviceState state = new DeviceState();
        try {
            assertEquals(228, RemoteRpcHostService.chatTextLimit(state, "channel", "0", -1));
            state.setDeviceMetadata(MeshProtos.DeviceMetadata.newBuilder()
                    .setFirmwareVersion("2.8.2.d5f1619").setHasXeddsa(true).build());
            state.addConfig(ConfigProtos.Config.newBuilder().setSecurity(ConfigProtos.Config.SecurityConfig.newBuilder()
                    .setPacketSignaturePolicy(ConfigProtos.Config.SecurityConfig.PacketSignaturePolicy.PACKET_SIGNATURE_POLICY_STRICT)).build());
            assertEquals(162, RemoteRpcHostService.chatTextLimit(state, "channel", "0", -1));
            assertEquals(228, RemoteRpcHostService.chatTextLimit(state, "dm", "!12345678", -1));
            state.addChannel(ChannelProtos.Channel.newBuilder().setIndex(0).setRole(ChannelProtos.Channel.Role.PRIMARY)
                    .setSettings(ChannelProtos.ChannelSettings.newBuilder().setUseAead(true)).build());
            assertEquals(216, RemoteRpcHostService.chatTextLimit(state, "channel", "0", -1));
            assertEquals(162, RemoteRpcHostService.chatTextLimit(state, "channel", "1", -1));
            assertEquals(140, RemoteRpcHostService.chatTextLimit(state, "channel", "0", 140));
            assertEquals(160, RemoteRpcHostService.chatTextLimit(state, "dm", "!12345678", 160));
            assertEquals(0, RemoteRpcHostService.chatTextLimit(state, "channel", "0", 0));
        } finally {
            state.shutdown();
        }
    }
}
