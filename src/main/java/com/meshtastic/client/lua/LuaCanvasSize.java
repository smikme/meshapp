package com.meshtastic.client.lua;

/**
 * Current size of a Lua canvas window.
 *
 * @param width  canvas width
 * @param height canvas height
 */
public record LuaCanvasSize(double width, double height) {

    public static LuaCanvasSize empty() {
        return new LuaCanvasSize(0, 0);
    }
}
