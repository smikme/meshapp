package com.meshtastic.client.utils;

import com.google.protobuf.ByteString;
import com.meshtastic.client.model.DeviceState;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;
import static org.junit.jupiter.api.Assertions.*;

class MeshtasticChannelCryptoTest {
    // Independent OpenSSL/cryptography AESCCM vectors using the firmware 2.8.2 wire layout:
    // plaintext 080112046d657368, nonce 40302010000000007856341200, AAD 78563412ffffffff, tag 12 bytes.
    @Test
    void decryptsFirmwareCompatibleAes128AndAes256Packets() {
        assertVector(new byte[]{1}, 166, "d0475c5abfc3c889dd319f5bc8774c4492acfad9");
        assertVector(HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"),
                164, "1d7bb1af976454a1e8865c56d3370e20507a8dac");
    }

    private static void assertVector(byte[] key, int channelHash, String ciphertext) {
        DeviceState state = new DeviceState();
        try {
            state.addChannel(ChannelProtos.Channel.newBuilder().setIndex(0)
                    .setRole(ChannelProtos.Channel.Role.PRIMARY)
                    .setSettings(ChannelProtos.ChannelSettings.newBuilder()
                            .setPsk(ByteString.copyFrom(key)).setUseAead(true)).build());
            MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                    .setFrom(0x12345678).setTo(-1).setId(0x10203040).setChannel(channelHash)
                    .setEncrypted(ByteString.copyFrom(HexFormat.of().parseHex(ciphertext))).build();
            var decrypted = MeshtasticChannelCrypto.decryptChannelPacket(packet, state).orElseThrow();
            assertEquals(Portnums.PortNum.TEXT_MESSAGE_APP, decrypted.decoded().getPortnum());
            assertEquals("mesh", decrypted.decoded().getPayload().toStringUtf8());
            assertFalse(decrypted.packet().hasEncrypted());
            // Channel mode is selected by the wire hash; no AES-CTR fallback on an AEAD channel.
            assertTrue(MeshtasticChannelCrypto.decryptChannelPacket(packet.toBuilder()
                    .setChannel(channelHash ^ 0xAE).build(), state).isEmpty());
            // Both header and ciphertext tampering must fail authentication.
            assertTrue(MeshtasticChannelCrypto.decryptChannelPacket(packet.toBuilder().setTo(9).build(), state).isEmpty());
            assertTrue(MeshtasticChannelCrypto.decryptChannelPacket(packet.toBuilder().setFrom(9).build(), state).isEmpty());
            assertTrue(MeshtasticChannelCrypto.decryptChannelPacket(packet.toBuilder().setId(9).build(), state).isEmpty());
            byte[] altered = packet.getEncrypted().toByteArray();
            altered[altered.length - 1] ^= 1;
            assertTrue(MeshtasticChannelCrypto.decryptChannelPacket(packet.toBuilder()
                    .setEncrypted(ByteString.copyFrom(altered)).build(), state).isEmpty());
            assertTrue(MeshtasticChannelCrypto.decryptChannelPacket(packet.toBuilder()
                    .setEncrypted(ByteString.copyFrom(new byte[12])).build(), state).isEmpty());
        } finally {
            state.shutdown();
        }
    }
    @Test
    void secondaryInheritsPrimaryKeyButExplicitZeroDisablesEncryption() {
        DeviceState state = new DeviceState();
        try {
            state.addChannel(ChannelProtos.Channel.newBuilder().setIndex(0)
                    .setRole(ChannelProtos.Channel.Role.PRIMARY)
                    .setSettings(ChannelProtos.ChannelSettings.newBuilder().setPsk(ByteString.copyFrom(new byte[]{1}))).build());
            assertArrayEquals(MeshtasticChannelCrypto.normalizePsk(ByteString.copyFrom(new byte[]{1})),
                    MeshtasticChannelCrypto.resolvePsk(ByteString.EMPTY, ChannelProtos.Channel.Role.SECONDARY, state));
            assertNull(MeshtasticChannelCrypto.resolvePsk(ByteString.copyFrom(new byte[]{0}), ChannelProtos.Channel.Role.SECONDARY, state));
            assertEquals(255, MeshtasticChannelCrypto.normalizePsk(ByteString.copyFrom(new byte[]{(byte) 255}))[15] & 0xff);
            assertEquals(16, MeshtasticChannelCrypto.normalizePsk(ByteString.copyFrom(new byte[]{1, 2})).length);
        } finally { state.shutdown(); }
    }

}
