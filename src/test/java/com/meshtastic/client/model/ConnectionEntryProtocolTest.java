package com.meshtastic.client.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConnectionEntryProtocolTest {

    @Test
    void constructorsDefaultToAutomaticProtocol() {
        ConnectionEntry tcp = new ConnectionEntry("tcp", "127.0.0.1", 4403);
        ConnectionEntry serial = new ConnectionEntry("serial", "COM3", 115200, ConnectionType.SERIAL);
        ConnectionEntry ble = new ConnectionEntry("ble", "AA:BB:CC:DD:EE:FF", "Test BLE");

        assertEquals(ProtocolType.AUTO, tcp.getProtocol());
        assertEquals(ProtocolType.AUTO, serial.getProtocol());
        assertEquals(ProtocolType.AUTO, ble.getProtocol());
        assertEquals(ProtocolType.AUTO, tcp.getEffectiveProtocol());
        assertEquals(ProtocolType.AUTO, serial.getEffectiveProtocol());
        assertEquals(ProtocolType.AUTO, ble.getEffectiveProtocol());
    }

    @Test
    void effectiveProtocolKeepsLegacyJsonEntriesCompatible() {
        ConnectionEntry legacy = new ConnectionEntry("legacy", "127.0.0.1", 4403);
        legacy.setProtocol(null);

        assertNull(legacy.getProtocol());
        assertEquals(ProtocolType.MESHTASTIC, legacy.getEffectiveProtocol());
    }
}
