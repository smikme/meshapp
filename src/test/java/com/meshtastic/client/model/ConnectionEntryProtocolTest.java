package com.meshtastic.client.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertNull(serial.getSerialModemLineMode());
        assertEquals(SerialModemLineMode.AUTO, serial.getEffectiveSerialModemLineMode());
        assertFalse(tcp.isAutoconnect());
        assertFalse(serial.isAutoconnect());
        assertFalse(ble.isAutoconnect());
    }

    @Test
    void effectiveProtocolKeepsLegacyJsonEntriesCompatible() {
        ConnectionEntry legacy = new ConnectionEntry("legacy", "127.0.0.1", 4403);
        legacy.setProtocol(null);

        assertNull(legacy.getProtocol());
        assertEquals(ProtocolType.MESHTASTIC, legacy.getEffectiveProtocol());
        assertFalse(legacy.isAutoconnect());
    }

    @Test
    void autoconnectFlagCanBeEnabled() {
        ConnectionEntry entry = new ConnectionEntry("tcp", "127.0.0.1", 4403);

        entry.setAutoconnect(true);

        assertTrue(entry.isAutoconnect());
    }

    @Test
    void remoteRpcEntryForcesRemoteRpcProtocol() {
        ConnectionEntry entry = ConnectionEntry.remoteRpc("rpc", "127.0.0.1", 44030, "mra1_test");

        entry.setProtocol(ProtocolType.MESHTASTIC);

        assertEquals(ProtocolType.REMOTE_RPC, entry.getProtocol());
        assertEquals(ConnectionType.REMOTE_RPC, entry.getEffectiveType());
        assertEquals(ProtocolType.REMOTE_RPC, entry.getEffectiveProtocol());
        assertEquals(RemoteRpcConnectionMode.DIRECT, entry.getEffectiveRemoteRpcMode());
    }

    @Test
    void remoteRpcRouterEntryKeepsRouterMode() {
        ConnectionEntry entry = ConnectionEntry.remoteRpcRouter("rpc", "router.example.org", 8080, "mra1_test");

        assertEquals(ProtocolType.REMOTE_RPC, entry.getProtocol());
        assertEquals(ConnectionType.REMOTE_RPC, entry.getEffectiveType());
        assertEquals(ProtocolType.REMOTE_RPC, entry.getEffectiveProtocol());
        assertEquals(RemoteRpcConnectionMode.ROUTER, entry.getEffectiveRemoteRpcMode());
        assertEquals(ConnectionEntry.CLOUD_RPC_ROUTER_SERVER, entry.getHost());
        assertEquals(ConnectionEntry.CLOUD_RPC_ROUTER_PORT, entry.getPort());
    }

    @Test
    void remoteRpcProtocolForcesRemoteRpcType() {
        ConnectionEntry entry = new ConnectionEntry("rpc", "127.0.0.1", 44030);

        entry.setProtocol(ProtocolType.REMOTE_RPC);

        assertEquals(ConnectionType.REMOTE_RPC, entry.getType());
        assertEquals(ConnectionType.REMOTE_RPC, entry.getEffectiveType());
        assertEquals(ProtocolType.REMOTE_RPC, entry.getEffectiveProtocol());
    }
}
