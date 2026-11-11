package com.meshtastic.client.utils;

import com.meshtastic.client.model.DeviceState;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MeshtasticPayloadLimitsTest {
    @Test
    void reservesSignatureBytesAndLetsAeadTakePrecedence() {
        DeviceState state = new DeviceState();
        try {
            assertEquals(0, MeshtasticPayloadLimits.channelAuthenticationOverhead(state, 0));
            state.setDeviceMetadata(MeshProtos.DeviceMetadata.newBuilder()
                    .setFirmwareVersion("2.8.2.d5f1619").setHasXeddsa(true).build());
            state.addConfig(ConfigProtos.Config.newBuilder().setSecurity(ConfigProtos.Config.SecurityConfig.newBuilder()
                    .setPacketSignaturePolicy(ConfigProtos.Config.SecurityConfig.PacketSignaturePolicy.PACKET_SIGNATURE_POLICY_STRICT)).build());
            assertEquals(66, MeshtasticPayloadLimits.channelAuthenticationOverhead(state, 0));
            state.addChannel(ChannelProtos.Channel.newBuilder().setIndex(0).setRole(ChannelProtos.Channel.Role.PRIMARY)
                    .setSettings(ChannelProtos.ChannelSettings.newBuilder().setUseAead(true)).build());
            assertEquals(12, MeshtasticPayloadLimits.channelAuthenticationOverhead(state, 0));
            assertEquals(66, MeshtasticPayloadLimits.channelAuthenticationOverhead(state, 1));
        } finally { state.shutdown(); }
    }
}
