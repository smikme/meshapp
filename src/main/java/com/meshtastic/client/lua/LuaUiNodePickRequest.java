package com.meshtastic.client.lua;

/**
 * Lua-script request for interactive node selection in the UI.
 *
 * @param scriptId Lua script id
 * @param requestId request id inside the runtime session
 * @param source Lua API that created the request
 * @param name script-defined request name
 * @param prompt title or hint text
 * @param query initial search query
 * @param chatType chat type associated with the request
 * @param chatKey chat key associated with the request
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record LuaUiNodePickRequest(long scriptId,
                                   String requestId,
                                   String source,
                                   String name,
                                   String prompt,
                                   String query,
                                   String chatType,
                                   String chatKey) {
}
