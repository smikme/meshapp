package com.meshtastic.client.protocol.meshcore;

import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.protocol.CommunicationProtocol;
import com.meshtastic.client.protocol.ProtocolRuntimeContext;

/**
 * MeshCore Companion Protocol adapter for the shared protocol registry.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class MeshCoreCompanionProtocol implements CommunicationProtocol<MeshCoreCompanionState> {

    /**
     * Returns the protocol type used to register this adapter.
     *
     * @return {@link ProtocolType#MESHCORE_COMPANION}
     */
    @Override
    public ProtocolType getType() {
        return ProtocolType.MESHCORE_COMPANION;
    }

    /**
     * Creates a MeshCore Companion runtime over an already opened transport.
     *
     * @param context connection and transport context
     * @return runtime that performs Companion handshake and metadata collection
     */
    @Override
    public MeshCoreCompanionProtocolRuntime createRuntime(ProtocolRuntimeContext context) {
        return new MeshCoreCompanionProtocolRuntime(context);
    }
}
