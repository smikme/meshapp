package com.meshtastic.client.protocol.meshcore;

import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.protocol.CommunicationProtocol;
import com.meshtastic.client.protocol.ProtocolRuntimeContext;

/**
 * MeshCore KISS modem protocol adapter for the shared protocol registry.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class MeshCoreKissProtocol implements CommunicationProtocol<MeshCoreKissState> {

    /**
     * Returns the protocol type used to register this adapter.
     *
     * @return {@link ProtocolType#MESHCORE_KISS}
     */
    @Override
    public ProtocolType getType() {
        return ProtocolType.MESHCORE_KISS;
    }

    /**
     * Creates a MeshCore KISS runtime over an already opened transport.
     *
     * @param context connection and transport context
     * @return runtime that performs KISS handshake and metadata collection
     */
    @Override
    public MeshCoreKissProtocolRuntime createRuntime(ProtocolRuntimeContext context) {
        return new MeshCoreKissProtocolRuntime(context);
    }
}
