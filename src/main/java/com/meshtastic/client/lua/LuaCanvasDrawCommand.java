package com.meshtastic.client.lua;

import javafx.scene.canvas.GraphicsContext;

/**
 * Одна команда рисования для Canvas-окна Lua.
 */
@FunctionalInterface
public interface LuaCanvasDrawCommand {

    /**
     * Выполняет рисование на JavaFX Canvas.
     *
     * @param gc graphics context Canvas
     */
    void draw(GraphicsContext gc);
}
