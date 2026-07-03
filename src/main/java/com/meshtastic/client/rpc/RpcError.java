package com.meshtastic.client.rpc;

/**
 * Structured RPC error returned by the remote peer.
 *
 * @param code stable machine-readable error code
 * @param message human-readable error message
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record RpcError(String code, String message) {
}
