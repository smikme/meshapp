package com.meshtastic.client.lua;

/**
 * Bridge from the Lua sandbox to live node-info requests.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface LuaNodeInfoBridge {

    /**
     * @return {@code true} when node info is available for the current runtime target
     */
    boolean isNodeInfoAvailable();

    /**
     * Creates a unique node-info request id.
     *
     * @return request id
     */
    String nextNodeInfoRequestId();

    /**
     * Sends a node-info request and delivers the result to {@code on_node_info(event)}.
     *
     * @param request request parameters
     */
    void requestNodeInfo(LuaNodeInfoRequest request);
}
