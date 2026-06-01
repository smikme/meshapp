package com.meshtastic.client.lua;

import com.meshtastic.client.model.NodeData;

/**
 * Result of an interactive node selection made for a Lua script.
 *
 * @param requestId id of the original request
 * @param source Lua API that created the original request
 * @param name script-defined name of the original request
 * @param selected {@code true} when the user selected a node
 * @param node selected node, or {@code null}
 * @param chatType chat type associated with the original request
 * @param chatKey chat key associated with the original request
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record LuaUiNodeSelection(String requestId,
                                 String source,
                                 String name,
                                 boolean selected,
                                 NodeData node,
                                 String chatType,
                                 String chatKey) {

    public static LuaUiNodeSelection selected(LuaUiNodePickRequest request, NodeData node) {
        return new LuaUiNodeSelection(
                request.requestId(),
                request.source(),
                request.name(),
                true,
                node,
                request.chatType(),
                request.chatKey());
    }

    public static LuaUiNodeSelection cancelled(LuaUiNodePickRequest request) {
        return new LuaUiNodeSelection(
                request.requestId(),
                request.source(),
                request.name(),
                false,
                null,
                request.chatType(),
                request.chatKey());
    }
}
