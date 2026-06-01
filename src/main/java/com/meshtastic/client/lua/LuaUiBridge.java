package com.meshtastic.client.lua;

/**
 * Bridge from the Lua sandbox to the MeshApp JavaFX interface.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface LuaUiBridge {

    /**
     * @return {@code true} when the current runtime can show a UI request
     */
    boolean isAvailable();

    /**
     * Creates a unique request id within the current Lua session.
     *
     * @return request id
     */
    String nextRequestId();

    /**
     * Sends a node-pick request to the UI.
     *
     * @param request Lua request
     */
    void requestNodePick(LuaUiNodePickRequest request);

    /**
     * Shows a temporary built-in bot message without saving it to the database.
     *
     * @param notice UI notice
     */
    void showBotNotice(LuaUiBotNotice notice);
}
