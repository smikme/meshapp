package com.meshtastic.client.utils;

import com.meshtastic.client.model.DeviceState;
import org.meshtastic.proto.ConfigProtos;

/** Extra bytes the radio adds to a channel broadcast before transmission. */
public final class MeshtasticPayloadLimits {
    private MeshtasticPayloadLimits() {}

    public static int channelAuthenticationOverhead(DeviceState state, int channelIndex) {
        if (state == null) {
            return 0;
        }
        var channel = state.getChannelStore().getChannelByIndex(channelIndex);
        if (channel != null && channel.getSettings().getUseAead()) {
            return 12; // Firmware AES-CCM tag; AEAD channels do not also sign.
        }
        if (state.getFirmwareCapabilities().xeddsa()) {
            for (var config : state.getConfigs()) {
                if (config.hasSecurity() && config.getSecurity().getPacketSignaturePolicy()
                        != ConfigProtos.Config.SecurityConfig.PacketSignaturePolicy.PACKET_SIGNATURE_POLICY_COMPATIBLE) {
                    return 66; // Signature bytes plus protobuf tag and length.
                }
            }
        }
        return 0;
    }
}
