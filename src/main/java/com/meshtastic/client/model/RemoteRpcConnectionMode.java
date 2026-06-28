package com.meshtastic.client.model;

/**
 * Remote MeshApp RPC transport mode.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public enum RemoteRpcConnectionMode {
    /**
     * Connect directly to the MeshApp Host RPC server.
     */
    DIRECT,

    /**
     * Connect to the MeshApp Host through an External RPC Router room.
     */
    ROUTER
}
