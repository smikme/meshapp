package com.meshtastic.client.terminal;

import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.protocol.meshcore.MeshCoreCompanionProtocolRuntime;

/**
 * Runtime objects for the currently active terminal connection.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
record ActiveConnection(String connectionId,
                        DeviceState state,
                        ProtocolHandler handler,
                        MeshCoreCompanionProtocolRuntime meshCore) {
}
