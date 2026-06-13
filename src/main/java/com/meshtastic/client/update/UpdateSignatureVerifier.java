package com.meshtastic.client.update;

import com.meshtastic.client.model.SelfUpdateArtifact;
import com.meshtastic.client.model.UpdateInfo;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Verifies self-update artifact signatures.
 */
public final class UpdateSignatureVerifier {

    public static final String PROP_PUBLIC_KEY = "meshapp.update.ed25519PublicKey";
    public static final String ENV_PUBLIC_KEY = "MESHAPP_UPDATE_ED25519_PUBLIC_KEY";
    public static final String PROP_ALLOW_UNSIGNED = "meshapp.update.allowUnsigned";
    public static final String ENV_ALLOW_UNSIGNED = "MESHAPP_UPDATE_ALLOW_UNSIGNED";

    private final PublicKey publicKey;
    private final boolean allowUnsigned;

    private UpdateSignatureVerifier(PublicKey publicKey, boolean allowUnsigned) {
        this.publicKey = publicKey;
        this.allowUnsigned = allowUnsigned;
    }

    public static UpdateSignatureVerifier current() {
        String keyText = firstNonBlank(
                System.getProperty(PROP_PUBLIC_KEY),
                System.getenv(ENV_PUBLIC_KEY),
                readBundledPublicKey()
        );
        PublicKey key = keyText == null ? null : parseEd25519PublicKey(keyText);
        boolean allowUnsigned = Boolean.parseBoolean(firstNonBlank(
                System.getProperty(PROP_ALLOW_UNSIGNED),
                System.getenv(ENV_ALLOW_UNSIGNED),
                "false"
        ));
        return new UpdateSignatureVerifier(key, allowUnsigned);
    }

    static UpdateSignatureVerifier trusted(PublicKey publicKey) {
        return new UpdateSignatureVerifier(publicKey, false);
    }

    static UpdateSignatureVerifier allowUnsignedForTests() {
        return new UpdateSignatureVerifier(null, true);
    }

    public boolean isTrusted(UpdateInfo info, SelfUpdateArtifact artifact) {
        if (info == null || artifact == null) {
            return false;
        }
        if (publicKey == null) {
            return allowUnsigned;
        }
        String signatureText = artifact.getSignature();
        if (signatureText == null || signatureText.isBlank()) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(publicKey);
            signature.update(artifact.signaturePayload(info.getVersion(), info.getVersionCode()));
            byte[] signatureBytes = Base64.getDecoder().decode(signatureText.trim());
            return signature.verify(signatureBytes);
        } catch (Exception ignored) {
            return false;
        }
    }

    static PublicKey parseEd25519PublicKey(String keyText) {
        try {
            String normalized = keyText
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] encoded = Base64.getDecoder().decode(normalized);
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Ed25519 update public key", e);
        }
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }

    private static String readBundledPublicKey() {
        try (var input = UpdateSignatureVerifier.class
                .getResourceAsStream("/update/ed25519-public-key.txt")) {
            if (input == null) {
                return null;
            }
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        }
    }
}
