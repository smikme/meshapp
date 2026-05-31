package com.meshtastic.client.lua;

import java.util.Set;

/**
 * Snapshot состояния клавиатуры для Lua Canvas.
 */
public record LuaCanvasKeyState(Set<String> pressedCodes,
                                String lastType,
                                String lastCode,
                                String lastKey,
                                String text,
                                boolean shiftDown,
                                boolean controlDown,
                                boolean altDown,
                                boolean metaDown,
                                double timeSeconds) {

    public static LuaCanvasKeyState empty() {
        return new LuaCanvasKeyState(Set.of(), "", "", "", "", false, false, false, false, 0);
    }
}
