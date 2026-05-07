package com.meshtastic.client.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class ConnectionEntryProtocolTest {

    @Test
    void constructorsDefaultToMeshtasticProtocol() {
        ConnectionEntry tcp = new ConnectionEntry("tcp", "127.0.0.1", 4403);
        ConnectionEntry serial = new ConnectionEntry("serial", "COM3", 115200, ConnectionType.SERIAL);
        ConnectionEntry ble = new ConnectionEntry("ble", "AA:BB:CC:DD:EE:FF", "Test BLE");

        assertEquals(ProtocolType.MESHTASTIC, tcp.getProtocol());
        assertEquals(ProtocolType.MESHTASTIC, serial.getProtocol());
        assertEquals(ProtocolType.MESHTASTIC, ble.getProtocol());
        assertEquals(ProtocolType.MESHTASTIC, tcp.getEffectiveProtocol());
        assertEquals(ProtocolType.MESHTASTIC, serial.getEffectiveProtocol());
        assertEquals(ProtocolType.MESHTASTIC, ble.getEffectiveProtocol());
    }

    @Test
    void effectiveProtocolKeepsLegacyJsonEntriesCompatible() {
        ConnectionEntry legacy = new ConnectionEntry("legacy", "127.0.0.1", 4403);
        legacy.setProtocol(null);

        assertNull(legacy.getProtocol());
        assertEquals(ProtocolType.MESHTASTIC, legacy.getEffectiveProtocol());
    }

}
