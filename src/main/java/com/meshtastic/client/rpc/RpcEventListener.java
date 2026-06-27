package com.meshtastic.client.rpc;

import com.google.gson.JsonElement;

/**
 * Receives push events sent by the MeshApp host over an active RPC session.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
@FunctionalInterface
public interface RpcEventListener {

    /**
     * Handles one event.
     *
     * @param event event name, for example {@code message.created}
     * @param payload event payload, never {@code null}
     */
    void onEvent(String event, JsonElement payload);
}
