package com.meshtastic.client.lua;

/**
 * Transient UI message from the built-in bot, not persisted to chat history.
 *
 * @param scriptId Lua script id
 * @param source event source
 * @param name script or request name
 * @param chatType chat type
 * @param chatKey chat key
 * @param text message text
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record LuaUiBotNotice(long scriptId,
                             String source,
                             String name,
                             String chatType,
                             String chatKey,
                             String text) {
}
