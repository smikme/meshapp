package com.meshtastic.client.lua;

/**
 * Текущий размер Canvas-окна Lua.
 *
 * @param width  ширина Canvas
 * @param height высота Canvas
 */
public record LuaCanvasSize(double width, double height) {

    public static LuaCanvasSize empty() {
        return new LuaCanvasSize(0, 0);
    }
}
