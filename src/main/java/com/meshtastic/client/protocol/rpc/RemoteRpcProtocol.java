package com.meshtastic.client.protocol.rpc;

import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.protocol.CommunicationProtocol;
import com.meshtastic.client.protocol.ProtocolRuntimeContext;

/**
 * MeshApp host RPC protocol adapter.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class RemoteRpcProtocol implements CommunicationProtocol<RemoteRpcState> {

    @Override
    public ProtocolType getType() {
        return ProtocolType.REMOTE_RPC;
    }

    @Override
    public RemoteRpcProtocolRuntime createRuntime(ProtocolRuntimeContext context) {
        return new RemoteRpcProtocolRuntime(context);
    }
}
