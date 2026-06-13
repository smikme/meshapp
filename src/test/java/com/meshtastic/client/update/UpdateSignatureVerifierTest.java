package com.meshtastic.client.update;

import com.google.gson.Gson;
import com.meshtastic.client.model.SelfUpdateArtifact;
import com.meshtastic.client.model.UpdateInfo;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateSignatureVerifierTest {

    private final Gson gson = new Gson();

    @Test
    void verifiesSignedSelfUpdateArtifact() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        UpdateInfo unsignedInfo = parse("""
                {
                  "version": "2.1.11",
                  "versionCode": 2111,
                  "selfUpdate": {
                    "linux-x86_64": {
                      "type": "full-archive",
                      "format": "zip",
                      "version": "2.1.11",
                      "url": "https://example.invalid/MeshApp.zip",
                      "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                      "size": 42
                    }
                  }
                }
                """);
        SelfUpdateArtifact unsignedArtifact = unsignedInfo.getSelfUpdate()
                .get("linux-x86_64");

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(unsignedArtifact.signaturePayload(
                unsignedInfo.getVersion(),
                unsignedInfo.getVersionCode()
        ));
        String signature = Base64.getEncoder().encodeToString(signer.sign());

        UpdateInfo signedInfo = parse("""
                {
                  "version": "2.1.11",
                  "versionCode": 2111,
                  "selfUpdate": {
                    "linux-x86_64": {
                      "type": "full-archive",
                      "format": "zip",
                      "version": "2.1.11",
                      "url": "https://example.invalid/MeshApp.zip",
                      "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                      "size": 42,
                      "signature": "%s"
                    }
                  }
                }
                """.formatted(signature));
        SelfUpdateArtifact signedArtifact = signedInfo.getSelfUpdate()
                .get("linux-x86_64");

        assertTrue(UpdateSignatureVerifier.trusted(keyPair.getPublic())
                .isTrusted(signedInfo, signedArtifact));
    }

    @Test
    void rejectsUnsignedArtifactWhenPublicKeyIsConfigured() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        UpdateInfo info = parse("""
                {
                  "version": "2.1.11",
                  "versionCode": 2111,
                  "selfUpdate": {
                    "linux-x86_64": {
                      "url": "https://example.invalid/MeshApp.zip",
                      "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                    }
                  }
                }
                """);

        assertFalse(UpdateSignatureVerifier.trusted(keyPair.getPublic())
                .isTrusted(info, info.getSelfUpdate().get("linux-x86_64")));
    }

    private UpdateInfo parse(String json) {
        return gson.fromJson(json, UpdateInfo.class);
    }
}
