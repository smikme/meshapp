package com.meshtastic.client.rpc;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class ExternalRouterAddressTest {
    @Test
    void buildsClientUriFromHostPortAndDerivedRoomId() {
        RpcAccessKey key = RpcAccessKey.parse("mra1_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8");

        URI uri = ExternalRouterAddress.clientUri("router.example.org", 8080, key);

        assertEquals(
                "ws://router.example.org:8080/rpc?roomId=erpc1_MpmfGysDJIvccpcIQYIfh0aeET-OORKNPAXG-UoAyK0&role=client",
                uri.toString());
    }

    @Test
    void keepsExplicitSecureSchemeAndPath() {
        RpcAccessKey key = RpcAccessKey.parse("mra1_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8");

        URI uri = ExternalRouterAddress.uri(
                "wss://router.example.org/mesh-rpc",
                8443,
                key.roomId(),
                "host");

        assertEquals(
                "wss://router.example.org:8443/mesh-rpc?roomId=erpc1_MpmfGysDJIvccpcIQYIfh0aeET-OORKNPAXG-UoAyK0&role=host",
                uri.toString());
    }
}
