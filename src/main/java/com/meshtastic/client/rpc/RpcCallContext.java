package com.meshtastic.client.rpc;

/**
 * Metadata for one RPC method invocation.
 *
 * @param requestId request id from the incoming envelope
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record RpcCallContext(String requestId) {
}
