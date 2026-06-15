package com.meshtastic.client.lua;

/**
 * Bridge from the Lua sandbox to Meshtastic remote administration.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface LuaRemoteAdminBridge {

    /**
     * @return {@code true} when remote admin is available for the current runtime target
     */
    boolean isRemoteAdminAvailable();

    /**
     * Creates a unique remote-admin request id.
     *
     * @return request id
     */
    String nextRemoteAdminRequestId();

    /**
     * Starts a remote-admin operation and delivers the result to
     * {@code on_admin(event)}.
     *
     * @param request request parameters
     */
    void requestRemoteAdmin(LuaRemoteAdminRequest request);
}
