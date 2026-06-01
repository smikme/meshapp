package com.meshtastic.client.lua;

/**
 * Node-info request issued from Lua.
 *
 * @param scriptId Lua script id
 * @param requestId request id inside the runtime session
 * @param source event source
 * @param name script or request name
 * @param targetNodeNum node_num of the target node
 * @param targetNodeId node_id of the target node
 * @param targetName display name of the target node
 * @param chatType chat type
 * @param chatKey chat key
 * @param timeoutSeconds response timeout in seconds
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record LuaNodeInfoRequest(long scriptId,
                                 String requestId,
                                 String source,
                                 String name,
                                 int targetNodeNum,
                                 String targetNodeId,
                                 String targetName,
                                 String chatType,
                                 String chatKey,
                                 int timeoutSeconds) {
}
