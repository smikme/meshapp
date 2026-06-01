package com.meshtastic.client.lua;

/**
 * Bridge from the Lua sandbox to traceroute operations on the active connection.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface LuaTracerouteBridge {

    /**
     * @return {@code true} when traceroute is available for the current runtime target
     */
    boolean isTracerouteAvailable();

    /**
     * Creates a unique traceroute request id.
     *
     * @return request id
     */
    String nextTracerouteRequestId();

    /**
     * Sends a traceroute request and delivers the result to {@code on_traceroute(event)}.
     *
     * @param request request parameters
     */
    void requestTraceroute(LuaTracerouteRequest request);
}
