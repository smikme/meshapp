package com.meshtastic.client.lua;

/**
 * Snapshot состояния мыши внутри Lua Canvas.
 */
public record LuaCanvasMouseState(double x,
                                  double y,
                                  double screenX,
                                  double screenY,
                                  boolean over,
                                  boolean pressed,
                                  boolean primaryDown,
                                  boolean middleDown,
                                  boolean secondaryDown,
                                  String button,
                                  int clickCount,
                                  double wheelDeltaX,
                                  double wheelDeltaY,
                                  String lastType,
                                  double timeSeconds) {

    public static LuaCanvasMouseState empty() {
        return new LuaCanvasMouseState(
                0, 0, 0, 0,
                false, false, false, false, false,
                "", 0, 0, 0, "", 0);
    }
}
