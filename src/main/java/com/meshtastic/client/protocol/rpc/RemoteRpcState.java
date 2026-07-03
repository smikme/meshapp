package com.meshtastic.client.protocol.rpc;

import com.google.gson.JsonObject;
import com.meshtastic.client.rpc.RpcClient;

/**
 * Runtime state for one remote MeshApp RPC connection.
 *
 * @param client RPC client
 * @param ping last successful system.ping response
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record RemoteRpcState(RpcClient client, JsonObject ping) {
}
