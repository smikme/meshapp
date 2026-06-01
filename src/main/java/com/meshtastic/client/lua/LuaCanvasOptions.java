package com.meshtastic.client.lua;

/**
 * Options for a canvas window created from Lua.
 *
 * @param title      window title
 * @param width      canvas width
 * @param height     canvas height
 * @param background initial background color, or an empty string
 * @param resizable  whether the canvas should resize with the window
 * @param fps        {@code on_canvas_frame} frequency; 0 disables the timer
 */
public record LuaCanvasOptions(String title,
                               double width,
                               double height,
                               String background,
                               boolean resizable,
                               double fps) {
}
