package com.meshtastic.client.rpc;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AES-GCM frame protection for one authenticated direct RPC session.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class RpcSessionCipher {

    private static final String FRAME_PREFIX = "enc1_";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String AES_ALGORITHM = "AES";
    private static final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int AES_KEY_BYTES = 32;
    private static final int NONCE_PREFIX_BYTES = 4;
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final byte[] SALT_CONTEXT = "meshapp-rpc-secure-v1:salt".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FRAME_AAD = "meshapp-rpc-secure-v1:frame".getBytes(StandardCharsets.UTF_8);
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec outboundKey;
    private final SecretKeySpec inboundKey;
    private final byte[] outboundNoncePrefix;
    private final byte[] inboundNoncePrefix;
    private final AtomicLong outboundCounter = new AtomicLong();
    private final AtomicLong inboundCounter = new AtomicLong();

    private RpcSessionCipher(byte[] outboundKey,
                             byte[] outboundNoncePrefix,
                             byte[] inboundKey,
                             byte[] inboundNoncePrefix) {
        this.outboundKey = new SecretKeySpec(Objects.requireNonNull(outboundKey, "outboundKey"), AES_ALGORITHM);
        this.inboundKey = new SecretKeySpec(Objects.requireNonNull(inboundKey, "inboundKey"), AES_ALGORITHM);
        this.outboundNoncePrefix = Objects.requireNonNull(outboundNoncePrefix, "outboundNoncePrefix");
        this.inboundNoncePrefix = Objects.requireNonNull(inboundNoncePrefix, "inboundNoncePrefix");
    }

    static RpcSessionCipher server(RpcAccessKey accessKey, String serverNonce, String clientNonce) {
        KeyMaterial material = derive(accessKey, serverNonce, clientNonce);
        return new RpcSessionCipher(
                material.serverToClientKey(),
                material.serverToClientNoncePrefix(),
                material.clientToServerKey(),
                material.clientToServerNoncePrefix());
    }

    static RpcSessionCipher client(RpcAccessKey accessKey, String serverNonce, String clientNonce) {
        KeyMaterial material = derive(accessKey, serverNonce, clientNonce);
        return new RpcSessionCipher(
                material.clientToServerKey(),
                material.clientToServerNoncePrefix(),
                material.serverToClientKey(),
                material.serverToClientNoncePrefix());
    }

    String encrypt(String plaintext) {
        Objects.requireNonNull(plaintext, "plaintext");
        long sequence = outboundCounter.getAndIncrement();
        if (sequence < 0) {
            throw new IllegalStateException("RPC secure session frame counter exhausted");
        }
        try {
            Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    outboundKey,
                    new GCMParameterSpec(GCM_TAG_BITS, nonce(outboundNoncePrefix, sequence)));
            cipher.updateAAD(FRAME_AAD);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return FRAME_PREFIX + ENCODER.encodeToString(encrypted);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to encrypt RPC frame", e);
        }
    }

    String decrypt(String frame) throws IOException {
        Objects.requireNonNull(frame, "frame");
        if (!frame.startsWith(FRAME_PREFIX)) {
            throw new IOException("encrypted RPC frame expected");
        }
        long sequence = inboundCounter.getAndIncrement();
        if (sequence < 0) {
            throw new IOException("RPC secure session frame counter exhausted");
        }
        try {
            byte[] encrypted = DECODER.decode(frame.substring(FRAME_PREFIX.length()));
            Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    inboundKey,
                    new GCMParameterSpec(GCM_TAG_BITS, nonce(inboundNoncePrefix, sequence)));
            cipher.updateAAD(FRAME_AAD);
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IOException("encrypted RPC frame authentication failed", e);
        }
    }

    private static KeyMaterial derive(RpcAccessKey accessKey, String serverNonce, String clientNonce) {
        Objects.requireNonNull(accessKey, "accessKey");
        byte[] salt = transcript(serverNonce, clientNonce);
        byte[] prk = hkdfExtract(salt, accessKey.keyMaterial());
        return new KeyMaterial(
                hkdfExpand(prk, info("client-to-server:key"), AES_KEY_BYTES),
                hkdfExpand(prk, info("client-to-server:nonce-prefix"), NONCE_PREFIX_BYTES),
                hkdfExpand(prk, info("server-to-client:key"), AES_KEY_BYTES),
                hkdfExpand(prk, info("server-to-client:nonce-prefix"), NONCE_PREFIX_BYTES));
    }

    private static byte[] hkdfExtract(byte[] salt, byte[] inputKeyMaterial) {
        return hmac(salt, inputKeyMaterial);
    }

    private static byte[] hkdfExpand(byte[] pseudoRandomKey, byte[] info, int length) {
        byte[] output = new byte[length];
        byte[] previous = new byte[0];
        int generated = 0;
        int counter = 1;
        while (generated < length) {
            ByteBuffer input = ByteBuffer.allocate(previous.length + info.length + 1);
            input.put(previous);
            input.put(info);
            input.put((byte) counter);
            previous = hmac(pseudoRandomKey, input.array());
            int copy = Math.min(previous.length, length - generated);
            System.arraycopy(previous, 0, output, generated, copy);
            generated += copy;
            counter++;
        }
        return output;
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to derive RPC session keys", e);
        }
    }

    private static byte[] nonce(byte[] prefix, long sequence) {
        byte[] nonce = new byte[GCM_NONCE_BYTES];
        System.arraycopy(prefix, 0, nonce, 0, Math.min(prefix.length, NONCE_PREFIX_BYTES));
        ByteBuffer.wrap(nonce, NONCE_PREFIX_BYTES, Long.BYTES).putLong(sequence);
        return nonce;
    }

    private static byte[] transcript(String serverNonce, String clientNonce) {
        byte[] server = requireText(serverNonce, "serverNonce").getBytes(StandardCharsets.UTF_8);
        byte[] client = requireText(clientNonce, "clientNonce").getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(SALT_CONTEXT.length + 4 + server.length + 4 + client.length);
        buffer.put(SALT_CONTEXT);
        buffer.putInt(server.length);
        buffer.put(server);
        buffer.putInt(client.length);
        buffer.put(client);
        return buffer.array();
    }

    private static byte[] info(String label) {
        return ("meshapp-rpc-secure-v1:" + label).getBytes(StandardCharsets.UTF_8);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private record KeyMaterial(byte[] clientToServerKey,
                               byte[] clientToServerNoncePrefix,
                               byte[] serverToClientKey,
                               byte[] serverToClientNoncePrefix) {
        private KeyMaterial {
            clientToServerKey = Arrays.copyOf(clientToServerKey, clientToServerKey.length);
            clientToServerNoncePrefix = Arrays.copyOf(clientToServerNoncePrefix, clientToServerNoncePrefix.length);
            serverToClientKey = Arrays.copyOf(serverToClientKey, serverToClientKey.length);
            serverToClientNoncePrefix = Arrays.copyOf(serverToClientNoncePrefix, serverToClientNoncePrefix.length);
        }
    }
}
