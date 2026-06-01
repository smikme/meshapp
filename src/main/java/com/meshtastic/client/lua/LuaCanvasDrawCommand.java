package com.meshtastic.client.lua;

import javafx.scene.canvas.GraphicsContext;

/**
 * Single drawing command for a Lua canvas window.
 */
@FunctionalInterface
public interface LuaCanvasDrawCommand {

    /**
     * Draws on a JavaFX canvas.
     *
     * @param gc canvas graphics context
     */
    void draw(GraphicsContext gc);
}
