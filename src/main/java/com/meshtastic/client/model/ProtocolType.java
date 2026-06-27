package com.meshtastic.client.model;

/**
 * Communication protocol layered on top of the selected transport.
 * <p>
 * Values are persisted by name in {@code ~/.meshapp/connections.json}. New
 * protocol adapters can be added here without changing the TCP, serial, or BLE
 * transport model.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public enum ProtocolType {
    /** Meshtastic protocol over TCP, serial, or BLE transport. */
    MESHTASTIC,

    /** MeshCore KISS modem protocol over a TCP or serial byte stream. */
    MESHCORE_KISS,

    /** MeshCore Companion Protocol over BLE RX/TX or a raw TCP/serial byte stream. */
    MESHCORE_COMPANION,

    /** MeshApp host RPC over a direct TCP or router-backed RPC session. */
    REMOTE_RPC
}
