package com.meshtastic.client.connection;

import com.meshtastic.client.model.ProtocolType;

/**
 * Framing format a transport applies to a continuous byte stream.
 * <p>
 * TCP and Serial do not preserve protocol message boundaries, so the transport
 * selects the parser matching the active protocol before runtime startup. BLE
 * usually delivers complete payloads and does not use this enum.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public enum FrameFormat {
    /** Meshtastic serial/TCP framing with the {@code 0x94 0xC3} header. */
    MESHTASTIC,

    /** Standard KISS TNC framing for the MeshCore KISS modem protocol. */
    KISS,

    /** Raw MeshCore Companion packets without a KISS wrapper. */
    MESHCORE_COMPANION;

    /**
     * Returns the framing format required by the selected protocol.
     *
     * @param protocolType selected protocol
     * @return framing format for TCP or Serial transport
     */
    public static FrameFormat forProtocol(ProtocolType protocolType) {
        if (protocolType == ProtocolType.MESHCORE_KISS) {
            return KISS;
        }
        if (protocolType == ProtocolType.MESHCORE_COMPANION) {
            return MESHCORE_COMPANION;
        }
        return MESHTASTIC;
    }
}
