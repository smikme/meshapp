package com.meshtastic.client.utils;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.meshtastic.client.model.DeviceState;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.CCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

/**
 * Meshtastic channel PSK crypto used for inspecting MQTT downlink packets before
 * they are forwarded to a local node.
 * <p>
 * The implementation mirrors the firmware AES-CTR/AES-CCM nonce layout and channel PSK
 * shorthand rules closely enough to classify encrypted channel packets for
 * filtering and packet monitoring. It does not handle PKI/direct-message
 * encryption.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class MeshtasticChannelCrypto {

    private static final byte[] DEFAULT_CHANNEL_KEY = new byte[] {
            (byte) 0xd4, (byte) 0xf1, (byte) 0xbb, 0x3a,
            0x20, 0x29, 0x07, 0x59,
            (byte) 0xf0, (byte) 0xbc, (byte) 0xff, (byte) 0xab,
            (byte) 0xcf, 0x4e, 0x69, 0x01
    };
    private static final String AES_ALGORITHM = "AES";
    private static final String AES_CTR_TRANSFORMATION = "AES/CTR/NoPadding";
    private static final int AES_128_BYTES = 16;
    private static final int AES_256_BYTES = 32;
    private static final int CCM_TAG_BYTES = 12;

    private record ChannelKey(ByteString bytes, boolean aead) {}

    private MeshtasticChannelCrypto() {}

    /**
     * Result of a successful channel-packet decryption.
     *
     * @param packet original packet with the encrypted payload replaced by decoded data
     * @param decoded parsed Meshtastic data payload
     */
    public record DecryptionResult(MeshProtos.MeshPacket packet, MeshProtos.Data decoded) {}

    /**
     * Attempts to decrypt a channel-encrypted MeshPacket using the local node's known channels.
     *
     * @param packet packet received from MQTT
     * @param state local device state containing channel settings and PSKs
     * @return decrypted packet and decoded data, or empty when the packet is not channel-encrypted
     *         with a known key
     */
    public static Optional<DecryptionResult> decryptChannelPacket(MeshProtos.MeshPacket packet,
                                                                  DeviceState state) {
        if (packet == null || state == null || !packet.hasEncrypted() || packet.getPkiEncrypted()) {
            return Optional.empty();
        }

        byte[] encrypted = packet.getEncrypted().toByteArray();
        if (encrypted.length == 0) {
            return Optional.empty();
        }

        for (ChannelKey key : channelKeys(state, packet.getChannel())) {
            byte[] decrypted = key.aead()
                    ? decryptAesCcm(packet, key.bytes().toByteArray(), encrypted)
                    : decryptAesCtr(packet, key.bytes().toByteArray(), encrypted);
            if (decrypted == null) {
                continue;
            }
            MeshProtos.Data decoded = parseDecodedData(decrypted);
            if (decoded == null) {
                continue;
            }
            MeshProtos.MeshPacket decryptedPacket = packet.toBuilder()
                    .clearPayloadVariant()
                    .setDecoded(decoded)
                    .build();
            return Optional.of(new DecryptionResult(decryptedPacket, decoded));
        }

        return Optional.empty();
    }

    /**
     * Converts a Meshtastic channel PSK field into a usable AES key.
     * <p>
     * A one-byte PSK is Meshtastic shorthand: {@code 0} disables encryption,
     * {@code 1} selects the default key, and {@code 2..255} select the default key
     * with the last byte incremented. Full 16-byte and 32-byte values are used as
     * AES-128/AES-256 keys. Short keys are zero-padded as in the firmware.
     *
     * @param psk raw protobuf PSK value from channel settings
     * @return AES key bytes, or {@code null} when the channel is unencrypted or unsupported
     */
    public static byte[] normalizePsk(ByteString psk) {
        if (psk == null || psk.isEmpty()) {
            return null;
        }

        byte[] raw = psk.toByteArray();
        if (raw.length == 1) {
            int shorthand = raw[0] & 0xFF;
            if (shorthand == 0) {
                return null;
            }
            byte[] key = DEFAULT_CHANNEL_KEY.clone();
            key[key.length - 1] = (byte) ((key[key.length - 1] & 0xFF) + shorthand - 1);
            return key;
        }

        if (raw.length > AES_256_BYTES) {
            return null;
        }
        return Arrays.copyOf(raw, raw.length <= AES_128_BYTES ? AES_128_BYTES : AES_256_BYTES);
    }

    /** An empty secondary-channel PSK inherits the primary key; explicit shorthand 0 does not. */
    public static byte[] resolvePsk(ByteString psk, ChannelProtos.Channel.Role role, DeviceState state) {
        if (psk != null && psk.isEmpty() && role == ChannelProtos.Channel.Role.SECONDARY && state != null) {
            synchronized (state.getChannels()) {
                for (ChannelProtos.Channel channel : state.getChannels()) {
                    if (channel.getRole() == ChannelProtos.Channel.Role.PRIMARY && channel.hasSettings()) {
                        return normalizePsk(channel.getSettings().getPsk());
                    }
                }
            }
        }
        return normalizePsk(psk);
    }

    private static List<ChannelKey> channelKeys(DeviceState state, int channelHash) {
        List<ChannelProtos.Channel> channels = state.getChannels();
        if (channels == null || channels.isEmpty()) {
            return List.of();
        }

        Set<ChannelKey> uniqueKeys = new LinkedHashSet<>();
        synchronized (channels) {
            for (ChannelProtos.Channel channel : channels) {
                if (channel == null
                        || channel.getRole() == ChannelProtos.Channel.Role.DISABLED
                        || !channel.hasSettings()) {
                    continue;
                }
                byte[] key = resolvePsk(channel.getSettings().getPsk(), channel.getRole(), state);
                if (key != null && channelHash == channelHash(channel.getSettings(), key, state)) {
                    uniqueKeys.add(new ChannelKey(ByteString.copyFrom(key), channel.getSettings().getUseAead()));
                }
            }
        }

        return new ArrayList<>(uniqueKeys);
    }

    private static int channelHash(ChannelProtos.ChannelSettings settings, byte[] key, DeviceState state) {
        String name = settings.getName();
        if (name.isEmpty()) {
            var lora = state.getConfigs().stream().filter(ConfigProtos.Config::hasLora)
                    .map(ConfigProtos.Config::getLora).findFirst().orElse(null);
            if (lora == null) {
                name = "LongFast";
            } else if (!lora.getUsePreset()) {
                name = "Custom";
            } else if (lora.getModemPreset() == ConfigProtos.Config.LoRaConfig.ModemPreset.LONG_MODERATE) {
                name = "LongMod";
            } else {
                StringBuilder preset = new StringBuilder();
                for (String word : lora.getModemPreset().name().split("_")) {
                    preset.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
                }
                name = preset.toString();
            }
        }
        int hash = settings.getUseAead() ? 0xAE : 0;
        for (byte value : name.getBytes(StandardCharsets.UTF_8)) { hash ^= value & 0xff; }
        for (byte value : key) { hash ^= value & 0xff; }
        return hash;
    }

    private static byte[] decryptAesCcm(MeshProtos.MeshPacket packet, byte[] key, byte[] encrypted) {
        if (encrypted.length <= CCM_TAG_BYTES) {
            return null;
        }
        // Firmware 2.8.2 uses L=2 (13-byte nonce), a 96-bit tag and LE sender/recipient AAD.
        byte[] aad = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(packet.getFrom()).putInt(packet.getTo()).array();
        var cipher = CCMBlockCipher.newInstance(AESEngine.newInstance());
        cipher.init(false, new AEADParameters(new KeyParameter(key), CCM_TAG_BYTES * 8,
                Arrays.copyOf(nonce(packet), 13), aad));
        try {
            byte[] plaintext = new byte[cipher.getOutputSize(encrypted.length)];
            int length = cipher.processBytes(encrypted, 0, encrypted.length, plaintext, 0);
            length += cipher.doFinal(plaintext, length);
            return Arrays.copyOf(plaintext, length);
        } catch (InvalidCipherTextException e) {
            return null;
        }
    }

    private static byte[] decryptAesCtr(MeshProtos.MeshPacket packet, byte[] key, byte[] encrypted) {
        if (packet == null || key == null || encrypted == null) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance(AES_CTR_TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, AES_ALGORITHM),
                    new IvParameterSpec(nonce(packet))
            );
            return cipher.doFinal(encrypted);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] nonce(MeshProtos.MeshPacket packet) {
        ByteBuffer buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(Integer.toUnsignedLong(packet.getId()));
        buffer.putInt(packet.getFrom());
        buffer.putInt(0);
        return buffer.array();
    }

    private static MeshProtos.Data parseDecodedData(byte[] bytes) {
        try {
            MeshProtos.Data decoded = MeshProtos.Data.parseFrom(bytes);
            return isMeaningfulDecodedData(decoded) ? decoded : null;
        } catch (InvalidProtocolBufferException e) {
            return null;
        }
    }

    private static boolean isMeaningfulDecodedData(MeshProtos.Data decoded) {
        if (decoded == null) {
            return false;
        }
        Portnums.PortNum portNum = decoded.getPortnum();
        return portNum != Portnums.PortNum.UNKNOWN_APP
                && portNum != Portnums.PortNum.UNRECOGNIZED;
    }

}
