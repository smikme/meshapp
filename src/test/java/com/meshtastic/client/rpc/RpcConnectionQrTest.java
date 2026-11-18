package com.meshtastic.client.rpc;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.meshtastic.client.model.ConnectionEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RpcConnectionQrTest {
    private static final String KEY = "mra1_" + "A".repeat(43);

    @Test
    void cloudIncludesTheKeyAndIgnoresAllLocalListenerSettings() {
        var payload = JsonParser.parseString(RpcConnectionQr.encode(
                true, "192.168.42.87", "44030", KEY)).getAsJsonObject();
        assertEquals("meshapp.rpc", payload.get("type").getAsString());
        assertEquals(1, payload.get("version").getAsInt());
        assertEquals("router", payload.get("mode").getAsString());
        assertEquals(ConnectionEntry.CLOUD_RPC_ROUTER_SERVER, payload.get("address").getAsString());
        assertEquals(KEY, payload.get("accessKey").getAsString());
        assertFalse(payload.has("port"));
        assertFalse(payload.toString().contains("192.168.42.87"));
        assertFalse(payload.toString().contains("44030"));
        assertEquals(payload.toString(), RpcConnectionQr.encode(true, "", "invalid", KEY));
        assertEquals(payload.toString(), RpcConnectionQr.encode(true, "0.0.0.0", "65536", KEY));
    }

    @Test
    void directIncludesItsAddressPortAndKey() {
        var payload = JsonParser.parseString(RpcConnectionQr.encode(
                false, " 192.168.1.25 ", " 44031 ", " " + KEY + " ")).getAsJsonObject();
        assertEquals("direct", payload.get("mode").getAsString());
        assertEquals("192.168.1.25", payload.get("address").getAsString());
        assertEquals(44031, payload.get("port").getAsInt());
        assertEquals(KEY, payload.get("accessKey").getAsString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"mesh-host.local", "2001:db8::25", "[2001:db8::25]"})
    void supportsHostnamesAndIpv6WithoutResolvingThem(String address) {
        var payload = JsonParser.parseString(RpcConnectionQr.encode(
                false, address, "44030", KEY)).getAsJsonObject();
        assertEquals(address.replace("[", "").replace("]", ""), payload.get("address").getAsString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0.0.0.0", "::", "[::]", "0:0:0:0:0:0:0:0", "host/path",
            "wss://cloud.example", "host:44030", "a b", "host?key=value"})
    void refusesAddressesThatCannotBeUsedAsADirectHost(String address) {
        assertThrows(IllegalArgumentException.class,
                () -> RpcConnectionQr.encode(false, address, "44030", KEY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "65536", "-1", "abc"})
    void refusesInvalidDirectPorts(String port) {
        assertThrows(IllegalArgumentException.class,
                () -> RpcConnectionQr.encode(false, "192.168.1.25", port, KEY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid", "mra1_AA"})
    void requiresAnActualAccessKeyInBothModes(String key) {
        for (boolean cloud : new boolean[] {false, true}) {
            assertThrows(IllegalArgumentException.class,
                    () -> RpcConnectionQr.encode(cloud, "192.168.1.25", "44030", key));
        }
    }
}
