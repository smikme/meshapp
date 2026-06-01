package com.meshtastic.client.protocol;

import com.meshtastic.client.connection.TransportConnection;
import com.meshtastic.client.model.ConnectionEntry;

/**
 * Immutable input set used to create a protocol runtime.
 *
 * @param connectionId string id of the connection profile
 * @param connectionEntry persisted connection profile
 * @param transportConnection open transport used by the protocol for byte I/O
 * @param transportDescription human-readable transport description for logs
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record ProtocolRuntimeContext(String connectionId,
                                     ConnectionEntry connectionEntry,
                                     TransportConnection transportConnection,
                                     String transportDescription) {
}
