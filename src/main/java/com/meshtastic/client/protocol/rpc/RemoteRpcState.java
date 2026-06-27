package com.meshtastic.client.protocol.rpc;

import com.google.gson.JsonObject;
import com.meshtastic.client.rpc.DirectRpcClient;

/**
 * Runtime state for one remote MeshApp RPC connection.
 *
 * @param client direct RPC client
 * @param ping last successful system.ping response
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record RemoteRpcState(DirectRpcClient client, JsonObject ping) {
}
