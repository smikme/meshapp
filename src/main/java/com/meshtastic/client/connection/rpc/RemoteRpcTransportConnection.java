package com.meshtastic.client.connection.rpc;

import com.meshtastic.client.connection.TransportConnection;
import com.meshtastic.client.rpc.RpcClient;

/**
 * {@link TransportConnection} carrying a ready MeshApp RPC client.
 * <p>
 * Implementations use the connection lifecycle expected by
 * {@code ConnectionManager}, while {@code RemoteRpcProtocolRuntime} obtains the
 * RPC client through {@link #getRpcClient()}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface RemoteRpcTransportConnection extends TransportConnection {

    /**
     * @return active RPC client for the connected remote host
     */
    RpcClient getRpcClient();
}
