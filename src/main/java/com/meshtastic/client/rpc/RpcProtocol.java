package com.meshtastic.client.rpc;

/**
 * Wire constants for the MeshApp JSON RPC envelope.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class RpcProtocol {

    static final String TYPE_REQUEST = "rpc_request";
    static final String TYPE_RESPONSE = "rpc_response";
    static final String TYPE_EVENT = "event";

    static final String ERROR_BAD_REQUEST = "BAD_REQUEST";
    static final String ERROR_METHOD_NOT_FOUND = "METHOD_NOT_FOUND";
    static final String ERROR_CONNECTION_FAILED = "CONNECTION_FAILED";
    static final String ERROR_INTERNAL = "INTERNAL_ERROR";
    static final String ERROR_TIMEOUT = "TIMEOUT";
    static final String ERROR_TRANSPORT_CLOSED = "TRANSPORT_CLOSED";

    private RpcProtocol() {
    }
}
