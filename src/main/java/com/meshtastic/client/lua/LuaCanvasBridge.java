package com.meshtastic.client.lua;

/**
 * Bridge from the Lua sandbox to the application's built-in Canvas window.
 *
 * <p>The window is created only by an explicit Lua API call and does not appear
 * in side-menu navigation.
 */
public interface LuaCanvasBridge {

    /**
     * Opens or updates the Canvas window for the current script.
     *
     * @param options window options
     */
    void openCanvas(LuaCanvasOptions options);

    /**
     * Closes the Canvas window for the current script.
     */
    void closeCanvas();

    /**
     * Queues a drawing command for the JavaFX Canvas.
     *
     * @param command drawing command
     */
    void enqueueCanvasDraw(LuaCanvasDrawCommand command);

    /**
     * Sets the callback rate for {@code on_canvas_frame(event)}.
     *
     * @param fps frames per second; {@code 0} disables the timer
     */
    void setCanvasFrameRate(double fps);

    /**
     * Returns the current mouse state inside the Canvas.
     *
     * @return mouse-state snapshot
     */
    LuaCanvasMouseState canvasMouseState();

    /**
     * Returns the current keyboard state for the Canvas window.
     *
     * @return keyboard-state snapshot
     */
    LuaCanvasKeyState canvasKeyState();

    /**
     * Returns the current Canvas size.
     *
     * @return size snapshot
     */
    LuaCanvasSize canvasSize();
}
