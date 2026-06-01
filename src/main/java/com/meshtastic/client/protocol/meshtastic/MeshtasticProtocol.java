package com.meshtastic.client.protocol.meshtastic;

import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.protocol.CommunicationProtocol;
import com.meshtastic.client.protocol.ProtocolRuntimeContext;

/**
 * Meshtastic protocol adapter.
 * <p>
 * Creates a Meshtastic runtime over an already opened transport. The adapter
 * does not hold connection state; all runtime state lives in
 * {@link MeshtasticProtocolRuntime}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class MeshtasticProtocol implements CommunicationProtocol<DeviceState> {

    /**
     * @return protocol type used by the registry
     */
    @Override
    public ProtocolType getType() {
        return ProtocolType.MESHTASTIC;
    }

    /**
     * Creates a Meshtastic runtime for one connection.
     *
     * @param context connection parameters and transport
     * @return Meshtastic protocol runtime
     */
    @Override
    public MeshtasticProtocolRuntime createRuntime(ProtocolRuntimeContext context) {
        return new MeshtasticProtocolRuntime(context);
    }

    /**
     * Returns whether the selected transport needs Meshtastic heartbeat traffic.
     * <p>
     * Heartbeat is part of the Meshtastic protocol, but only TCP and Serial need
     * keepalive writes; BLE does not.
     *
     * @param entry connection profile
     * @return {@code true} when heartbeat should be started
     */
    public static boolean shouldStartHeartbeat(ConnectionEntry entry) {
        ConnectionType type = entry.getEffectiveType();
        return type == ConnectionType.TCP || type == ConnectionType.SERIAL;
    }
}
